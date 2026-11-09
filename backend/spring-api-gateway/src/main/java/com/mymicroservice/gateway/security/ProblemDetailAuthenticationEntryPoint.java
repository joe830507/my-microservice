package com.mymicroservice.gateway.security;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.web.server.ServerWebExchange;

import com.mymicroservice.gateway.web.ProblemDetailWriter;

import reactor.core.publisher.Mono;

// 沒帶 token 或 token 無效時回 401，格式和下游服務一致：ProblemDetail（application/problem+json）+ code
// 另外帶 WWW-Authenticate: Bearer（RFC 6750），讓用戶端知道要用 Bearer token
// detail 不帶驗證失敗的細節（簽章、過期、iss...），避免洩漏驗證規則；細節只在 debug log
public class ProblemDetailAuthenticationEntryPoint implements ServerAuthenticationEntryPoint {

    static final String CODE = "UNAUTHENTICATED";

    private final ProblemDetailWriter problemDetailWriter;

    public ProblemDetailAuthenticationEntryPoint(ProblemDetailWriter problemDetailWriter) {
        this.problemDetailWriter = problemDetailWriter;
    }

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException exception) {
        boolean invalidToken = exception instanceof InvalidBearerTokenException;
        String detail = invalidToken ? "access token is invalid or expired." : "authentication is required.";

        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE,
                invalidToken ? "Bearer error=\"invalid_token\"" : "Bearer");
        return problemDetailWriter.write(exchange, HttpStatus.UNAUTHORIZED, CODE, detail);
    }
}
