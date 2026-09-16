package dev.lumjahaj.subscription.hub.payment.api.mapper;

import dev.lumjahaj.subscription.hub.billing.infra.jpa.InvoiceEntity;
import dev.lumjahaj.subscription.hub.payment.api.dto.PaymentCreateRequest;
import dev.lumjahaj.subscription.hub.payment.api.dto.PaymentResponse;
import dev.lumjahaj.subscription.hub.payment.domain.PaymentStatus;
import dev.lumjahaj.subscription.hub.payment.infra.jpa.PaymentEntity;
import dev.lumjahaj.subscription.hub.testsupport.MapperValidationSupport;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentMapperTest extends MapperValidationSupport {

    @Test
    void toResponse_mapsAllFieldsIncludingInvoiceRelation() {
        InvoiceEntity invoice = new InvoiceEntity();
        invoice.setId(UUID.randomUUID());

        PaymentEntity entity = new PaymentEntity();
        entity.setId(UUID.randomUUID());
        entity.setInvoice(invoice);
        entity.setAmountCents(3249);
        entity.setCurrency("USD");
        entity.setStatus(PaymentStatus.FAILED);
        entity.setProvider("fake");
        entity.setPaymentMethod("pm_card_chargeDeclined");
        entity.setProviderReference("fake_pi_123");
        entity.setFailureCode("card_declined");
        entity.setIdempotencyKey("key-1");
        entity.setCreatedAt(Instant.parse("2026-02-01T00:00:00Z"));
        entity.setUpdatedAt(Instant.parse("2026-02-01T00:00:01Z"));

        PaymentResponse response = PaymentMapper.toResponse(entity);

        assertThat(response.id()).isEqualTo(entity.getId());
        assertThat(response.invoiceId()).isEqualTo(invoice.getId());
        assertThat(response.amountCents()).isEqualTo(3249);
        assertThat(response.currency()).isEqualTo("USD");
        assertThat(response.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(response.provider()).isEqualTo("fake");
        assertThat(response.paymentMethod()).isEqualTo("pm_card_chargeDeclined");
        assertThat(response.providerReference()).isEqualTo("fake_pi_123");
        assertThat(response.failureCode()).isEqualTo("card_declined");
        assertThat(response.createdAt()).isEqualTo(entity.getCreatedAt());
        assertThat(response.updatedAt()).isEqualTo(entity.getUpdatedAt());
    }

    @Test
    void response_exposesNeitherTenantIdNorIdempotencyKey() {
        assertThat(Arrays.stream(PaymentResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .map(String::toLowerCase))
                .noneMatch(name -> name.contains("tenant") || name.contains("idempotency"));
    }

    @Test
    void request_rejectsBlankPaymentMethod() {
        assertThat(VALIDATOR.validate(new PaymentCreateRequest(" "))).isNotEmpty();
    }

    @Test
    void request_rejectsOverlongPaymentMethod() {
        assertThat(VALIDATOR.validate(new PaymentCreateRequest("pm_" + "x".repeat(62)))).isNotEmpty();
    }

    @Test
    void request_acceptsValidPayload() {
        assertThat(VALIDATOR.validate(new PaymentCreateRequest("pm_card_visa"))).isEmpty();
    }
}
