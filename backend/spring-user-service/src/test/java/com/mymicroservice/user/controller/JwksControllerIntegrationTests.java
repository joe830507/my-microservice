package com.mymicroservice.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.mymicroservice.user.TestcontainersConfiguration;
import com.mymicroservice.user.properties.JwtProperties;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;

// GET /api/v1/auth/jwks 的整合測試：只公開公鑰，且 Gateway 拿這份 JWKS 就能驗過登入簽出的 token
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class JwksControllerIntegrationTests {
    private static final String JWKS_URL = "/api/v1/auth/jwks";

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
    void jwks_returnsSingleRsaSigningKeyWithConfiguredKid() throws Exception {
        mockMvc.perform(get(JWKS_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys", hasSize(1)))
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].kid").value(jwtProperties.keyId()))
                .andExpect(jsonPath("$.keys[0].use").value("sig"))
                .andExpect(jsonPath("$.keys[0].alg").value("RS256"))
                .andExpect(jsonPath("$.keys[0].n").isNotEmpty())
                .andExpect(jsonPath("$.keys[0].e").isNotEmpty());
    }

    @Test
    void jwks_doesNotExposePrivateKeyParameters() throws Exception {
        // RSA 私鑰的所有參數（RFC 7518 6.3.2），任何一個出現都代表私鑰外洩
        var result = mockMvc.perform(get(JWKS_URL)).andExpect(status().isOk());
        for (String privateParam : new String[] { "d", "p", "q", "dp", "dq", "qi", "oth" }) {
            result.andExpect(jsonPath("$.keys[0]." + privateParam).doesNotExist());
        }

        // 再從解析的角度確認：讀回來的 JWK 不是私鑰
        String body = result.andReturn().getResponse().getContentAsString();
        RSAKey key = JWKSet.parse(body).getKeys().get(0).toRSAKey();
        assertThat(key.isPrivate()).isFalse();
    }

    @Test
    void jwks_allowsCaching() throws Exception {
        mockMvc.perform(get(JWKS_URL))
                .andExpect(header().string("Cache-Control", containsString("max-age=300")))
                .andExpect(header().string("Cache-Control", containsString("public")));
    }

    @Test
    void jwks_canVerifyTokenIssuedByLogin() throws Exception {
        // 模擬 Gateway：只拿 JWKS 的公鑰，就能驗過登入簽出的 token
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "alice@example.com", "password": "password123", "displayName": "Alice"}
                        """))
                .andExpect(status().isCreated());
        String loginBody = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "alice@example.com", "password": "password123"}
                        """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String accessToken = JsonPath.read(loginBody, "$.accessToken");

        String jwksBody = mockMvc.perform(get(JWKS_URL))
                .andReturn().getResponse().getContentAsString();
        RSAKey publicJwk = JWKSet.parse(jwksBody).getKeyByKeyId(jwtProperties.keyId()).toRSAKey();

        Jwt jwt = NimbusJwtDecoder.withPublicKey(publicJwk.toRSAPublicKey()).build().decode(accessToken);

        assertThat(jwt.getHeaders()).containsEntry("kid", jwtProperties.keyId());
        assertThat(jwt.getClaims()).containsKey("roles");
        assertThat(Map.copyOf(jwt.getHeaders())).containsEntry("alg", "RS256");
    }
}
