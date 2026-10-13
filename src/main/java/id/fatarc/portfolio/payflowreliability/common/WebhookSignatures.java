package id.fatarc.portfolio.payflowreliability.common;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Webhook signature format: {@code t=<unix seconds>,v1=<hex HMAC-SHA256(secret, "<t>." + raw body)>}.
 * Several {@code v1} values may be present while a secret is being rotated.
 */
public final class WebhookSignatures {
    private WebhookSignatures() {
    }

    public static String sign(String secret, long epochSeconds, byte[] body) {
        return "t=" + epochSeconds + ",v1=" + Hashing.hmacSha256Hex(secret, signedPayload(epochSeconds, body));
    }

    static byte[] signedPayload(long epochSeconds, byte[] body) {
        byte[] prefix = (epochSeconds + ".").getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, payload, 0, prefix.length);
        System.arraycopy(body, 0, payload, prefix.length, body.length);
        return payload;
    }

    public static String expectedSignature(String secret, long epochSeconds, byte[] body) {
        return Hashing.hmacSha256Hex(secret, signedPayload(epochSeconds, body));
    }

    /** Parsed header, or null when it is not in the expected format. */
    public static Parsed parse(String header) {
        if (header == null || header.isBlank() || header.length() > 1000) {
            return null;
        }
        Long timestamp = null;
        List<String> signatures = new ArrayList<>();
        for (String part : header.split(",")) {
            String[] keyValue = part.trim().split("=", 2);
            if (keyValue.length != 2) {
                return null;
            }
            switch (keyValue[0]) {
                case "t" -> {
                    try {
                        timestamp = Long.parseLong(keyValue[1]);
                    } catch (NumberFormatException ex) {
                        return null;
                    }
                }
                case "v1" -> signatures.add(keyValue[1]);
                default -> {
                }
            }
        }
        return timestamp == null || signatures.isEmpty() ? null : new Parsed(timestamp, signatures);
    }

    public record Parsed(long timestamp, List<String> signatures) {
    }
}
