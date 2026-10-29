package com.mymicroservice.product.config;

import org.jspecify.annotations.Nullable;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.LoggingCacheErrorHandler;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    @Override
    public @Nullable CacheErrorHandler errorHandler() {
        return new LoggingCacheErrorHandler(false);
    }
}
