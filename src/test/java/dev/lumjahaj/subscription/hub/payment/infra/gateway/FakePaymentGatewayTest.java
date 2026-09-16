package dev.lumjahaj.subscription.hub.payment.infra.gateway;

import dev.lumjahaj.subscription.hub.payment.domain.PaymentEvent;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentGatewayException;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FakePaymentGatewayTest {

    private final List<PaymentEvent> delivered = new ArrayList<>();
    private final FakePaymentGateway gateway = new FakePaymentGateway(delivered::add);

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "pm_card_visa,                                 SUCCEEDED, null",
            "pm_card_visa_chargeDeclined,                  FAILED,    card_declined",
            "pm_card_visa_chargeDeclinedInsufficientFunds, FAILED,    insufficient_funds",
            "pm_something_unknown,                         FAILED,    payment_method_invalid"
    })
    void createPayment_deliversTheOutcomeForThePaymentMethod(
            String paymentMethod, PaymentEvent.Outcome outcome, String failureCode) {
        PaymentRequest request = request(paymentMethod);

        String reference = gateway.createPayment(request);

        assertThat(delivered).singleElement().satisfies(event -> {
            assertThat(event.outcome()).isEqualTo(outcome);
            assertThat(event.failureCode()).isEqualTo(failureCode);
            assertThat(event.provider()).isEqualTo("fake");
            assertThat(event.tenantId()).isEqualTo("acme");
            assertThat(event.paymentId()).isEqualTo(request.paymentId());
            assertThat(event.providerReference()).isEqualTo(reference);
        });
    }

    @Test
    void createPayment_whenTheProviderIsUnavailable_throwsAndDeliversNothing() {
        assertThatThrownBy(() -> gateway.createPayment(request("pm_fake_provider_unavailable")))
                .isInstanceOf(PaymentGatewayException.class);
        assertThat(delivered).isEmpty();
    }

    @Test
    void createPayment_forTheSamePaymentTwice_returnsTheSameReference() {
        PaymentRequest request = request("pm_card_visa");

        assertThat(gateway.createPayment(request)).isEqualTo(gateway.createPayment(request));
    }

    private static PaymentRequest request(String paymentMethod) {
        return new PaymentRequest("acme", UUID.randomUUID(), 2999, "USD", paymentMethod);
    }
}
