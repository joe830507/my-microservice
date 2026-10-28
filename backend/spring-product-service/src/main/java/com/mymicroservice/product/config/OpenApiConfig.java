package com.mymicroservice.product.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI productServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Product Service API")
                        .description("商品與分類相關 API")
                        .version("v1"));
    }
}
