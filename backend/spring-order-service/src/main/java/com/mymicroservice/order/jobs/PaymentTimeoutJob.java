package com.mymicroservice.order.jobs;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mymicroservice.order.properties.PaymentTimeoutProperties;
import com.mymicroservice.order.repositories.OrderRepository;
import com.mymicroservice.order.service.OrderService;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 付款逾時：建立超過 timeout 仍是 PENDING_PAYMENT 的訂單自動取消，並釋放預扣
// 多個實例同時執行也沒關係：取消用條件式 UPDATE，同一張訂單只會被取消一次
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentTimeoutJob {
    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final PaymentTimeoutProperties properties;

    // 不加 @Transactional：每張訂單各自處理，一張失敗不影響其他張
    // 多個副本時每一輪只有一個執行（ShedLock，見 common 的 SchedulingLockAutoConfiguration）
    @SchedulerLock(name = "paymentTimeout", lockAtMostFor = "${app.scheduling.lock.at-most-for:PT5M}",
            lockAtLeastFor = "${app.scheduling.lock.at-least-for:PT10S}")
    @Scheduled(cron = "${app.payment-timeout.cron}", zone = "UTC")
    public void run() {
        Instant cutoff = Instant.now().minus(properties.timeout());
        int cancelled = 0;
        while (true) {
            List<UUID> orderIds = orderRepository.findPendingCreatedBefore(cutoff,
                    PageRequest.of(0, properties.batchSize()));
            int processed = 0;
            for (UUID orderId : orderIds) {
                try {
                    // 回 false 代表剛好被付款或取消，也算處理完，不會再被查到
                    if (orderService.expireUnpaidOrder(orderId)) {
                        cancelled++;
                    }
                    processed++;
                } catch (RuntimeException exception) {
                    log.error("failed to cancel unpaid order {}", orderId, exception);
                }
            }
            // 最後一批，或整批都失敗（再查一次會拿到同樣的資料，避免無窮迴圈）
            if (orderIds.size() < properties.batchSize() || processed == 0) {
                break;
            }
        }
        if (cancelled > 0) {
            log.info("cancelled {} unpaid order(s) created before {}", cancelled, cutoff);
        }
    }
}
