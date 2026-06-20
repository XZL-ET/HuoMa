package com.bookstore.qrcode.config;

import com.bookstore.qrcode.entity.User;
import com.bookstore.qrcode.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 数据初始化器。
 * <p>
 * 应用启动后检查 users 表，若无启用的用户则自动创建默认管理员账号。
 * 默认密码通过 {@code app.admin.default-password} 配置，支持环境变量覆盖。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin.default-username:admin}")
    private String defaultUsername;

    @Value("${app.admin.default-password:}")
    private String defaultPassword;

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.countByEnabledTrue() > 0) {
            return;
        }
        if (defaultPassword == null || defaultPassword.isBlank()) {
            log.warn("未配置默认管理员密码(app.admin.default-password)，跳过自建。"
                + "请手动创建管理员账户或设置 ADMIN_DEFAULT_PASSWORD 环境变量");
            return;
        }
        User admin = User.builder()
                .username(defaultUsername)
                .passwordHash(passwordEncoder.encode(defaultPassword))
                .displayName("系统管理员")
                .role(User.UserRole.admin)
                .enabled(true)
                .build();
        userRepository.save(admin);
        log.info("默认管理员已创建: {}（密码请查看环境变量或配置文件）", defaultUsername);
    }
}
