package com.mymicroservice.product;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

import com.mymicroservice.common.web.RequireRolesGuard;

// RequireRolesGuard：舊版 common 或 interceptor 沒註冊時啟動失敗，避免 @RequireRoles 被默默忽略
@SpringBootApplication
@Import(RequireRolesGuard.class)
public class ProductServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(ProductServiceApplication.class, args);
	}

}
