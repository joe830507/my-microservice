package com.mymicroservice.common.scheduling;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;

// 排程的分散式鎖（階段 B4）：服務跑多個副本時，每個排程每一輪只有一個副本執行。
//
// 服務設定 app.scheduling.lock.table（例如 order_service.shedlock，表由服務自己的 Flyway migration 建立）就會啟用；
// 沒設定的服務（例如沒有排程的 product-service）完全不受影響。
// 在排程方法上加 @SchedulerLock(name = ...)：
//   lockAtMostFor  執行的上限（副本當機時鎖最久這麼久之後釋放），要比最慢的一輪長
//   lockAtLeastFor 至少持有這麼久：多個副本在同一秒觸發同一個 cron，先做完的副本放掉鎖之後，另一個副本不能再搶到；
//                  要大於副本之間的時鐘差，又小於 cron 的間隔。時間用資料庫的時間（usingDbTime），不依賴各 Pod 的時鐘
// 這些排程本身都是冪等的（條件式 UPDATE），鎖是為了不重複工作與不重複呼叫下游，不是為了正確性
@AutoConfiguration(afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@ConditionalOnProperty("app.scheduling.lock.table")
@ConditionalOnBean(DataSource.class)
@EnableSchedulerLock(defaultLockAtMostFor = "PT5M")
public class SchedulingLockAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(LockProvider.class)
    LockProvider lockProvider(DataSource dataSource, @Value("${app.scheduling.lock.table}") String table) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .withTableName(table)
                .usingDbTime()
                .build());
    }
}
