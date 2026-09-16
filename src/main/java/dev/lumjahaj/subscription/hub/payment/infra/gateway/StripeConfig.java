package dev.lumjahaj.subscription.hub.payment.infra.gateway;

import com.stripe.StripeClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the Stripe client, and only when Stripe is the configured
 * provider — the SDK is on the classpath so a real provider *can* be
 * selected, not because one is.
 *
 * A StripeClient instance rather than the global static {@code Stripe.apiKey}:
 * the static form is process-wide mutable state that any code could change,
 * and it makes pointing tests at stripe-mock a matter of setting a global
 * instead of injecting a differently-built client.
 *
 * The key is validated here rather than at the first payment, the same
 * reason JwtConfig checks its secret at startup: a misconfigured deployment
 * should refuse to start, not fail on the first customer who tries to pay.
 */
@Configuration
@ConditionalOnProperty(name = "payment.provider", havingValue = "stripe")
public class StripeConfig {

    @Bean
    public StripeClient stripeClient(
            @Value("${stripe.secret-key:}") String secretKey,
            // Empty means the real Stripe API. Local runs and
            // StripePaymentGatewayTest point this at stripe-mock, which is
            // the only reason the adapter needs no network.
            @Value("${stripe.api-base:}") String apiBase
    ) {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException(
                    "stripe.secret-key must be set when payment.provider=stripe "
                            + "(set STRIPE_SECRET_KEY, or leave payment.provider=fake)");
        }

        StripeClient.StripeClientBuilder builder = StripeClient.builder().setApiKey(secretKey);
        if (!apiBase.isBlank()) {
            builder.setApiBase(apiBase);
        }
        return builder.build();
    }
}
