package com.mymicroservice.payment.jobs;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mymicroservice.payment.properties.OrderNotificationRetryProperties;
import com.mymicroservice.payment.repositories.PaymentRepository;
import com.mymicroservice.payment.service.PaymentService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 重試通知 order-service：付款成功但通知失敗（order-service 連不上、5xx）的付款，重新通知
// order-service 那邊是冪等的，重複通知沒關係；多個實例同時執行也只會改一次結果（條件式 UPDATE）
// 每次只掃一輪：這輪失敗的（order-service 還沒恢復）留到下次排程
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderNotificationRetryJob {
    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;
    private final OrderNotificationRetryProperties properties;

    @Scheduled(cron = "${app.order-notification-retry.cron}", zone = "UTC")
    public void run() {
        Instant cutoff = Instant.now().minus(properties.minAge());
        List<UUID> paymentIds = paymentRepository.findPendingNotificationUpdatedBefore(cutoff,
                PageRequest.of(0, properties.batchSize()));
        int done = 0;
        for (UUID paymentId : paymentIds) {
            try {
                if (paymentService.retryOrderNotification(paymentId)) {
                    done++;
                }
            } catch (RuntimeException exception) {
                log.error("failed to notify order of payment {}", paymentId, exception);
            }
        }
        if (!paymentIds.isEmpty()) {
            log.info("notified order-service of {} of {} payment(s)", done, paymentIds.size());
        }
    }
}
