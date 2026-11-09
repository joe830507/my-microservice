package com.mymicroservice.gateway.ratelimit;

import java.util.List;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.support.HasRouteId;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.mymicroservice.gateway.web.ProblemDetailWriter;

import jakarta.validation.constraints.Min;

// 依來源 IP 限流，在路由上用 name: ClientIpRateLimit（見 application.yml 的 user-service-login）
// 計數用 Spring Cloud Gateway 的 RedisRateLimiter（token bucket，Lua script 原子操作），多個 Gateway 實例共用
// 不用內建的 RequestRateLimiter：它超過上限時回空 body，這裡要回和下游一致的 ProblemDetail + code
// Redis 掛掉或逾時：RedisRateLimiter 放行（和 user-service 的登入失敗計數一致，寧可少擋也不要讓登入整個壞掉）
@Component
public class ClientIpRateLimitGatewayFilterFactory
        extends AbstractGatewayFilterFactory<ClientIpRateLimitGatewayFilterFactory.Config> {

    static final String CODE = "TOO_MANY_REQUESTS";

    private final RedisRateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;
    private final ProblemDetailWriter problemDetailWriter;

    public ClientIpRateLimitGatewayFilterFactory(RedisRateLimiter rateLimiter, ClientIpResolver clientIpResolver,
            ProblemDetailWriter problemDetailWriter) {
        super(Config.class);
        this.rateLimiter = rateLimiter;
        this.clientIpResolver = clientIpResolver;
        this.problemDetailWriter = problemDetailWriter;
    }

    @Override
    public GatewayFilter apply(Config config) {
        String routeId = config.getRouteId();
        // RedisRateLimiter 依 routeId 找設定
        rateLimiter.getConfig().put(routeId, new RedisRateLimiter.Config()
                .setReplenishRate(config.getReplenishRate())
                .setBurstCapacity(config.getBurstCapacity())
                .setRequestedTokens(config.getRequestedTokens()));
        // 等到補滿一個請求需要的 token 要幾秒
        String retryAfterSeconds = String.valueOf(
                (config.getRequestedTokens() + config.getReplenishRate() - 1) / config.getReplenishRate());

        return (exchange, chain) -> {
            // 取不到 IP 時大家共用同一個 key：寧可一起被限制，也不要讓這類請求不受限
            String ip = clientIpResolver.resolve(exchange).orElse("unknown");

            // RedisRateLimiter 的 Redis key 是 request_rate_limiter.{routeId.ip}.tokens，不同路由的計數不會混在一起
            return rateLimiter.isAllowed(routeId, ip).flatMap(response -> {
                if (response.isAllowed()) {
                    return chain.filter(exchange);
                }
                exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, retryAfterSeconds);
                return problemDetailWriter.write(exchange, HttpStatus.TOO_MANY_REQUESTS, CODE,
                        "too many requests, please try again later.");
            });
        };
    }

    @Override
    public List<String> shortcutFieldOrder() {
        return List.of("replenishRate", "burstCapacity", "requestedTokens");
    }

    // HasRouteId：路由建立時 Gateway 會把 routeId 填進來
    public static class Config implements HasRouteId {
        private String routeId;
        // 每秒補幾個 token（最小 1）
        @Min(1)
        private int replenishRate = 1;
        // bucket 最多存幾個 token，也就是瞬間最多能用掉多少
        @Min(1)
        private int burstCapacity = 1;
        // 每個請求消耗幾個 token；大於 1 可以做出比每秒 1 個更慢的速率
        @Min(1)
        private int requestedTokens = 1;

        @Override
        public String getRouteId() {
            return routeId;
        }

        @Override
        public void setRouteId(String routeId) {
            this.routeId = routeId;
        }

        public int getReplenishRate() {
            return replenishRate;
        }

        public void setReplenishRate(int replenishRate) {
            this.replenishRate = replenishRate;
        }

        public int getBurstCapacity() {
            return burstCapacity;
        }

        public void setBurstCapacity(int burstCapacity) {
            this.burstCapacity = burstCapacity;
        }

        public int getRequestedTokens() {
            return requestedTokens;
        }

        public void setRequestedTokens(int requestedTokens) {
            this.requestedTokens = requestedTokens;
        }
    }
}
