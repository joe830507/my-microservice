package com.mymicroservice.common.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EventTraceTest {
    @Test
    void extractsKafkaParentScopesWorkAndEndsFailedSpan() {
        OpenTelemetry telemetry = mock(OpenTelemetry.class);
        Tracer tracer = mock(Tracer.class);
        SpanBuilder builder = mock(SpanBuilder.class, RETURNS_SELF);
        Span span = mock(Span.class, CALLS_REAL_METHODS);
        when(telemetry.getPropagators()).thenReturn(ContextPropagators.create(W3CTraceContextPropagator.getInstance()));
        when(telemetry.getTracer(anyString())).thenReturn(tracer);
        when(tracer.spanBuilder(anyString())).thenReturn(builder);
        when(builder.startSpan()).thenReturn(span);
        var record = new ConsumerRecord<String, String>("orders", 0, 1, "key", "{}");
        String trace = "0123456789abcdef0123456789abcdef";
        record.headers().add("traceparent", ("00-" + trace + "-0123456789abcdef-01").getBytes(StandardCharsets.UTF_8));
        RuntimeException failure = new RuntimeException("rollback");
        Span previous = Span.current();
        assertThatThrownBy(() -> EventTrace.consume(telemetry, record, () -> {
            assertThat(Span.current()).isSameAs(span);
            throw failure;
        })).isSameAs(failure);
        var parent = ArgumentCaptor.forClass(Context.class);
        verify(builder).setParent(parent.capture());
        assertThat(Span.fromContext(parent.getValue()).getSpanContext().getTraceId()).isEqualTo(trace);
        verify(span).recordException(failure);
        verify(span).setStatus(StatusCode.ERROR);
        verify(span).end();
        assertThat(Span.current()).isSameAs(previous);
    }
}
