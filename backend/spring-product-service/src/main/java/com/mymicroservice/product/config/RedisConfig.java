package com.mymicroservice.product.config;

import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.lettuce.core.ClientOptions;

@Configuration
public class RedisConfig {

    // Redis 斷線時讓指令立即失敗，由 CategoryTreeCache 改查 DB；預設會排隊等重連直到 timeout
    @Bean
    public LettuceClientOptionsBuilderCustomizer rejectCommandsWhenDisconnected() {
        return (builder) -> builder.disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
    }
}
