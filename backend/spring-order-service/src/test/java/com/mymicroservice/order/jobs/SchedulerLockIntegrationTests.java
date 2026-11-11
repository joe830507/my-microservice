package com.mymicroservice.order.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.mymicroservice.order.TestcontainersConfiguration;
import com.mymicroservice.order.repositories.OrderRepository;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;

// 排程的分散式鎖（階段 B4）：副本之間每一輪只有一個執行。鎖放在 order_service.shedlock，用資料庫的時間。
// 這裡把「至少持有」時間設成 1 分鐘，模擬兩個副本在同一個 cron 時間點觸發同一個排程
@SpringBootTest(properties = "app.scheduling.lock.at-least-for=PT1M")
@Import(TestcontainersConfiguration.class)
class SchedulerLockIntegrationTests {

    @Autowired
    private PaymentTimeoutJob paymentTimeoutJob;
    @Autowired
    private LockProvider lockProvider;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    // 排程每次執行都會先查逾時的訂單：用這個呼叫次數判斷有沒有真的執行
    @MockitoSpyBean
    private OrderRepository orderRepository;

    @BeforeEach
    void setUp() {
        // 不能刪掉資料列：ShedLock 記得「這個鎖已經有資料列」，之後只會 UPDATE，列被刪掉就再也拿不到鎖
        // 改成讓所有鎖過期，等於上一輪已經結束（ShedLock 的時間是 UTC 的 TIMESTAMP；用 now() 會依連線的時區寫入，JVM 不是 UTC 時就算錯）
        jdbcTemplate.execute("UPDATE order_service.shedlock SET lock_until = timezone('utc', now()) - interval '1 hour'");
        clearInvocations(orderRepository);
    }

    @Test
    void firstRunExecutes_secondRunInTheSameRoundIsSkipped() {
        paymentTimeoutJob.run();
        verify(orderRepository, times(1)).findPendingCreatedBefore(any(Instant.class), any(Pageable.class));

        // 另一個副本在同一輪（lockAtLeastFor 內）觸發：略過
        paymentTimeoutJob.run();
        verify(orderRepository, times(1)).findPendingCreatedBefore(any(Instant.class), any(Pageable.class));

        // 鎖記在資料庫裡
        List<String> lockedBy = jdbcTemplate.queryForList(
                "SELECT locked_by FROM order_service.shedlock WHERE name = 'paymentTimeout'", String.class);
        assertThat(lockedBy).hasSize(1);
    }

    @Test
    void runIsSkippedWhileAnotherReplicaHoldsTheLock_andRunsAfterItIsReleased() {
        // 另一個副本拿著鎖（用同一個資料庫，等於另一個副本）
        Optional<SimpleLock> held = lockProvider.lock(new LockConfiguration(Instant.now(), "paymentTimeout",
                Duration.ofMinutes(5), Duration.ZERO));
        assertThat(held).isPresent();

        paymentTimeoutJob.run();
        verify(orderRepository, times(0)).findPendingCreatedBefore(any(Instant.class), any(Pageable.class));

        held.get().unlock();
        // lockAtLeastFor = 0 的鎖放掉就釋放；之後這個副本可以執行
        paymentTimeoutJob.run();
        verify(orderRepository, times(1)).findPendingCreatedBefore(any(Instant.class), any(Pageable.class));
    }

    @Test
    void differentJobsUseDifferentLocks() {
        Optional<SimpleLock> held = lockProvider.lock(new LockConfiguration(Instant.now(), "reservationSync",
                Duration.ofMinutes(5), Duration.ZERO));
        assertThat(held).isPresent();

        // 拿著 reservationSync 的鎖，不影響 paymentTimeout
        paymentTimeoutJob.run();

        verify(orderRepository, times(1)).findPendingCreatedBefore(any(Instant.class), any(Pageable.class));
        held.get().unlock();
    }

    @Test
    void concurrentRuns_executeExactlyOnce() throws Exception {
        int replicas = 4;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(replicas);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < replicas; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    paymentTimeoutJob.run();
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        verify(orderRepository, times(1)).findPendingCreatedBefore(any(Instant.class), any(Pageable.class));
    }
}
