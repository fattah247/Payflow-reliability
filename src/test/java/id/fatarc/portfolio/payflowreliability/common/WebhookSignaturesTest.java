package id.fatarc.portfolio.payflowreliability.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignaturesTest {
    private static final byte[] BODY = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);

    @Test
    void shouldSignTheTimestampTogetherWithTheBody() {
        String early = WebhookSignatures.sign("secret", 1_700_000_000L, BODY);
        String late = WebhookSignatures.sign("secret", 1_700_000_001L, BODY);

        assertThat(early).startsWith("t=1700000000,v1=");
        assertThat(early.substring(early.indexOf("v1="))).isNotEqualTo(late.substring(late.indexOf("v1=")));
    }

    @Test
    void shouldParseSeveralSignaturesSoTheProviderCanSignWithOldAndNewSecrets() {
        WebhookSignatures.Parsed parsed = WebhookSignatures.parse("t=1700000000,v1=aa,v1=bb");

        assertThat(parsed.timestamp()).isEqualTo(1_700_000_000L);
        assertThat(parsed.signatures()).containsExactly("aa", "bb");
    }

    @Test
    void shouldTreatMalformedHeadersAsMissing() {
        assertThat(WebhookSignatures.parse(null)).isNull();
        assertThat(WebhookSignatures.parse("local-demo-signature")).isNull();
        assertThat(WebhookSignatures.parse("t=abc,v1=aa")).isNull();
        assertThat(WebhookSignatures.parse("t=1700000000")).isNull();
    }
}
