package com.mymicroservice.common.events;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class OutboxMetrics {
    private final MeterRegistry registry;
    private final AtomicLong pending = new AtomicLong();

    public OutboxMetrics(MeterRegistry registry) {
        this.registry = registry;
        registry.gauge("events.outbox.pending", pending);
    }

    public void refreshPendingCount(JdbcTemplate jdbc, String schema) {
        try {
            pending.set(jdbc.queryForObject("SELECT count(*) FROM " + schema
                    + ".outbox_event WHERE published_at IS NULL", Long.class));
        } catch (Exception ignored) {
            // The migration may not have completed during application startup.
        }
    }
}
