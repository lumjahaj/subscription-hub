package dev.lumjahaj.subscription.hub.tenancy;

import com.zaxxer.hikari.HikariDataSource;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
import dev.lumjahaj.subscription.hub.tenancy.infra.jpa.TenantAwareDataSource;
import dev.lumjahaj.subscription.hub.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@link TenantAwareDataSource} puts on a connection, and — the part that
 * actually matters — what it takes back off.
 *
 * <p>The policies read {@code app.tenant_id} as a <i>session</i> setting, which
 * outlives the transaction, so a pooled connection could carry one tenant's
 * value to whoever borrows it next. Without a test, the only thing standing
 * between that and a cross-tenant read is a reset buried in a close()
 * interceptor.
 *
 * <p><b>Why its own single-connection pool</b> rather than the application's.
 * To show the reset happened, the connection has to be read <i>without</i>
 * going through the decorator — a second borrow through it would rebind the
 * setting and the assertion would pass whether or not anything was ever reset.
 * So the test reaches the pool underneath. That is only deterministic when the
 * pool holds exactly one connection, and forcing
 * {@code maximum-pool-size=1} on the application's pool would mean doing it on
 * the shared base (CLAUDE.md §5), constraining all 30-odd integration classes
 * and risking deadlock. A pool built here costs one connection and is exact.
 */
class TenantConnectionBindingIntegrationTest extends AbstractIntegrationTest {

    /** The pool the application itself uses, decorator and all. */
    @Autowired
    private DataSource applicationDataSource;

    /** The suite's fixture template, which runs as the owner. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private HikariDataSource pool;
    private DataSource tenantAware;

    @BeforeEach
    void setUp() {
        pool = new HikariDataSource();
        pool.setJdbcUrl(postgresJdbcUrl());
        pool.setUsername(APP_ROLE);
        pool.setPassword(APP_ROLE_PASSWORD);
        // One connection, so "borrow again" is guaranteed to be the same
        // physical connection rather than probably the same one.
        pool.setMaximumPoolSize(1);
        tenantAware = new TenantAwareDataSource(pool);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        pool.close();
    }

    @Test
    void bindsTheCurrentTenantOnEveryBorrow() throws SQLException {
        TenantContext.setTenantId("acme");
        try (Connection connection = tenantAware.getConnection()) {
            assertThat(boundTenant(connection)).isEqualTo("acme");
        }

        // Same physical connection, different tenant: the binding is per
        // borrow, not per connection, which is why connection-init-sql could
        // not have done this job.
        TenantContext.setTenantId("demo");
        try (Connection connection = tenantAware.getConnection()) {
            assertThat(boundTenant(connection)).isEqualTo("demo");
        }
    }

    @Test
    void bindsTheSentinelWhenNoTenantIsInContext() throws SQLException {
        TenantContext.clear();
        try (Connection connection = tenantAware.getConnection()) {
            assertThat(boundTenant(connection)).isEqualTo(TenantContext.NO_TENANT);
        }
    }

    @Test
    void clearsTheTenantWhenTheConnectionGoesBackToThePool() throws SQLException {
        TenantContext.setTenantId("acme");
        try (Connection connection = tenantAware.getConnection()) {
            assertThat(boundTenant(connection)).isEqualTo("acme");
        }

        // Read the pool directly, NOT through the decorator: borrowing through
        // it would set the value again and prove nothing. This is the same
        // physical connection acme just used.
        TenantContext.clear();
        try (Connection leftInThePool = pool.getConnection()) {
            assertThat(boundTenant(leftInThePool))
                    .as("a connection sitting in the pool must name no tenant")
                    .isEmpty();
        }
    }

    /**
     * The premise everything else rests on, asserted against the application's
     * own pool rather than the one this test builds.
     *
     * <p>Postgres exempts superusers and table owners from row-level security,
     * so if the application ever went back to connecting as POSTGRES_USER the
     * policies would still be there, still be correct, and enforce nothing.
     * Every other test in the suite would keep passing. This is the one that
     * would not.
     */
    @Test
    void theApplicationConnectsAsARoleThePoliciesCanActuallyApplyTo() throws SQLException {
        assertThat(applicationDataSource)
                .as("the application's DataSource must be wrapped, or no tenant is ever bound")
                .isInstanceOf(TenantAwareDataSource.class);

        try (Connection connection = applicationDataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT current_user,"
                             + " (SELECT rolsuper FROM pg_roles WHERE rolname = current_user),"
                             + " (SELECT rolbypassrls FROM pg_roles WHERE rolname = current_user)")) {
            rs.next();
            assertThat(rs.getString(1)).isEqualTo(APP_ROLE);
            assertThat(rs.getBoolean(2)).as("a superuser bypasses every policy").isFalse();
            assertThat(rs.getBoolean(3)).as("BYPASSRLS does exactly what it says").isFalse();
        }
    }

    /**
     * The other half of the same premise: the suite's fixture SQL must NOT be
     * subject to the policies, or fifteen test classes would silently start
     * updating zero rows the moment they exist.
     *
     * <p>Asserted here because, until policies land, running fixtures as the
     * owner and as the application's role look exactly the same — a green
     * build proves nothing about which one is in use. See
     * OwnerJdbcTemplateConfig.
     */
    @Test
    void fixtureSqlRunsAsARoleThePoliciesDoNotApplyTo() {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT current_user AS who,"
                        + " (SELECT rolsuper FROM pg_roles WHERE rolname = current_user) AS superuser");

        assertThat(row.get("who"))
                .as("fixtures must not run as the application's restricted role")
                .isNotEqualTo(APP_ROLE);
        assertThat(row.get("superuser"))
                .as("the owner is what makes fixture SQL exempt from the policies")
                .isEqualTo(true);
    }

    /** What the RLS policies will read. */
    private String boundTenant(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT current_setting('app.tenant_id', true)")) {
            rs.next();
            // current_setting(..., true) is NULL when never set on this
            // session; the reset writes an empty string. Both mean "no tenant"
            // and neither can equal a tenant_id.
            String value = rs.getString(1);
            return value == null ? "" : value;
        }
    }
}
