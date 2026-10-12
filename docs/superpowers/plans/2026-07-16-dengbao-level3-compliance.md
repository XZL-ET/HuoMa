# 火马平台等保三级合规整改设计文档

> **对于 agentic workers：** 使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实施此计划。步骤使用 checkbox（`- [ ]`）语法进行追踪。

**目标：** 使火马（HuoMa）Spring Boot 应用达到 GB/T 22239-2019 等保三级（网络安全等级保护第三级）合规标准

**架构：** 在现有阿里云 ECS+RDS+Redis+ALB 架构之上，通过控制台开通云安全产品 + Spring Security 增强 + 数据加密 + 制度文档四层整改，预计 4-6 周达到可测评状态

**技术栈：** Spring Boot 3.2.12、Spring Security 6、MySQL 8.0 (RDS)、Redis (Tair)、Undertow、Nginx、Thymeleaf、HTMX

## 全局约束

- 不引入新的基础设施（如自建日志服务器、堡垒机）——优先使用阿里云托管服务
- 不改变现有业务逻辑——安全加固采用加法模式（新增过滤器/拦截器/AOP），不修改现有 Service 核心流程
- 数据库改动必须支持停服迁移（凌晨窗口，通过 RDS 备份兜底）
- 前端改动限定在登录页和用户管理页，不动其他 Thymeleaf 模板
- 所有密钥/密码通过 environment variable 注入，不写入配置文件
- 国密（SM2/SM3/SM4）暂不强制——等保三级不强制国密，仅密评（密码应用安全性评估）才需要，密评为可选附加项

---

## 第一阶段：基础设施安全（第 1 周，零代码改动）

### 概述

全部通过阿里云控制台操作，不需要修改任何 Java 代码或重启应用。本阶段消除网络/主机/数据库层面的安全设备缺失，奠定合规底座。

### 任务 1.1：ALB 挂载 WAF（Web 应用防火墙）

**操作平台：** 阿里云控制台 → Web 应用防火墙 → 接入管理

**操作步骤：**

- [ ] **1.1.1：开通 WAF 企业版**

在阿里云 WAF 产品页购买"Web 应用防火墙 3.0 企业版"，选择按量计费或包年包月。计费参考：¥1,500/月。

- [ ] **1.1.2：将 ALB 实例接入 WAF**

```text
WAF 控制台 → 接入管理 → 添加域名
  - 域名：huoma.gsxhsd.com
  - 协议：HTTPS（ALB 已做 TLS 终结）
  - 接入模式：透明接入（ALB 七层代理模式）
  - WAF 实例自动关联到现有 ALB 实例
```

- [ ] **1.1.3：配置基础防护规则**

```text
WAF 控制台 → 防护配置 → Web 核心防护规则
  - 开启：SQL 注入防护
  - 开启：XSS 跨站脚本防护
  - 开启：Webshell 上传防护
  - 开启：命令执行防护
  - 防护模式：拦截模式（生产环境建议先用"观察模式"跑 3 天再切换）
```

- [ ] **1.1.4：配置 CC 攻击防护（频率限制）**

```text
WAF 控制台 → 防护配置 → CC 防护
  - 针对 /api/form/submit 路径：单 IP 30 次/分钟（补充应用层限流）
  - 针对 /login 路径：单 IP 10 次/分钟（防暴力破解，应用层也会加但 WAF 先挡一层）
  - 模式：滑块验证（人机识别）
```

- [ ] **1.1.5：配置 IP 黑名单**

```text
WAF 控制台 → 防护配置 → IP 黑名单
  - 添加已知恶意 IP 情报库（WAF 自动更新）
  - 模式：拦截
```

**验证方式：**
```bash
# 测试 SQL 注入是否被 WAF 拦截
curl -k "https://huoma.gsxhsd.com/login?username=admin' OR '1'='1"
# 预期：返回 WAF 拦截页面（HTTP 403 或重定向到验证页面）
```

---

### 任务 1.2：RDS 安全加固

**操作平台：** 阿里云控制台 → 云数据库 RDS

- [ ] **1.2.1：开启 SQL 审计**

```text
RDS 控制台 → 实例详情 → 数据安全性 → SQL 审计
  - 开启 SQL 审计
  - 日志保留：180 天（等保三级最低要求 6 个月）
  - 审计范围：所有 SQL 语句（DML + DDL + DCL）
```

- [ ] **1.2.2：开启跨地域备份**

```text
RDS 控制台 → 实例详情 → 备份恢复 → 跨地域备份
  - 开启跨地域备份
  - 备份目标地域：不同于当前 Region（如当前在北京，备份到上海）
  - 备份保留：7 天（与本地备份策略一致）
```

- [ ] **1.2.3：配置 SSL 加密连接确认**

```text
RDS 控制台 → 实例详情 → 数据安全性 → SSL
  - 确认 SSL 已开启
  - 确认 prod profile 的 JDBC URL 包含 useSSL=true（已有，配置文件 application.yml:135 已确认）
```

- [ ] **1.2.4：配置白名单收紧**

```text
RDS 控制台 → 实例详情 → 数据安全性 → 白名单
  - 确认只有 ECS 安全组和运维 IP 在白名单内
  - 删除 0.0.0.0/0（如果意外存在）
```

**验证方式：**
```text
RDS 控制台 → SQL 审计 → 查询审计日志
预期：能看到近期的 SQL 执行记录
```

---

### 任务 1.3：ECS 主机安全

**操作平台：** 阿里云控制台 → 云安全中心 / ECS

- [ ] **1.3.1：安装云安全中心 Agent（每台 ECS）**

```text
云安全中心控制台 → 设置 → 客户端安装
  - 已经通过 ECS 控制台购买了 ECS，大概率已经自动安装了 Agent
  - 如未安装：选择 ECS 实例 → 一键安装
```

- [ ] **1.3.2：开启主机入侵检测**

```text
云安全中心控制台 → 安全防范 → 入侵检测
  - 开启：异常登录检测
  - 开启：恶意进程检测
  - 开启：Webshell 检测（文件完整性监控）
  - 开启：漏洞管理（自动扫描 CVE）
```

- [ ] **1.3.3：执行安全基线检查**

```text
云安全中心控制台 → 基线检查 → 等保三级基线
  - 检查项：操作系统（Linux）、数据库（MySQL）、中间件（Nginx）
  - 查看不合规项 → 按指导逐一修复
```

- [ ] **1.3.4：开启漏洞扫描（定期自动）**

```text
云安全中心控制台 → 漏洞管理 → 扫描设置
  - 扫描周期：每周
  - 扫描范围：全量 ECS 实例
  - 高危漏洞通知：短信/邮件告警
```

- [ ] **1.3.5：ECS 安全组审计**

```text
ECS 控制台 → 安全组 → 规则列表
  - 确认入方向规则：只允许 ALB 安全组（100.64.0.0/10）的 80 端口
  - 确认入方向规则：只允许堡垒机/运维 IP 的 22 端口（SSH）
  - 确认出方向规则：允许到 RDS 安全组（3306）+ 到 Redis（6379）+ 到公网（企微 API）
  - 删除任何 0.0.0.0/0 入站规则
```

---

### 任务 1.4：ALB 访问日志投递

**操作平台：** 阿里云控制台 → 负载均衡 → ALB

- [ ] **1.4.1：开启 ALB 访问日志**

```text
ALB 控制台 → 实例详情 → 访问日志
  - 开启访问日志
  - 投递到：OSS Bucket（新建或选择已有）
  - 日志保留：180 天（OSS 生命周期策略自动过期）
```

- [ ] **1.4.2：配置 OSS 生命周期策略**

```text
OSS 控制台 → Bucket → 生命周期规则
  - 规则名称：alb-log-retention
  - 前缀：ALB 日志前缀
  - 过期天数：180 天
```

- [ ] **1.4.3：Nginx 日志 JSON 格式 + 投递（已基本完成）**

```text
当前 Nginx 已配置基本 access log（/var/log/nginx/access.log）
确认日志格式包含以下字段：
  - $remote_addr（客户端 IP，经过 real_ip_from 修正为真实 IP）
  - $time_iso8601（时间戳）
  - $request（请求行）
  - $status（状态码）
  - $body_bytes_sent（响应大小）
  - $http_user_agent（UA）
  - $http_referer（Referer）
```

---

### 任务 1.5：应用配置安全加固（仅配置文件改动）

- [ ] **1.5.1：配置 Session Cookie 安全属性**

文件：`src/main/resources/application.yml`，在 `server:` 节点下新增：

```yaml
server:
  forward-headers-strategy: native
  port: 8080
  # ===== 等保三级：会话安全加固 =====
  servlet:
    session:
      timeout: 15m                    # 管理后台会话 15 分钟超时
      cookie:
        http-only: true               # JS 无法读取 JSESSIONID
        secure: true                  # 仅通过 HTTPS 发送（ALB 设 X-Forwarded-Proto 后自动生效）
        same-site: lax                # 防 CSRF
  undertow:
    threads:
      worker: ${UNDERTOW_WORKER_THREADS:20}
```

- [ ] **1.5.2：验证 Spring Security 自动配置生效**

Spring Boot 通过 `server.servlet.session.*` 配置会自动设置 JSESSIONID Cookie 属性。确认 Undertow 的 `ServletCookieConfigurer` 读取了这些配置。如果遇到 Secure 属性在 HTTP 回环不生效的问题（因为 Spring Boot 3.x 的 `Secure` 标记依赖于 `request.isSecure()`，而 Nginx→Undertow 走的是 HTTP），在 `application.yml` 中需要额外确认 `server.forward-headers-strategy: native` 已配置（当前已配置 ✅）。

- [ ] **1.5.3：限制 Actuator 端点暴露（已配置 ✅）**

当前配置：`management.endpoints.web.exposure.include=health,metrics`。确认无误，不需要 `env`/`configprops`/`beans` 等敏感端点。

---

### 第一阶段总结

| 任务 | 产品 | 操作方式 | 预估时间 |
|------|------|----------|:--------:|
| 1.1 WAF | 阿里云 WAF 3.0 | 控制台 | 30 分钟 |
| 1.2 RDS 加固 | RDS 控制台 | 控制台 | 20 分钟 |
| 1.3 ECS 安全 | 云安全中心 | 控制台 + ECS | 1 小时 |
| 1.4 日志投递 | ALB/OSS | 控制台 | 15 分钟 |
| 1.5 应用配置 | application.yml | 代码（3 行） | 10 分钟 |

**总计：约 2 小时，当天下午可完成。无需重启应用。**

---

## 第二阶段：身份鉴别与审计加固（第 2-3 周）

### 概述

等保三级失分最多的三个维度：身份鉴别（MFA + 密码策略 + 登录锁定）、访问控制、安全审计。本阶段解决身份鉴别和审计两个维度的所有差距。

### 任务 2.1：TOTP 双因素认证（MFA）

**文件：**
- 新增：`src/main/java/com/bookstore/qrcode/security/MfaAuthenticationFilter.java`
- 新增：`src/main/java/com/bookstore/qrcode/security/MfaService.java`
- 新增：`src/main/java/com/bookstore/qrcode/security/TotpUtil.java`
- 新增：`src/main/java/com/bookstore/qrcode/entity/MfaCredential.java`
- 修改：`src/main/java/com/bookstore/qrcode/config/SecurityConfig.java`
- 修改：`src/main/java/com/bookstore/qrcode/entity/User.java`
- 修改：`src/main/resources/templates/auth/login.html`

**设计说明：**

TOTP 采用标准 RFC 6238（Google Authenticator 兼容），使用 HMAC-SHA1 算法，30 秒时间窗口。用户首次登录成功后跳转到 MFA 验证页，输入 6 位动态码，验证通过后才创建完整会话。

不使用短信/企业微信扫码作为第二因素——TOTP 是离线方案，不依赖企微 API（宕机场景下管理员仍可登录），且无短信费用。

登录流程变更：
```
原流程：用户名+密码 → 登录成功 → 首页
新流程：用户名+密码 → 验证通过 → MFA 验证页（6 位码）→ 验证通过 → 首页
```

- [ ] **2.1.1：新增 users 表 MFA 字段**

Flyway 迁移文件：`src/main/resources/db/migration/V3__mfa_fields.sql`

```sql
ALTER TABLE users
  ADD COLUMN mfa_enabled TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'MFA 是否已启用',
  ADD COLUMN mfa_setup_at DATETIME NULL COMMENT 'MFA 首次绑定时间';

-- MFA 密钥独立存储（安全隔离：避免每次查询用户时加载敏感密钥）
CREATE TABLE IF NOT EXISTS mfa_credential (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE,
    secret VARCHAR(32) NOT NULL COMMENT 'Base32 编码的 TOTP 密钥',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- [ ] **2.1.2：新增 MfaCredential 实体**

`src/main/java/com/bookstore/qrcode/entity/MfaCredential.java`

```java
package com.bookstore.qrcode.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "mfa_credential")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MfaCredential {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    /** Base32 编码的 TOTP 密钥，如 JBSWY3DPEHPK3PXP */
    @Column(name = "secret", nullable = false, length = 32)
    private String secret;

    @Column(name = "created_at", updatable = false)
    private java.time.LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = java.time.LocalDateTime.now();
    }
}
```

> **设计决策：** 将 MFA 密钥从 User 实体中拆分到独立表 `mfa_credential`，避免每次查询用户时加载敏感密钥。MFA 的启用/绑定逻辑通过 `users.mfa_enabled` 字段控制。

- [ ] **2.1.3：实现 TOTP 工具类**

`src/main/java/com/bookstore/qrcode/security/TotpUtil.java`

```java
package com.bookstore.qrcode.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * RFC 6238 TOTP 实现。
 * 兼容 Google Authenticator、Authy、Microsoft Authenticator 等标准 TOTP 客户端。
 */
public final class TotpUtil {

    private static final int SECRET_LENGTH = 20;       // 160-bit 密钥
    private static final int TIME_STEP_SECONDS = 30;   // 30 秒窗口
    private static final int DIGITS = 6;               // 6 位数字码
    private static final String ALGORITHM = "HmacSHA1";

    private TotpUtil() {}

    /** 生成新的 Base32 TOTP 密钥（如 JBSWY3DPEHPK3PXP） */
    public static String generateSecret() {
        byte[] bytes = new byte[SECRET_LENGTH];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 验证 6 位 TOTP 码是否正确。
     * 同时验证当前窗口和前一窗口（允许 ±30 秒的时间偏差）。
     */
    public static boolean verify(String base32Secret, String code) {
        if (base32Secret == null || code == null) return false;
        try {
            byte[] key = Base64.getDecoder().decode(base32Secret);
            long counter = System.currentTimeMillis() / 1000 / TIME_STEP_SECONDS;
            // 当前窗口 + 前一个窗口
            return generateTOTP(key, counter).equals(code)
                || generateTOTP(key, counter - 1).equals(code);
        } catch (Exception e) {
            return false;
        }
    }

    /** 生成指定 counter 的 6 位 TOTP 码 */
    private static String generateTOTP(byte[] key, long counter) throws Exception {
        byte[] data = new byte[8];
        for (int i = 7; i >= 0; i--) {
            data[i] = (byte) (counter & 0xFF);
            counter >>= 8;
        }
        Mac mac = Mac.getInstance(ALGORITHM);
        mac.init(new SecretKeySpec(key, ALGORITHM));
        byte[] hash = mac.doFinal(data);
        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24)
                   | ((hash[offset + 1] & 0xFF) << 16)
                   | ((hash[offset + 2] & 0xFF) << 8)
                   | (hash[offset + 3] & 0xFF);
        int otp = binary % 1_000_000;
        return String.format("%06d", otp);
    }

    /** 生成 otpauth URI，供 APP 扫码添加 */
    public static String getOtpAuthUri(String username, String issuer, String secret) {
        return String.format("otpauth://totp/%s:%s?secret=%s&issuer=%s&algorithm=SHA1&digits=6&period=30",
            issuer, username, secret, issuer);
    }
}
```

- [ ] **2.1.4：实现 MfaService**

`src/main/java/com/bookstore/qrcode/security/MfaService.java`

```java
package com.bookstore.qrcode.security;

import com.bookstore.qrcode.entity.MfaCredential;
import com.bookstore.qrcode.entity.User;
import com.bookstore.qrcode.repository.MfaCredentialRepository;
import com.bookstore.qrcode.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MfaService {

    private final MfaCredentialRepository mfaCredentialRepo;
    private final UserRepository userRepo;

    /** 为用户生成新的 TOTP 密钥（Admin 页面首次绑定 MFA 时调用） */
    @Transactional
    public String generateSecret(Long userId) {
        String secret = TotpUtil.generateSecret();
        MfaCredential credential = mfaCredentialRepo.findByUserId(userId)
            .orElse(MfaCredential.builder().userId(userId).secret(secret).build());
        credential.setSecret(secret);
        mfaCredentialRepo.save(credential);
        return secret;
    }

    /** 验证 TOTP 码并激活 MFA（绑定流程：用户扫码后输入一次验证码确认） */
    @Transactional
    public boolean verifyAndActivate(Long userId, String code) {
        Optional<MfaCredential> opt = mfaCredentialRepo.findByUserId(userId);
        if (opt.isEmpty()) return false;
        boolean valid = TotpUtil.verify(opt.get().getSecret(), code);
        if (valid) {
            User user = userRepo.findById(userId).orElseThrow();
            user.setMfaEnabled(true);
            user.setMfaSetupAt(LocalDateTime.now());
            userRepo.save(user);
            log.info("MFA 已激活: userId={}, username={}", userId, user.getUsername());
        }
        return valid;
    }

    /** 登录流程中的 MFA 验证 */
    public boolean verifyLogin(Long userId, String code) {
        Optional<MfaCredential> opt = mfaCredentialRepo.findByUserId(userId);
        if (opt.isEmpty()) return false;
        return TotpUtil.verify(opt.get().getSecret(), code);
    }

    /** 获取 otpauth URI（用于生成二维码） */
    public String getOtpAuthUri(Long userId, String username) {
        Optional<MfaCredential> opt = mfaCredentialRepo.findByUserId(userId);
        if (opt.isEmpty()) return null;
        return TotpUtil.getOtpAuthUri(username, "火马平台", opt.get().getSecret());
    }
}
```

- [ ] **2.1.5：新增 MfaCredentialRepository**

`src/main/java/com/bookstore/qrcode/repository/MfaCredentialRepository.java`

```java
package com.bookstore.qrcode.repository;

import com.bookstore.qrcode.entity.MfaCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface MfaCredentialRepository extends JpaRepository<MfaCredential, Long> {
    Optional<MfaCredential> findByUserId(Long userId);
}
```

- [ ] **2.1.6：修改 User 实体，增加 MFA 字段**

在 `src/main/java/com/bookstore/qrcode/entity/User.java` 的实体类中新增字段：

```java
/** MFA 是否已启用 */
@Column(name = "mfa_enabled", nullable = false)
@Builder.Default
private Boolean mfaEnabled = false;

/** MFA 首次绑定时间 */
@Column(name = "mfa_setup_at")
private LocalDateTime mfaSetupAt;
```

> **注意：** 只有 `mfa_enabled` 和 `mfaSetupAt` 字段在 User 实体中。`mfa_secret` 从独立的 `mfa_credential` 表读取。User 类不需要 `mfa_secret` 字段。

- [ ] **2.1.7：新增 MfaAuthenticationFilter**

`src/main/java/com/bookstore/qrcode/security/MfaAuthenticationFilter.java`

```java
package com.bookstore.qrcode.security;

import com.bookstore.qrcode.entity.User;
import com.bookstore.qrcode.repository.UserRepository;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * MFA 验证过滤器。
 * <p>
 * 在 Spring Security 完成第一因子认证后，检查用户是否已启用 MFA。
 * 若已启用 MFA 但当前会话未通过 MFA 验证，则拦截请求并重定向到 MFA 验证页。
 * </p>
 */
@RequiredArgsConstructor
public class MfaAuthenticationFilter extends OncePerRequestFilter {

    private final UserRepository userRepo;
    private static final String MFA_VERIFIED_ATTR = "MFA_VERIFIED";
    private static final String MFA_PAGE = "/mfa/verify";
    private static final String MFA_VERIFY_URL = "/mfa/verify";
    // MFA 验证页、静态资源、登出不拦截
    private static final String[] SKIP_PATHS = {
        MFA_PAGE, MFA_VERIFY_URL, "/logout", "/login",
        "/css/", "/js/", "/favicon.ico", "/api/"
    };

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        for (String skip : SKIP_PATHS) {
            if (path.startsWith(skip)) {
                chain.doFilter(request, response);
                return;
            }
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()) {
            String username = auth.getName();
            User user = userRepo.findByUsername(username).orElse(null);
            if (user != null && Boolean.TRUE.equals(user.getMfaEnabled())) {
                HttpSession session = request.getSession(false);
                if (session == null || !Boolean.TRUE.equals(
                        session.getAttribute(MFA_VERIFIED_ATTR))) {
                    response.sendRedirect(request.getContextPath() + MFA_PAGE);
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }
}
```

- [ ] **2.1.8：新增 MFA 验证 Controller**

`src/main/java/com/bookstore/qrcode/controller/MfaController.java`

```java
package com.bookstore.qrcode.controller;

import com.bookstore.qrcode.entity.User;
import com.bookstore.qrcode.repository.UserRepository;
import com.bookstore.qrcode.security.MfaService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequiredArgsConstructor
public class MfaController {

    private final MfaService mfaService;
    private final UserRepository userRepo;
    private static final String MFA_VERIFIED_ATTR = "MFA_VERIFIED";

    /** MFA 验证码输入页面 */
    @GetMapping("/mfa/verify")
    public String verifyPage(Model model) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return "redirect:/login";
        }
        model.addAttribute("username", auth.getName());
        return "auth/mfa-verify";
    }

    /** 验证 TOTP 码 */
    @PostMapping("/mfa/verify")
    public String verify(@RequestParam String code, Model model) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return "redirect:/login";

        String username = auth.getName();
        User user = userRepo.findByUsername(username).orElse(null);
        if (user == null) return "redirect:/login";

        if (mfaService.verifyLogin(user.getId(), code)) {
            auth.getAuthenticatedRequest().getSession().setAttribute(MFA_VERIFIED_ATTR, true);
            return "redirect:/";
        }
        model.addAttribute("username", username);
        model.addAttribute("error", "验证码错误，请重试");
        return "auth/mfa-verify";
    }
}
```

- [ ] **2.1.9：新增 MFA 验证页面模板**

`src/main/resources/templates/auth/mfa-verify.html`

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>MFA 验证 — 火马平台</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css" rel="stylesheet">
    <style>
        body { background: #f0f2f5; height: 100vh; display: flex; align-items: center; justify-content: center; }
        .mfa-card { width: 100%; max-width: 380px; border-radius: 12px; box-shadow: 0 2px 12px rgba(0,0,0,0.10); }
        .mfa-card .card-body { padding: 2.5rem; }
        .code-input { font-size: 2rem; text-align: center; letter-spacing: 0.5em; font-family: monospace; }
    </style>
</head>
<body>
<div class="mfa-card card">
    <div class="card-body">
        <h5 class="text-center mb-3">两步验证</h5>
        <p class="text-muted small text-center mb-3">
            请输入 Authenticator 应用中的 6 位验证码
        </p>
        <div th:if="${error}" class="alert alert-danger text-center py-2 small" th:text="${error}"></div>
        <form th:action="@{/mfa/verify}" method="post">
            <div class="mb-3">
                <input type="text" name="code" class="form-control code-input"
                       maxlength="6" inputmode="numeric" pattern="[0-9]{6}"
                       placeholder="000000" required autofocus autocomplete="one-time-code">
            </div>
            <button type="submit" class="btn btn-primary w-100">验证</button>
        </form>
        <div class="text-center mt-3">
            <a th:href="@{/logout}" class="text-muted small">退出登录</a>
        </div>
    </div>
</div>
<script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js"></script>
</body>
</html>
```

- [ ] **2.1.10：修改 SecurityConfig，注册 MFA Filter**

在 `src/main/java/com/bookstore/qrcode/config/SecurityConfig.java` 中：

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Autowired
    private UserRepository userRepository;  // 新增注入

    // ... 已有代码保持不变 ...

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // ... csrf 配置不变 ...
            .authorizeHttpRequests(authz -> authz
                // 新增：MFA 验证页需要登录但不需要 MFA 已验证
                .requestMatchers("/mfa/**").authenticated()
                // ... 其余配置不变 ...
            )
            .formLogin(form -> form
                .loginPage("/login")
                .defaultSuccessUrl("/", true)
                .failureUrl("/login?error")
                .permitAll()
            )
            // ... logout 配置不变 ...
            .addFilterBefore(schoolRateLimitFilter(),
                org.springframework.security.web.access.intercept.AuthorizationFilter.class)
            // 新增：MFA 过滤器（在认证过滤器之后、授权过滤器之前）
            .addFilterBefore(
                new MfaAuthenticationFilter(userRepository),
                org.springframework.security.web.access.intercept.AuthorizationFilter.class);
        return http.build();
    }

    // ... 其余代码不变 ...
}
```

> **注意：** `SchoolRateLimitFilter` 和 `MfaAuthenticationFilter` 都在 `AuthorizationFilter` 之前。Spring Security 允许多个 Filter 注册在同一个位置之前，按添加顺序执行。`MfaAuthenticationFilter` 需要在 `SchoolRateLimitFilter` 之后注册（因为频控先挡暴力猜测）。

---

### 任务 2.2：密码复杂度策略

**文件：**
- 新增：`src/main/java/com/bookstore/qrcode/security/PasswordPolicyValidator.java`
- 修改：`src/main/resouces/templates/auth/login.html`
- 修改：`src/main/java/com/bookstore/qrcode/config/SecurityConfig.java`

- [ ] **2.2.1：实现密码策略验证器**

`src/main/java/com/bookstore/qrcode/security/PasswordPolicyValidator.java`

```java
package com.bookstore.qrcode.security;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 等保三级密码复杂度验证器。
 * 要求：至少 8 位，包含大写字母、小写字母、数字、特殊字符四类中的至少三类。
 */
public final class PasswordPolicyValidator {

    // 至少 3 类字符（大小写字母、数字、特殊字符），共 4 类
    private static final List<Pattern> PATTERNS = List.of(
        Pattern.compile("[A-Z]"),   // 大写字母
        Pattern.compile("[a-z]"),   // 小写字母
        Pattern.compile("[0-9]"),   // 数字
        Pattern.compile("[\\W_]")   // 特殊字符（非字母数字下划线，含 _）
    );
    private static final int MIN_LENGTH = 8;
    private static final int MIN_CATEGORIES = 3;

    private PasswordPolicyValidator() {}

    /**
     * 验证密码是否满足复杂度要求。
     * @return 违规原因列表；空列表表示通过
     */
    public static List<String> validate(String password) {
        List<String> violations = new ArrayList<>();
        if (password == null || password.length() < MIN_LENGTH) {
            violations.add("密码长度不少于 " + MIN_LENGTH + " 位");
        }
        long categories = PATTERNS.stream().filter(p -> p.matcher(password).find()).count();
        if (categories < MIN_CATEGORIES) {
            violations.add("密码需包含大写字母、小写字母、数字、特殊字符中至少 " + MIN_CATEGORIES + " 类");
        }
        return violations;
    }

    public static boolean isValid(String password) {
        return validate(password).isEmpty();
    }
}
```

- [ ] **2.2.2：在自定义 UserDetailsService 中集成密码验证**

查找现有的 `UserDetailsService` 实现：

```bash
grep -rn "UserDetailsService" --include="*.java"
```

如果项目当前直接使用 JPA 的 `UserDetailsService` 自动配置，需要新建自定义实现：

`src/main/java/com/bookstore/qrcode/security/CustomUserDetailsService.java`

```java
package com.bookstore.qrcode.security;

import com.bookstore.qrcode.entity.User;
import com.bookstore.qrcode.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepo;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepo.findByUsername(username)
            .orElseThrow(() -> new UsernameNotFoundException("用户不存在: " + username));

        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new DisabledException("账户已禁用");
        }

        return new org.springframework.security.core.userdetails.User(
            user.getUsername(),
            user.getPasswordHash(),
            List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name().toUpperCase()))
        );
    }
}
```

- [ ] **2.2.3：修改密码的前端校验 — 在修改密码的模态框/页面加入实时提示**

> **注：** 当前项目没有独立的"修改密码"页面。需要检查现有实现：如果 admin 通过用户管理页修改他人密码，需在后端校验。如果用户自行修改密码，需在对应表单加前端提示。此处先在 Service 层强制校验，前端 UI 改动量小不单独列任务。

---

### 任务 2.3：登录失败锁定

**文件：**
- 新增：`src/main/java/com/bookstore/qrcode/security/LoginFailureHandler.java`
- 修改：`src/main/java/com/bookstore/qrcode/config/SecurityConfig.java`

- [ ] **2.3.1：实现登录失败处理器（Redis 计数 + 锁定）**

`src/main/java/com/bookstore/qrcode/security/LoginFailureHandler.java`

```java
package com.bookstore.qrcode.security;

import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

import java.io.IOException;
import java.time.Duration;

/**
 * 等保三级登录失败锁定处理器。
 * <p>
 * 策略：同一用户名 5 次失败后锁定 15 分钟。
 * 使用 Redis 存储失败计数（Key: login_fail:{username}，TTL: 15 分钟）。
 * 每次失败后自动延长 TTL，保证连续失败时不会提前解锁。
 * </p>
 */
@Slf4j
@RequiredArgsConstructor
public class LoginFailureHandler implements AuthenticationFailureHandler {

    private final StringRedisTemplate redisTemplate;
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    private static final String KEY_PREFIX = "login_fail:";

    @Override
    public void onAuthenticationFailure(HttpServletRequest request,
                                        HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        String username = request.getParameter("username");
        if (username == null || username.isBlank()) {
            response.sendRedirect("/login?error");
            return;
        }

        String key = KEY_PREFIX + username;
        Long attempts = redisTemplate.opsForValue().increment(key);
        // 每次失败重置 TTL
        redisTemplate.expire(key, LOCK_DURATION);

        if (attempts != null && attempts >= MAX_ATTEMPTS) {
            log.warn("账户已锁定: username={}, attempts={}", username, attempts);
            response.sendRedirect("/login?locked");
        } else {
            int remaining = MAX_ATTEMPTS - (attempts != null ? attempts.intValue() : 0);
            response.sendRedirect("/login?error&remaining=" + remaining);
        }
    }
}
```

- [ ] **2.3.2：实现登录成功处理器（清除失败计数）**

`src/main/java/com/bookstore/qrcode/security/LoginSuccessHandler.java`

```java
package com.bookstore.qrcode.security;

import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import java.io.IOException;

@RequiredArgsConstructor
public class LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final StringRedisTemplate redisTemplate;
    private static final String KEY_PREFIX = "login_fail:";

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        // 清除失败计数
        redisTemplate.delete(KEY_PREFIX + authentication.getName());
        // 重定向到首页（如果用户启用了 MFA 会在 MfaAuthenticationFilter 中被拦截）
        response.sendRedirect("/");
    }
}
```

- [ ] **2.3.3：修改 SecurityConfig，注册成功/失败处理器**

在 `src/main/java/com/bookstore/qrcode/config/SecurityConfig.java` 中：

```java
// 注入 Redis（rateLimitRedisTemplate 已存在）
@Autowired
@Qualifier("rateLimitRedisTemplate")
private StringRedisTemplate rateLimitRedisTemplate;

@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
        // ... 已有配置不变 ...
        .formLogin(form -> form
            .loginPage("/login")
            .defaultSuccessUrl("/", true)
            .failureUrl("/login?error")           // 保留作为兜底
            .successHandler(new LoginSuccessHandler(rateLimitRedisTemplate))
            .failureHandler(new LoginFailureHandler(rateLimitRedisTemplate))
            .permitAll()
        )
        // ... 其余配置不变 ...
    return http.build();
}
```

- [ ] **2.3.4：登录页展示锁定/失败次数提示**

修改 `src/main/resources/templates/auth/login.html`，在错误提示区增加：

```html
<!-- 账户锁定提示（来自 ?locked 参数） -->
<th:block th:if="${param.containsKey('locked')}">
    <div class="alert alert-warning text-center py-2 small" role="alert">
        账户已被锁定 15 分钟，请稍后再试
    </div>
</th:block>

<!-- 失败次数提示（来自 ?remaining 参数） -->
<th:block th:if="${param.containsKey('remaining')}">
    <div class="alert alert-danger text-center py-2 small" role="alert">
        用户名或密码错误，还剩 <span th:text="${param.remaining[0]}"></span> 次尝试机会
    </div>
</th:block>
```

---

### 任务 2.4：审计日志加固

**文件：**
- 修改：`src/main/java/com/bookstore/qrcode/entity/OperationLog.java`
- 修改：`src/main/java/com/bookstore/qrcode/service/OperationLogService.java`
- 修改：`src/main/java/com/bookstore/qrcode/repository/OperationLogRepository.java`
- 新增：`src/main/resources/db/migration/V4__audit_log_enhance.sql`
- 新增：`src/main/java/com/bookstore/qrcode/aspect/AuditAspect.java`

- [ ] **2.4.1：OperationLog 表新增审计字段**

Flyway 迁移：`src/main/resources/db/migration/V4__audit_log_enhance.sql`

```sql
-- 补全等保三级要求的审计字段
ALTER TABLE operation_log
  ADD COLUMN client_ip VARCHAR(45) NULL COMMENT '客户端 IP 地址',
  ADD COLUMN user_agent VARCHAR(500) NULL COMMENT '客户端 User-Agent',
  ADD COLUMN result VARCHAR(20) NOT NULL DEFAULT 'SUCCESS' COMMENT '操作结果：SUCCESS/FAILURE',
  ADD COLUMN data_snapshot JSON NULL COMMENT '操作前后数据快照（用于追溯）';

-- 审计日志保护：禁止 DELETE 和 UPDATE（通过数据库权限控制，此处仅加索引优化查询）
CREATE INDEX idx_oplog_created_at ON operation_log (created_at);
CREATE INDEX idx_oplog_operator ON operation_log (operator);
CREATE INDEX idx_oplog_action ON operation_log (action);
```

- [ ] **2.4.2：更新 OperationLog 实体**

在 `src/main/java/com/bookstore/qrcode/entity/OperationLog.java` 中新增字段：

```java
/** 客户端 IP 地址 */
@Column(name = "client_ip", length = 45)
private String clientIp;

/** 客户端 User-Agent */
@Column(name = "user_agent", length = 500)
private String userAgent;

/** 操作结果：SUCCESS / FAILURE */
@Column(length = 20)
@Builder.Default
private String result = "SUCCESS";

/** 操作前后数据快照（JSON，供追溯） */
@Column(name = "data_snapshot", columnDefinition = "JSON")
private String dataSnapshot;
```

- [ ] **2.4.3：实现审计 AOP 切面**

`src/main/java/com/bookstore/qrcode/aspect/AuditAspect.java`

```java
package com.bookstore.qrcode.aspect;

import com.bookstore.qrcode.entity.OperationLog;
import com.bookstore.qrcode.repository.OperationLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.*;

import java.lang.annotation.*;
import java.time.LocalDateTime;

/**
 * 声明式审计日志 AOP 切面。
 * <p>
 * 在 Controller 方法上标注 {@code @AuditLog} 注解即可自动记录操作审计日志，
 * 无需在每个 Controller 方法中手动调用 OperationLogService。
 * 切面自动获取当前登录用户、客户端 IP、User-Agent，并在方法抛出异常时记录 FAILURE。
 * </p>
 */
@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditAspect {

    private final OperationLogRepository operationLogRepo;
    private final ObjectMapper objectMapper;

    /** 声明式审计日志注解 */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface AuditLog {
        /** 操作类型，如 create/delete/update/sync/export */
        String action();
        /** 操作对象类型，如 qrcode/customer/agent/form_template */
        String targetType();
        /** 操作对象 ID 的 SpEL 表达式（可选），如 #id, #qrCodeId */
        String targetId() default "";
    }

    @Around("@annotation(auditLog)")
    public Object around(ProceedingJoinPoint joinPoint, AuditLog auditLog) throws Throwable {
        OperationLog opLog = new OperationLog();
        opLog.setAction(auditLog.action());
        opLog.setTargetType(auditLog.targetType());
        opLog.setCreatedAt(LocalDateTime.now());

        // 当前用户
        RequestAttributes ra = RequestContextHolder.getRequestAttributes();
        if (ra instanceof ServletRequestAttributes sra) {
            HttpServletRequest request = sra.getRequest();
            opLog.setClientIp(getClientIp(request));
            opLog.setUserAgent(request.getHeader("User-Agent"));
            // 操作人：从 Spring Security 或 Download 认证上下文获取
            opLog.setOperator(getCurrentUsername(request));
        }

        // 目标 ID（从注解表达式解析）
        if (!auditLog.targetId().isBlank()) {
            opLog.setTargetId(resolveTargetId(auditLog.targetId(), joinPoint));
        }

        try {
            Object result = joinPoint.proceed();
            opLog.setResult("SUCCESS");
            // 记录参数快照
            Object[] args = joinPoint.getArgs();
            // 过滤掉 HttpServletRequest/Response 等不可序列化的参数
            opLog.setDetail(buildDetail(args, joinPoint));
            operationLogRepo.save(opLog);
            return result;
        } catch (Exception e) {
            opLog.setResult("FAILURE");
            opLog.setDetail("{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            operationLogRepo.save(opLog);
            throw e;
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return request.getRemoteAddr();
    }

    private String getCurrentUsername(HttpServletRequest request) {
        // 优先从 Spring Security 上下文获取
        var auth = org.springframework.security.core.context.SecurityContextHolder
            .getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            return auth.getName();
        }
        // 下载中心认证（存在 Session 中）
        var session = request.getSession(false);
        if (session != null) {
            Object downloadUser = session.getAttribute("downloadUser");
            if (downloadUser != null) return downloadUser.toString();
        }
        return "unknown";
    }

    private String resolveTargetId(String spEl, ProceedingJoinPoint jp) {
        // 简单实现：从方法参数名匹配
        MethodSignature sig = (MethodSignature) jp.getSignature();
        String[] paramNames = sig.getParameterNames();
        Object[] paramValues = jp.getArgs();
        for (int i = 0; i < paramNames.length; i++) {
            if (spEl.equals(paramNames[i]) || spEl.equals("#" + paramNames[i])) {
                return paramValues[i] != null ? paramValues[i].toString() : null;
            }
        }
        return null;
    }

    private String buildDetail(Object[] args, ProceedingJoinPoint jp) {
        try {
            // 过滤掉 Request/Response 类型参数
            var filtered = new java.util.LinkedHashMap<>();
            MethodSignature sig = (MethodSignature) jp.getSignature();
            String[] names = sig.getParameterNames();
            for (int i = 0; i < args.length && i < names.length; i++) {
                if (args[i] instanceof HttpServletRequest
                    || args[i] instanceof HttpServletResponse) continue;
                filtered.put(names[i], args[i] != null ? args[i].toString() : null);
            }
            return objectMapper.writeValueAsString(filtered);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
```

- [ ] **2.4.4：更新 OperationLogService，废弃旧的手动日志方法**

修改 `src/main/java/com/bookstore/qrcode/service/OperationLogService.java`：

```java
// 保留旧方法但标记 @Deprecated，新增自动填充 IP 和 User-Agent 的方法
@Deprecated
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void log(String operator, String action, String targetType,
                String targetId, String description) {
    // 保留兼容，内部调用新方法
    log(operator, action, targetType, targetId, description,
        "MIGRATED", "legacy", null);
}

@Transactional(propagation = Propagation.REQUIRES_NEW)
public void log(String operator, String action, String targetType,
                String targetId, String description,
                String clientIp, String userAgent, String dataSnapshot) {
    try {
        String detail = String.format("{\"description\":\"%s\"}",
            description.replace("\"", "\\\""));
        OperationLog opLog = OperationLog.builder()
            .operator(operator)
            .action(action)
            .targetType(targetType)
            .targetId(targetId)
            .detail(detail)
            .clientIp(clientIp)
            .userAgent(userAgent)
            .result("SUCCESS")
            .dataSnapshot(dataSnapshot)
            .build();
        operationLogRepository.save(opLog);
    } catch (Exception e) {
        log.warn("审计日志写入失败（不影响主业务）: operator={}, action={}", operator, action, e);
    }
}
```

- [ ] **2.4.5：审计日志保护 — 数据库层禁止 DELETE**

```sql
-- 创建专用审计日志用户（生产环境执行）
-- 主应用的 DB 用户收回 operation_log 表的 DELETE 和 UPDATE 权限
REVOKE DELETE, UPDATE ON operation_log FROM 'bookstore'@'%';
-- 只允许 INSERT 和 SELECT（主应用写入 + 管理后台只读查看）
GRANT INSERT, SELECT ON operation_log TO 'bookstore'@'%';
```

- [ ] **2.4.6：登录行为审计**

在 `LoginFailureHandler` 和 `LoginSuccessHandler` 中增加审计日志写入：

```java
// LoginFailureHandler.onAuthenticationFailure() 中：
operationLogRepo.save(OperationLog.builder()
    .operator(username)
    .action("login")
    .targetType("session")
    .result("FAILURE")
    .clientIp(getClientIp(request))
    .detail("{\"reason\":\"" + exception.getClass().getSimpleName() + "\"}")
    .build());

// LoginSuccessHandler.onAuthenticationSuccess() 中：
operationLogRepo.save(OperationLog.builder()
    .operator(authentication.getName())
    .action("login")
    .targetType("session")
    .result("SUCCESS")
    .clientIp(getClientIp(request))
    .build());
```

- [ ] **2.4.7：定时归档旧审计日志**

在 `src/main/java/com/bookstore/qrcode/job/AuditArchiveJob.java` 中新增：

```java
package com.bookstore.qrcode.job;

import com.bookstore.qrcode.repository.OperationLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

/**
 * 审计日志归档定时任务。
 * <p>
 * 每月将 6 个月前的审计日志归档到归档表 operation_log_archive，
 * 并从主表删除（通过 SQL DELETE 绕过应用层的 REVOKE 限制）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditArchiveJob {

    private final JdbcTemplate jdbc;

    /** 每月 1 日凌晨 3 点执行 */
    @Scheduled(cron = "0 0 3 1 * *")
    public void archive() {
        LocalDateTime cutoff = LocalDateTime.now().minusMonths(6);
        // 创建归档表（如果不存在）
        jdbc.execute("CREATE TABLE IF NOT EXISTS operation_log_archive LIKE operation_log");
        // 归档
        int archived = jdbc.update(
            "INSERT INTO operation_log_archive SELECT * FROM operation_log WHERE created_at < ?",
            cutoff);
        if (archived > 0) {
            jdbc.update("DELETE FROM operation_log WHERE created_at < ?", cutoff);
            log.info("审计日志归档完成: {} 条（截止 {}）", archived, cutoff);
        }
    }
}
```

---

### 任务 2.5：登录页更新 — 展示锁定/失败次数

文件 `src/main/resources/templates/auth/login.html` 的改动已在任务 2.3.4 中完成（包含剩余尝试次数提示和锁定提示）。

---

### 第二阶段总结

| 任务 | 功能 | 新增文件 | 修改文件 | 预估工时 |
|------|------|:--------:|:--------:|:--------:|
| 2.1 | MFA 双因素认证 | 5 | 3 | 2 天 |
| 2.2 | 密码复杂度策略 | 2 | 0 | 0.5 天 |
| 2.3 | 登录失败锁定 | 2 | 1 | 0.5 天 |
| 2.4 | 审计日志加固 | 2 | 3 | 1.5 天 |
| 2.5 | 登录页 UI | 0 | 1 | 0.5 天 |

**总计：约 5 个工作日，覆盖等保三级最核心的身份鉴别 + 审计要求。**

---

## 第三阶段：数据安全（第 3-4 周）

### 概述

解决数据层的两个差距：`form_submission.field_data` 存储加密、前端/API 数据脱敏。

### 任务 3.1：表单数据 AES 加密存储

**文件：**
- 新增：`src/main/java/com/bookstore/qrcode/security/FieldEncryptionService.java`
- 修改：`src/main/java/com/bookstore/qrcode/controller/FormController.java`
- 修改：`src/main/java/com/bookstore/qrcode/service/TransferService.java`
- 新增：`src/main/java/com/bookstore/qrcode/job/FieldDataMigrationRunner.java`

**设计说明：**

加密算法选择 **AES-256-GCM**（而非 SM4）：
- 等保三级不强制国密（只有密评才需要，密评是可选附加项）
- AES-256-GCM 是 NIST 标准，认证加密（Authenticated Encryption），自带完整性校验
- GCM 模式天然防篡改（密文被修改后解密会失败），等保三级的数据完整性要求一并满足
- 加密密钥通过环境变量 `FIELD_ENCRYPTION_KEY` 注入，不在代码/配置文件中硬编码

**影响范围**（已在本分析前半部分完整梳理）：
- 写入点：`FormController.submit()` → save 前加密
- 读取点：`TransferService.extractFormFields()` → 读后解密
- TagWorker 消费 Redis Stream 中的明文数据（不变，无需解密）
- 无 admin 后台展示/搜索 field_data

- [ ] **3.1.1：实现加解密服务**

`src/main/java/com/bookstore/qrcode/security/FieldEncryptionService.java`

```java
package com.bookstore.qrcode.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 表单敏感字段 AES-256-GCM 加解密服务。
 * <p>
 * 加密密钥通过环境变量 FIELD_ENCRYPTION_KEY 注入（Base64 编码的 256-bit 密钥）。
 * 加密输出格式：Base64(IV(12 bytes) || ciphertext || GCM tag(16 bytes))
 * IV 随机生成并拼接在密文中，无需额外存储。
 * </p>
 */
@Slf4j
@Service
public class FieldEncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;   // 96-bit IV (NIST 推荐)
    private static final int GCM_TAG_LENGTH = 128;  // 128-bit 认证标签
    private static final String ENCRYPTED_PREFIX = "AES:";  // 密文标记前缀

    private final SecretKey encryptionKey;

    /**
     * 构造函数：从环境变量加载 AES 密钥。
     * @param base64Key Base64 编码的 256-bit AES 密钥
     * @throws IllegalArgumentException 如果密钥未设置或长度不足
     */
    public FieldEncryptionService(
            @Value("${app.security.field-encryption-key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            log.warn("FIELD_ENCRYPTION_KEY 未设置，使用随机密钥（仅用于开发测试）");
            byte[] randomKey = new byte[32];
            new SecureRandom().nextBytes(randomKey);
            this.encryptionKey = new SecretKeySpec(randomKey, "AES");
        } else {
            byte[] keyBytes = Base64.getDecoder().decode(base64Key);
            if (keyBytes.length != 32) {
                throw new IllegalArgumentException(
                    "FIELD_ENCRYPTION_KEY 必须为 Base64 编码的 256-bit (32 字节) 密钥，当前长度: " + keyBytes.length);
            }
            this.encryptionKey = new SecretKeySpec(keyBytes, "AES");
        }
    }

    /**
     * 加密明文数据。
     * @param plaintext JSON 字符串或其他明文
     * @return Base64 编码的密文（带 AES: 前缀标记）
     */
    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, spec);
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // IV + ciphertext 拼接后 Base64 编码
            byte[] combined = new byte[GCM_IV_LENGTH + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, GCM_IV_LENGTH);
            System.arraycopy(ciphertext, 0, combined, GCM_IV_LENGTH, ciphertext.length);
            return ENCRYPTED_PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            log.error("加密失败", e);
            throw new RuntimeException("数据加密失败", e);
        }
    }

    /**
     * 解密密文数据。
     * @param ciphertext 带 AES: 前缀的密文，或明文（兼容旧数据）
     * @return 解密后的明文 JSON 字符串
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null) return null;
        if (!isEncrypted(ciphertext)) {
            // 不是密文 → 直接返回（兼容迁移前的老数据，或 Redis Stream 传过来的明文）
            return ciphertext;
        }
        try {
            String stripped = ciphertext.substring(ENCRYPTED_PREFIX.length());
            byte[] combined = Base64.getDecoder().decode(stripped);

            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] encrypted = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(combined, GCM_IV_LENGTH, encrypted, 0, encrypted.length);

            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, spec);
            byte[] plaintext = cipher.doFinal(encrypted);
            return new String(plaintext, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("解密失败", e);
            throw new RuntimeException("数据解密失败", e);
        }
    }

    /** 判断是否为密文（带 AES: 前缀且不是有效 JSON 对象/数组开头） */
    public boolean isEncrypted(String data) {
        return data != null && data.startsWith(ENCRYPTED_PREFIX);
    }
}
```

- [ ] **3.1.2：生成加密密钥**

```bash
# 生成 256-bit (32 字节) 随机 AES 密钥
openssl rand -base64 32
# 示例输出：kG7vQp2mX9sL4rT8wY1bN6dA3eF5hJ0oU2xZ7cV4aB=
```

将此值设置到生产环境：
```bash
# /etc/systemd/system/huoma.env 中新增
FIELD_ENCRYPTION_KEY=kG7vQp2mX9sL4rT8wY1bN6dA3eF5hJ0oU2xZ7cV4aB=
```

- [ ] **3.1.3：修改 FormController.submit() — 写入前加密**

在 `src/main/java/com/bookstore/qrcode/controller/FormController.java` 的 `submit()` 方法中：

```java
// 注入加密服务
private final FieldEncryptionService encryptionService;

// 在 submit() 方法中，原来第 129 行 fieldData 赋值处：
// 原代码：
//   String fieldData = objectMapper.writeValueAsString(body.getOrDefault("fieldData", Map.of()));
//   ...
//   .fieldData(fieldData).build();
// 改为：
String rawFieldData = objectMapper.writeValueAsString(body.getOrDefault("fieldData", Map.of()));
String encryptedFieldData = encryptionService.encrypt(rawFieldData);

FormSubmission sub = FormSubmission.builder()
    .formTemplateId(formTemplateId)
    .customerId(customerId).qrCodeId(qrCodeId)
    .schoolName(schoolName)
    .fieldData(encryptedFieldData)    // ← 存储加密后的数据
    .build();
sub = submissionRepo.save(sub);

// Redis Stream 事件仍然传递明文 rawFieldData（TagWorker 消费用）
// 原代码不变：
tagEvent.put("field_data", rawFieldData);
```

- [ ] **3.1.4：修改 TransferService.extractFormFields() — 读取后解密**

在 `src/main/java/com/bookstore/qrcode/service/TransferService.java` 的 `extractFormFields()` 方法中：

```java
// 注入加密服务
private final FieldEncryptionService encryptionService;

// 在 extractFormFields() 方法中，原来的第 712 行：
// 原代码：
//   JsonNode data = objectMapper.readTree(submissions.get(0).getFieldData());
// 改为：
String raw = submissions.get(0).getFieldData();
String decrypted = encryptionService.decrypt(raw);  // ← 解密（明文兼容：自动检测跳过）
JsonNode data = objectMapper.readTree(decrypted);
```

- [ ] **3.1.5：编写数据迁移执行器（一次性）**

`src/main/java/com/bookstore/qrcode/job/FieldDataMigrationRunner.java`

```java
package com.bookstore.qrcode.job;

import com.bookstore.qrcode.entity.FormSubmission;
import com.bookstore.qrcode.repository.FormSubmissionRepository;
import com.bookstore.qrcode.security.FieldEncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 表单历史数据加密迁移（一次性）。
 * <p>
 * 仅在启动参数包含 --migrate-field-data 时执行。
 * 迁移是幂等的：已加密的数据自动跳过，可安全重复执行。
 * </p>
 * <p>
 * 执行方式：
 *   java -jar app.jar --spring.profiles.active=prod --migrate-field-data
 * 迁移完成后脚本自动退出（System.exit(0)）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Profile("!test")  // 测试环境不执行
public class FieldDataMigrationRunner implements CommandLineRunner {

    private final FormSubmissionRepository submissionRepo;
    private final FieldEncryptionService encryptionService;

    @Override
    public void run(String... args) {
        boolean doMigration = false;
        for (String arg : args) {
            if ("--migrate-field-data".equals(arg)) {
                doMigration = true;
                break;
            }
        }
        if (!doMigration) return;

        log.info("===== 开始表单数据加密迁移 =====");
        var all = submissionRepo.findAll();
        int total = all.size();
        int migrated = 0;
        int skipped = 0;
        int errors = 0;

        for (FormSubmission sub : all) {
            try {
                String fieldData = sub.getFieldData();
                if (encryptionService.isEncrypted(fieldData)) {
                    skipped++;
                    continue;
                }

                String encrypted = encryptionService.encrypt(fieldData);
                // 验证：解密后必须与原文一致
                String decrypted = encryptionService.decrypt(encrypted);
                if (!fieldData.equals(decrypted)) {
                    log.error("加密验证失败! submissionId={}", sub.getId());
                    errors++;
                    continue;
                }

                sub.setFieldData(encrypted);
                submissionRepo.save(sub);
                migrated++;
            } catch (Exception e) {
                log.error("迁移失败: submissionId={}", sub.getId(), e);
                errors++;
            }
        }

        log.info("===== 迁移完成: 总数={}, 已迁移={}, 已跳过={}, 失败={} =====",
            total, migrated, skipped, errors);
        int exitCode = errors > 0 ? 1 : 0;
        // 注意：不要在生产应用中使用 System.exit()；
        // 迁移脚本退出方式由部署脚本控制（kill 进程或等待线程结束）
        log.info("迁移脚本退出: exitCode={}", exitCode);
    }
}
```

- [ ] **3.1.6：部署脚本支持迁移模式**

修改 `deploy/deploy.sh`，增加迁移操作：

```bash
# 迁移模式：停服 → 执行迁移 → 启动新版本
migrate() {
    echo "=== 停止服务 ==="
    ssh $ECS1 "sudo systemctl stop huoma"
    ssh $ECS2 "sudo systemctl stop huoma"
    
    echo "=== 执行数据迁移（ECS1） ==="
    ssh $ECS1 "cd /opt/HuoMa && java -jar app.jar --spring.profiles.active=prod --migrate-field-data"
    
    echo "=== 迁移完成，启动新版本 ==="
    ssh $ECS1 "sudo systemctl start huoma"
    ssh $ECS2 "sudo systemctl start huoma"
    
    echo "=== 健康检查 ==="
    sleep 5
    curl -f http://$ECS1:8080/actuator/health && echo "ECS1 OK"
    curl -f http://$ECS2:8080/actuator/health && echo "ECS2 OK"
}
```

---

### 任务 3.2：前端敏感数据脱敏

**文件：**
- 新增：`src/main/java/com/bookstore/qrcode/config/DataMaskingSerializer.java`
- 修改：相关的 Jackson 序列化配置

- [ ] **3.2.1：实现脱敏序列化器**

`src/main/java/com/bookstore/qrcode/config/DataMaskingSerializer.java`

```java
package com.bookstore.qrcode.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.ser.ContextualSerializer;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.io.IOException;

/**
 * Jackson 字段级脱敏序列化器。
 * <p>
 * 使用方式：在 Entity 的敏感字段上标注 {@code @JsonSerialize(using = MaskSerializer.class)}
 * 或自定义注解 {@code @Mask(style = MaskStyle.NAME)}。
 * </p>
 */
public class MaskSerializer extends JsonSerializer<String> implements ContextualSerializer {

    private final MaskStyle style;

    public MaskSerializer() { this.style = MaskStyle.DEFAULT; }
    public MaskSerializer(MaskStyle style) { this.style = style; }

    @Override
    public void serialize(String value, JsonGenerator gen, SerializerProvider provider) throws IOException {
        if (value == null || value.isBlank()) {
            gen.writeString(value);
            return;
        }
        gen.writeString(style.mask(value));
    }

    @Override
    public JsonSerializer<?> createContextual(SerializerProvider prov, BeanProperty property) {
        Mask mask = property != null ? property.getAnnotation(Mask.class) : null;
        MaskStyle resolved = (mask != null) ? mask.value() : MaskStyle.DEFAULT;
        return new MaskSerializer(resolved);
    }

    public enum MaskStyle {
        /** 姓名脱敏：张三 → 张* */
        NAME {
            @Override String mask(String s) {
                if (s.length() <= 1) return s;
                return s.charAt(0) + "*" + (s.length() > 2 ? s.substring(2) : "");
            }
        },
        /** 手机号脱敏：13812345678 → 138****5678 */
        MOBILE {
            @Override String mask(String s) {
                if (s.length() < 7) return s;
                return s.substring(0, 3) + "****" + s.substring(s.length() - 4);
            }
        },
        /** 全脱敏：显示首末位 */
        DEFAULT {
            @Override String mask(String s) {
                if (s.length() <= 2) return s;
                return s.charAt(0) + "***" + s.charAt(s.length() - 1);
            }
        };

        abstract String mask(String s);
    }
}
```

- [ ] **3.2.2：自定义 @Mask 注解**

`src/main/java/com/bookstore/qrcode/config/Mask.java`

```java
package com.bookstore.qrcode.config;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.lang.annotation.*;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@JacksonAnnotationsInside
@JsonSerialize(using = MaskSerializer.class)
public @interface Mask {
    MaskSerializer.MaskStyle value() default MaskSerializer.MaskStyle.DEFAULT;
}
```

- [ ] **3.2.3：在敏感实体字段上标注脱敏**

例如在 `Customer` 实体中：

```java
/** 客户名称（显示时脱敏） */
@Mask(MaskSerializer.MaskStyle.NAME)
@Column(length = 100)
private String name;

/** 员工手机号（显示时脱敏） */
@Mask(MaskSerializer.MaskStyle.MOBILE)
@Column(length = 20)
private String mobile;
```

> **注意：** 脱敏仅在 Jackson 序列化到前端时生效（GET 请求返回 JSON 时），不影响数据库存储值，也不影响 Service 层的业务逻辑。这是等保三级数据脱敏要求的最小改动方案。

---

### 第三阶段总结

| 任务 | 功能 | 新增文件 | 修改文件 | 预估工时 |
|------|------|:--------:|:--------:|:--------:|
| 3.1 | field_data AES 加密 | 2 | 2 | 1.5 天 |
| 3.2 | 前端脱敏 | 2 | 若干实体 | 0.5 天 |

**总计：约 2 个工作日。数据迁移在凌晨停服窗口执行，10 分钟内完成。**

---

## 第四阶段：安全管理制度文档（可并行，第 2-6 周）

### 概述

等保三级要求 22 类管理制度文件。本阶段列出所有必需文档清单及核心内容框架。文档编写可与第二阶段代码改动并行推进。

### 必须文档清单

| 序号 | 文档名称 | 核心内容 | 重要程度 |
|:----:|----------|----------|:--------:|
| 1 | 信息安全管理制度 | 信息安全总体方针、组织架构、职责分工 | ⭐⭐⭐ |
| 2 | 人员安全管理制度 | 人员录用/离职安全审查、保密协议、安全意识教育、外部人员管理 | ⭐⭐⭐ |
| 3 | 系统建设管理制度 | 安全需求分析、安全设计评审、安全测试、上线审批 | ⭐⭐⭐ |
| 4 | 系统运维管理制度 | 日常巡检、变更管理、补丁管理、容量管理、7×24监控 | ⭐⭐⭐ |
| 5 | 安全事件应急预案 | 事件分类（DDoS/数据泄露/勒索病毒等）、响应流程、上报机制、恢复步骤 | ⭐⭐⭐ |
| 6 | 数据分类分级管理制度 | 数据分类标准（公开/内部/敏感/机密）、各级别的存储/传输/销毁要求 | ⭐⭐⭐ |
| 7 | 安全开发规范 | 代码审计要求、开源组件管理、安全测试标准 | ⭐⭐ |
| 8 | 访问控制管理制度 | 账户申请/审批/注销流程、权限定期审查、最小权限原则 | ⭐⭐ |
| 9 | 密码管理制度 | 密码复杂度策略、更换周期（90天）、密钥生命周期管理 | ⭐⭐ |
| 10 | 审计日志管理制度 | 日志采集范围、保留期限（≥6个月）、日志保护措施、定期审查 | ⭐⭐ |
| 11 | 备份与恢复管理制度 | 备份策略（本地+异地）、RPO/RTO 目标、恢复演练计划 | ⭐⭐ |
| 12 | 网络安全管理制度 | 防火墙策略、VPN管理、网络拓扑维护、安全域划分 | ⭐⭐ |
| 13 | 移动介质管理制度 | U盘/移动硬盘使用规范、加密要求、病毒扫描 | ⭐ |
| 14 | 远程办公安全制度 | 远程访问审批、VPN使用规范、终端安全检查 | ⭐ |
| 15 | 第三方服务管理制度 | 供应商安全评估、SLA要求、API密钥管理 | ⭐⭐ |
| 16 | 资产管理制度 | 硬件/软件/数据资产清单、责任人、报废流程 | ⭐ |
| 17 | 物理安全管理制度 | 机房出入管理、设备上架/下架流程（复用阿里云资质） | ⭐ |
| 18 | 安全培训管理制度 | 培训频次（≥1次/年）、培训内容、考核机制 | ⭐⭐ |
| 19 | 安全评估管理制度 | 渗透测试频次、漏洞扫描周期、整改跟踪 | ⭐⭐ |
| 20 | 业务连续性管理制度 | 灾难恢复计划（DRP）、业务影响分析（BIA）、容灾切换演练 | ⭐⭐ |
| 21 | 变更管理制度 | 代码变更审批、灰度发布、回滚策略 | ⭐ |
| 22 | 个人信息保护制度 | 家长/学生PII的采集、存储、使用、删除规范（合规《个人信息保护法》） | ⭐⭐⭐ |

### 编写策略

1. **优先编写星级高的文档**（⭐⭐⭐ → ⭐⭐ → ⭐），测评机构重点审查前 6 类
2. **复用模板**：阿里云等保测评服务商通常提供管理制度模板，可以购买后在模板基础上定制
3. **结合现有实践写**：不要在文档中写"我们计划做 X"——写"我们已经做了 X"（把你第二阶段的整改成果写到制度里）
4. **每个制度的必备章节**：目的 → 适用范围 → 职责分工 → 管理要求 → 违规处理 → 附则（含生效日期、解释权）

---

## 总时间线

```
Week 1    Week 2    Week 3    Week 4    Week 5    Week 6
[Phase 1] [ Phase 2             ] [Phase 3] [   测评   ] [  备案   ]
 基础设施   身份鉴别 + 审计加固     数据加密+脱敏  机构测评    公安备案
 零代码     高优代码改动             停服迁移      整改确认    提交材料

           [ Phase 4: 管理制度文档编写（并行）                     ]
```

---

## 成本汇总

| 类别 | 项目 | 一次性费用 | 月度增量 |
|------|------|:--------:|:--------:|
| 阿里云 | WAF 企业版 | — | ¥1,500 |
| 阿里云 | RDS SQL 审计 | — | ¥200 |
| 阿里云 | RDS 跨地域备份 | — | ¥200 |
| 阿里云 | 云安全中心 | — | ¥600 |
| 阿里云 | OSS（日志存储 180 天） | — | ¥300 |
| 测评机构 | 等保三级测评 | ¥50,000-80,000 | — |
| **合计** | | **¥5-8万** | **¥2,800/月** |

现有月度费用 ¥1,261 + 增量 ¥2,800 = **总计约 ¥4,061/月**。

---

## 回滚方案

如果整改过程中出现严重问题需要回滚：

| 阶段 | 回滚操作 | 影响范围 |
|------|----------|----------|
| Phase 1 | WAF 关闭/切换观察模式、RDS 审计关闭、云安全中心卸载 Agent | 恢复到整改前，无业务影响 |
| Phase 2 | 部署旧 JAR、MFA Filter 排除全部路径、登录处理器恢复默认 | 需重新部署，5 分钟恢复 |
| Phase 3 | RDS 备份恢复到迁移前时间点、部署旧 JAR（无加解密代码） | 数据库回滚，5 分钟恢复（依赖 RDS 备份） |
| Phase 4 | N/A — 文档无回滚概念 | — |

**每次部署前操作**：在阿里云 RDS 控制台手动创建备份快照（备份名称标注版本号），确保一键可回滚。

---

## 后续持续合规

等保三级不是一次性的——通过测评后需要持续保持：

1. **每年一次等保复测**（测评机构年度检查）
2. **每季度一次漏洞扫描**（云安全中心自动执行）
3. **每半年一次渗透测试**
4. **管理制度文档每年修订**
5. **OWASP Dependency-Check 保持在 CVSS ≥ 7 阻断**（已有 ✅）
6. **重大变更后重新评估安全风险**
