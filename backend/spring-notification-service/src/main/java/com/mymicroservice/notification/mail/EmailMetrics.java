package com.mymicroservice.notification.mail;

import java.util.concurrent.TimeUnit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Component
public class EmailMetrics {
    private final MeterRegistry registry;
    private final JdbcTemplate jdbc;

    public EmailMetrics(MeterRegistry registry, JdbcTemplate jdbc) {
        this.registry = registry;
        this.jdbc = jdbc;
        Gauge.builder("notification.email.pending", this, EmailMetrics::pendingCount)
                .description("Number of pending notification emails").register(registry);
        Gauge.builder("notification.email.oldest_pending_seconds", this, EmailMetrics::oldestPendingSeconds)
                .description("Age in seconds of the oldest pending notification email").register(registry);
    }

    public void result(String template, String result) {
        Counter.builder("notification.emails")
                .description("Notification email delivery outcomes")
                .tag("template", template)
                .tag("result", result)
                .register(registry)
                .increment();
    }

    public void sendDuration(String template, long nanos) {
        Timer.builder("notification.email.send.duration")
                .description("SMTP send duration")
                .tag("template", template)
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    private double pendingCount() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM notification_service.email_message WHERE status='PENDING'",
                Long.class);
        return count == null ? 0 : count;
    }

    private double oldestPendingSeconds() {
        Double seconds = jdbc.queryForObject("SELECT COALESCE(EXTRACT(EPOCH FROM (now()-min(created_at))),0) "
                + "FROM notification_service.email_message WHERE status='PENDING'", Double.class);
        return seconds == null ? 0 : seconds;
    }
}
