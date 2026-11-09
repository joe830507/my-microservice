package com.mymicroservice.gateway.ratelimit;

import java.net.InetSocketAddress;
import java.util.Optional;

import org.springframework.cloud.gateway.support.ipresolver.RemoteAddressResolver;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.mymicroservice.gateway.properties.ClientIpProperties;

// 取得來源 IP
//   trusted-proxies = 0：直接用連線 IP，完全不看 X-Forwarded-For（用戶端可以任意填）
//   trusted-proxies = N：X-Forwarded-For 從右邊數第 N 個值；右邊 N 個是信任的代理加上的，
//     用戶端自己填的值只會在更左邊，無法偽造。header 不存在時退回連線 IP
@Component
public class ClientIpResolver {

    private final RemoteAddressResolver delegate;

    public ClientIpResolver(ClientIpProperties properties) {
        this.delegate = properties.trustedProxies() == 0
                ? new RemoteAddressResolver() { }
                : XForwardedRemoteAddressResolver.maxTrustedIndex(properties.trustedProxies());
    }

    public Optional<String> resolve(ServerWebExchange exchange) {
        // getHostString 而不是 getAddress()：X-Forwarded-For 解析出來的是未解析（unresolved）的位址，getAddress() 會是 null；
        // getHostString 也不會做反向 DNS 查詢
        return Optional.ofNullable(delegate.resolve(exchange))
                .map(InetSocketAddress::getHostString)
                .filter(host -> !host.isBlank());
    }
}
