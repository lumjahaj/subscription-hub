package dev.lumjahaj.subscription.hub.testsupport;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Runs the integration tests' fixture SQL as the schema owner rather than as
 * the application's role.
 *
 * <p>Fifteen integration test classes reach for JdbcTemplate to set a
 * subscription's period end, force a dunning attempt due, age a payment, or
 * read a status back. Every one of those statements addresses a tenant-scoped
 * table <i>by primary key</i>, with no tenant_id in the WHERE clause — which is
 * exactly what row-level security is about to stop. On a test thread, which has
 * no TenantContext, they would all quietly touch zero rows. The
 * {@code queryForObject} calls would at least fail loudly; the
 * {@code update(...)} ones would pass vacuously, leaving a test asserting
 * against a fixture that was never actually set up.
 *
 * <p>Running them as the owner is also the honest description of what they are:
 * these tests build and inspect fixtures as an operator would, out of band,
 * not as a tenant making a request. The behaviour under test still goes through
 * the application's own restricted connection over HTTP.
 *
 * <p><b>This bean replaces Boot's rather than competing with it.</b>
 * {@code JdbcTemplateConfiguration} is
 * {@code @ConditionalOnMissingBean(JdbcOperations.class)}, so defining one here
 * switches the auto-configured one off — no
 * {@code spring.main.allow-bean-definition-overriding} needed. Nothing in
 * src/main injects a JdbcTemplate, so tests are the only consumer, and all
 * fifteen classes autowire it by type and needed no edits.
 *
 * <p>Credentials come from {@code spring.flyway.*} on purpose: fixtures run as
 * whoever migrates, so the two cannot drift apart.
 *
 * <p>A plain DriverManagerDataSource, not a pool. It opens a connection per
 * operation, which is irrelevant at this volume, and it keeps a second Hikari
 * pool out of the metrics {@code MetricsEndpointIntegrationTest} reads. It is
 * also not a bean, so the DataSource-wrapping BeanPostProcessor cannot reach
 * it — which matters, since binding a tenant here would reintroduce the very
 * problem this exists to avoid.
 */
@TestConfiguration(proxyBeanMethods = false)
public class OwnerJdbcTemplateConfig {

    @Bean
    JdbcTemplate jdbcTemplate(
            @Value("${spring.flyway.url}") String url,
            @Value("${spring.flyway.user}") String username,
            @Value("${spring.flyway.password}") String password
    ) {
        return new JdbcTemplate(new DriverManagerDataSource(url, username, password));
    }
}
