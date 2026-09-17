package dev.lumjahaj.subscription.hub.billing.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.lumjahaj.subscription.hub.catalog.domain.PlanEntitlementRepository;
import dev.lumjahaj.subscription.hub.catalog.infra.jpa.PlanEntitlementEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns a plan's entitlements into meter prices. An entitlement whose
 * key is a meter key and whose value carries unitAmountCents (e.g.
 * {"includedQuantity": 1000, "unitAmountCents": 2}) is a priced meter;
 * plan_entitlement is a general-purpose bag ({"limit": 10},
 * {"sso": true}), so most entitlements simply aren't meters at all and
 * are skipped rather than treated as a configuration error.
 */
@Component
public class MeterPriceResolver {

    private static final Logger log = LoggerFactory.getLogger(MeterPriceResolver.class);

    private final PlanEntitlementRepository entitlements;
    private final ObjectMapper objectMapper;

    public MeterPriceResolver(PlanEntitlementRepository entitlements, ObjectMapper objectMapper) {
        this.entitlements = entitlements;
        this.objectMapper = objectMapper;
    }

    public Map<String, MeterPrice> forPlan(String tenantId, UUID planId) {
        Map<String, MeterPrice> prices = new HashMap<>();
        for (PlanEntitlementEntity entitlement : entitlements.findByTenantIdAndPlanId(tenantId, planId)) {
            JsonNode value = readTree(entitlement.getValueJson());
            parse(value).ifPresent(price -> prices.put(entitlement.getKey(), price));
        }
        return prices;
    }

    private JsonNode readTree(String valueJson) {
        try {
            return objectMapper.readTree(valueJson);
        } catch (JsonProcessingException e) {
            // Written via writeValueAsString when the entitlement was
            // created (see PlanEntitlementMapper) - unparseable content
            // means the stored row is corrupt, not that this call is bad.
            throw new IllegalStateException("Stored entitlement value is not valid JSON", e);
        }
    }

    /**
     * Package-private and static so the parsing rule is unit-testable
     * without Spring - the same split SubscriptionRenewalService uses
     * between load/save and the pure renewalFor rule.
     */
    static Optional<MeterPrice> parse(JsonNode node) {
        JsonNode unitAmount = node.path("unitAmountCents");
        if (!unitAmount.isNumber()) {
            log.debug("Entitlement {} has no unitAmountCents - not a priced meter", node);
            return Optional.empty();
        }
        long unitAmountCents = unitAmount.asLong();
        if (unitAmountCents < 0) {
            throw new IllegalStateException("Negative meter price: " + node);
        }
        BigDecimal includedQuantity = node.path("includedQuantity").isNumber()
                ? node.path("includedQuantity").decimalValue()
                : BigDecimal.ZERO;
        return Optional.of(new MeterPrice(includedQuantity, unitAmountCents));
    }
}
