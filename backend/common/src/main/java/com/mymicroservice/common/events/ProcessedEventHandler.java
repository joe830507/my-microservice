package com.mymicroservice.common.events;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class ProcessedEventHandler {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final String schema;

    public ProcessedEventHandler(JdbcTemplate jdbc, TransactionTemplate transactions,
            @Value("${app.events.schema:public}") String schema) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        if (!schema.matches("[a-zA-Z_][a-zA-Z0-9_]*")) throw new IllegalArgumentException("Invalid events schema");
        this.schema = schema;
    }

    /** 在同一交易內記錄事件並更新業務資料；重複事件回傳 false。 */
    public boolean handle(UUID eventId, String eventType, Runnable businessUpdate) {
        return transactions.execute(status -> {
            // 以資料庫唯一鍵處理並行重送；業務處理失敗時，此筆去重紀錄也會回滾。
            int inserted = jdbc.update("INSERT INTO " + schema + ".processed_event(event_id,event_type) VALUES (?,?) "
                    + "ON CONFLICT (event_id) DO NOTHING", eventId, eventType);
            if (inserted == 0) return false;
            businessUpdate.run();
            return true;
        });
    }
}
