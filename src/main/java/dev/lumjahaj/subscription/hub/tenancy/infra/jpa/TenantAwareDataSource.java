package dev.lumjahaj.subscription.hub.tenancy.infra.jpa;

import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Binds the current tenant to every connection handed out of the pool, so
 * Postgres row-level security can see it.
 *
 * <p>The policies read {@code current_setting('app.tenant_id')}. Something has
 * to put it there, and where that happens is the whole design:
 *
 * <ul>
 *   <li><b>Not {@code connection-init-sql}</b> — Hikari runs that once per
 *       <i>physical</i> connection, when it is created, not each time one is
 *       borrowed. Every request after the first would inherit whichever tenant
 *       happened to open the connection.</li>
 *   <li><b>Not {@code SET LOCAL} / {@code set_config(..., true)}</b> — the
 *       transaction-local form, which would be self-cleaning. Spring borrows
 *       the connection <i>before</i> it issues BEGIN, so a transaction-local
 *       set here would run in its own implicit transaction and be discarded
 *       immediately. It would silently do nothing.</li>
 *   <li><b>So: session-level, on borrow, reset on return.</b></li>
 * </ul>
 *
 * <p><b>Why the reset matters and also why it is not load-bearing.</b> Session
 * level means the setting outlives the transaction, so a pooled connection
 * could otherwise carry one tenant's value to the next borrower. It cannot,
 * because binding happens on <i>every</i> borrow and always overwrites — the
 * next borrower can never read a stale value. The reset is what keeps the
 * stronger invariant true anyway: a connection sitting in the pool names no
 * tenant, so anything that reaches the pool without going through here (a
 * {@code unwrap} to the Hikari DataSource, say) is fail-closed rather than
 * inheriting whoever used it last. {@code TenantConnectionBindingIntegrationTest}
 * pins both halves.
 *
 * <p><b>The tenant is bound as a parameter</b>, never concatenated. It comes
 * from a verified JWT claim, but a session variable assembled by string
 * concatenation is an injection sink regardless of how trustworthy today's
 * caller is.
 *
 * <p><b>The cost</b> is one extra round trip per connection borrow, and with
 * {@code open-in-view} off that is one per transaction rather than one per
 * request. Skipping the call when the value is unchanged is not safe: a pooled
 * connection is shared across threads, so "unchanged" would have to be tracked
 * per physical connection rather than per thread.
 *
 * <p>Wrapping is done by a BeanPostProcessor in {@code TenantDataSourceConfig}
 * rather than by declaring a DataSource bean, because
 * {@code DataSourceAutoConfiguration} is
 * {@code @ConditionalOnMissingBean(DataSource.class)} and a bean here would
 * switch Boot's own configuration off.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    private static final Logger log = LoggerFactory.getLogger(TenantAwareDataSource.class);

    private static final String SET_TENANT = "SELECT set_config('app.tenant_id', ?, false)";

    /**
     * Blank rather than the sentinel: a connection in the pool is not "no
     * tenant's connection", it is nobody's. Either way the policies match no
     * rows, since no tenant_id is ever blank.
     */
    private static final String NO_TENANT_BOUND = "";

    public TenantAwareDataSource(DataSource targetDataSource) {
        super(targetDataSource);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return bindTenant(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return bindTenant(super.getConnection(username, password));
    }

    private Connection bindTenant(Connection connection) throws SQLException {
        String tenantId = TenantContext.getTenantId();
        try {
            applyTenant(connection, tenantId != null ? tenantId : TenantContext.NO_TENANT);
        } catch (SQLException | RuntimeException e) {
            // Never hand back a connection that is not scoped to anything: an
            // unbound connection under RLS reads nothing, which would surface
            // as mysteriously empty results rather than as this failure.
            closeQuietly(connection);
            throw e;
        }
        return proxyForReset(connection);
    }

    private void applyTenant(Connection connection, String tenantId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SET_TENANT)) {
            statement.setString(1, tenantId);
            statement.execute();
        }
    }

    /**
     * Intercepts close() to clear the setting before the connection goes back
     * to the pool. A dynamic proxy rather than a hand-written wrapper so that
     * the other ~50 methods of {@link Connection} — and anything a future JDBC
     * version adds — keep working without being listed here.
     */
    private Connection proxyForReset(Connection connection) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName()) && (args == null || args.length == 0)) {
                        resetQuietly(connection);
                    }
                    try {
                        return method.invoke(connection, args);
                    } catch (InvocationTargetException e) {
                        throw e.getTargetException();
                    }
                });
    }

    /**
     * Failure here is logged and swallowed rather than thrown: this runs on
     * the way to close(), and a transaction that has already failed leaves the
     * connection unable to execute anything ("current transaction is aborted").
     * Turning that into an exception would replace the real error with this
     * one. Correctness does not depend on it — see the class javadoc.
     */
    private void resetQuietly(Connection connection) {
        try {
            if (!connection.isClosed()) {
                applyTenant(connection, NO_TENANT_BOUND);
            }
        } catch (SQLException e) {
            log.debug("Could not clear app.tenant_id before returning the connection: {}", e.getMessage());
        }
    }

    private void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException e) {
            log.debug("Could not close a connection that failed tenant binding: {}", e.getMessage());
        }
    }
}
