package dev.lumjahaj.subscription.hub.subscription.api.mapper;

import dev.lumjahaj.subscription.hub.catalog.infra.jpa.PlanEntity;
import dev.lumjahaj.subscription.hub.customer.infra.jpa.CustomerEntity;
import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionCreateRequest;
import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionPlanChangeRequest;
import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionResponse;
import dev.lumjahaj.subscription.hub.subscription.domain.SubscriptionStatus;
import dev.lumjahaj.subscription.hub.subscription.infra.jpa.SubscriptionEntity;
import dev.lumjahaj.subscription.hub.testsupport.MapperValidationSupport;
import jakarta.validation.ConstraintViolation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionMapperTest extends MapperValidationSupport {

    @Test
    void toResponse_mapsAllFieldsFromEntityIncludingCustomerAndPlanRelations() {
        CustomerEntity customer = new CustomerEntity();
        customer.setId(UUID.randomUUID());

        PlanEntity plan = new PlanEntity();
        plan.setCode("pro-monthly");

        SubscriptionEntity entity = new SubscriptionEntity();
        entity.setId(UUID.randomUUID());
        entity.setCustomer(customer);
        entity.setPlan(plan);
        entity.setStatus(SubscriptionStatus.TRIALING);
        entity.setStartAt(Instant.parse("2026-01-01T00:00:00Z"));
        entity.setCurrentPeriodStart(Instant.parse("2026-01-01T00:00:00Z"));
        entity.setCurrentPeriodEnd(Instant.parse("2026-01-15T00:00:00Z"));
        entity.setNextRenewal(Instant.parse("2026-01-15T00:00:00Z"));
        entity.setCancelAt(null);
        entity.setCanceledAt(null);
        entity.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        entity.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));

        SubscriptionResponse response = SubscriptionMapper.toResponse(entity);

        assertThat(response.id()).isEqualTo(entity.getId());
        assertThat(response.customerId()).isEqualTo(customer.getId());
        assertThat(response.planCode()).isEqualTo("pro-monthly");
        assertThat(response.pendingPlanCode()).as("no scheduled plan change").isNull();
        assertThat(response.status()).isEqualTo(SubscriptionStatus.TRIALING);
        assertThat(response.startAt()).isEqualTo(entity.getStartAt());
        assertThat(response.currentPeriodStart()).isEqualTo(entity.getCurrentPeriodStart());
        assertThat(response.currentPeriodEnd()).isEqualTo(entity.getCurrentPeriodEnd());
        assertThat(response.nextRenewal()).isEqualTo(entity.getNextRenewal());
        assertThat(response.cancelAt()).isNull();
        assertThat(response.canceledAt()).isNull();
        assertThat(response.createdAt()).isEqualTo(entity.getCreatedAt());
        assertThat(response.updatedAt()).isEqualTo(entity.getUpdatedAt());
    }

    @Test
    void toResponse_mapsCancelAtAndCanceledAtWhenSet() {
        CustomerEntity customer = new CustomerEntity();
        customer.setId(UUID.randomUUID());

        PlanEntity plan = new PlanEntity();
        plan.setCode("pro-monthly");

        SubscriptionEntity entity = new SubscriptionEntity();
        entity.setId(UUID.randomUUID());
        entity.setCustomer(customer);
        entity.setPlan(plan);
        entity.setStatus(SubscriptionStatus.CANCELED);
        entity.setStartAt(Instant.parse("2026-01-01T00:00:00Z"));
        entity.setCurrentPeriodStart(Instant.parse("2026-01-01T00:00:00Z"));
        entity.setCurrentPeriodEnd(Instant.parse("2026-02-01T00:00:00Z"));
        entity.setCancelAt(Instant.parse("2026-01-20T00:00:00Z"));
        entity.setCanceledAt(Instant.parse("2026-01-20T00:00:00Z"));

        SubscriptionResponse response = SubscriptionMapper.toResponse(entity);

        assertThat(response.status()).isEqualTo(SubscriptionStatus.CANCELED);
        assertThat(response.cancelAt()).isEqualTo(entity.getCancelAt());
        assertThat(response.canceledAt()).isEqualTo(entity.getCanceledAt());
    }

    @Test
    void toResponse_mapsAScheduledPlanChangeToItsCode() {
        CustomerEntity customer = new CustomerEntity();
        customer.setId(UUID.randomUUID());

        PlanEntity plan = new PlanEntity();
        plan.setCode("basic-monthly");
        PlanEntity pending = new PlanEntity();
        pending.setCode("pro-monthly");

        SubscriptionEntity entity = new SubscriptionEntity();
        entity.setId(UUID.randomUUID());
        entity.setCustomer(customer);
        entity.setPlan(plan);
        entity.setPendingPlan(pending);
        entity.setStatus(SubscriptionStatus.ACTIVE);

        SubscriptionResponse response = SubscriptionMapper.toResponse(entity);

        // The plan in force is still the old one: nothing moves until renewal.
        assertThat(response.planCode()).isEqualTo("basic-monthly");
        assertThat(response.pendingPlanCode()).isEqualTo("pro-monthly");
    }

    @Test
    void planChangeRequest_rejectsBlankPlanCode() {
        var request = new SubscriptionPlanChangeRequest("  ");

        Set<ConstraintViolation<SubscriptionPlanChangeRequest>> violations = VALIDATOR.validate(request);

        assertThat(violations).isNotEmpty();
    }

    @Test
    void request_rejectsNullCustomerId() {
        var request = new SubscriptionCreateRequest(null, "pro-monthly");

        Set<ConstraintViolation<SubscriptionCreateRequest>> violations = VALIDATOR.validate(request);

        assertThat(violations).isNotEmpty();
    }

    @Test
    void request_rejectsBlankPlanCode() {
        var request = new SubscriptionCreateRequest(UUID.randomUUID(), "");

        Set<ConstraintViolation<SubscriptionCreateRequest>> violations = VALIDATOR.validate(request);

        assertThat(violations).isNotEmpty();
    }

    @Test
    void request_acceptsValidPayload() {
        var request = new SubscriptionCreateRequest(UUID.randomUUID(), "pro-monthly");

        Set<ConstraintViolation<SubscriptionCreateRequest>> violations = VALIDATOR.validate(request);

        assertThat(violations).isEmpty();
    }
}