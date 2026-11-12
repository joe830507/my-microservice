package com.mymicroservice.common.events;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.nio.charset.StandardCharsets;
import java.util.stream.StreamSupport;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Headers;

/** 在 listener 的交易及 outbox 寫入期間保留 Kafka 傳入的追蹤脈絡。 */
public final class EventTrace {
    private EventTrace() {}

    private static final TextMapGetter<Headers> GETTER = new TextMapGetter<>() {
        @Override public Iterable<String> keys(Headers carrier) {
            return StreamSupport.stream(carrier.spliterator(), false).map(h -> h.key()).toList();
        }
        @Override public String get(Headers carrier, String key) {
            var header = carrier.lastHeader(key);
            return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
        }
    };

    public static void consume(OpenTelemetry telemetry, ConsumerRecord<String, String> record,
            ResilientEventConsumer.EventWork work) throws Exception {
        Context parent = W3CTraceContextPropagator.getInstance()
                .extract(Context.root(), record.headers(), GETTER);
        Span span = telemetry.getTracer("my-microservice.events")
                .spanBuilder(record.topic() + " process").setSpanKind(SpanKind.CONSUMER)
                .setParent(parent).startSpan();
        try (Scope scope = span.makeCurrent()) {
            work.run();
        } catch (Exception failure) {
            span.recordException(failure);
            span.setStatus(StatusCode.ERROR);
            throw failure;
        } finally {
            span.end();
        }
    }
}
