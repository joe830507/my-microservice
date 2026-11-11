package com.mymicroservice.common.events;

import com.mymicroservice.common.exception.AppException;
import io.opentelemetry.api.OpenTelemetry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 重試與死信轉送由 listener container 的 DefaultErrorHandler 統一管理。 */
@Component
public class ResilientEventConsumer {
    @Autowired(required = false)
    private OpenTelemetry openTelemetry = OpenTelemetry.noop();

    public void consume(ConsumerRecord<String, String> record,
            EventWork work) throws Exception {
        EventTrace.consume(openTelemetry, record,
                () -> classifyFailure(work));
    }

    /** 暫時性錯誤原樣拋出（重試）；資料與業務規則錯誤轉成 EventRejectedException（直接進 DLT）。 */
    public void classifyFailure(EventWork work) throws Exception {
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
