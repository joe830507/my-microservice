package com.mymicroservice.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;

import com.mymicroservice.common.web.RequireRolesGuard;

// RequireRolesGuard：舊版 common 或 interceptor 沒註冊時啟動失敗，避免 @RequireRoles 被默默忽略
@SpringBootApplication
@ConfigurationPropertiesScan
@Import(RequireRolesGuard.class)
public class PaymentServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
