package com.mymicroservice.gateway.resilience;

import java.util.concurrent.TimeoutException;

import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import com.mymicroservice.gateway.web.ProblemDetailWriter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import reactor.core.publisher.Mono;

// 路由的 CircuitBreaker filter 失敗時（連不上、逾時、熔斷打開）轉到這裡，回統一格式的錯誤（ProblemDetail + code）
// 路由經 forward 到這裡，不會再經過一次安全檢查；直接呼叫 /fallback/** 要先登入（anyExchange().authenticated()），沒有資訊可以洩漏
@RestController
public class FallbackController {
    private static final Logger log = LoggerFactory.getLogger(FallbackController.class);

    private final ProblemDetailWriter problemDetailWriter;

    public FallbackController(ProblemDetailWriter problemDetailWriter) {
        this.problemDetailWriter = problemDetailWriter;
    }

    @RequestMapping("/fallback/unavailable")
    public Mono<Void> unavailable(ServerWebExchange exchange) {
        Throwable cause = exchange.getAttribute(ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR);
        if (cause instanceof CallNotPermittedException) {
            log.warn("circuit open, request not forwarded: {}", exchange.getRequest().getPath().value());
            return problemDetailWriter.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                    "service is temporarily unavailable, please try again later.");
        }
        if (isTimeout(cause)) {
            return problemDetailWriter.write(exchange, HttpStatus.GATEWAY_TIMEOUT, "GATEWAY_TIMEOUT",
                    "service did not respond in time.");
        }
        if (cause instanceof ResponseStatusException responseStatus) {
            HttpStatusCode status = responseStatus.getStatusCode();
            if (status.value() == HttpStatus.GATEWAY_TIMEOUT.value()) {
                return problemDetailWriter.write(exchange, HttpStatus.GATEWAY_TIMEOUT, "GATEWAY_TIMEOUT",
                        "service did not respond in time.");
            }
        }
        log.warn("forwarding failed for {}: {}", exchange.getRequest().getPath().value(),
                cause == null ? "unknown" : cause.toString());
        return problemDetailWriter.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                "service is temporarily unavailable, please try again later.");
    }

    private static boolean isTimeout(Throwable cause) {
        for (int depth = 0; cause != null && depth < 10; depth++, cause = cause.getCause()) {
            if (cause instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }
}
