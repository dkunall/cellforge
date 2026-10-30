package com.cellforge.edge.billing.adapter.payments;

import com.cellforge.edge.billing.application.PaymentProvider;
import com.cellforge.edge.billing.domain.WebhookSignature;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Stands in for a card processor. It never sees card numbers, only tokens the client got from the
 * processor's own form, and it reports outcomes the way a real one does: later, over a signed webhook.
 * Cards ending 0002 are declined, like the usual test cards.
 */
@Component
class FakePaymentProvider implements PaymentProvider {

    private static final Map<String, String> TOKENS = Map.of("tok_visa", "4242", "tok_mastercard", "4444",
        "tok_declined", "0002");
    private static final Logger log = LoggerFactory.getLogger(FakePaymentProvider.class);

    private final RestClient http = RestClient.create();
    private final ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor();
    private final ObjectMapper json;
    private final Environment env;
    private final Clock clock;
    private final byte[] secret;

    FakePaymentProvider(ObjectMapper json, Environment env, Clock clock,
                        @Value("${cellforge.payments.webhook-secret}") String secret) {
        this.json = json;
        this.env = env;
        this.clock = clock;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public Optional<String> cardFor(String token) {
        return Optional.ofNullable(TOKENS.get(token));
    }

    @Override
    public void charge(Charge charge) {
        boolean ok = charge.cardLast4() != null && !charge.cardLast4().equals("0002");
        // One event per attempt, so a resent webhook is recognisably the same event.
        String eventId = "evt_" + charge.invoiceId() + "_" + charge.attempt();
        String body;
        try {
            body = json.writeValueAsString(Map.of("id", eventId,
                "type", ok ? "payment.succeeded" : "payment.failed",
                "data", Map.of("invoiceId", charge.invoiceId().toString(), "amountCents", charge.amountCents(),
                    "reason", ok ? "" : charge.cardLast4() == null ? "no-payment-method" : "card-declined")));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        senders.submit(() -> deliver(body));
    }

    private void deliver(String body) {
        // The listen port is only known once the server is up, so resolve the URL at send time.
        String url = env.getProperty("cellforge.payments.webhook-url",
            "http://localhost:" + env.getProperty("local.server.port", "7890") + "/webhooks/payments");
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                http.post().uri(url).contentType(MediaType.APPLICATION_JSON)
                    .header("Payment-Signature", WebhookSignature.header(secret, clock.instant(), body))
                    .body(body).retrieve().toBodilessEntity();
                return;
            } catch (RestClientException e) {
                log.warn("Webhook delivery attempt {} failed: {}", attempt, e.getMessage());
                try {
                    Thread.sleep(200L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
