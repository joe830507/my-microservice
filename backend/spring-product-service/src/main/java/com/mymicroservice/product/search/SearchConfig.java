package com.mymicroservice.product.search;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;

// Java client 使用自己的 Jackson 2 mapper；專案其他服務使用的 Jackson 3 不相容。
@Configuration
@EnableConfigurationProperties(SearchProperties.class)
public class SearchConfig {

    @Bean(destroyMethod = "close")
    ElasticsearchClient elasticsearchClient(SearchProperties properties) {
        Rest5Client restClient = Rest5Client.builder(properties.endpoint()).build();
        Rest5ClientTransport transport = new Rest5ClientTransport(restClient, new JacksonJsonpMapper());
        return new ElasticsearchClient(transport);
    }
}
