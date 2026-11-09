package com.mymicroservice.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.mymicroservice.user.TestcontainersConfiguration;
import com.mymicroservice.user.properties.JwtProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

// 金鑰輪替期間的狀態：目前的金鑰是 test-*.pem，輪替前的舊公鑰是 test-previous-public.pem
// 用 JWKS 回傳的內容建立 decoder，模擬 Gateway 依 kid 挑公鑰驗簽
@SpringBootTest(properties = {
        "jwt.previous-keys[0].key-id=user-service-test-previous",
        "jwt.previous-keys[0].public-key-location=classpath:jwt/test-previous-public.pem" })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class JwksKeyRotationIntegrationTests {
    private static final String JWKS_URL = "/api/v1/auth/jwks";
    private static final String PREVIOUS_KEY_ID = "user-service-test-previous";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JwtProperties jwtProperties;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE user_service.users CASCADE");
    }

    @Test
    void jwks_publishesCurrentKeyFirstThenPreviousKeys() throws Exception {
        mockMvc.perform(get(JWKS_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys", hasSize(2)))
                .andExpect(jsonPath("$.keys[*].kid", contains(jwtProperties.keyId(), PREVIOUS_KEY_ID)))
                .andExpect(jsonPath("$.keys[1].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[1].use").value("sig"))
                .andExpect(jsonPath("$.keys[1].alg").value("RS256"));
    }

    @Test
    void jwks_doesNotExposePrivateParametersOfAnyKey() throws Exception {
        String json = mockMvc.perform(get(JWKS_URL)).andReturn().getResponse().getContentAsString();

        for (String privateParam : List.of("d", "p", "q", "dp", "dq", "qi")) {
            assertThat((List<?>) JsonPath.read(json, "$.keys[*]." + privateParam)).isEmpty();
        }
    }

    @Test
    void login_signsWithCurrentKeyOnly() throws Exception {
        Jwt jwt = jwksDecoder().decode(loginAccessToken());

        assertThat(jwt.getHeaders()).containsEntry("kid", jwtProperties.keyId());
    }

    @Test
    void tokenSignedBeforeRotation_isStillVerifiableThroughJwks() throws Exception {
        // 輪替前用舊私鑰簽發、尚未到期的 access token
        String oldToken = sign(readRsaKey("jwt/test-previous-private.pem", "jwt/test-previous-public.pem",
                PREVIOUS_KEY_ID));

        Jwt jwt = jwksDecoder().decode(oldToken);

        assertThat(jwt.getHeaders()).containsEntry("kid", PREVIOUS_KEY_ID);
        assertThat(jwt.getSubject()).isEqualTo("user-before-rotation");
    }

    @Test
    void tokenSignedWithUnpublishedKey_isRejected() throws Exception {
        // 舊公鑰從設定移除後（或根本不是我們的金鑰），用它簽的 token 驗不過
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var keyPair = generator.generateKeyPair();
        RSAKey unknownKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID("removed-key")
                .build();

        String token = sign(unknownKey);
        JwtDecoder decoder = jwksDecoder();

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    // ---------- helpers ----------

    // 以 JWKS endpoint 回傳的 JSON 建立 decoder，依 token header 的 kid 選公鑰，和 Gateway 的作法相同
    private JwtDecoder jwksDecoder() throws Exception {
        String json = mockMvc.perform(get(JWKS_URL)).andReturn().getResponse().getContentAsString();
        JWKSet published = JWKSet.parse(json);

        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(
                new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, new ImmutableJWKSet<>(published)));
        return new NimbusJwtDecoder(processor);
    }

    private String loginAccessToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "alice@example.com", "password": "password123", "displayName": "Alice"}
                        """))
                .andExpect(status().isCreated());
        String response = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "alice@example.com", "password": "password123"}
                        """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.accessToken");
    }

    private String sign(RSAKey key) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .subject("user-before-rotation")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)))
                .encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();
    }

    private static RSAKey readRsaKey(String privatePath, String publicPath, String keyId) throws Exception {
        try (InputStream privateIn = new ClassPathResource(privatePath).getInputStream();
                InputStream publicIn = new ClassPathResource(publicPath).getInputStream()) {
            return new RSAKey.Builder(RsaKeyConverters.x509().convert(publicIn))
                    .privateKey(RsaKeyConverters.pkcs8().convert(privateIn))
                    .keyID(keyId)
                    .build();
        }
    }
}
