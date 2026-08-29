package dev.lumjahaj.subscription.hub.billing.api.mapper;

import dev.lumjahaj.subscription.hub.billing.api.dto.InvoiceResponse;
import dev.lumjahaj.subscription.hub.billing.domain.InvoiceLineKind;
import dev.lumjahaj.subscription.hub.billing.domain.InvoiceStatus;
import dev.lumjahaj.subscription.hub.billing.infra.jpa.InvoiceEntity;
import dev.lumjahaj.subscription.hub.billing.infra.jpa.InvoiceLineEntity;
import dev.lumjahaj.subscription.hub.customer.infra.jpa.CustomerEntity;
import dev.lumjahaj.subscription.hub.subscription.infra.jpa.SubscriptionEntity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Plain class, not MapperValidationSupport - there's no request DTO here
// to validate against (unlike UsageCounterMapperTest, which validates
// UsageRecordRequest). An invoice has no toEntity().
class InvoiceMapperTest {

    @Test
    void toResponse_mapsAllFieldsIncludingSubscriptionAndCustomerRelations() {
        UUID subscriptionId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        SubscriptionEntity subscription = new SubscriptionEntity();
        subscription.setId(subscriptionId);
        CustomerEntity customer = new CustomerEntity();
        customer.setId(customerId);

        InvoiceEntity invoice = new InvoiceEntity();
        invoice.setId(UUID.randomUUID());
        invoice.setSubscription(subscription);
        invoice.setCustomer(customer);
        invoice.setNumber("INV-000001");
        invoice.setStatus(InvoiceStatus.OPEN);
        invoice.setCurrency("USD");
        invoice.setTotalCents(3249);
        invoice.setPeriodStart(Instant.parse("2026-01-01T00:00:00Z"));
        invoice.setPeriodEnd(Instant.parse("2026-02-01T00:00:00Z"));
        invoice.setIssuedAt(Instant.parse("2026-02-01T00:05:00Z"));
        invoice.setDueAt(Instant.parse("2026-02-15T00:05:00Z"));
        invoice.setCreatedAt(Instant.parse("2026-02-01T00:05:00Z"));
        invoice.setUpdatedAt(Instant.parse("2026-02-01T00:05:00Z"));

        InvoiceResponse response = InvoiceMapper.toResponse(invoice);

        assertThat(response.id()).isEqualTo(invoice.getId());
        assertThat(response.subscriptionId()).isEqualTo(subscriptionId);
        assertThat(response.customerId()).isEqualTo(customerId);
        assertThat(response.number()).isEqualTo("INV-000001");
        assertThat(response.status()).isEqualTo(InvoiceStatus.OPEN);
        assertThat(response.currency()).isEqualTo("USD");
        assertThat(response.totalCents()).isEqualTo(3249);
        assertThat(response.periodStart()).isEqualTo(invoice.getPeriodStart());
        assertThat(response.periodEnd()).isEqualTo(invoice.getPeriodEnd());
    }

    @Test
    void toResponse_mapsEveryLine() {
        InvoiceEntity invoice = minimalInvoice();
        InvoiceLineEntity base = line(InvoiceLineKind.BASE, "Plan", "1", 2999, 2999);
        InvoiceLineEntity usage = line(InvoiceLineKind.USAGE, "api.calls", "50", 5, 250);
        invoice.addLine(base);
        invoice.addLine(usage);

        InvoiceResponse response = InvoiceMapper.toResponse(invoice);

        assertThat(response.lines()).hasSize(2);
        assertThat(response.lines())
                .extracting(l -> l.kind(), l -> l.amountCents())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(InvoiceLineKind.BASE, 2999L),
                        org.assertj.core.groups.Tuple.tuple(InvoiceLineKind.USAGE, 250L));
    }

    @Test
    void toResponse_doesNotExposeTenantId() {
        // Inspected via the record components rather than a fixed field
        // list, so this keeps failing correctly if someone adds a field
        // later.
        boolean hasTenantId = false;
        for (RecordComponent component : InvoiceResponse.class.getRecordComponents()) {
            if (component.getName().toLowerCase().contains("tenant")) {
                hasTenantId = true;
            }
        }
        assertThat(hasTenantId).isFalse();
    }

    private static InvoiceEntity minimalInvoice() {
        SubscriptionEntity subscription = new SubscriptionEntity();
        subscription.setId(UUID.randomUUID());
        CustomerEntity customer = new CustomerEntity();
        customer.setId(UUID.randomUUID());

        InvoiceEntity invoice = new InvoiceEntity();
        invoice.setId(UUID.randomUUID());
        invoice.setSubscription(subscription);
        invoice.setCustomer(customer);
        invoice.setNumber("INV-000001");
        invoice.setStatus(InvoiceStatus.OPEN);
        invoice.setCurrency("USD");
        invoice.setPeriodStart(Instant.now());
        invoice.setPeriodEnd(Instant.now());
        invoice.setCreatedAt(Instant.now());
        invoice.setUpdatedAt(Instant.now());
        return invoice;
    }

    private static InvoiceLineEntity line(InvoiceLineKind kind, String description, String quantity,
                                           long unitAmountCents, long amountCents) {
        InvoiceLineEntity line = new InvoiceLineEntity();
        line.setKind(kind);
        line.setDescription(description);
        line.setQuantity(new BigDecimal(quantity));
        line.setUnitAmountCents(unitAmountCents);
        line.setAmountCents(amountCents);
        return line;
    }
}
