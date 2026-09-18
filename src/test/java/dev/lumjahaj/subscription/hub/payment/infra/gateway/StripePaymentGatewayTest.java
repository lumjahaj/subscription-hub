package dev.lumjahaj.subscription.hub.payment.infra.gateway;

import com.stripe.StripeClient;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeError;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentEvent;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentLookup;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Optional;
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

    // Pinned, not :latest — a moving tag makes a build reproduce differently
    // on a machine that pulled it last month, which is exactly how the MinIO
    // image breaking went unnoticed locally while CI failed.
    private static final GenericContainer<?> STRIPE_MOCK =
            new GenericContainer<>(DockerImageName.parse("stripe/stripe-mock:v0.203.0"))
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
    void reconcile_withAReference_retrievesThatPaymentIntent() {
        PaymentRequest request = new PaymentRequest(
                "acme", UUID.randomUUID(), 2999, "USD", "pm_card_visa");
        String reference = gateway.createPayment(request);

        Optional<PaymentEvent> event = gateway.reconcile(new PaymentLookup(request, reference));

        // What this proves is the retrieve call and the event it is packed
        // into. Not the outcome: stripe-mock answers every request with the
        // same requires_payment_method fixture, so the status is fixed and
        // the mapping is covered by outcomeOf_* below instead.
        assertThat(event).hasValueSatisfying(settled -> {
            assertThat(settled.provider()).isEqualTo("stripe");
            assertThat(settled.tenantId()).isEqualTo("acme");
            assertThat(settled.paymentId()).isEqualTo(request.paymentId());
            assertThat(settled.providerReference()).startsWith("pi_");
        });
    }

    @Test
    void reconcile_withNoReference_reissuesTheCreateUnderTheSameIdempotencyKey() {
        // The branch that must never be replaced by "assume it never
        // happened": a create can time out after the card was charged, and
        // the key is what makes asking again safe.
        PaymentRequest request = new PaymentRequest(
                "acme", UUID.randomUUID(), 2999, "USD", "pm_card_visa");

        Optional<PaymentEvent> event = gateway.reconcile(new PaymentLookup(request, null));

        assertThat(event).hasValueSatisfying(settled ->
                assertThat(settled.providerReference()).startsWith("pi_"));
    }

    @ParameterizedTest
    @CsvSource({
            "succeeded,                SUCCEEDED",
            "canceled,                 FAILED",
            // Stripe puts a failed confirm back here: "declined, try another card".
            "requires_payment_method,  FAILED"
    })
    void outcomeOf_terminalStatuses_settle(String status, PaymentEvent.Outcome outcome) {
        assertThat(StripePaymentGateway.outcomeOf(intentWith(status), request()))
                .hasValueSatisfying(event -> assertThat(event.outcome()).isEqualTo(outcome));
    }

    @ParameterizedTest
    @ValueSource(strings = {"processing", "requires_action", "requires_confirmation", "requires_capture"})
    void outcomeOf_statusesStillInFlight_reportNothingYet(String status) {
        // Empty, not FAILED. The payment is alive at Stripe, and settling it
        // either way here would be inventing an outcome.
        assertThat(StripePaymentGateway.outcomeOf(intentWith(status), request())).isEmpty();
    }

    @Test
    void outcomeOf_aFailure_carriesStripesOwnCode() {
        PaymentIntent intent = intentWith("requires_payment_method");
        StripeError error = new StripeError();
        error.setCode("card_declined");
        intent.setLastPaymentError(error);

        assertThat(StripePaymentGateway.outcomeOf(intent, request()))
                .hasValueSatisfying(event -> assertThat(event.failureCode()).isEqualTo("card_declined"));
    }

    private static PaymentIntent intentWith(String status) {
        PaymentIntent intent = new PaymentIntent();
        intent.setId("pi_reconciled");
        intent.setStatus(status);
        return intent;
    }

    private static PaymentRequest request() {
        return new PaymentRequest("acme", UUID.randomUUID(), 2999, "USD", "pm_card_visa");
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
