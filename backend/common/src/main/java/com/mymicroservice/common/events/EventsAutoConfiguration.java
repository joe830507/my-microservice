package com.mymicroservice.common.events;

import org.apache.kafka.common.TopicPartition;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

@AutoConfiguration
@EnableScheduling
@Import({OutboxRelay.class, OutboxMetrics.class, ProcessedEventHandler.class, ResilientEventConsumer.class})
public class EventsAutoConfiguration {
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(
            KafkaTemplate<String, String> template, MeterRegistry registry) {
        // 先建立零值基線，讓第一次 DLT 投遞也能被 increase() 告警偵測。
        var published = registry.counter("events.dlt.published");
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, failure) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
        // DLT 發送失敗必須拋出，避免原始訊息被當作已處理。
        recoverer.setFailIfSendResultIsError(true);
        var handler = new DefaultErrorHandler((record, failure) -> {
            recoverer.accept(record, failure);
            published.increment();
        }, new FixedBackOff(500L, 3L));
        // 資料格式與業務規則錯誤重試無益，直接送 DLT。
        handler.addNotRetryableExceptions(IllegalArgumentException.class,
                JacksonException.class,
                ResilientEventConsumer.EventRejectedException.class);
        return handler;
    }

    // Kafka 用戶端指標由 Boot 的 KafkaMetricsAutoConfiguration 註冊，避免重複監聽。
}
