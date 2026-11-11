package com.mymicroservice.product.search;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import com.mymicroservice.common.events.EventDeadLetterRecoverer;
import io.micrometer.core.instrument.MeterRegistry;

// 搜尋索引消費者遇到 ES 暫時故障時必須持續重試，不能沿用共用的有限重試策略。
@Configuration(proxyBeanMethods = false)
public class ProductSearchKafkaConfig {

    @Bean("productSearchKafkaListenerContainerFactory")
    ConcurrentKafkaListenerContainerFactory<String, String> productSearchKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<String, String> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate,
            MeterRegistry meterRegistry) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        @SuppressWarnings("rawtypes")
        ConcurrentKafkaListenerContainerFactory rawFactory = factory;
        @SuppressWarnings("rawtypes")
        ConsumerFactory rawConsumerFactory = consumerFactory;
        configurer.configure(rawFactory, rawConsumerFactory);

        var backOff = new ExponentialBackOff(1_000L, 2.0);
        backOff.setMaxInterval(30_000L);
        var errorHandler = new DefaultErrorHandler(
                new EventDeadLetterRecoverer(kafkaTemplate, meterRegistry)::accept, backOff);
        errorHandler.setClassifications(Map.of(ProductIndexingTransientException.class, true), false);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
