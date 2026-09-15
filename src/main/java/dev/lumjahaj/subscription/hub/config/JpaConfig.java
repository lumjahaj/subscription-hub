package dev.lumjahaj.subscription.hub.config;

import dev.lumjahaj.subscription.hub.tenancy.infra.jpa.TenantIdentifierResolver;
import org.hibernate.cfg.MultiTenancySettings;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration
@EnableJpaAuditing
public class JpaConfig {

    /**
     * Spring Boot does not auto-wire a CurrentTenantIdentifierResolver bean
     * into Hibernate on its own — it has to be set explicitly as this
     * property, or Hibernate throws "SessionFactory configured for
     * multi-tenancy, but no tenant identifier specified" the first time it
     * needs a Session, including internally when Spring Data JPA probes
     * for named queries while building repository proxies at startup.
     */
    @Bean
    public HibernatePropertiesCustomizer tenantIdentifierResolverCustomizer(TenantIdentifierResolver resolver) {
        return properties -> properties.put(MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER, resolver);
    }
}
