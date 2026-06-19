package com.bookstore.qrcode.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * 本地缓存配置（Caffeine 内存缓存，不依赖 Redis）。
 * <p>
 * 用于学校自助查询的市州/区县列表缓存，与核心打标业务的 Redis 完全隔离。
 * </p>
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(Arrays.asList(
                buildCache("cities", 5),
                buildCache("districts", 5)
        ));
        return manager;
    }

    private CaffeineCache buildCache(String name, int ttlMinutes) {
        return new CaffeineCache(name, Caffeine.newBuilder()
                .expireAfterWrite(ttlMinutes, TimeUnit.MINUTES)
                .maximumSize(100)
                .recordStats()
                .build());
    }
}
