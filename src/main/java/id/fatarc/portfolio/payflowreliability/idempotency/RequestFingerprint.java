package id.fatarc.portfolio.payflowreliability.idempotency;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import id.fatarc.portfolio.payflowreliability.common.Hashing;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What "the same request" means for an idempotency key: a SHA-256 over a canonical form of the request,
 * so a retry is recognized even when the client serializes it differently, and a different request under
 * the same key is refused instead of being answered with someone else's result.
 *
 * <ul>
 *   <li><b>Business requests</b> (payment and refund creation) are fingerprinted from their fields, not
 *       their bytes: {@code 50000} and {@code 50000.00} are the same amount. Fields are sorted by name and
 *       length-prefixed, so no two different requests can produce the same input. A field that is null is
 *       left out, which is what lets an optional field be added later without changing the fingerprint of
 *       every request stored before it existed.</li>
 *   <li><b>Operator calls</b> (any POST through {@link IdempotencyFilter}) are fingerprinted from the method,
 *       the path, the query parameters in sorted order, and the JSON body in canonical form: object keys
 *       sorted, insignificant whitespace removed, numbers compared by value. A body that is not JSON (a
 *       bank statement file, for example) is hashed byte for byte.</li>
 * </ul>
 *
 * The operation is always part of the fingerprint, so a key reused for a different operation never matches.
 */
public final class RequestFingerprint {
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .build();

    private RequestFingerprint() {
    }

    /**
     * @param operation names the business operation, for example {@code payment-intents.create}
     * @param fields    the request's business fields; null values are left out
     */
    public static String of(String operation, Map<String, ?> fields) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, "operation", operation);
        new TreeMap<>(fields).forEach((name, value) -> {
            if (value != null) {
                append(canonical, name, value instanceof BigDecimal amount ? plain(amount) : value.toString());
            }
        });
        return Hashing.sha256Hex(canonical.toString());
    }

    /** The fingerprint of an HTTP call: method, path, sorted query parameters and the canonical body. */
    public static String ofHttp(String method, String path, String query, byte[] body) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, "method", method);
        append(canonical, "path", path);
        append(canonical, "query", canonicalQuery(query));
        append(canonical, "body", canonicalBody(body));
        return Hashing.sha256Hex(canonical.toString());
    }

    static String canonicalQuery(String query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        String[] parameters = query.split("&");
        Arrays.sort(parameters);
        return String.join("&", parameters);
    }

    static String canonicalBody(byte[] body) {
        if (body == null || body.length == 0) {
            return "empty";
        }
        try {
            JsonNode node = JSON.readTree(body);
            if (node == null || node.isMissingNode()) {
                return "empty";
            }
            StringBuilder canonical = new StringBuilder("json:");
            write(canonical, node);
            return canonical.toString();
        } catch (IOException notJson) {
            return "bytes:" + Hashing.sha256Hex(body);
        }
    }

    private static void write(StringBuilder out, JsonNode node) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            Iterator<String> iterator = node.fieldNames();
            iterator.forEachRemaining(names::add);
            names.sort(String::compareTo);
            out.append('{');
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(JSON.getNodeFactory().textNode(names.get(i))).append(':');
                write(out, node.get(names.get(i)));
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(out, node.get(i));
            }
            out.append(']');
        } else if (node.isNumber()) {
            out.append(plain(node.decimalValue()));
        } else {
            out.append(node);
        }
    }

    private static String plain(BigDecimal amount) {
        return amount.signum() == 0 ? "0" : amount.stripTrailingZeros().toPlainString();
    }

    private static void append(StringBuilder canonical, String name, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        canonical.append(name).append('=').append(bytes.length).append(':').append(value).append('\n');
    }
}
