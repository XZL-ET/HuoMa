package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Sql(scripts = "classpath:schema-test.sql")
@DisplayName("UserRepository 自定义查询")
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager em;

    @BeforeEach
    void setUp() {
        em.persistFlushFind(User.builder()
                .username("admin")
                .passwordHash("$2a$10$hash")
                .displayName("管理员")
                .role(User.UserRole.admin)
                .enabled(true)
                .build());
        em.persistFlushFind(User.builder()
                .username("operator1")
                .passwordHash("$2a$10$hash2")
                .displayName("运营人员")
                .role(User.UserRole.operator)
                .enabled(true)
                .build());
        em.persistFlushFind(User.builder()
                .username("disabled_user")
                .passwordHash("$2a$10$hash3")
                .displayName("已禁用")
                .role(User.UserRole.operator)
                .enabled(false)
                .build());
    }

    @Test
    @DisplayName("findByUsername — 按用户名查找")
    void findByUsername() {
        assertThat(userRepository.findByUsername("admin")).isPresent();
        assertThat(userRepository.findByUsername("not-exist")).isEmpty();
    }

    @Test
    @DisplayName("existsByUsername — 检查用户名是否存在")
    void existsByUsername() {
        assertThat(userRepository.existsByUsername("admin")).isTrue();
        assertThat(userRepository.existsByUsername("new_user")).isFalse();
    }

    @Test
    @DisplayName("countByEnabledTrue — 统计启用用户数")
    void countByEnabledTrue() {
        assertThat(userRepository.countByEnabledTrue()).isEqualTo(2);
    }
}
