package com.mymicroservice.order.configs;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// 啟用 @Scheduled（PaymentTimeoutJob、ReservationSyncJob）
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
