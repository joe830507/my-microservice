package com.mymicroservice.common.events;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.events.enabled", havingValue = "true")
public class OutboxRelay {
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transactions;
    private final OutboxMetrics metrics;
    private final String schema;
    // 使用 Spring 管理的 SDK；GlobalOpenTelemetry 未必已註冊，可能只回傳 no-op。
    @Autowired(required = false)
    private OpenTelemetry openTelemetry = OpenTelemetry.noop();

    public OutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka,
            TransactionTemplate transactions, OutboxMetrics metrics,
            @Value("${app.events.schema:public}") String schema) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.transactions = transactions;
        this.metrics = metrics;
        if (!schema.matches("[a-zA-Z_][a-zA-Z0-9_]*")) throw new IllegalArgumentException("Invalid events schema");
        this.schema = schema;
    }

    @Scheduled(fixedDelayString = "${app.events.outbox-poll-interval:500}")
    public void publishPending() {
        try {
            transactions.executeWithoutResult(status -> {
                String table = schema + ".outbox_event";
                // 每個 aggregate 只取最早的待送事件，避免後續事件越過尚未成功發布的事件。
                // SKIP LOCKED 讓多個服務副本各自領取不同資料，鎖會持有至本次交易結束。
                List<PendingEvent> events = jdbc.query("SELECT e.event_id, e.topic, e.aggregate_id, e.payload::text, e.traceparent "
                        + "FROM " + table + " e WHERE e.published_at IS NULL "
                        + "AND NOT EXISTS (SELECT 1 FROM " + table + " older WHERE older.topic = e.topic "
                        + "AND older.aggregate_id = e.aggregate_id AND older.published_at IS NULL "
                        + "AND older.sequence_id < e.sequence_id) "
                        + "ORDER BY e.sequence_id "
                        + "LIMIT 10 FOR UPDATE SKIP LOCKED", (rs, row) -> new PendingEvent(
                                rs.getObject("event_id", UUID.class), rs.getString("topic"),
                                rs.getString("aggregate_id"), rs.getString("payload"), rs.getString("traceparent")));
                for (PendingEvent event : events) {
                    try {
                        ProducerRecord<String, String> record = new ProducerRecord<>(event.topic(), event.aggregateId(), event.payload());
                        if (event.traceparent() != null) {
                            record.headers().add(new RecordHeader("traceparent", event.traceparent().getBytes(StandardCharsets.UTF_8)));
                        }
                        // 等待 broker ACK 才標記完成；若 ACK 後 DB 交易失敗，事件可能重送，消費端需去重。
                        send(record, event.traceparent());
                        jdbc.update("UPDATE " + table + " SET published_at = now() WHERE event_id = ?", event.id());
                    } catch (Exception exception) {
                        // 回滾本批發布標記，保留事件供下次輪詢重送。
                        status.setRollbackOnly();
                        log.warn("Outbox publish failed for event {}", event.id(), exception);
                        break;
                    }
                }
            });
        } catch (Exception exception) {
            log.warn("Outbox relay cycle failed", exception);
        }
        metrics.refreshPendingCount(jdbc, schema);
    }

    @Scheduled(cron = "0 20 3 * * *", zone = "UTC")
    public void purgePublishedEvents() {
        jdbc.update("DELETE FROM " + schema + ".outbox_event WHERE published_at < now() - interval '7 days'");
    }

    private record PendingEvent(UUID id, String topic, String aggregateId, String payload, String traceparent) {}

    private void send(ProducerRecord<String, String> record, String traceparent) throws Exception {
        Span relaySpan = null;
        Scope scope = null;
        // 排程執行緒沒有原始 HTTP 請求的 context，因此從 outbox 還原父 span。
        if (traceparent != null) {
            String[] fields = traceparent.split("-");
            if (fields.length == 4 && "00".equals(fields[0])) {
                SpanContext parent = SpanContext.createFromRemoteParent(fields[1], fields[2],
                        TraceFlags.fromHex(fields[3], 0), TraceState.getDefault());
                relaySpan = openTelemetry.getTracer("my-microservice.outbox")
                        .spanBuilder("outbox publish").setParent(Context.root().with(Span.wrap(parent))).startSpan();
                scope = relaySpan.makeCurrent();
            }
        }
        try {
            kafka.send(record).get(3, TimeUnit.SECONDS);
        } finally {
            if (scope != null) scope.close();
            if (relaySpan != null) relaySpan.end();
        }
    }
}
