# 火马（HuoMa）阿里云双机热备部署方案

> 编制日期：2026-06-21  
> 状态：已确认 · 待执行

---

## 一、架构总览

```
                         互联网
                           │
              ┌────────────┴────────────┐
              │                         │
        ┌─────▼──────┐          ┌──────▼──────┐
        │  NAT 网关   │          │   SLB (后补) │  ← 阶段一：EIP 绑 ECS-1
        │  + EIP      │          │   负载均衡    │     阶段二：EIP 改绑 SLB
        └─────┬──────┘          └──────┬──────┘
              │                        │
    ┌─────────┴────────────────────────┴─────────┐
    │                  VPC 内网                    │
    │                                             │
    │  ┌─────────────┐       ┌─────────────┐      │
    │  │  ECS-1 (主)  │       │  ECS-2 (备)  │      │
    │  │  App+Nginx  │       │  App+Nginx  │      │
    │  │  :443 :80   │       │  :443 :80   │      │
    │  └──┬──────┬───┘       └──┬──────┬───┘      │
    │     │      │              │      │          │
    │  ┌──▼──┐ ┌─▼──┐      ┌──▼──┐ ┌─▼──┐       │
    │  │ RDS │ │Redis│     │ RDS │ │Redis│       │
    │  │主+读│ │ 2G │      │ 主+读│ │ 2G │       │
    │  └─────┘ └────┘      └─────┘ └────┘       │
    │     ↑ 同一套，共享    ↑                     │
    └─────────────────────────────────────────────┘

    OSS: JAR 包 + 配置 + 数据库备份
```

## 二、产品清单

| # | 产品 | 规格 | 月费 |
|---|------|------|:---:|
| 1 | ECS-1 | 4C8G · AMD c7a.xlarge · Ubuntu 22.04 · 40G | ¥240 |
| 2 | ECS-2 | 4C8G · AMD c7a.xlarge · Ubuntu 22.04 · 40G | ¥240 |
| 3 | EIP | 按流量 · 50 Mbps · BGP | ¥15 |
| 4 | NAT 网关 | 小型 | ¥60 |
| 5 | RDS MySQL 主 | 2C4G · 高可用版 · 100G SSD · 8.0 | ¥460 |
| 6 | RDS MySQL 只读 | 1C2G | ¥210 |
| 7 | Redis | 开源版 · 2G 主从 · 5.0 | ¥198 |
| 8 | SLB (后补) | 私网型 + 公网 IP | ¥70-150 |
| 9 | OSS | 20 GiB · 生命周期 30 天 | ¥3 |
| 10 | SSL 证书 | 免费 DV | ¥0 |
| 11 | 域名 (后补) | 已备案 | — |
| **合计（阶段一）** | | | **¥1,426/月** |

## 三、分阶段部署

### 阶段一：单机跑通（当前）

- EIP 绑定 ECS-1
- ECS-1 对外提供服务（Nginx :443 → App :8080）
- ECS-2 部署相同应用，通过 NAT 网关出公网
- 两台 ECS 共享同一套 RDS 和 Redis
- 不依赖域名——企微回调先用 EIP 直接验证（后续切域名）

### 阶段二：SLB + 域名到位后

- 购买 SLB 私网型，绑定公网 IP
- 域名 DNS 解析到 SLB IP
- EIP 从 ECS-1 解绑，改绑到 SLB（或 SLB 自带公网 IP）
- SSL 证书配置在 SLB 层（或在 ECS Nginx 层）
- 两台 ECS 加入 SLB 后端池
- 企微回调 URL 改为域名

## 四、ECS 配置

### 基础环境

```bash
# Ubuntu 22.04
apt update && apt upgrade -y
apt install -y openjdk-17-jdk nginx certbot python3-certbot-nginx
```

### 应用目录结构

```
/opt/HuoMa/
├── app.jar              # Spring Boot JAR
├── deploy.sh            # 部署脚本
└── backup.sh            # 备份脚本

/etc/nginx/
├── ssl/                 # SSL 证书（阶段二）
└── conf.d/
    └── huoma.conf       # Nginx 反代配置

/etc/systemd/system/
├── huoma.env            # 环境变量
└── huoma.service        # systemd 服务

/var/log/huoma/          # 日志目录
```

### JVM 参数

```
-Xms512m -Xmx2g -XX:+UseG1GC -XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/var/log/huoma/
-Duser.timezone=Asia/Shanghai
-Dspring.profiles.active=prod
```

### 内存分配 (8 GiB 总)

| 组件 | 内存 | 
|------|:---:|
| JVM 堆 | 2 GiB |
| JVM 堆外 + OS | 2 GiB |
| Nginx | 50 MiB |
| 预留 | 4 GiB |

## 五、RDS MySQL

- 库名：`bookstore_qrcode`，字符集 `utf8mb4`
- 账号：`bookstore`
- 白名单：ECS-1 + ECS-2 内网 IP
- 备份：自动全量 + binlog，保留 7 天

## 六、Redis

- 版本 5.0，主从双副本
- 密码认证
- 白名单：ECS-1 + ECS-2 内网 IP

## 七、NAT 网关

- 用途：为 ECS-2 提供固定出口 IP，企微 API 白名单
- 绑定 EIP 作为出口 IP
- ECS-1 也可通过 NAT 网关出公网（统一出口）

## 八、安全组规则

| 端口 | 来源 | 用途 |
|------|------|------|
| 443 | 0.0.0.0/0 | HTTPS |
| 80 | 0.0.0.0/0 | HTTP → HTTPS |
| 22 | 管理员 IP | SSH |
| 8080 | 127.0.0.1 | Spring Boot |
| 3306 | ECS-1, ECS-2 内网 IP | RDS |
| 6379 | ECS-1, ECS-2 内网 IP | Redis |

## 九、宕机恢复

- ECS-1 宕机 → SLB 自动切到 ECS-2（阶段二）
- 阶段一：ECS-1 宕机 → 手动将 EIP 解绑并重新绑定 ECS-2（1-2 分钟）
- 数据层：RDS 自带高可用主备切换，Redis 自带主从切换
