package com.cellforge.edge.billing.adapter.web;

import com.cellforge.edge.billing.application.Billing;
import com.cellforge.edge.billing.domain.WebhookSignature;
import com.cellforge.shared.error.InvalidRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Unauthenticated by design: the signature over the raw body is the authentication. */
@RestController
class PaymentWebhookController {

    private final Billing billing;
    private final ObjectMapper json;
    private final Clock clock;
    private final byte[] secret;

    PaymentWebhookController(Billing billing, ObjectMapper json, Clock clock,
                             @Value("${cellforge.payments.webhook-secret}") String secret) {
        this.billing = billing;
        this.json = json;
        this.clock = clock;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/webhooks/payments")
    Map<String, Object> receive(@RequestHeader(name = "Payment-Signature", required = false) String signature,
                                @RequestBody String body) {
        if (signature == null || !WebhookSignature.verify(secret, signature, body, clock.instant())) {
            throw new InvalidRequestException("invalid-signature", "Webhook signature did not verify");
        }
        JsonNode event;
        try {
            event = json.readTree(body);
        } catch (IOException e) {
            throw new InvalidRequestException("invalid-body", "Webhook body is not JSON");
        }
        String id = event.path("id").asText("");
        String type = event.path("type").asText("");
        String invoice = event.path("data").path("invoiceId").asText("");
        if (id.isEmpty() || invoice.isEmpty()) {
            throw new InvalidRequestException("invalid-body", "Webhook needs id and data.invoiceId");
        }
        UUID invoiceId;
        try {
            invoiceId = UUID.fromString(invoice);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("invalid-body", "data.invoiceId is not a uuid");
        }
        billing.onPaymentEvent(id, type, invoiceId);
        return Map.of("received", true);
    }
}
