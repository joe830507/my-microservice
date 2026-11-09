package com.mymicroservice.user.configs;

import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.io.Resource;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.mymicroservice.user.properties.JwtProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
// 注意是 Nimbus 的 SecurityContext，不是 Spring Security 的
import com.nimbusds.jose.proc.SecurityContext;

// 啟動時讀進 RSA 金鑰對，提供登入簽發 JWT 用的 JwtEncoder
// 金鑰讀不到或格式錯誤時直接啟動失敗，不等到第一次登入才發現
@Configuration
public class JwtConfig {

    // 目前的簽章金鑰（含私鑰）；publicJwkSet 也從它取公鑰，確保 kid 與簽章用的金鑰一致
    @Bean
    public RSAKey rsaKey(JwtProperties jwtProperties) {
        RSAPublicKey publicKey = readKey(jwtProperties.publicKeyLocation(), RsaKeyConverters.x509());
        RSAPrivateKey privateKey = readKey(jwtProperties.privateKeyLocation(), RsaKeyConverters.pkcs8());

        // keyID 一定要設：JWT header 的 kid 與 JWKS 裡的 kid 都來自這裡，Gateway 靠它找公鑰
        // use、alg 會一併出現在 JWKS，標明這把金鑰只用於 RS256 簽章
        return new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(jwtProperties.keyId())
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .build();
    }

    // JWKS endpoint 發布的公鑰：目前的簽章金鑰在第一個，後面接輪替前的舊公鑰
    // 簽章只用目前的金鑰（jwtEncoder 只拿到 rsaKey），舊公鑰只用來讓 Gateway 驗輪替前簽發的 token
    // kid 重複時 Gateway 無法分辨該用哪一把，啟動時直接失敗
    @Bean
    public JWKSet publicJwkSet(RSAKey rsaKey, JwtProperties jwtProperties) {
        List<JWK> keys = new ArrayList<>();
        keys.add(rsaKey.toPublicJWK());
        for (JwtProperties.PreviousKey previousKey : jwtProperties.previousKeys()) {
            RSAPublicKey publicKey = readKey(previousKey.publicKeyLocation(), RsaKeyConverters.x509());
            keys.add(new RSAKey.Builder(publicKey)
                    .keyID(previousKey.keyId())
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .build());
        }

        Set<String> keyIds = new HashSet<>();
        for (JWK key : keys) {
            if (!keyIds.add(key.getKeyID())) {
                throw new IllegalStateException("duplicate JWT key id: " + key.getKeyID());
            }
        }
        return new JWKSet(keys);
    }

    // RSAKey → JWKSet → ImmutableJWKSet（JWKSource）→ NimbusJwtEncoder
    @Bean
    public JwtEncoder jwtEncoder(RSAKey rsaKey) {
        JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(rsaKey));
        return new NimbusJwtEncoder(jwkSource);
    }

    // 解析失敗時把檔案位置帶進錯誤訊息，方便判斷是哪一個檔案有問題
    // 格式不符時 converter 丟出的是 IllegalArgumentException，一併包起來
    private static <T> T readKey(Resource location, Converter<InputStream, T> converter) {
        try (InputStream inputStream = location.getInputStream()) {
            return converter.convert(inputStream);
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("failed to read JWT key from " + location, exception);
        }
    }
}
