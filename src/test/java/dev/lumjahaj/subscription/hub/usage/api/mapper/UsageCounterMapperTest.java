package dev.lumjahaj.subscription.hub.usage.api.mapper;

import dev.lumjahaj.subscription.hub.subscription.infra.jpa.SubscriptionEntity;
import dev.lumjahaj.subscription.hub.testsupport.MapperValidationSupport;
import dev.lumjahaj.subscription.hub.usage.api.dto.UsageCounterResponse;
import dev.lumjahaj.subscription.hub.usage.api.dto.UsageRecordRequest;
import dev.lumjahaj.subscription.hub.usage.infra.jpa.UsageCounterEntity;
import jakarta.validation.ConstraintViolation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UsageCounterMapperTest extends MapperValidationSupport {

    @Test
    void toResponse_mapsAllFieldsFromEntityIncludingSubscriptionRelation() {
        SubscriptionEntity subscription = new SubscriptionEntity();
        subscription.setId(UUID.randomUUID());

        UsageCounterEntity entity = new UsageCounterEntity();
        entity.setId(UUID.randomUUID());
        entity.setSubscription(subscription);
        entity.setMeterKey("emails.sent");
        entity.setPeriodStart(Instant.parse("2026-01-01T00:00:00Z"));
        entity.setPeriodEnd(Instant.parse("2026-02-01T00:00:00Z"));
        entity.setAmount(new BigDecimal("150.000000"));
        entity.setCreatedAt(Instant.parse("2026-01-05T00:00:00Z"));
        entity.setUpdatedAt(Instant.parse("2026-01-10T00:00:00Z"));

        UsageCounterResponse response = UsageCounterMapper.toResponse(entity);

        assertThat(response.id()).isEqualTo(entity.getId());
        assertThat(response.subscriptionId()).isEqualTo(subscription.getId());
        assertThat(response.meterKey()).isEqualTo("emails.sent");
        assertThat(response.periodStart()).isEqualTo(entity.getPeriodStart());
        assertThat(response.periodEnd()).isEqualTo(entity.getPeriodEnd());
        assertThat(response.amount()).isEqualByComparingTo("150.000000");
        assertThat(response.createdAt()).isEqualTo(entity.getCreatedAt());
        assertThat(response.updatedAt()).isEqualTo(entity.getUpdatedAt());
    }

    @Test
    void request_rejectsBlankMeterKey() {
        var request = new UsageRecordRequest("", BigDecimal.ONE);

        Set<ConstraintViolation<UsageRecordRequest>> violations = VALIDATOR.validate(request);

        assertThat(violations).isNotEmpty();
    }

    @Test
    void request_rejectsNullAmount() {
        var request = new UsageRecordRequest("emails.sent", null);

        Set<ConstraintViolation<UsageRecordRequest>> violations = VALIDATOR.validate(request);

        assertThat(violations).isNotEmpty();
    }

    @Test
    void request_rejectsZeroAmount() {
        var request = new UsageRecordRequest("emails.sent", BigDecimal.ZERO);

        Set<ConstraintViolation<UsageRecordRequest>> violations = VALIDATOR.validate(request);

        assertThat(violations).isNotEmpty();
    }

    @Test
    void request_rejectsNegativeAmount() {
        var request = new UsageRecordRequest("emails.sent", new BigDecimal("-1"));

        Set<ConstraintViolation<UsageRecordRequest>> violations = VALIDATOR.validate(request);

        assertThat(violations).isNotEmpty();
    }

    @Test
    void request_acceptsValidPayload() {
        var request = new UsageRecordRequest("emails.sent", new BigDecimal("2.5"));

        Set<ConstraintViolation<UsageRecordRequest>> violations = VALIDATOR.validate(request);

        assertThat(violations).isEmpty();
    }
}
