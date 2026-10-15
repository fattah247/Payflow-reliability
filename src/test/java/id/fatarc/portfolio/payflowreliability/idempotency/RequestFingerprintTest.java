package id.fatarc.portfolio.payflowreliability.idempotency;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** "The same request" is decided by meaning, not by bytes, and never across operations. */
class RequestFingerprintTest {

    private static String http(String query, String body) {
        return RequestFingerprint.ofHttp("POST", "/api/reconciliation/reports", query,
                body == null ? null : body.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> fields(Object... namesAndValues) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            fields.put((String) namesAndValues[i], namesAndValues[i + 1]);
        }
        return fields;
    }

    @Test
    void shouldIgnoreKeyOrderWhitespaceAndNumberFormattingInJsonBodies() {
        String compact = "{\"rows\":[{\"providerTransactionId\":\"tx-1\",\"amount\":1000,\"currency\":\"IDR\"}]}";
        String reformatted = """
                {
                  "rows": [ { "currency": "IDR", "amount": 1000.00, "providerTransactionId": "tx-1" } ]
                }""";

        assertThat(http(null, reformatted)).isEqualTo(http(null, compact));
    }

    @Test
    void shouldTellDifferentValuesArrayOrderAndTypesApart() {
        String base = http(null, "{\"rows\":[{\"amount\":1000},{\"amount\":2000}]}");

        assertThat(http(null, "{\"rows\":[{\"amount\":1001},{\"amount\":2000}]}")).isNotEqualTo(base);
        assertThat(http(null, "{\"rows\":[{\"amount\":2000},{\"amount\":1000}]}")).as("array order is meaningful").isNotEqualTo(base);
        assertThat(http(null, "{\"rows\":[{\"amount\":\"1000\"},{\"amount\":2000}]}")).as("a string is not a number").isNotEqualTo(base);
    }

    @Test
    void shouldSortQueryParametersAndDistinguishPathsAndMethods() {
        String fingerprint = RequestFingerprint.ofHttp("POST", "/api/settlements/batches", "merchantId=m&currency=IDR", null);

        assertThat(RequestFingerprint.ofHttp("POST", "/api/settlements/batches", "currency=IDR&merchantId=m", null))
                .isEqualTo(fingerprint);
        assertThat(RequestFingerprint.ofHttp("POST", "/api/outbox/run", "currency=IDR&merchantId=m", null)).isNotEqualTo(fingerprint);
        assertThat(RequestFingerprint.ofHttp("PUT", "/api/settlements/batches", "merchantId=m&currency=IDR", null)).isNotEqualTo(fingerprint);
    }

    @Test
    void shouldHashABodyThatIsNotJsonByteForByte() {
        String statement = "<Document><BkToCstmrStmt/></Document>";

        assertThat(http(null, statement)).isEqualTo(http(null, statement));
        assertThat(http(null, statement + " ")).isNotEqualTo(http(null, statement));
        assertThat(http(null, "{\"a\":1,\"a\":2}")).as("duplicate keys are not valid JSON; hashed as bytes")
                .isNotEqualTo(http(null, "{\"a\":2,\"a\":1}"));
    }

    @Test
    void shouldCompareBusinessFieldsByValueAndLeaveOutNullFields() {
        String fingerprint = RequestFingerprint.of("payment-intents.create",
                fields("merchantReference", "ORDER-1", "amount", new BigDecimal("50000"), "currency", "IDR"));

        assertThat(RequestFingerprint.of("payment-intents.create",
                fields("currency", "IDR", "amount", new BigDecimal("50000.0000"), "merchantReference", "ORDER-1")))
                .isEqualTo(fingerprint);
        // An optional field added later (null for every old request) does not change old fingerprints.
        assertThat(RequestFingerprint.of("payment-intents.create",
                fields("merchantReference", "ORDER-1", "amount", new BigDecimal("50000"), "currency", "IDR", "action", null)))
                .isEqualTo(fingerprint);
        assertThat(RequestFingerprint.of("refunds.create",
                fields("merchantReference", "ORDER-1", "amount", new BigDecimal("50000"), "currency", "IDR")))
                .as("the operation is part of the fingerprint").isNotEqualTo(fingerprint);
    }

    @Test
    void shouldNotLetOneFieldsValueImitateAnother() {
        assertThat(RequestFingerprint.of("op", fields("a", "x\nb=1:y")))
                .isNotEqualTo(RequestFingerprint.of("op", fields("a", "x", "b", "y")));
    }
}
