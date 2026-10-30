package com.cellforge.edge.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WebhookSignatureTest {

    static final byte[] SECRET = "test-only-signing-key".getBytes(StandardCharsets.UTF_8);
    static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");
    static final String BODY = "{\"id\":\"evt_1\",\"type\":\"payment.succeeded\"}";

    @Test
    void acceptsWhatItSigned() {
        assertThat(WebhookSignature.verify(SECRET, WebhookSignature.header(SECRET, NOW, BODY), BODY, NOW)).isTrue();
    }

    @Test
    void rejectsAChangedBody() {
        String header = WebhookSignature.header(SECRET, NOW, BODY);
        assertThat(WebhookSignature.verify(SECRET, header, BODY.replace("succeeded", "failed"), NOW)).isFalse();
    }

    @Test
    void rejectsAnotherKey() {
        String header = WebhookSignature.header("someone-else".getBytes(StandardCharsets.UTF_8), NOW, BODY);
        assertThat(WebhookSignature.verify(SECRET, header, BODY, NOW)).isFalse();
    }

    @Test
    void rejectsReplaysOutsideTheWindow() {
        String header = WebhookSignature.header(SECRET, NOW, BODY);
        assertThat(WebhookSignature.verify(SECRET, header, BODY, NOW.plus(Duration.ofMinutes(4)))).isTrue();
        assertThat(WebhookSignature.verify(SECRET, header, BODY, NOW.plus(Duration.ofMinutes(6)))).isFalse();
    }

    @Test
    void rejectsMalformedHeaders() {
        assertThat(WebhookSignature.verify(SECRET, null, BODY, NOW)).isFalse();
        assertThat(WebhookSignature.verify(SECRET, "garbage", BODY, NOW)).isFalse();
        assertThat(WebhookSignature.verify(SECRET, "t=abc,v1=00", BODY, NOW)).isFalse();
        assertThat(WebhookSignature.verify(SECRET, "t=" + NOW.getEpochSecond(), BODY, NOW)).isFalse();
    }
}
