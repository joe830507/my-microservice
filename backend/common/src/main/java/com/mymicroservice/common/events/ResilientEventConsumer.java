package com.mymicroservice.common.events;

import com.mymicroservice.common.exception.AppException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 重試與死信轉送由 listener container 的 DefaultErrorHandler 統一管理。 */
@Component
@ConditionalOnProperty(name = "app.events.enabled", havingValue = "true")
public class ResilientEventConsumer {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.opentelemetry.api.OpenTelemetry openTelemetry = io.opentelemetry.api.OpenTelemetry.noop();

    public void consume(org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record,
            EventWork work) throws Exception {
        EventTrace.consume(openTelemetry, record,
                () -> consume(record.topic(), record.key(), record.value(), work));
    }
    public void consume(String topic, String key, String payload, EventWork work) throws Exception {
        try {
            work.run();
        } catch (AppException failure) {
            // 下游暫時性故障仍可重試；資料與業務規則衝突則直接轉死信。
            if (failure.getErrorCode().getStatus().is5xxServerError()) throw failure;
            throw new EventRejectedException(failure);
        }
    }

    public static class EventRejectedException extends RuntimeException {
        public EventRejectedException(AppException cause) {
            super(cause);
        }
    }

    @FunctionalInterface
    public interface EventWork {
        void run() throws Exception;
    }
}
