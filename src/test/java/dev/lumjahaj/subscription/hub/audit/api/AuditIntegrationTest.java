package dev.lumjahaj.subscription.hub.audit.api;

import com.fasterxml.jackson.databind.JsonNode;
import dev.lumjahaj.subscription.hub.audit.app.AuditService;
import dev.lumjahaj.subscription.hub.audit.domain.AuditEventType;
import dev.lumjahaj.subscription.hub.auth.api.dto.TokenRequest;
import dev.lumjahaj.subscription.hub.auth.api.dto.TokenResponse;
import dev.lumjahaj.subscription.hub.tenancy.domain.TenantContext;
import dev.lumjahaj.subscription.hub.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The audit module on its own: how events are written and who may read
 * them. Which use cases record which events is covered where those use
 * cases are tested.
 *
 * Every test uses a fresh random entity id and filters on it, so events
 * written by other test classes in the shared database never interfere.
 */
class AuditIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AuditService auditService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void recordedEvent_isReadableByTheTenantsAdmin() {
        String subscriptionId = UUID.randomUUID().toString();
        recordInTransaction("acme", subscriptionId);

        JsonNode events = historyOf("acme", subscriptionId);

        assertThat(events).hasSize(1);
        JsonNode event = events.get(0);
        assertThat(event.get("type").asText()).isEqualTo("SUBSCRIPTION_CANCELED");
        assertThat(event.get("entityType").asText()).isEqualTo("SUBSCRIPTION");
        assertThat(event.get("entityId").asText()).isEqualTo(subscriptionId);
        // Written from a test thread with no authentication, which is
        // exactly what a scheduled job looks like.
        assertThat(event.get("actorType").asText()).isEqualTo("SYSTEM");
        assertThat(event.get("actorId").isNull()).isTrue();
        assertThat(event.get("data").get("reason").asText()).isEqualTo("test");
    }

    @Test
    void recordOutsideATransaction_isRefused() {
        String subscriptionId = UUID.randomUUID().toString();

        assertThatThrownBy(() -> TenantContext.runAs("acme", () -> auditService.record(
                AuditEventType.SUBSCRIPTION_CANCELED, subscriptionId, Map.of())))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void rolledBackChange_leavesNoEvent() {
        String subscriptionId = UUID.randomUUID().toString();

        TenantContext.runAs("acme", () -> transactionTemplate.executeWithoutResult(status -> {
            auditService.record(AuditEventType.SUBSCRIPTION_CANCELED, subscriptionId, Map.of());
            status.setRollbackOnly();
        }));

        assertThat(historyOf("acme", subscriptionId)).isEmpty();
    }

    @Test
    void anotherTenant_cannotSeeTheEvent() {
        String subscriptionId = UUID.randomUUID().toString();
        recordInTransaction("acme", subscriptionId);

        assertThat(historyOf("demo", subscriptionId)).isEmpty();
    }

    @Test
    void nonAdminRole_isForbidden() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(supportToken());

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/audit-events", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void entityTypeWithoutEntityId_isBadRequest() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/audit-events?entityType=SUBSCRIPTION", HttpMethod.GET,
                new HttpEntity<>(tenantHeaders("acme")), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("AUDIT_FILTER_INCOMPLETE");
    }

    private void recordInTransaction(String tenantId, String subscriptionId) {
        TenantContext.runAs(tenantId, () -> transactionTemplate.executeWithoutResult(status ->
                auditService.record(AuditEventType.SUBSCRIPTION_CANCELED, subscriptionId, Map.of("reason", "test"))));
    }

    private JsonNode historyOf(String tenantId, String subscriptionId) {
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                "/api/audit-events?entityType=SUBSCRIPTION&entityId=" + subscriptionId, HttpMethod.GET,
                new HttpEntity<>(tenantHeaders(tenantId)), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().get("content");
    }

    private String supportToken() {
        ResponseEntity<TokenResponse> response = restTemplate.exchange(
                "/api/auth/token", HttpMethod.POST,
                new HttpEntity<>(new TokenRequest("acme", "support@acme.test", "subscriptionhub")),
                TokenResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().token();
    }
}
