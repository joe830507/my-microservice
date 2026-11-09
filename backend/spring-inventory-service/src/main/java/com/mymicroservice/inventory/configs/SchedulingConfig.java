package com.mymicroservice.inventory.configs;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// 啟用 @Scheduled（ReservationExpiryJob）
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
