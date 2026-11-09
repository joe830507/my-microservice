package com.mymicroservice.payment.configs;

import java.net.http.HttpClient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.mymicroservice.payment.properties.DownstreamServicesProperties;

// 呼叫 order-service 的 RestClient；一定要設逾時，預設沒有讀取逾時
@Configuration
public class RestClientConfig {

    @Bean
    RestClient orderRestClient(RestClient.Builder builder, DownstreamServicesProperties properties) {
        // 固定 HTTP/1.1：JDK HttpClient 預設會對 http:// 嘗試升級成 HTTP/2（h2c），
        // 下游提早回應（例如 5xx）時請求 body 可能沒送出（order-service 開發時遇過）
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());
        return builder.clone()
                .baseUrl(properties.orderUri().toString())
                .requestFactory(factory)
                .build();
    }
}
