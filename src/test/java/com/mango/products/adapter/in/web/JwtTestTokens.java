package com.mango.products.adapter.in.web;

import java.io.InputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import org.springframework.security.converter.RsaKeyConverters;

final class JwtTestTokens {

    static final String ISSUER = "products-challenge-dev";
    static final String AUDIENCE = "products-api";
    private static final RSAPrivateKey PRIVATE_KEY = loadPrivateKey();
    private static final RSAPrivateKey WRONG_PRIVATE_KEY = generateWrongPrivateKey();

    private JwtTestTokens() {
    }

    static String reader() {
        return token(JWSAlgorithm.RS256, PRIVATE_KEY, ISSUER, AUDIENCE, "products.read",
                Instant.now().minusSeconds(5), Instant.now().minusSeconds(5), Instant.now().plusSeconds(900));
    }

    static String writer() {
        return token(JWSAlgorithm.RS256, PRIVATE_KEY, ISSUER, AUDIENCE,
                "products.read products.write", Instant.now().minusSeconds(5),
                Instant.now().minusSeconds(5), Instant.now().plusSeconds(900));
    }

    static String token(
            JWSAlgorithm algorithm,
            RSAPrivateKey privateKey,
            String issuer,
            String audience,
            String scope,
            Instant issuedAt,
            Instant notBefore,
            Instant expiresAt
    ) {
        try {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .subject("security-test")
                    .issuer(issuer)
                    .issueTime(Date.from(issuedAt));
            if (audience != null) {
                claims.audience(List.of(audience));
            }
            if (scope != null) {
                claims.claim("scope", scope);
            }
            if (notBefore != null) {
                claims.notBeforeTime(Date.from(notBefore));
            }
            if (expiresAt != null) {
                claims.expirationTime(Date.from(expiresAt));
            }
            SignedJWT jwt = new SignedJWT(new JWSHeader(algorithm), claims.build());
            jwt.sign(new RSASSASigner(privateKey));
            return jwt.serialize();
        }
        catch (Exception exception) {
            throw new IllegalStateException("Could not create test JWT", exception);
        }
    }

    static String wrongSignature() {
        return token(JWSAlgorithm.RS256, WRONG_PRIVATE_KEY, ISSUER, AUDIENCE, "products.read",
                Instant.now().minusSeconds(5), Instant.now().minusSeconds(5), Instant.now().plusSeconds(900));
    }

    static String tampered() {
        String token = reader();
        String[] parts = token.split("\\.");
        char replacement = parts[2].charAt(0) == 'A' ? 'B' : 'A';
        parts[2] = replacement + parts[2].substring(1);
        return String.join(".", parts);
    }

    static RSAPrivateKey privateKey() {
        return PRIVATE_KEY;
    }

    private static RSAPrivateKey loadPrivateKey() {
        try (InputStream input = JwtTestTokens.class.getResourceAsStream("/security/test-private-key.pem")) {
            if (input == null) {
                throw new IllegalStateException("Test private key was not found");
            }
            return RsaKeyConverters.pkcs8().convert(input);
        }
        catch (Exception exception) {
            throw new IllegalStateException("Could not read test private key", exception);
        }
    }

    private static RSAPrivateKey generateWrongPrivateKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            return (RSAPrivateKey) keyPair.getPrivate();
        }
        catch (Exception exception) {
            throw new IllegalStateException("Could not generate alternate test key", exception);
        }
    }
}
