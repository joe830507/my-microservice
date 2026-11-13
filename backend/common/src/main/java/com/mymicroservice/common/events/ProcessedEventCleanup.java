package com.mymicroservice.common.events;

import java.time.Duration;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name = "app.events.processed-cleanup-enabled", havingValue = "true")
public class ProcessedEventCleanup {
    private final JdbcTemplate jdbc;
    private final String schema;
    private final Duration retention;
    private final TransactionTemplate transactions;
    private final int batchSize;

    public ProcessedEventCleanup(JdbcTemplate jdbc, TransactionTemplate transactions, @Value("${app.events.schema:public}") String schema,
            @Value("${app.events.processed-retention:14d}") Duration retention,
            @Value("${app.events.cleanup-batch-size:5000}") int batchSize) {
        if (!schema.matches("[a-zA-Z_][a-zA-Z0-9_]*")) throw new IllegalArgumentException("Invalid events schema");
        if (retention.compareTo(Duration.ofDays(7)) <= 0) throw new IllegalArgumentException("Retention must exceed Kafka's seven-day retention");
        if (batchSize < 1) throw new IllegalArgumentException("Cleanup batch size must be positive");
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.batchSize = batchSize;
        this.schema = schema;
        this.retention = retention;
    }

    @Scheduled(cron = "0 40 3 * * *", zone = "UTC")
    @SchedulerLock(name = "processedEventPurge", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void purge() {
        int deleted;
        do {
            deleted = transactions.execute(status -> jdbc.update("DELETE FROM " + schema + ".processed_event WHERE event_id IN ("
                    + "SELECT event_id FROM " + schema + ".processed_event "
                    + "WHERE processed_at < now() - (? * interval '1 second') LIMIT ? FOR UPDATE SKIP LOCKED)",
                    retention.toSeconds(), batchSize));
        } while (deleted == batchSize);
    }
}
