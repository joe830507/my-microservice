package com.mymicroservice.gateway.filters;

import java.nio.charset.StandardCharsets;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.UriUtils;

import com.mymicroservice.gateway.web.ProblemDetailWriter;

import reactor.core.publisher.Mono;

// /internal/** 是服務之間互相呼叫的 API，不能從外部經過 Gateway 進入（一律 404，假裝不存在）
// 路由表本來就沒有 /internal，但仍明確擋下：
//   - 之後有人加了 /api/** 之類的寬鬆路由，也不會不小心放行
//   - Gateway 轉送時保留原始路徑，下游（Tomcat）才做正規化：
//     /api/v1/products/../../../internal/x 會符合 products 的路由，到了下游卻變成 /internal/x
// 所以除了開頭是 /internal，含有 . 或 .. 路徑片段的請求也一律擋下（正常的 API 路徑不會有）
// WebFilter 而不是 GlobalFilter：要在 Spring Security 之前執行，沒登入也回 404，不回 401
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InternalPathBlockingFilter implements WebFilter {

    static final String CODE = "NOT_FOUND";

    private static final String INTERNAL_SEGMENT = "internal";
    // %252e 這類多重編碼：最多解碼這麼多次，還在變就直接擋下
    private static final int MAX_DECODE_ROUNDS = 3;

    private final ProblemDetailWriter problemDetailWriter;

    public InternalPathBlockingFilter(ProblemDetailWriter problemDetailWriter) {
        this.problemDetailWriter = problemDetailWriter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (isBlocked(exchange.getRequest().getURI().getRawPath())) {
            return problemDetailWriter.write(exchange, HttpStatus.NOT_FOUND, CODE, "resource not found.");
        }
        return chain.filter(exchange);
    }

    static boolean isBlocked(String rawPath) {
        if (rawPath == null) {
            return false;
        }
        String path = decodeFully(rawPath);
        if (path == null) {
            return true;
        }

        // 反斜線在部分伺服器也被當成路徑分隔
        String[] segments = path.replace('\\', '/').split("/");
        boolean firstSegment = true;
        for (String rawSegment : segments) {
            // Tomcat 會去掉 ;參數，例如 ..;/ 會被當成 ../
            String segment = stripPathParameters(rawSegment);
            if (segment.isEmpty()) {
                continue;
            }
            if (segment.equals(".") || segment.equals("..")) {
                return true;
            }
            if (firstSegment && segment.equalsIgnoreCase(INTERNAL_SEGMENT)) {
                return true;
            }
            firstSegment = false;
        }
        return false;
    }

    // 解碼到不再變化；編碼格式錯誤或解碼次數過多回 null（擋下）
    private static String decodeFully(String rawPath) {
        String current = rawPath;
        for (int round = 0; round < MAX_DECODE_ROUNDS; round++) {
            String decoded;
            try {
                decoded = UriUtils.decode(current, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException exception) {
                // 原始路徑就編碼錯誤：擋下；解碼後才出現的 %（例如 100%25 → 100%）是正常字元
                return round == 0 ? null : current;
            }
            if (decoded.equals(current)) {
                return current;
            }
            current = decoded;
        }
        // 解了 MAX_DECODE_ROUNDS 次還能再解：多重編碼，視為刻意繞過
        try {
            return UriUtils.decode(current, StandardCharsets.UTF_8).equals(current) ? current : null;
        } catch (IllegalArgumentException exception) {
            return current;
        }
    }

    private static String stripPathParameters(String segment) {
        int semicolon = segment.indexOf(';');
        return semicolon < 0 ? segment : segment.substring(0, semicolon);
    }
}
