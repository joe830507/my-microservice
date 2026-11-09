package com.mymicroservice.product;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.utility.MountableFile;
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

    // 相容 S3 的物件儲存（SeaweedFS），版本與 infra/docker-compose.yml 一致；帳號與匿名讀取權限在 seaweedfs/s3.json
    // bucket 由各測試自己建立（compose 是 object-storage-init 容器）
    @Bean
    GenericContainer<?> objectStorageContainer() {
        return new GenericContainer<>("chrislusf/seaweedfs:3.80")
                .withCopyFileToContainer(MountableFile.forClasspathResource("seaweedfs/s3.json"), "/etc/seaweedfs/s3.json")
                .withCommand("server", "-s3", "-s3.port=8333", "-s3.config=/etc/seaweedfs/s3.json", "-dir=/data")
                .withExposedPorts(8333)
                .waitingFor(org.testcontainers.containers.wait.strategy.Wait
                        .forLogMessage(".*connected to filer.*", 1));
    }

    @Bean
    DynamicPropertyRegistrar objectStorageProperties(GenericContainer<?> objectStorageContainer) {
        return registry -> {
            String endpoint = "http://" + objectStorageContainer.getHost() + ":"
                    + objectStorageContainer.getMappedPort(8333);
            registry.add("storage.endpoint", () -> endpoint);
            registry.add("storage.public-base-url", () -> endpoint + "/product-images");
            registry.add("storage.access-key", () -> "test-access");
            registry.add("storage.secret-key", () -> "test-secret");
        };
    }
}
