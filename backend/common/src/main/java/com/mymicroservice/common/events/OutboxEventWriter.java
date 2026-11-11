package com.mymicroservice.common.events;

import java.sql.Timestamp;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class OutboxEventWriter {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final String schema;

    public OutboxEventWriter(JdbcTemplate jdbc, ObjectMapper objectMapper,
            @Value("${app.events.schema:public}") String schema) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        if (!schema.matches("[a-zA-Z_][a-zA-Z0-9_]*")) throw new IllegalArgumentException("Invalid events schema");
        this.schema = schema;
    }

    /** 加入呼叫端的業務交易，讓資料更新與事件寫入一起提交或回滾。 */
    @Transactional
    public UUID append(String topic, String type, String aggregateId, Object payload) {
        UUID eventId = UuidV7.generate();
        EventEnvelope<Object> event = new EventEnvelope<>(eventId, type, 1, Instant.now(), aggregateId, payload);
        // 保存原始請求的追蹤資訊，讓稍後執行的 relay 能延續同一條 trace。
        SpanContext span = Span.current().getSpanContext();
        String traceparent = span.isValid()
                ? "00-" + span.getTraceId() + "-" + span.getSpanId() + "-" + span.getTraceFlags().asHex()
                : null;
        try {
            jdbc.update("INSERT INTO " + schema + ".outbox_event (event_id, topic, event_type, aggregate_id, occurred_at, payload, traceparent) "
                    + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)", eventId, topic, type, aggregateId,
                    Timestamp.from(event.occurredAt()),
                    objectMapper.writeValueAsString(event), traceparent);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Could not serialize event " + type, exception);
        }
        return eventId;
    }
}
