package com.mymicroservice.user.service.impl;

import java.time.Instant;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.mymicroservice.user.properties.RefreshTokenCleanupProperties;
import com.mymicroservice.user.repositories.RefreshTokenRepository;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 定期刪除過期的 refresh token，避免表無限成長
// 多個實例會各自執行，但 DELETE 重複執行結果相同，不需要分散式鎖
@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenCleanupJob {
    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenCleanupProperties properties;

    // cron 寫在設定檔；@Scheduled 只能用 placeholder 讀取，所以和 RefreshTokenCleanupProperties 指向同一個 key
    // 多個副本時每一輪只有一個執行（ShedLock，見 common 的 SchedulingLockAutoConfiguration）
    @SchedulerLock(name = "refreshTokenCleanup", lockAtMostFor = "${app.scheduling.lock.at-most-for:PT5M}",
            lockAtLeastFor = "${app.scheduling.lock.at-least-for:PT10S}")
    @Scheduled(cron = "${app.refresh-token-cleanup.cron}", zone = "UTC")
    @Transactional
    public void run() {
        Instant cutoff = Instant.now().minus(properties.retention());
        int deleted = refreshTokenRepository.deleteExpiredBefore(cutoff);
        log.info("deleted {} refresh token(s) expired before {}", deleted, cutoff);
    }
}
