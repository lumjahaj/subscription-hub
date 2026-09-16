package dev.lumjahaj.subscription.hub.testsupport;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Signs webhook payloads the way Stripe does, so tests can post events with
 * no Stripe account. Extracted once a second test class needed it: the
 * signing scheme is a detail of Stripe, not of any one test.
 */
public final class StripeTestSignatures {

    /** Must match AbstractIntegrationTest's stripe.webhook-secret. */
    public static final String SECRET = "whsec_test_only_webhook_signing_secret";

    private StripeTestSignatures() {
    }

    public static String sign(String payload) {
        return sign(payload, Instant.now().getEpochSecond());
    }

    /** Stripe's scheme: HMAC-SHA256 over "<timestamp>.<raw body>", hex encoded. */
    public static String sign(String payload, long timestamp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signature = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
            return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(signature);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not sign the test payload", ex);
        }
    }
}
