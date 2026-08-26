
package dev.lumjahaj.subscription.hub.catalog.api.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanEntitlementCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.infra.jpa.PlanEntitlementEntity;
import dev.lumjahaj.subscription.hub.catalog.infra.jpa.PlanEntity;
import dev.lumjahaj.subscription.hub.testsupport.MapperValidationSupport;
import jakarta.validation.ConstraintViolation;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PlanEntitlementMapperTest extends MapperValidationSupport {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PlanEntitlementMapper mapper = new PlanEntitlementMapper(objectMapper);

    @Test
    void toEntity_serializesJsonValueToString() throws Exception {
        JsonNode value = objectMapper.readTree("{\"limit\":50}");
        var request = new PlanEntitlementCreateRequest("max_users", value);

        PlanEntitlementEntity entity = mapper.toEntity(request);

        assertThat(entity.getKey()).isEqualTo("max_users");
        assertThat(objectMapper.readTree(entity.getValueJson())).isEqualTo(value);
    }

    @Test
    void toEntity_doesNotSetPlan() {
        // parent plan comes from the URL path, resolved by the app service
        JsonNode value = objectMapper.valueToTree(true);
        var request = new PlanEntitlementCreateRequest("feature_x_enabled", value);

        PlanEntitlementEntity entity = mapper.toEntity(request);

        assertThat(entity.getPlan()).isNull();
    }

    @Test
    void toEntity_thenToResponse_roundTripsJsonValueExactly() throws Exception {
        JsonNode originalValue = objectMapper.readTree("{\"limit\":50,\"unit\":\"seats\"}");
        var request = new PlanEntitlementCreateRequest("max_users", originalValue);

        PlanEntitlementEntity entity = mapper.toEntity(request);

        PlanEntity plan = new PlanEntity();
        plan.setCode("pro-monthly");
        entity.setPlan(plan);

        var response = mapper.toResponse(entity);

        assertThat(response.key()).isEqualTo("max_users");
        assertThat(response.planCode()).isEqualTo("pro-monthly");
        assertThat(response.value()).isEqualTo(originalValue);
    }

    @Test
    void request_rejectsBlankKey() {
        JsonNode value = objectMapper.valueToTree(50);
        var request = new PlanEntitlementCreateRequest("", value);

        Set<ConstraintViolation<PlanEntitlementCreateRequest>> violations = VALIDATOR.validate(request);

        assertThat(violations).isNotEmpty();
    }

    @Test
    void request_rejectsNullValue() {
        var request = new PlanEntitlementCreateRequest("max_users", null);

        Set<ConstraintViolation<PlanEntitlementCreateRequest>> violations = VALIDATOR.validate(request);

        assertThat(violations).isNotEmpty();
    }
}