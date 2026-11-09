package com.mymicroservice.user.service.impl;

import java.time.Instant;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.mymicroservice.user.properties.RefreshTokenCleanupProperties;
import com.mymicroservice.user.repositories.RefreshTokenRepository;

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
    @Scheduled(cron = "${app.refresh-token-cleanup.cron}", zone = "UTC")
    @Transactional
    public void run() {
        Instant cutoff = Instant.now().minus(properties.retention());
        int deleted = refreshTokenRepository.deleteExpiredBefore(cutoff);
        log.info("deleted {} refresh token(s) expired before {}", deleted, cutoff);
    }
}
