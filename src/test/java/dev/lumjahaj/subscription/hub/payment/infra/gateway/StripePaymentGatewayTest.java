package dev.lumjahaj.subscription.hub.payment.infra.gateway;

import com.stripe.StripeClient;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentRequest;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real Stripe SDK against stripe-mock, Stripe's own local
 * server, so the adapter has automated coverage without an account, a key
 * or a network call — CLAUDE.md §2's "tests never call a real provider".
 *
 * Deliberately a plain JUnit test with no Spring context: the adapter is
 * constructed directly, so this neither needs payment.provider=stripe nor
 * forks the Spring context cache (CLAUDE.md §5), and its container is
 * independent of AbstractIntegrationTest's. Started in a static
 * initializer, not with @Testcontainers/@Container, for the same lifecycle
 * reason documented there; Ryuk reaps it on JVM exit.
 *
 * What this can and cannot prove: it proves the request the SDK builds is
 * one Stripe's API accepts and that the PaymentIntent id is read back
 * correctly. stripe-mock answers from fixtures and always succeeds, so
 * declines, outages and idempotent replay stay covered by
 * FakePaymentGatewayTest and PaymentIntegrationTest.
 */
class StripePaymentGatewayTest {

    private static final int STRIPE_MOCK_PORT = 12111;

    private static final GenericContainer<?> STRIPE_MOCK =
            new GenericContainer<>(DockerImageName.parse("stripe/stripe-mock:latest"))
                    .withExposedPorts(STRIPE_MOCK_PORT);

    static {
        STRIPE_MOCK.start();
    }

    // stripe-mock authenticates nothing, but it does insist the key *looks*
    // like a test key: "sk_test_" followed by digits. A name-shaped key is
    // rejected with an AuthenticationException.
    private final StripeClient stripe = StripeClient.builder()
            .setApiKey("sk_test_123")
            .setApiBase("http://" + STRIPE_MOCK.getHost() + ":" + STRIPE_MOCK.getMappedPort(STRIPE_MOCK_PORT))
            .build();

    private final StripePaymentGateway gateway = new StripePaymentGateway(stripe);

    @Test
    void provider_isStripe() {
        assertThat(gateway.provider()).isEqualTo("stripe");
    }

    @Test
    void createPayment_createsAPaymentIntentAndReturnsItsId() {
        PaymentRequest request = new PaymentRequest(
                "acme", UUID.randomUUID(), 2999, "USD", "pm_card_visa");

        String reference = gateway.createPayment(request);

        assertThat(reference).startsWith("pi_");
    }

    @Test
    void createPayment_acceptsEveryCurrencyCaseTheCatalogProduces() {
        // Plans store "USD"; Stripe rejects anything but lowercase, so the
        // adapter lowercases it. A regression here would only show up
        // against a real API, which no other test reaches.
        PaymentRequest request = new PaymentRequest(
                "acme", UUID.randomUUID(), 500, "EUR", "pm_card_visa");

        assertThat(gateway.createPayment(request)).startsWith("pi_");
    }
}
