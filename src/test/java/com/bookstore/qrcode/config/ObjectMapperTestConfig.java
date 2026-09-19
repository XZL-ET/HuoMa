package com.bookstore.qrcode.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** 为 @DataJpaTest 切片提供 ObjectMapper（切片不含 Jackson 自动配置）。 */
@TestConfiguration
public class ObjectMapperTestConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
