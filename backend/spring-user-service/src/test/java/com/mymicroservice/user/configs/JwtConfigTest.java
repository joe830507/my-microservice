package com.mymicroservice.user.configs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.mymicroservice.user.properties.JwtProperties;
import com.mymicroservice.user.properties.JwtProperties.PreviousKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;

// 金鑰輪替設定的檢查；不啟動 Spring，直接呼叫 @Bean 方法
class JwtConfigTest {
    private final JwtConfig jwtConfig = new JwtConfig();

    @Test
    void publicJwkSet_withoutPreviousKeys_containsOnlyCurrentPublicKey() {
        JwtProperties properties = properties(List.of());
        RSAKey rsaKey = jwtConfig.rsaKey(properties);

        JWKSet jwkSet = jwtConfig.publicJwkSet(rsaKey, properties);

        assertThat(jwkSet.getKeys()).singleElement().satisfies(key -> {
            assertThat(key.getKeyID()).isEqualTo("current");
            assertThat(key.isPrivate()).isFalse();
        });
    }

    @Test
    void publicJwkSet_previousKeysArePublicOnly() {
        JwtProperties properties = properties(List.of(
                new PreviousKey("previous", new ClassPathResource("jwt/test-previous-public.pem"))));

        JWKSet jwkSet = jwtConfig.publicJwkSet(jwtConfig.rsaKey(properties), properties);

        assertThat(jwkSet.getKeys()).extracting(key -> key.getKeyID()).containsExactly("current", "previous");
        assertThat(jwkSet.getKeys()).noneMatch(key -> key.isPrivate());
    }

    @Test
    void publicJwkSet_duplicateKeyId_failsFast() {
        // 舊金鑰忘了換 kid：Gateway 無法分辨該用哪一把驗簽
        JwtProperties properties = properties(List.of(
                new PreviousKey("current", new ClassPathResource("jwt/test-previous-public.pem"))));
        RSAKey rsaKey = jwtConfig.rsaKey(properties);

        assertThatThrownBy(() -> jwtConfig.publicJwkSet(rsaKey, properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate JWT key id: current");
    }

    @Test
    void publicJwkSet_missingPreviousKeyFile_failsWithLocation() {
        JwtProperties properties = properties(List.of(
                new PreviousKey("previous", new ClassPathResource("jwt/does-not-exist.pem"))));
        RSAKey rsaKey = jwtConfig.rsaKey(properties);

        assertThatThrownBy(() -> jwtConfig.publicJwkSet(rsaKey, properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does-not-exist.pem");
    }

    private static JwtProperties properties(List<PreviousKey> previousKeys) {
        return new JwtProperties(
                new ClassPathResource("jwt/test-private.pem"),
                new ClassPathResource("jwt/test-public.pem"),
                "current",
                "user-service",
                Duration.ofMinutes(15),
                Duration.ofDays(14),
                Duration.ofSeconds(10),
                previousKeys);
    }
}
