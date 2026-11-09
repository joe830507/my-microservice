package com.mymicroservice.gateway;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

// 所有整合測試共用一個 Redis 容器（版本與 infra/docker-compose.yml 一致），整個測試執行只啟動一次
// 不用 @ServiceConnection bean：各測試類別的設定不同會建立不同的 context，每個 context 都會再啟動一個容器
// 測試不會連到本機 docker-compose 的 gateway-redis
final class TestRedis {

    @SuppressWarnings("resource") // 由 Testcontainers 的 Ryuk 在測試結束後清掉
    static final GenericContainer<?> CONTAINER = new GenericContainer<>(DockerImageName.parse("redis:8.2"))
            .withExposedPorts(6379);

    static {
        CONTAINER.start();
    }

    private TestRedis() {
    }

    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", CONTAINER::getHost);
        registry.add("spring.data.redis.port", () -> CONTAINER.getMappedPort(6379));
    }
}
