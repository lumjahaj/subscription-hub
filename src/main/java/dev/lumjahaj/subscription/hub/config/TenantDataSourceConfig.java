package dev.lumjahaj.subscription.hub.config;

import dev.lumjahaj.subscription.hub.tenancy.infra.jpa.TenantAwareDataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Wraps the application's DataSource so every connection it hands out names
 * the current tenant, which is what the row-level security policies read.
 *
 * <p><b>A BeanPostProcessor rather than a DataSource bean.</b>
 * {@code DataSourceAutoConfiguration} is
 * {@code @ConditionalOnMissingBean(DataSource.class)}, so declaring one here
 * would switch Boot's own configuration off and leave us building Hikari by
 * hand. That breaks in tests, where {@code @ServiceConnection}-style
 * configuration arrives as a {@code JdbcConnectionDetails} bean rather than as
 * {@code spring.datasource.*} properties. Post-processing decorates what Boot
 * built and leaves all of that alone.
 *
 * <p><b>Flyway is deliberately untouched.</b> It has its own DataSource, built
 * from {@code spring.flyway.url/user/password} and connecting as the schema
 * owner, so migrations neither need nor get a tenant binding. That separation
 * is the whole reason the policies can be strict without breaking a
 * cross-tenant backfill like V3's.
 */
@Configuration
public class TenantDataSourceConfig {

    /**
     * Matched by bean name, not by type. Boot names the auto-configured pool
     * "dataSource", and other DataSource beans exist or will (the owner
     * connection the cross-tenant metrics gauges read). Wrapping by type would
     * bind a tenant to those too, which for the owner connection would defeat
     * its only purpose.
     *
     * <p>Static so the container can create it before the beans it inspects,
     * without dragging their dependencies into premature initialisation.
     */
    @Bean
    static BeanPostProcessor tenantAwareDataSourcePostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if ("dataSource".equals(beanName) && bean instanceof DataSource dataSource
                        && !(bean instanceof TenantAwareDataSource)) {
                    return new TenantAwareDataSource(dataSource);
                }
                return bean;
            }
        };
    }
}
