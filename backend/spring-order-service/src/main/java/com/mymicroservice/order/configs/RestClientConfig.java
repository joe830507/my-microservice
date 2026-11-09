package com.mymicroservice.order.configs;

import java.net.http.HttpClient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.mymicroservice.order.properties.DownstreamServicesProperties;

// 每個下游服務一個 RestClient，base URL 與逾時都由設定決定
// 一定要設逾時：預設沒有讀取逾時，下游卡住時建單請求會一直佔著執行緒
@Configuration
public class RestClientConfig {

    @Bean
    RestClient productRestClient(RestClient.Builder builder, DownstreamServicesProperties properties) {
        return builder.clone()
                .baseUrl(properties.productUri().toString())
                .requestFactory(requestFactory(properties))
                .build();
    }

    @Bean
    RestClient inventoryRestClient(RestClient.Builder builder, DownstreamServicesProperties properties) {
        return builder.clone()
                .baseUrl(properties.inventoryUri().toString())
                .requestFactory(requestFactory(properties))
                .build();
    }

    private static JdkClientHttpRequestFactory requestFactory(DownstreamServicesProperties properties) {
        // 固定 HTTP/1.1：JDK HttpClient 預設會對 http:// 嘗試升級成 HTTP/2（h2c），
        // 帶 body 的請求遇到下游提早回應（例如 5xx）時，body 可能沒送出；服務之間都是 Tomcat HTTP/1.1，不需要升級
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());
        return factory;
    }
}
