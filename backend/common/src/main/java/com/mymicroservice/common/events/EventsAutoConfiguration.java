package com.mymicroservice.common.events;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

@AutoConfiguration
@EnableScheduling
@Import({ProcessedEventCleanup.class, OutboxRelay.class, OutboxMetrics.class, ProcessedEventHandler.class, ResilientEventConsumer.class})
public class EventsAutoConfiguration {
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(
            KafkaTemplate<String, String> template, MeterRegistry registry) {
        var recoverer = new EventDeadLetterRecoverer(template, registry);
        var handler = new DefaultErrorHandler(recoverer::accept, new FixedBackOff(500L, 3L));
        // 資料格式與業務規則錯誤重試無益，直接送 DLT。
        handler.addNotRetryableExceptions(IllegalArgumentException.class,
                JacksonException.class,
                ResilientEventConsumer.EventRejectedException.class);
        handler.setBackOffFunction((record, failure) -> {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof org.springframework.dao.TransientDataAccessException
                        || cause instanceof org.springframework.dao.DataAccessResourceFailureException
                        || cause instanceof org.springframework.transaction.CannotCreateTransactionException
                        || cause instanceof com.mymicroservice.common.exception.AppException app
                            && app.getErrorCode().getStatus().is5xxServerError()) {
                    var backOff = new org.springframework.util.backoff.ExponentialBackOff(1000L, 2.0);
                    backOff.setMaxInterval(30_000L);
                    return backOff;
                }
            }
            return new FixedBackOff(500L, 3L);
        });
        return handler;
    }

    // Kafka 用戶端指標由 Boot 的 KafkaMetricsAutoConfiguration 註冊，避免重複監聽。
}
