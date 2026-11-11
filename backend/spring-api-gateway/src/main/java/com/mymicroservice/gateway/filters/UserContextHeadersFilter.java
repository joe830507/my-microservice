package com.mymicroservice.gateway.filters;

import java.util.List;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import com.mymicroservice.gateway.ratelimit.ClientIpResolver;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

// 下游服務信任 X-User-Id、X-User-Roles，不自己驗 token（common 的 @CurrentUserId、@RequireRoles、稽核欄位）
// 所以 Gateway 一定要：
//   1. 先移除用戶端送來的同名 header（HttpHeaders 不分大小寫，同名多筆也一起移除），公開路由也一樣
//   2. 有有效的 JWT 才填入 sub 與 roles
// X-Client-Ip 同樣：先移除用戶端送來的值，再填入 ClientIpResolver 解析出的來源 IP（所有路由，不論有沒有登入）
// 執行時 Spring Security 的 WebFilter 已經驗完 token；/api/v1/auth/** 不讀 token，所以只會移除、不會填入
@Component
public class UserContextHeadersFilter implements GlobalFilter, Ordered {

    private final ClientIpResolver clientIpResolver;

    public UserContextHeadersFilter(ClientIpResolver clientIpResolver) {
        this.clientIpResolver = clientIpResolver;
    }

    // 名稱和 common 的 UserContextHeaders 一致；Gateway 不依賴 common，所以自己定義
    public static final String USER_ID = "X-User-Id";
    public static final String USER_ROLES = "X-User-Roles";
    // 用戶端來源 IP：下游服務（user-service 的登入失敗鎖定）看不到真實 IP，由 Gateway 帶過去（SEC-05）
    public static final String CLIENT_IP = "X-Client-Ip";

    private static final String ROLES_CLAIM = "roles";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String clientIp = clientIpResolver.resolve(exchange).orElse(null);
        return exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(principal -> ((JwtAuthenticationToken) principal).getToken())
                .map(jwt -> withIdentity(exchange.getRequest(), jwt, clientIp))
                .switchIfEmpty(Mono.fromSupplier(() -> withoutIdentity(exchange.getRequest(), clientIp)))
                .flatMap(request -> chain.filter(exchange.mutate().request(request).build()));
    }

    private static void applyClientIp(org.springframework.http.HttpHeaders headers, String clientIp) {
        headers.remove(CLIENT_IP);
        if (clientIp != null) {
            headers.set(CLIENT_IP, clientIp);
        }
    }

    private static ServerHttpRequest withoutIdentity(ServerHttpRequest request, String clientIp) {
        return request.mutate().headers(headers -> {
            headers.remove(USER_ID);
            headers.remove(USER_ROLES);
            applyClientIp(headers, clientIp);
        }).build();
    }

    private static ServerHttpRequest withIdentity(ServerHttpRequest request, Jwt jwt, String clientIp) {
        // sub 一定存在（SecurityConfig 的 validator 已檢查）
        List<String> roles = jwt.hasClaim(ROLES_CLAIM) ? jwt.getClaimAsStringList(ROLES_CLAIM) : List.of();
        return request.mutate().headers(headers -> {
            headers.remove(USER_ID);
            headers.remove(USER_ROLES);
            applyClientIp(headers, clientIp);
            headers.set(USER_ID, jwt.getSubject());
            // 沒有角色就不帶，下游視為沒有任何角色
            if (!roles.isEmpty()) {
                headers.set(USER_ROLES, String.join(",", roles));
            }
        }).build();
    }

    // 最先執行：之後的 filter（例如 Step 5 的限流）看到的都是已清理過的 header
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
