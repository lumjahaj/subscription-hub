package dev.lumjahaj.subscription.hub.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * A minimal client for Mailpit's REST API, used only by tests to assert an
 * email actually arrived - the application itself only ever sends over
 * SMTP and never calls this API. Field names below (Subject, ID, Text,
 * HTML, Attachments, FileName, ContentType) come straight from Mailpit's
 * {@code storage.Message} / {@code storage.MessageSummary} Go structs,
 * which have no JSON tags and so serialize with their exported Go names.
 */
public final class MailpitTestClient {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;

    public MailpitTestClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** Every message currently in the mailbox, newest first, as summaries. */
    public List<JsonNode> listMessages() {
        JsonNode root = get("/api/v1/messages");
        List<JsonNode> result = new ArrayList<>();
        root.path("messages").forEach(result::add);
        return result;
    }

    /** The full message (Text, HTML, Attachments) by its Mailpit id. */
    public JsonNode getMessage(String id) {
        return get("/api/v1/message/" + id);
    }

    /**
     * The full message whose subject contains the given text, or null if
     * none has arrived yet - callers poll this with Awaitility, since
     * delivery is asynchronous (relay -> SQS -> listener).
     */
    public JsonNode findBySubjectContaining(String fragment) {
        return listMessages().stream()
                .filter(summary -> summary.path("Subject").asText("").contains(fragment))
                .findFirst()
                .map(summary -> getMessage(summary.path("ID").asText()))
                .orElse(null);
    }

    /**
     * The full message whose subject and text body both contain the given
     * fragments, or null if none has arrived yet.
     *
     * The subject alone does not always identify a message. The cancellation
     * email names no invoice, and the mailbox is shared by every test in the
     * JVM - including notifications a different class enqueued, because
     * NotificationRelayJob claims every active tenant's PENDING rows rather
     * than only the ones the calling test created. Matching on the invoice
     * number in the body is what makes such an assertion unambiguous.
     */
    public JsonNode findBySubjectAndTextContaining(String subjectFragment, String textFragment) {
        return listMessages().stream()
                .filter(summary -> summary.path("Subject").asText("").contains(subjectFragment))
                .map(summary -> getMessage(summary.path("ID").asText()))
                .filter(message -> message.path("Text").asText("").contains(textFragment))
                .findFirst()
                .orElse(null);
    }

    private JsonNode get(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            return MAPPER.readTree(response.body());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to call Mailpit API at " + baseUrl + path, e);
        }
    }
}
