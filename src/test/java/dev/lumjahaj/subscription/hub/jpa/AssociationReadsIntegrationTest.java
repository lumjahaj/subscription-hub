package dev.lumjahaj.subscription.hub.jpa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.PlanEntitlementCreateRequest;
import dev.lumjahaj.subscription.hub.catalog.api.dto.ProductCreateRequest;
import dev.lumjahaj.subscription.hub.customer.api.dto.CustomerCreateRequest;
import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionCreateRequest;
import dev.lumjahaj.subscription.hub.subscription.api.dto.SubscriptionPlanChangeRequest;
import dev.lumjahaj.subscription.hub.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every read endpoint whose response is built from a lazy association: a
 * plan's product, an entitlement's or subscription's plan, an invoice's lines.
 *
 * Mappers run in the controller, after the service's transaction has ended.
 * Under spring.jpa.open-in-view those reads worked by accident - the request
 * kept a Hibernate session open until the response was written. With it off,
 * each of these was a 500 LazyInitializationException, and before this class
 * existed the suite could not see four of them: nothing read the plan list, a
 * single plan, the entitlement list or the subscription list over HTTP. Each
 * test asserts the field that needs the association, not just the status.
 */
class AssociationReadsIntegrationTest extends AbstractIntegrationTest {

    private static final String TENANT = "acme";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void planList_andSinglePlan_carryTheProductCode() {
        Fixture f = fixture();

        JsonNode list = get("/api/plans?size=1000");
        assertThat(list.get("content")).anySatisfy(plan -> {
            assertThat(plan.get("code").asText()).isEqualTo(f.planCode());
            assertThat(plan.get("productCode").asText()).isEqualTo(f.productCode());
        });

        assertThat(get("/api/plans/" + f.planCode()).get("productCode").asText()).isEqualTo(f.productCode());
    }

    @Test
    void entitlementList_carriesThePlanCode() {
        Fixture f = fixture();

        JsonNode entitlements = get("/api/plans/" + f.planCode() + "/entitlements");

        assertThat(entitlements).hasSize(1);
        assertThat(entitlements.get(0).get("planCode").asText()).isEqualTo(f.planCode());
    }

    @Test
    void subscriptionList_bothFinders_carryThePlanCode() {
        Fixture f = fixture();

        assertThat(get("/api/subscriptions?size=1000").get("content"))
                .anySatisfy(s -> assertThat(s.get("planCode").asText()).isEqualTo(f.planCode()));
        assertThat(get("/api/subscriptions?customerId=" + f.customerId()).get("content"))
                .singleElement()
                .satisfies(s -> assertThat(s.get("planCode").asText()).isEqualTo(f.planCode()));
    }

    @Test
    void subscriptionTransitions_carryThePlanCode() {
        Fixture f = fixture();

        JsonNode paused = post("/api/subscriptions/" + f.subscriptionId() + "/pause");
        assertThat(paused.get("planCode").asText()).isEqualTo(f.planCode());
        assertThat(get("/api/subscriptions/" + f.subscriptionId()).get("planCode").asText()).isEqualTo(f.planCode());
    }

    /**
     * pendingPlan is the second lazy association on a subscription, and the
     * only nullable one - so it is fetched as a LEFT JOIN and every test above
     * (whose subscriptions have no pending change) already covers the null
     * side. This covers the side that has to be loaded.
     */
    @Test
    void subscription_allThreeFinders_carryThePendingPlanCode() {
        Fixture f = fixture();
        String targetPlan = "assoc-target-" + UUID.randomUUID().toString().substring(0, 8);
        create("/api/plans",
                new PlanCreateRequest(f.productCode(), targetPlan, "Association Target", "MONTH", 1, 2500L, "EUR", 0));

        JsonNode scheduled = post("/api/subscriptions/" + f.subscriptionId() + "/change-plan",
                new SubscriptionPlanChangeRequest(targetPlan));
        assertThat(scheduled.get("pendingPlanCode").asText()).isEqualTo(targetPlan);

        assertThat(get("/api/subscriptions/" + f.subscriptionId()).get("pendingPlanCode").asText())
                .isEqualTo(targetPlan);
        assertThat(get("/api/subscriptions?size=1000").get("content"))
                .filteredOn(s -> s.get("id").asText().equals(f.subscriptionId().toString()))
                .singleElement()
                .satisfies(s -> assertThat(s.get("pendingPlanCode").asText()).isEqualTo(targetPlan));
        assertThat(get("/api/subscriptions?customerId=" + f.customerId()).get("content"))
                .singleElement()
                .satisfies(s -> assertThat(s.get("pendingPlanCode").asText()).isEqualTo(targetPlan));
    }

    @Test
    void invoiceList_bothFinders_carryTheLines() {
        Fixture f = fixture();
        jdbcTemplate.update("UPDATE subscription SET current_period_end = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), f.subscriptionId());
        post("/api/subscriptions/" + f.subscriptionId() + "/invoices");

        assertThat(get("/api/invoices?subscriptionId=" + f.subscriptionId()).get("content"))
                .singleElement()
                .satisfies(invoice -> assertThat(invoice.get("lines")).isNotEmpty());
        assertThat(get("/api/invoices?size=1000").get("content"))
                .filteredOn(invoice -> invoice.get("subscriptionId").asText().equals(f.subscriptionId().toString()))
                .singleElement()
                .satisfies(invoice -> assertThat(invoice.get("lines")).isNotEmpty());
    }

    // ---- helpers ----

    private record Fixture(String productCode, String planCode, UUID customerId, UUID subscriptionId) {
    }

    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String productCode = "assoc-product-" + suffix;
        String planCode = "assoc-plan-" + suffix;

        create("/api/products", new ProductCreateRequest(productCode, "Association Product", null));
        create("/api/plans", new PlanCreateRequest(productCode, planCode, "Association Plan", "MONTH", 1, 1500L, "EUR", 0));
        create("/api/plans/" + planCode + "/entitlements",
                new PlanEntitlementCreateRequest("seats", JsonNodeFactory.instance.numberNode(5)));
        UUID customerId = UUID.fromString(create("/api/customers",
                new CustomerCreateRequest(null, "assoc-" + suffix + "@example.com", "Association Customer"))
                .get("id").asText());
        UUID subscriptionId = UUID.fromString(create("/api/subscriptions",
                new SubscriptionCreateRequest(customerId, planCode)).get("id").asText());
        return new Fixture(productCode, planCode, customerId, subscriptionId);
    }

    private JsonNode create(String path, Object body) {
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                path, HttpMethod.POST, new HttpEntity<>(body, tenantHeaders(TENANT)), JsonNode.class);
        assertThat(response.getStatusCode()).as("POST %s", path).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private JsonNode post(String path) {
        return post(path, null);
    }

    private JsonNode post(String path, Object body) {
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                path, HttpMethod.POST, new HttpEntity<>(body, tenantHeaders(TENANT)), JsonNode.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as("POST %s returned %s", path, response.getStatusCode()).isTrue();
        return response.getBody();
    }

    private JsonNode get(String path) {
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                path, HttpMethod.GET, new HttpEntity<>(tenantHeaders(TENANT)), JsonNode.class);
        assertThat(response.getStatusCode()).as("GET %s", path).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }
}
