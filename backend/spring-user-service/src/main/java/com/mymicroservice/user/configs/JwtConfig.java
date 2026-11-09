package com.mymicroservice.user.configs;

import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.io.Resource;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.mymicroservice.user.properties.JwtProperties;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
// 注意是 Nimbus 的 SecurityContext，不是 Spring Security 的
import com.nimbusds.jose.proc.SecurityContext;

// 啟動時讀進 RSA 金鑰對，提供登入簽發 JWT 用的 JwtEncoder
// 金鑰讀不到或格式錯誤時直接啟動失敗，不等到第一次登入才發現
@Configuration
public class JwtConfig {

    // 單獨做成 bean：JWKS endpoint 會注入同一把 RSAKey 輸出公鑰，確保 kid 與簽章用的金鑰一致
    @Bean
    public RSAKey rsaKey(JwtProperties jwtProperties) {
        RSAPublicKey publicKey = readKey(jwtProperties.publicKeyLocation(), RsaKeyConverters.x509());
        RSAPrivateKey privateKey = readKey(jwtProperties.privateKeyLocation(), RsaKeyConverters.pkcs8());

        // keyID 一定要設：JWT header 的 kid 與 JWKS 裡的 kid 都來自這裡，Gateway 靠它找公鑰
        return new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(jwtProperties.keyId())
                .build();
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
