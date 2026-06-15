# 火马（HuoMa）阿里云部署方案

> 适用场景：年均增长至 300 万用户 · 书店家校企微活码管理  
> 编制日期：2026-06-15

---

## 一、架构总览

```
                         互联网
                           │
                     ┌─────▼─────┐
                     │   域名 DNS  │  ← 已备案域名
                     └─────┬─────┘
                           │ HTTPS (443)
                     ┌─────▼─────┐
                     │   Nginx   │  ← SSL 终止 + 反向代理
                     │  同 ECS   │     免费证书（云盾/Let's Encrypt）
                     └─────┬─────┘
                           │ localhost:8080
                     ┌─────▼──────────────┐
                     │  ECS 应用服务器      │  ← 4 vCPU · 8 GiB · 包年
                     │  Spring Boot JAR   │     CentOS 7.9 / Alibaba Linux 3
                     │  Undertow :8080    │     -Xms512m -Xmx2g G1GC
                     └──┬──────────┬──────┘
                        │          │
               ┌────────▼──┐  ┌───▼──────────┐
               │ RDS MySQL │  │ Redis Tair    │
               │ 主：2C4G  │  │ 标准版 2 GiB  │
               │ 只读：1C2G│  │ 主从双副本     │
               │ 100G SSD  │  │ 自动备份       │
               └───────────┘  └──────────────┘

           ┌─────────────────────────────────────┐
           │            OSS 对象存储              │
           │  配置备份 / JAR 包 / mysqldump      │
           └─────────────────────────────────────┘

           ┌─────────────────────────────────────┐
           │         冷备 ECS（按量，关机）        │
           │  4 vCPU · 8 GiB · 仅收系统盘费用     │
           │  主 ECS 故障 → 开机 → 拉 JAR → 恢复  │
           └─────────────────────────────────────┘
```

---

## 二、产品清单与价格

### 2.1 必选产品

| # | 产品 | 规格 | 计费方式 | 月费（约） |
|---|------|------|----------|:-----:|
| 1 | **ECS 应用服务器** | 4 vCPU + 8 GiB，40G 系统盘，CentOS 7.9 | 包年包月 | ¥280 |
| 2 | **RDS MySQL 主节点** | 2 vCPU + 4 GiB 通用型，100G SSD | 包年包月 | ¥460 |
| 3 | **RDS MySQL 只读** | 1 vCPU + 2 GiB，100G SSD（同主） | 包年包月 | ¥210 |
| 4 | **Redis Tair** | 2 GiB 标准版主从双副本 | 包年包月 | ¥198 |
| 5 | **弹性公网 IP** | 按流量计费，2 Mbps 保底 | 按量 | ¥80 |
| 6 | **OSS 标准存储** | 20 GiB，存储配置 + JAR + 数据库备份 | 按量 | ¥3 |
| | | | **合计** | **¥1,231** |

### 2.2 可选/备用产品

| # | 产品 | 规格 | 计费方式 | 月费（约） |
|---|------|------|----------|:-----:|
| 7 | **冷备 ECS** | 同主 ECS 规格，**保持关机** | 按量（仅磁盘） | ¥30 |
| 8 | **NAT 网关** | 小型（如需固定出口 IP 调企微 API） | 按量 | ¥60 |

> **总计：约 ¥1,231-1,321 /月**

### 2.3 省钱技巧

| 技巧 | 省多少 | 说明 |
|------|:------:|------|
| ECS + RDS + Redis 全部**包年** | 15-25% | 包年比包月便宜，比按量便宜 40%+ |
| RDS 只读用**按量**（管理后台低频访问） | ~¥100/月 | 不常访问时费用极低 |
| OSS 设**生命周期规则** | — | 30 天前的备份自动转低频存储 |
| 云盾免费 SSL 证书 | ¥0 | 一年一换，自动续签 |
| 冷备 ECS **只开一台、平时关机** | ¥250+/月 | 比双机热备省 10 倍 |

---

## 三、各组件详解

### 3.1 ECS 应用服务器（4 vCPU · 8 GiB）

| 项目 | 配置 |
|------|------|
| 机型 | ecs.c6.xlarge（计算型）或 ecs.g6.2xlarge（通用型） |
| OS | Alibaba Cloud Linux 3 / CentOS 7.9 |
| JDK | OpenJDK 17（yum 安装） |
| JVM 参数 | `-Xms512m -Xmx2g -XX:+UseG1GC -XX:+HeapDumpOnOutOfMemoryError` |
| Web 服务器 | Undertow 内嵌（worker 线程数 20） |
| 应用端口 | `127.0.0.1:8080`（仅本地监听，不对外开放） |
| Nginx | yum 安装，反代 443→8080，SSL 终止 |
| 应用目录 | `/opt/HuoMa/` |
| 日志目录 | `/var/log/huoma/`（定时清理 30 天前） |
| systemd 服务 | `huoma.service`，开机自启，崩溃自动重启 |

**内存分配**：

| 组件 | 内存 | 说明 |
|------|:----:|------|
| JVM 堆 | 2 GiB | `-Xmx2g`，实际常驻 ~1 GiB |
| JVM 堆外 + OS | 2 GiB | Metaspace、NIO Buffer、系统缓存 |
| Nginx | 50 MiB | 仅反代，内存消耗极低 |
| 预留 | 4 GiB | 余量充足 |

---

### 3.2 RDS MySQL 8.0（主 2C4G + 只读 1C2G）

| 项目 | 主节点 | 只读节点 |
|------|--------|----------|
| 规格 | 2 vCPU · 4 GiB | 1 vCPU · 2 GiB |
| 存储 | 100 GiB SSD | 同主（共享存储） |
| 用途 | 所有业务写入 | 管理后台查询（`/dashboard`、`/customers`） |
| 连接池 | HikariCP max=50 | HikariCP max=20（另配只读数据源） |
| 备份 | 自动全量 + binlog，保留 7 天 | — |
| 高可用 | 主备自动切换（RDS 自带） | — |

**扛 300 万用户**：

| 指标 | 数值 | 是否够用 |
|------|------|:---:|
| 数据总量 | ~10 GiB（含索引） | ✅ |
| InnoDB Buffer Pool | 2.5-3 GiB（RDS 自动分配约 75%） | ✅ 工作集基本全内存 |
| 日常 QPS | < 50 | ✅ |
| 高峰期 QPS | < 200 | ✅ 2C4G 轻松 2000+ QPS |
| 管理后台慢查询 | JOIN 百万级表 | → 走只读节点，不阻塞写入 |

**Spring Boot 读写分离配置（后续改造）**：

```yaml
# 添加只读数据源，管理后台查询走这里
spring:
  datasource:
    read:
      url: jdbc:mysql://rr-xxxx.mysql.rds.aliyuncs.com:3306/bookstore_qrcode
```

不改也行——200 QPS 的主库根本谈不上"压力"，只读副本是锦上添花。

---

### 3.3 Redis Tair（2 GiB 标准版）

| 项目 | 配置 |
|------|------|
| 版本 | 5.0 标准版（主从双副本） |
| 内存 | 2 GiB |
| 用途 | Stream 消息队列 / 日计数器 / 分布式锁 / 速率滑窗 |
| 持久化 | AOF everysec + RDB 每日备份 |
| 最大连接数 | 10,000 |
| 备份 | Tair 自动备份，保留 7 天 |

**2 GiB 够不够**：

| 数据 | 预估内存 |
|------|:--------:|
| callback/ tag/ datafill Stream | < 20 MiB（已消费消息自动 trim） |
| agent:daily:* 日计数 keys | < 5 MiB（每天几百个 key，TTL 午夜过期） |
| rate:* 滑窗 ZSET | < 10 MiB（15s/60s 窗口自动清理） |
| rotate:lock:* 锁 | < 1 MiB（临时性，用完删除） |
| 总计 | **< 50 MiB** ✅ 2 GiB 绰绰有余 |

---

### 3.4 Nginx 配置

部署在 ECS 上，与 Spring Boot 同机：

```nginx
# /etc/nginx/conf.d/huoma.conf

upstream huoma_backend {
    server 127.0.0.1:8080;
}

# HTTP → HTTPS 重定向
server {
    listen 80;
    server_name huoma.example.com;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    server_name huoma.example.com;

    ssl_certificate     /etc/nginx/ssl/fullchain.pem;
    ssl_certificate_key /etc/nginx/ssl/privkey.pem;
    ssl_protocols       TLSv1.2 TLSv1.3;

    # 企微回调 — 不缓冲，立即转发
    location /api/wecom/callback {
        proxy_pass http://huoma_backend;
        proxy_buffering off;
        proxy_read_timeout 5s;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }

    # 管理后台
    location / {
        proxy_pass http://huoma_backend;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}
```

---

### 3.5 冷备 ECS（¥30/月 保险）

| 项目 | 说明 |
|------|------|
| 什么时候用 | 主 ECS 宕机后手动启动 |
| 平时状态 | **关机**，只付系统盘 ¥30/月 |
| 恢复流程 | 开机 → 登录 → 从 OSS 拉 JAR 和配置 → `systemctl start huoma` |
| 恢复时间 | **< 10 分钟** |
| 要不要做热备 | **不要**，火马宕机 10 分钟不影响扫码（活码在企微侧） |

**不丢数据**：RDS 和 Redis 是独立服务，应用服务器宕机对数据零影响。换台 ECS 连上同一个 RDS/Redis，等于无缝恢复。

---

### 3.6 OSS 备份存储

```
backups/
├── app/
│   └── bookstore-qrcode-0.1.0.jar          ← 最新 JAR 包
├── config/
│   ├── application-prod.yml                 ← 生产配置
│   ├── nginx.conf                           ← Nginx 配置
│   └── huoma.env                            ← 环境变量文件
└── db/
    └── mysqldump-2026-06-15-030000.sql.gz   ← 每日自动备份
```

自动备份脚本（ECS 上 crontab，每天 3:00 执行）：

```bash
#!/bin/bash
# /opt/HuoMa/scripts/backup.sh
# 1. mysqldump → OSS
# 2. 上传配置到 OSS

DATE=$(date +%Y%m%d-%H%M%S)
OSS_BUCKET="oss://huoma-backups"

# 数据库备份（从 RDS 导出）
mysqldump -h ${RDS_HOST} -u ${RDS_USER} -p${RDS_PASS} \
  --single-transaction --routines --triggers \
  bookstore_qrcode | gzip > /tmp/dump-${DATE}.sql.gz

# 上传到 OSS
ossutil cp /tmp/dump-${DATE}.sql.gz ${OSS_BUCKET}/db/
ossutil cp /opt/HuoMa/target/*.jar ${OSS_BUCKET}/app/ --update
ossutil cp /etc/nginx/conf.d/huoma.conf ${OSS_BUCKET}/config/ --update

# 清理 30 天前的备份
ossutil rm ${OSS_BUCKET}/db/ --include "*.sql.gz" \
  --older-than 30d
```

---

## 四、宕机恢复 SOP

```
┌─────────────────────────────────────────────────┐
│              故障检测（自动/人工）                  │
│  阿里云云监控：ECS 不可达 1 分钟 → 短信/钉钉告警     │
└────────────────────┬────────────────────────────┘
                     │
              ┌──────▼──────┐
              │ 判断严重程度  │
              └──┬───────┬──┘
                 │       │
          临时故障      硬件故障/长时间不可恢复
                 │       │
          ┌──────▼──┐ ┌──▼──────────────┐
          │ 重启ECS  │ │ 启动冷备 ECS     │
          │ 等2分钟  │ │ 阿里云控制台:     │
          └──────┬──┘ │ 开机             │
                 │    │ ssh 登录          │
                 │    │ 从 OSS 拉 JAR+配置 │
                 │    │ systemctl start   │
                 │    │ huoma             │
                 │    └──────┬───────────┘
                 │           │
          ┌──────▼───────────▼──────┐
          │     验证服务恢复          │
          │ curl / 返回 200         │
          │ curl /actuator/health    │
          │ 企微回调模拟测试          │
          └──────┬──────────────────┘
                 │
          ┌──────▼──────┐
          │  事后补救     │
          │ 停机期间漏掉的│
          │ 回调 → 手动  │
          │ 批量补打标    │
          └─────────────┘
```

---

## 五、安全组与网络

| 端口 | 来源 | 用途 |
|------|------|------|
| 443 | 0.0.0.0/0 | HTTPS（Nginx → Spring Boot） |
| 80 | 0.0.0.0/0 | HTTP → 重定向 HTTPS |
| 22 | 仅管理员 IP | SSH |
| 8080 | 127.0.0.1 | Spring Boot（不对外开放） |
| 3306 | ECS 安全组 | RDS MySQL |
| 6379 | ECS 安全组 | Redis Tair |

> **企微回调要求**：443 端口公网可达 + 备案域名 + 有效 SSL 证书。

---

## 六、配置汇总

### 环境变量（RDS 参数注入 `/etc/systemd/system/huoma.env`）

```bash
DB_URL=jdbc:mysql://rm-xxxx.mysql.rds.aliyuncs.com:3306/bookstore_qrcode?useSSL=true&serverTimezone=Asia/Shanghai
DB_USERNAME=bookstore
DB_PASSWORD=<RDS密码>
REDIS_HOST=r-xxxx.redis.rds.aliyuncs.com
REDIS_PASSWORD=<Redis密码>
WECOM_CORP_ID=<企微CorpID>
WECOM_CORP_SECRET=<企微应用Secret>
WECOM_CALLBACK_TOKEN=<回调Token>
WECOM_CALLBACK_AES_KEY=<回调AESKey>
```

### systemd 服务

```ini
[Unit]
Description=HuoMa Bookstore QR Code Platform
After=network.target

[Service]
User=root
WorkingDirectory=/opt/HuoMa
EnvironmentFile=/etc/systemd/system/huoma.env
ExecStart=/bin/bash -c "exec /usr/bin/java -jar \
  -Xms512m -Xmx2g \
  -XX:+UseG1GC \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/var/log/huoma/ \
  -Duser.timezone=Asia/Shanghai \
  -Dspring.profiles.active=prod \
  /opt/HuoMa/app.jar >> /var/log/huoma/stdout.log 2>&1"
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

---

## 七、成本与扩展路径

```
         现在                    年活300万后                未来的未来
    ┌──────────┐            ┌──────────────┐          ┌──────────────┐
    │ ECS 单机  │            │ ECS 单机      │          │ ECS × 2 + SLB│
    │ + RDS 主  │  ─────▶   │ + RDS 主+只读  │  ─────▶ │ + RDS 主+只读 │
    │ + Redis   │            │ + Redis       │          │ + Redis 集群  │
    │ + 冷备    │            │ + 冷备        │          │ + 多消费者    │
    │ ¥1,231/月│            │ ¥1,231/月     │          │ ¥2,500+/月   │
    └──────────┘            └──────────────┘          └──────────────┘
         ↑                        ↑                        ↑
    当前最佳选                 加了只读副本              除非火马变成
                             日常访问量暴涨              SaaS 商业化产品
```

---

## 八、为什么这套方案对火马足够

| 维度 | 方案给的能力 | 火马实际需求 |
|------|-------------|-------------|
| 计算 | 4 vCPU 跑 Spring Boot + Nginx，CPU 使用率 < 30% | 业务线程大部分在等 I/O |
| 内存 | 8 GiB，JVM 用 2G，剩余充足 | 无需更多 |
| 数据库 | RDS 2C4G，100G，自动备份 | 300 万用户 ≈ 10G 数据，buffer pool 命中率 > 95% |
| Redis | 2G Tair，主从双副本 | 实际只用 < 100 MiB |
| 高可用 | 冷备 ECS，< 10 分钟恢复 | 宕机不影响扫码加好友 |
| 备份 | RDS 自动 + OSS 手动 | 多重保障 |

> 🤖 本文档由 Claude Code 根据火马项目源码分析与阿里云产品调研生成。
