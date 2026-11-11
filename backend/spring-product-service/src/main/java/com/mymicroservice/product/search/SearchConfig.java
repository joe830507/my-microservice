package com.mymicroservice.product.search;

import org.apache.hc.core5.util.Timeout;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;

// Java client 使用自己的 Jackson 2 mapper；專案其他服務使用的 Jackson 3 不相容。
@Configuration
@EnableConfigurationProperties(SearchProperties.class)
public class SearchConfig {

    public static final String QUERY_CLIENT = "searchQueryClient";

    // 索引管理、事件索引、重建用的預設 client；注入時不指定名稱就是這個
    @Bean(destroyMethod = "close")
    @Primary
    ElasticsearchClient elasticsearchClient(SearchProperties properties) {
        Rest5Client restClient = Rest5Client.builder(properties.endpoint()).build();
        Rest5ClientTransport transport = new Rest5ClientTransport(restClient, new JacksonJsonpMapper());
        return new ElasticsearchClient(transport);
    }

    // 使用者請求的搜尋專用：短逾時，ES 卡住時快速退回資料庫查詢，不佔住請求執行緒
    @Bean(name = QUERY_CLIENT, destroyMethod = "close")
    ElasticsearchClient searchQueryClient(SearchProperties properties) {
        Rest5Client restClient = Rest5Client.builder(properties.endpoint())
                .setConnectionConfigCallback(connection -> connection.setConnectTimeout(
                        Timeout.ofMilliseconds(properties.queryConnectTimeout().toMillis())))
                .setRequestConfigCallback(request -> request.setResponseTimeout(
                        Timeout.ofMilliseconds(properties.queryReadTimeout().toMillis())))
                .build();
        Rest5ClientTransport transport = new Rest5ClientTransport(restClient, new JacksonJsonpMapper());
        return new ElasticsearchClient(transport);
    }
}
