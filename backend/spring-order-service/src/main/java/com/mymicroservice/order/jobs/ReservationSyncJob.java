package com.mymicroservice.order.jobs;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mymicroservice.order.properties.ReservationSyncProperties;
import com.mymicroservice.order.repositories.OrderRepository;
import com.mymicroservice.order.service.OrderService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 補做庫存同步：取消或付款後呼叫 inventory 釋放、確認失敗的訂單（reservation_synced = false），重新呼叫
// inventory 那邊是冪等的，重複呼叫沒關係
// 每次只掃一輪：這輪失敗的（inventory 還沒恢復）留到下次排程，不在同一次裡反覆重試
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationSyncJob {
    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final ReservationSyncProperties properties;

    @Scheduled(cron = "${app.reservation-sync.cron}", zone = "UTC")
    public void run() {
        Instant cutoff = Instant.now().minus(properties.minAge());
        List<UUID> orderIds = orderRepository.findUnsyncedUpdatedBefore(cutoff,
                PageRequest.of(0, properties.batchSize()));
        int synced = 0;
        for (UUID orderId : orderIds) {
            try {
                if (orderService.retryReservationSync(orderId)) {
                    synced++;
                }
            } catch (RuntimeException exception) {
                log.error("failed to sync reservation of order {}", orderId, exception);
            }
        }
        if (!orderIds.isEmpty()) {
            log.info("synced {} of {} order reservation(s) with inventory", synced, orderIds.size());
        }
    }
}
