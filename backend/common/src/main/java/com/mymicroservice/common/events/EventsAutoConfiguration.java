package com.mymicroservice.common.events;

import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;

import com.mymicroservice.common.exception.AppException;

import io.micrometer.core.instrument.MeterRegistry;

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
        handler.setBackOffFunction((record, failure) -> retryBackOff(failure));
        return handler;
    }

    static BackOff retryBackOff(Throwable failure) {
        if (isTransient(failure)) {
            var backOff = new ExponentialBackOff(1000L, 2.0);
            backOff.setMaxInterval(30_000L);
            return backOff;
        }
        return new FixedBackOff(500L, 3L);
    }

    private static boolean isTransient(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TransientDataAccessException
                    || cause instanceof DataAccessResourceFailureException
                    || cause instanceof RecoverableDataAccessException
                    || cause instanceof CannotCreateTransactionException
                    || cause instanceof SQLTransientException
                    || cause instanceof SQLRecoverableException
                    || cause instanceof AppException app && app.getErrorCode().getStatus().is5xxServerError()) {
                return true;
            }
        }
        return false;
    }

    // Kafka 用戶端指標由 Boot 的 KafkaMetricsAutoConfiguration 註冊，避免重複監聽。
}
