package dev.lumjahaj.subscription.hub.payment.infra.gateway;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StripeConfigTest {

    private final StripeConfig config = new StripeConfig();

    @Test
    void stripeClient_withoutASecretKey_failsAtStartupRatherThanAtTheFirstPayment() {
        assertThatThrownBy(() -> config.stripeClient("", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stripe.secret-key");
    }

    @Test
    void stripeClient_withAKey_buildsAClient() {
        assertThat(config.stripeClient("sk_test_key", "")).isNotNull();
    }

    @Test
    void stripeClient_withAnApiBase_buildsAClient() {
        assertThat(config.stripeClient("sk_test_key", "http://localhost:12111")).isNotNull();
    }
}
