package com.mymicroservice.gateway;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

// 測試用的金鑰與 token：模擬 user-service 簽發 access token、發布 JWKS
final class TestJwts {

    static final String ISSUER = "user-service";
    static final String USER_ID = "42";

    // user-service 目前的簽章金鑰，JWKS 裡有它的公鑰
    static final RSAKey SIGNING_KEY = generate("test-key-1");

    private TestJwts() {
    }

    static RSAKey generate(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    // JWKS 只放公鑰，和 user-service 一樣
    static String jwks(RSAKey... keys) {
        return new JWKSet(List.of(keys)).toPublicJWKSet().toString();
    }

    static String validToken() {
        return token(SIGNING_KEY, claims -> { });
    }

    // 預設是有效的 claims，用 customizer 改成要測試的情況
    static String token(RSAKey key, Consumer<JWTClaimsSet.Builder> customizer) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(USER_ID)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(900)))
                .claim("roles", List.of("CUSTOMER"));
        customizer.accept(claims);

        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                claims.build());
        try {
            jwt.sign(new RSASSASigner(key));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }

    // 已過期，而且超過預設 60 秒的時鐘誤差容許範圍
    static String expiredToken() {
        Instant now = Instant.now();
        return token(SIGNING_KEY, claims -> claims
                .issueTime(Date.from(now.minusSeconds(1200)))
                .expirationTime(Date.from(now.minusSeconds(300))));
    }
}
