package com.bookstore.qrcode.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security 安全配置。
 * <p>
 * 采用表单登录 + BCrypt 密码编码，保护所有管理后台页面，
 * 同时保持企微回调接口和健康检查端点公开访问。
 * </p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(authz -> authz
                        // 企微回调：URL 验证 + 事件推送，必须公开
                        .requestMatchers("/api/wecom/callback/**").permitAll()
                        // Actuator 健康检查：供 K8s 探针使用
                        .requestMatchers("/actuator/health/**").permitAll()
                        // 下载中心全部路径：由 DownloadAuthenticationFilter 独立处理认证
                        .requestMatchers("/download/**").permitAll()
                        // 用户管理：仅 admin 可访问
                        .requestMatchers("/users/**").hasRole("ADMIN")
                        // 区县负责人配置：仅 admin 可访问
                        .requestMatchers("/admin/district-managers/**").hasRole("ADMIN")
                        // 登录页面及静态资源
                        .requestMatchers("/login", "/css/**", "/js/**").permitAll()
                        // 其余所有请求需要登录
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .defaultSuccessUrl("/", true)
                        .failureUrl("/login?error")
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                        .permitAll()
                );
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
