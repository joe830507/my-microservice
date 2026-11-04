package com.mymicroservice.product.config;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import com.mymicroservice.product.cache.CacheNames;
import com.mymicroservice.product.dto.product.getProductById.GetProductByIdRes;

import io.lettuce.core.ClientOptions;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@RequiredArgsConstructor
public class RedisConfig {
    private final JsonMapper jsonMapper;

    // Redis 斷線時讓指令立即失敗，由 CategoryTreeCache 改查 DB；預設會排隊等重連直到 timeout
    @Bean
    public LettuceClientOptionsBuilderCustomizer rejectCommandsWhenDisconnected() {
        return (builder) -> builder.disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
    }

    // Lettuce 下 RedisCacheWriter 預設非同步寫入：put / evict 回傳時指令可能還沒執行
    // 會讓「commit 後刪快取」失去保證（刪除晚於下一次讀取），也可能讓舊資料的 put 蓋在 evict 之後，因此改為同步寫入
    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheManagerBuilderCustomizer(
            RedisConnectionFactory connectionFactory) {
        return (builder) -> builder
                .cacheWriter(RedisCacheWriter.create(connectionFactory, writer -> writer.immediateWrites()))
                .disableCreateOnMissingCache() // 沒先設定過的 cacheName，直接報錯
                .withCacheConfiguration(CacheNames.PRODUCT_DETAIL, cacheConfig(GetProductByIdRes.class))
                .withCacheConfiguration(CacheNames.PRODUCT_SKU_TO_ID, cacheConfig(UUID.class));
    }

    // 每個 cache 只存一種型別，序列化時指定型別，Redis 中不會夾帶類別資訊
    private RedisCacheConfiguration cacheConfig(Class<?> valueType) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .computePrefixWith(CacheNames::prefix)
                .serializeValuesWith(SerializationPair
                        .fromSerializer(new JacksonJsonRedisSerializer<>(jsonMapper, valueType)))
                .entryTtl((key, value) -> value == null ? Duration.ofSeconds(90)
                        : Duration.ofMinutes(30).plusSeconds(ThreadLocalRandom.current().nextLong(0, 300)));
    }
}