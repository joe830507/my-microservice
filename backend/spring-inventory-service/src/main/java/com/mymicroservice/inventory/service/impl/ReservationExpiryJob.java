package com.mymicroservice.inventory.service.impl;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mymicroservice.inventory.properties.ReservationExpiryProperties;
import com.mymicroservice.inventory.repositories.ReservationRepository;
import com.mymicroservice.inventory.service.ReservationService;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 預扣的保險：order-service 正常會在取消或付款逾時時呼叫釋放，
// 但它自己也失敗時（例如預扣成功後訂單存檔失敗、連釋放都送不出去），庫存會一直被扣住
// 這裡定期釋放超過 ttl 仍是 RESERVED 的預扣
// 多個實例同時執行也沒關係：釋放用條件式 UPDATE，同一筆只會被釋放一次
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationExpiryJob {
    private final ReservationRepository reservationRepository;
    private final ReservationService reservationService;
    private final ReservationExpiryProperties properties;

    // 不加 @Transactional：每筆用 ReservationService.release 自己的交易，一筆失敗不影響其他筆
    // 多個副本時每一輪只有一個執行（ShedLock，見 common 的 SchedulingLockAutoConfiguration）
    @SchedulerLock(name = "reservationExpiry", lockAtMostFor = "${app.scheduling.lock.at-most-for:PT5M}",
            lockAtLeastFor = "${app.scheduling.lock.at-least-for:PT10S}")
    @Scheduled(cron = "${app.reservation-expiry.cron}", zone = "UTC")
    public void run() {
        Instant cutoff = Instant.now().minus(properties.ttl());
        int released = 0;
        while (true) {
            List<UUID> orderIds = reservationRepository.findReservedCreatedBefore(cutoff,
                    PageRequest.of(0, properties.batchSize()));
            int releasedInBatch = 0;
            for (UUID orderId : orderIds) {
                try {
                    reservationService.release(orderId);
                    releasedInBatch++;
                    // 正常不該發生：代表 order-service 沒有釋放，記下來方便追查
                    log.warn("released expired reservation of order {}", orderId);
                } catch (RuntimeException exception) {
                    log.error("failed to release expired reservation of order {}", orderId, exception);
                }
            }
            released += releasedInBatch;
            // 最後一批，或整批都失敗（再查一次會拿到同樣的資料，避免無窮迴圈）
            if (orderIds.size() < properties.batchSize() || releasedInBatch == 0) {
                break;
            }
        }
        if (released > 0) {
            log.info("released {} expired reservation(s) created before {}", released, cutoff);
        }
    }
}
