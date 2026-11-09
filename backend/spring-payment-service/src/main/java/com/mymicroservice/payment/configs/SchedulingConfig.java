package com.mymicroservice.payment.configs;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// 啟用 @Scheduled（OrderNotificationRetryJob）
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
