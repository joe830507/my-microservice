package com.mymicroservice.common.events;

import java.time.Duration;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.events.processed-cleanup-enabled", havingValue = "true")
public class ProcessedEventCleanup {
    private final JdbcTemplate jdbc;
    private final String schema;
    private final Duration retention;

    public ProcessedEventCleanup(JdbcTemplate jdbc, @Value("${app.events.schema:public}") String schema,
            @Value("${app.events.processed-retention:14d}") Duration retention) {
        if (!schema.matches("[a-zA-Z_][a-zA-Z0-9_]*")) throw new IllegalArgumentException("Invalid events schema");
        if (retention.compareTo(Duration.ofDays(7)) <= 0) throw new IllegalArgumentException("Retention must exceed Kafka's seven-day retention");
        this.jdbc = jdbc;
        this.schema = schema;
        this.retention = retention;
    }

    @Scheduled(cron = "0 40 3 * * *", zone = "UTC")
    @SchedulerLock(name = "processedEventPurge", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void purge() {
        jdbc.update("DELETE FROM " + schema + ".processed_event WHERE processed_at < now() - (? * interval '1 second')",
                retention.toSeconds());
    }
}
