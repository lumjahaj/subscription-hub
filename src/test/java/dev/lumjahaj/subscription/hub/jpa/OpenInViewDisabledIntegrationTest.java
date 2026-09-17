package dev.lumjahaj.subscription.hub.jpa;

import dev.lumjahaj.subscription.hub.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewFilter;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins spring.jpa.open-in-view off.
 *
 * Boot's default is on, so deleting one line from application.yml would
 * quietly bring it back: a request would again hold a Hibernate session, and
 * with it a pooled JDBC connection, until its response is written - through
 * a payment provider's HTTP call and a PDF download - and lazy reads outside
 * a transaction would start working again by accident, hiding the next
 * missing fetch plan. Nothing else would fail.
 */
class OpenInViewDisabledIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void noOpenEntityManagerInViewIsRegistered() {
        assertThat(context.getBeanNamesForType(OpenEntityManagerInViewInterceptor.class)).isEmpty();
        assertThat(context.getBeanNamesForType(OpenEntityManagerInViewFilter.class)).isEmpty();
    }
}
