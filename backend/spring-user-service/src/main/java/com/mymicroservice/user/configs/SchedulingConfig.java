package com.mymicroservice.user.configs;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// 啟用 @Scheduled（RefreshTokenCleanupJob）
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
