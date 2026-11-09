package com.mymicroservice.gateway.configs;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.util.StringUtils;

import com.mymicroservice.gateway.properties.JwtProperties;
import com.mymicroservice.gateway.security.ProblemDetailAuthenticationEntryPoint;
import com.mymicroservice.gateway.web.ProblemDetailWriter;

import reactor.core.publisher.Mono;

// Gateway 只確認「有登入」（JWT 有效）；角色檢查（例如後台的 ADMIN）留在下游服務的 @RequireRoles
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, ProblemDetailWriter problemDetailWriter) {
        ProblemDetailAuthenticationEntryPoint entryPoint = new ProblemDetailAuthenticationEntryPoint(problemDetailWriter);

        return http
                // 無狀態 API：不用 session、cookie，CSRF 與表單登入都不需要
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        // 登入、註冊、JWKS；refresh、logout 用 refresh token 證明身分，不需要 access token
                        .pathMatchers("/api/v1/auth/**").permitAll()
                        // 商品、分類只有讀取公開；新增、修改、刪除要登入（ADMIN 由 product-service 檢查）
                        .pathMatchers(HttpMethod.GET, "/api/v1/products/**", "/api/v1/categories/**").permitAll()
                        .pathMatchers("/actuator/health").permitAll()
                        // 其餘都要登入，包含不在路由表的路徑：沒登入的人拿到 401，不會知道哪些路徑存在
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenConverter(bearerTokenConverter())
                        .jwt(jwt -> { })
                        // 沒帶 token、token 無效都走這裡
                        .authenticationEntryPoint(entryPoint))
                // 沒經過 resource server 的情況（例如完全沒帶 Authorization）也用相同格式
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint))
                .build();
    }

    // /api/v1/auth/** 不讀 Authorization：用戶端常在 access token 過期後才呼叫 refresh，
    // 若仍帶著過期的 token，照 Spring 預設會先驗 token 而回 401，refresh 就永遠無法成功
    // 其他公開路由（GET 商品）帶了無效 token 仍回 401，用戶端 refresh 後重試即可
    private static ServerAuthenticationConverter bearerTokenConverter() {
        ServerBearerTokenAuthenticationConverter delegate = new ServerBearerTokenAuthenticationConverter();
        ServerWebExchangeMatcher authPaths = ServerWebExchangeMatchers.pathMatchers("/api/v1/auth/**");
        return exchange -> authPaths.matches(exchange)
                .flatMap(result -> result.isMatch() ? Mono.empty() : delegate.convert(exchange));
    }

    // 驗證：RS256 簽章（公鑰依 kid 從 JWKS 挑）、exp / nbf（預設容許 60 秒時鐘誤差）、iss、必須有 sub
    // JWKS 會快取；遇到不認得的 kid 會重新抓一次，user-service 換金鑰後不必重啟 Gateway
    @Bean
    ReactiveJwtDecoder reactiveJwtDecoder(JwtProperties jwtProperties) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwtProperties.jwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(jwtProperties.issuer()),
                // sub 是 userId，下一步要轉成 X-User-Id 給下游
                new JwtClaimValidator<String>(JwtClaimNames.SUB, StringUtils::hasText)));
        return decoder;
    }
}
