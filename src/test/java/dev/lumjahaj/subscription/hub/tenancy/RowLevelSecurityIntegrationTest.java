package dev.lumjahaj.subscription.hub.tenancy;

import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
import dev.lumjahaj.subscription.hub.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Postgres refuses the cross-tenant read, rather than the application
 * remembering not to ask for it.
 *
 * <p>This is the case {@code TenantIsolationIntegrationTest} structurally
 * cannot cover. That one proves Hibernate's {@code @TenantId} adds a
 * {@code tenant_id =} predicate to the queries Hibernate builds. Everything
 * here goes around Hibernate entirely — raw JDBC on the application's own
 * connection, with <b>no tenant_id anywhere in the statement</b> — which is
 * exactly the shape of a future native query, reporting tool or migration that
 * forgets. Four such native queries already exist.
 *
 * <p>Fixtures are planted with the owner's template, which is exempt from the
 * policies, so the rows genuinely exist and the assertions are about
 * visibility rather than about whether the insert worked.
 */
class RowLevelSecurityIntegrationTest extends AbstractIntegrationTest {

    /** The application's pool: restricted role, tenant bound on every borrow. */
    @Autowired
    private DataSource applicationDataSource;

    /** The owner's: exempt from the policies, used only to plant and clean up. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String code = "rls-" + UUID.randomUUID();
    private UUID acmeProductId;

    @BeforeEach
    void plantAnAcmeRow() {
        acmeProductId = jdbcTemplate.queryForObject("""
                INSERT INTO product (tenant_id, code, name)
                VALUES ('acme', ?, 'Planted for the row-level security test')
                RETURNING id
                """, UUID.class, code);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        jdbcTemplate.update("DELETE FROM product WHERE code = ?", code);
    }

    @Test
    void aRawQueryNamingNoTenantCannotReadAnotherTenantsRow() throws SQLException {
        // The row exists - the owner can see it.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM product WHERE id = ?", Integer.class, acmeProductId))
                .isEqualTo(1);

        // The same statement, on the application's connection, as demo.
        // Note what is NOT in it: any mention of tenant_id. Before the
        // policies this returned the row, and nothing in the application
        // would have stopped it.
        assertThat(countProductsById(acmeProductId, "demo"))
                .as("demo must not see acme's row through a query that never mentions a tenant")
                .isZero();

        // And the same statement as acme does see it, so the zero above is
        // isolation rather than the row being unreachable to everyone.
        assertThat(countProductsById(acmeProductId, "acme")).isEqualTo(1);
    }

    @Test
    void aWriteCannotBeAimedAtAnotherTenant() {
        assertThatThrownBy(() -> {
            try (Connection connection = connectionAs("demo");
                 PreparedStatement statement = connection.prepareStatement(
                         "INSERT INTO product (tenant_id, code, name) VALUES ('acme', ?, 'smuggled')")) {
                statement.setString(1, code + "-smuggled");
                statement.execute();
            }
        })
                .as("WITH CHECK is what stops a tenant writing into another's data")
                .hasMessageContaining("row-level security");

        // An UPDATE cannot reach across either: demo simply has no such row
        // to update, so this reports zero rows rather than failing.
        assertThat(updateNameAs(acmeProductId, "demo")).isZero();
        assertThat(updateNameAs(acmeProductId, "acme")).isEqualTo(1);
    }

    @Test
    void aConnectionWithNoTenantReadsNothingAtAll() throws SQLException {
        // A scrape, a stray job, or a transaction that opened before anyone
        // set a tenant. current_setting(..., true) yields NULL, the predicate
        // is NULL, and nothing matches - fail closed rather than fail open.
        TenantContext.clear();
        try (Connection connection = applicationDataSource.getConnection()) {
            assertThat(count(connection, "SELECT count(*) FROM product"))
                    .as("an unbound connection must read nothing, not everything")
                    .isZero();
        }
    }

    /**
     * Guards V22 and V23 against the next tenant-owned table.
     *
     * <p>A migration that adds one with a tenant_id column and no policy
     * leaves it readable across tenants, and nothing else in the suite would
     * notice: every existing test would pass, because correct code scopes by
     * tenant anyway.
     *
     * <p>There are no exceptions left. app_user and audit_event were the last
     * two, and they were the ones that mattered — the only tables with no
     * {@code @TenantId} backstop either, so a single repository method written
     * without a tenant would have leaked with nothing to catch it.
     */
    @Test
    void everyTenantOwnedTableIsCoveredByAPolicy() {
        List<String> unprotected = jdbcTemplate.queryForList("""
                SELECT c.relname
                  FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = 'public'
                   AND c.relkind = 'r'
                   AND c.relrowsecurity = false
                   AND EXISTS (SELECT 1 FROM information_schema.columns col
                                WHERE col.table_schema = 'public'
                                  AND col.table_name = c.relname
                                  AND col.column_name = 'tenant_id')
                 ORDER BY c.relname
                """, String.class);

        assertThat(unprotected)
                .as("a tenant_id column with no row-level security is a table that leaks")
                .isEmpty();
    }

    /**
     * app_user is the table this whole change was most needed for.
     *
     * <p>It has no {@code @TenantId} — a login has no tenant in context, since
     * reading this table is what establishes one — so until now
     * {@code findByTenantIdAndEmail} was the <i>only</i> thing scoping it, and
     * a second method written without the tenant would have leaked silently.
     * Now the database refuses regardless of what the query says.
     */
    @Test
    void aRawQueryCannotReadAnotherTenantsUsers() throws SQLException {
        // The seeded admins exist under both tenants (db/seed V9001).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM app_user WHERE email = ?", Integer.class, "admin@acme.test"))
                .isEqualTo(1);

        assertThat(countUsersByEmail("admin@acme.test", "demo"))
                .as("demo must not see acme's users through a query that never mentions a tenant")
                .isZero();
        assertThat(countUsersByEmail("admin@acme.test", "acme")).isEqualTo(1);
    }

    private int countUsersByEmail(String email, String tenantId) throws SQLException {
        try (Connection connection = connectionAs(tenantId);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT count(*) FROM app_user WHERE email = ?")) {
            statement.setString(1, email);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int countProductsById(UUID id, String tenantId) throws SQLException {
        try (Connection connection = connectionAs(tenantId);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT count(*) FROM product WHERE id = ?")) {
            statement.setObject(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int updateNameAs(UUID id, String tenantId) {
        try (Connection connection = connectionAs(tenantId);
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE product SET name = 'renamed' WHERE id = ?")) {
            statement.setObject(1, id);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private int count(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /**
     * A connection from the application's own pool, with the tenant bound the
     * way a request or a job would bind it — through TenantContext, which
     * TenantAwareDataSource reads on borrow.
     */
    private Connection connectionAs(String tenantId) throws SQLException {
        TenantContext.setTenantId(tenantId);
        return applicationDataSource.getConnection();
    }
}
