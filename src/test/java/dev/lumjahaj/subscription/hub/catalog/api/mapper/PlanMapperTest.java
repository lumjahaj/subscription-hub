package dev.lumjahaj.subscription.hub.catalog.api.mapper;

import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.domain.IntervalUnit;
import dev.lumjahaj.subscription.hub.catalog.infra.jpa.PlanEntity;
import dev.lumjahaj.subscription.hub.catalog.infra.jpa.ProductEntity;
import dev.lumjahaj.subscription.hub.testsupport.MapperValidationSupport;
import jakarta.validation.ConstraintViolation;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PlanMapperTest extends MapperValidationSupport {

    @Test
    void toEntity_mapsAllFieldsFromRequest() {
        var request = new PlanCreateRequest("messaging", "pro-monthly", "Pro Monthly",
                "MONTH", 3, 4999L, "USD", 14);

        PlanEntity entity = PlanMapper.toEntity(request);

        assertThat(entity.getCode()).isEqualTo("pro-monthly");
        assertThat(entity.getName()).isEqualTo("Pro Monthly");
        assertThat(entity.getIntervalUnit()).isEqualTo(IntervalUnit.MONTH);
        assertThat(entity.getIntervalCount()).isEqualTo(3);
        assertThat(entity.getAmountCents()).isEqualTo(4999L);
        assertThat(entity.getCurrency()).isEqualTo("USD");
        assertThat(entity.getTrialDays()).isEqualTo(14);
    }

    @Test
    void toEntity_keepsEntityDefaults_whenCurrencyAndTrialDaysAreNull() {
        // currency defaults to "EUR" and trialDays to 0 on the entity itself;
        // the mapper should not overwrite them with null
        var request = new PlanCreateRequest("messaging", "pro-monthly", "Pro Monthly",
                "MONTH", 1, 4999L, null, null);

        PlanEntity entity = PlanMapper.toEntity(request);

        assertThat(entity.getCurrency()).isEqualTo("EUR");
        assertThat(entity.getTrialDays()).isEqualTo(0);
    }

    @Test
    void toEntity_defaultsIntervalCountToOneWhenNull() {
        // intervalCount defaults to 1 on the entity itself (a plain
        // monthly/annual plan); the mapper should not overwrite it with null
        var request = new PlanCreateRequest("messaging", "pro-monthly", "Pro Monthly",
                "MONTH", null, 4999L, "USD", 14);

        PlanEntity entity = PlanMapper.toEntity(request);

        assertThat(entity.getIntervalCount()).isEqualTo(1);
    }

    @Test
    void toEntity_doesNotSetProduct() {
        // resolving productCode -> ProductEntity is the app service's job,
        // not the mapper's (mapper has no repository access)
        var request = new PlanCreateRequest("messaging","pro-monthly", "Pro Monthly",
                "MONTH", 1, 4999L, "USD", 14);

        PlanEntity entity = PlanMapper.toEntity(request);

        assertThat(entity.getProduct()).isNull();
    }

    @Test
    void toResponse_mapsProductCodeFromRelatedProduct() {
        ProductEntity product = new ProductEntity();
        product.setCode("acme-product");

        PlanEntity plan = new PlanEntity();
        plan.setCode("pro-monthly");
        plan.setName("Pro Monthly");
        plan.setIntervalUnit(IntervalUnit.MONTH);
        plan.setIntervalCount(3);
        plan.setAmountCents(4999L);
        plan.setCurrency("USD");
        plan.setTrialDays(14);
        plan.setProduct(product);

        var response = PlanMapper.toResponse(plan);

        assertThat(response.productCode()).isEqualTo("acme-product");
        assertThat(response.code()).isEqualTo("pro-monthly");
        assertThat(response.name()).isEqualTo("Pro Monthly");
        assertThat(response.intervalUnit()).isEqualTo(IntervalUnit.MONTH);
        assertThat(response.intervalCount()).isEqualTo(3);
        assertThat(response.amountCents()).isEqualTo(4999L);
        assertThat(response.currency()).isEqualTo("USD");
        assertThat(response.trialDays()).isEqualTo(14);
    }

    @Test
    void request_rejectsIntervalUnitOtherThanMonthOrYear() {
        var request = new PlanCreateRequest("messaging", "pro-monthly", "Pro Monthly",
                "WEEK", 1, 4999L, "USD", 14);

        Set<ConstraintViolation<PlanCreateRequest>> violations = VALIDATOR.validate(request);

        assertThat(violations).isNotEmpty();
    }

    @Test
    void request_rejectsZeroIntervalCount() {
        var request = new PlanCreateRequest("messaging", "pro-monthly", "Pro Monthly",
                "MONTH", 0, 4999L, "USD", 14);

        Set<ConstraintViolation<PlanCreateRequest>> violations = VALIDATOR.validate(request);

        assertThat(violations).isNotEmpty();
    }

    @Test
    void request_rejectsNegativeIntervalCount() {
        var request = new PlanCreateRequest("messaging", "pro-monthly", "Pro Monthly",
                "MONTH", -3, 4999L, "USD", 14);

        Set<ConstraintViolation<PlanCreateRequest>> violations = VALIDATOR.validate(request);

        assertThat(violations).isNotEmpty();
    }

    @Test
    void request_rejectsNegativeAmount() {
        var request = new PlanCreateRequest("messaging", "pro-monthly", "Pro Monthly",
                "MONTH", 1, -100L, "USD", 14);

        Set<ConstraintViolation<PlanCreateRequest>> violations = VALIDATOR.validate(request);

        assertThat(violations).isNotEmpty();
    }
}
