package com.mymicroservice.gateway.security;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

// 沒帶 token 或 token 無效時回 401，格式和下游服務一致：ProblemDetail（application/problem+json）+ code
// 另外帶 WWW-Authenticate: Bearer（RFC 6750），讓用戶端知道要用 Bearer token
// detail 不帶驗證失敗的細節（簽章、過期、iss...），避免洩漏驗證規則；細節只在 debug log
public class ProblemDetailAuthenticationEntryPoint implements ServerAuthenticationEntryPoint {

    static final String CODE = "UNAUTHENTICATED";

    private final JsonMapper jsonMapper;

    public ProblemDetailAuthenticationEntryPoint(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException exception) {
        boolean invalidToken = exception instanceof InvalidBearerTokenException;
        String detail = invalidToken ? "access token is invalid or expired." : "authentication is required.";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", HttpStatus.UNAUTHORIZED.getReasonPhrase());
        body.put("status", HttpStatus.UNAUTHORIZED.value());
        body.put("detail", detail);
        body.put("instance", exchange.getRequest().getPath().value());
        body.put("code", CODE);

        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        response.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE,
                invalidToken ? "Bearer error=\"invalid_token\"" : "Bearer");

        DataBuffer buffer = response.bufferFactory()
                .wrap(jsonMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
