package com.mymicroservice.user;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

// 整合測試用的 PostgreSQL 與 Redis，版本與 infra/docker-compose.yml 一致
// @ServiceConnection 會自動把容器的連線資訊套到 spring.datasource 與 spring.data.redis
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer("postgres:18.6");
    }

    // Testcontainers 沒有官方的 Redis 模組，用 GenericContainer 並以 name 指定連線類型
    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>("redis:8.2").withExposedPorts(6379);
    }
}
