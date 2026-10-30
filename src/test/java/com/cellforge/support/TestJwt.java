package com.cellforge.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Tokens signed with a key generated per JVM. Every app the platform harness starts trusts this key instead of
 * an identity provider, so tests go through the real security filter chains.
 */
public final class TestJwt {

    private static final KeyPair KEYS = generate();

    private TestJwt() {
    }

    /** A person, identified by their user id, with an email claim derived from it. */
    public static String user(String userId) {
        return token(userId, userId + "@example.test", "cellforge.user");
    }

    public static String user(String userId, String email) {
        return token(userId, email, "cellforge.user");
    }

    public static String service(String clientId, String... scopes) {
        return token(clientId, null, scopes);
    }

    public static String token(String subject, String email, String... scopes) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
            .issuer("http://test-issuer/cellforge")
            .subject(subject)
            .jwtID(UUID.randomUUID().toString())
            .issueTime(new Date())
            .expirationTime(Date.from(Instant.now().plusSeconds(900)))
            .claim("scope", String.join(" ", scopes));
        if (email != null) {
            claims.claim("email", email);
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
        try {
            jwt.sign(new RSASSASigner(KEYS.getPrivate()));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }

    public static JwtDecoder decoder() {
        return NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
