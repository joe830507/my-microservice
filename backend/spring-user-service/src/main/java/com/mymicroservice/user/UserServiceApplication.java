package com.mymicroservice.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.mymicroservice.common.web.RequireRolesGuard;

// RequireRolesGuard：舊版 common 或 interceptor 沒註冊時啟動失敗，避免 @RequireRoles 被默默忽略
@SpringBootApplication
@ConfigurationPropertiesScan
@Import(RequireRolesGuard.class)
public class UserServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }

    @Bean
    public PasswordEncoder createAPasswordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
