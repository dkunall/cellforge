package com.cellforge.edge.billing.domain;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * {@code CellForge-Signature: t=<unix seconds>,v1=<hex hmac-sha256 of "t.body">}. Signing the timestamp with
 * the body is what lets the tolerance window stop replays of an old, validly signed request.
 */
public final class WebhookSignature {

    public static final Duration TOLERANCE = Duration.ofMinutes(5);

    private WebhookSignature() {
    }

    public static String header(byte[] secret, Instant at, String body) {
        long t = at.getEpochSecond();
        return "t=" + t + ",v1=" + hmac(secret, t + "." + body);
    }

    public static boolean verify(byte[] secret, String header, String body, Instant now) {
        if (header == null) {
            return false;
        }
        Long t = null;
        String v1 = null;
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if (kv[0].equals("t")) {
                try {
                    t = Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if (kv[0].equals("v1")) {
                v1 = kv[1];
            }
        }
        if (t == null || v1 == null) {
            return false;
        }
        Duration age = Duration.between(Instant.ofEpochSecond(t), now).abs();
        if (age.compareTo(TOLERANCE) > 0) {
            return false;
        }
        byte[] expected = hmac(secret, t + "." + body).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, v1.getBytes(StandardCharsets.US_ASCII));
    }

    private static String hmac(byte[] secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
