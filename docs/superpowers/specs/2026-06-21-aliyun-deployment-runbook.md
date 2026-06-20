# 火马（HuoMa）阿里云部署操作手册

> 编制日期：2026-06-21  
> 状态：阶段一已完成 · 阶段二待办

---

## 一、架构总览

```
                        互联网
                          │
             ┌────────────┴────────────┐
             │                         │
       ┌─────▼──────┐          ┌──────▼──────┐
       │  NAT 网关   │          │   SLB (后补) │  ← 阶段一：EIP 绑 NAT，DNAT 到 ECS-1
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
   │  │10.0.0.231   │       │ 10.0.0.x    │      │
   │  └──┬──────┬───┘       └──┬──────┬───┘      │
   │     │      │              │      │          │
   │  ┌──▼──┐ ┌─▼──┐      ┌──▼──┐ ┌─▼──┐       │
   │  │ RDS │ │Redis│     │ RDS │ │Redis│       │
   │  │主+读│ │ 2G │      │ 主+读│ │ 2G │       │
   │  └─────┘ └────┘      └─────┘ └────┘       │
   │     ↑ 同一套，共享    ↑                     │
   └─────────────────────────────────────────────┘
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

## 三、关键连接信息

| 资源 | 地址 |
|------|------|
| NAT 网关 EIP | 用于外网访问的固定公网 IP |
| ECS-1 内网 IP | `10.0.0.231` |
| ECS-2 内网 IP | `10.0.0.x`（第二台） |
| RDS 端点 | `rm-0jl9lbcy46x479oc6.mysql.rds.aliyuncs.com` |
| Redis 端点 | `r-0jlxfoa4mit1ore7cc.redis.rds.aliyuncs.com` |
| 数据库名 | `bookstore_qrcode` |
| 数据库用户 | `bookstore` |

---

## 四、已完成步骤（阶段一）

### 4.1 网络配置

- [x] **EIP 绑定到 NAT 网关**（非直接绑定 ECS）
- [x] **NAT 网关 DNAT 规则** — 端口转发到 ECS-1：
  - 端口 `22` → ECS-1 `10.0.0.231:22`
  - 端口 `80` → ECS-1 `10.0.0.231:80`
  - 端口 `443` → ECS-1 `10.0.0.231:443`
- [x] **NAT 网关 SNAT 规则** — 交换机级别 `10.0.0.0/24`，ECS 可出公网
- [x] **安全组规则**：22（管理员IP）、80/443（0.0.0.0/0）、8080（127.0.0.1）、3306/6379（内网）

### 4.2 ECS-1 环境

- [x] `apt update && apt upgrade -y`
- [x] `apt install -y openjdk-17-jdk nginx`
- [x] Java 17 已安装
- [x] Nginx 已安装，默认站点已删除

### 4.3 ECS-2 环境

- [x] 同 ECS-1 基础环境
- [x] 通过 ECS-1 跳板 SSH 访问

### 4.4 应用部署

- [x] `/etc/systemd/system/huoma.env` — 环境变量（RDS、Redis、企微凭证、管理员密码）
- [x] `/etc/systemd/system/huoma.service` — systemd 服务，JVM 参数：
  ```
  -Xms512m -Xmx2g -XX:+UseG1GC -XX:+HeapDumpOnOutOfMemoryError
  -XX:HeapDumpPath=/var/log/huoma/
  -Duser.timezone=Asia/Shanghai -Dspring.profiles.active=prod
  ```
- [x] `/etc/nginx/conf.d/huoma.conf` — Nginx 反代 `:80 → 127.0.0.1:8080`，`/api/wecom/callback` 不缓冲
- [x] 应用 JAR 已部署到 `/opt/HuoMa/app.jar`
- [x] ECS-1 健康检查通过：`{"status":"UP"}`
- [x] ECS-2 健康检查通过：`{"status":"UP"}`
- [x] 浏览器访问 `http://<NAT_EIP>/` 显示登录页面
- [x] RDS MySQL 共 19 张表正常初始化
- [x] Redis 连接正常

### 4.5 代码修复（已提交）

- [x] **pom.xml** — 添加 `maven-compiler-plugin` `<parameters>true</parameters>` 修复 Spring 参数名保留
- [x] **RedisConfig.java** — `@Primary` + `@Qualifier` 解决多 StringRedisTemplate 冲突
- [x] **deploy.sh** — 双机热备部署脚本（本地编译 → 上传 ECS-1 → ECS-1 分发 ECS-2 → 重启 → 健康检查）

### 4.6 数据库备份（临时方案）

- [x] `/opt/HuoMa/scripts/backup.sh` 已创建

---

## 五、待完成事项

### 5.1 立即执行（今天）

- [ ] **测试 backup.sh** — 在 ECS-1 上执行 `/opt/HuoMa/scripts/backup.sh` 确认能正常导出
- [ ] **添加 crontab 定时备份** — ECS-1 每天凌晨 3:00：
  ```
  0 3 * * * /opt/HuoMa/scripts/backup.sh >> /var/log/huoma/backup.log 2>&1
  ```

### 5.2 短期（需等待账号主人）

- [ ] **创建 RAM 子账号** — 分配 OSS 读写权限，生成 AccessKey
- [ ] **配置 OSS 备份上传** — 每日备份后自动上传到 OSS
- [ ] **配置 OSS 生命周期** — 自动删除 30 天前的备份
- [ ] **企微回调 URL** — 当前使用 EIP 直连，验证回调是否正常

### 5.3 阶段二：SLB + 域名上线

- [ ] **购买域名** — 备案域名
- [ ] **ICP 备案** — 阿里云 ICP 备案流程
- [ ] **购买 SLB** — 私网型 SLB + 公网 IP
- [ ] **EIP 迁移** — 从 NAT 网关解绑 EIP，改绑到 SLB（或 SLB 自带公网IP）
- [ ] **SLB 后端池** — 添加 ECS-1 + ECS-2
- [ ] **SLB 健康检查** — 配置 `/actuator/health`
- [ ] **DNS 解析** — 域名 A 记录指向 SLB IP
- [ ] **SSL 证书** — 申请免费 DV 证书，配置在 SLB 或 Nginx
- [ ] **企微回调更新** — URL 改为 `https://域名/api/wecom/callback`
- [ ] **Nginx 调整** — 如 SSL 终结在 SLB，ECS 只需处理 HTTP
- [ ] **SLB 自动故障转移测试** — 停 ECS-1 验证流量自动切到 ECS-2

### 5.4 运维加固（建议）

- [ ] **监控告警** — 阿里云云监控配置 ECS/RDS/Redis 告警
- [ ] **日志轮转** — `/var/log/huoma/` 配置 logrotate
- [ ] **定期安全更新** — `unattended-upgrades` 配置安全自动更新
- [ ] **异地备份** — OSS 跨区域复制（可选）

---

## 六、日常运维命令

### 应用管理

```bash
# 查看服务状态
systemctl status huoma

# 重启服务
systemctl restart huoma

# 查看实时日志
journalctl -u huoma -f

# 查看最近 100 行日志
journalctl -u huoma -n 100 --no-pager
```

### 部署更新（在本机执行）

```bash
# 部署到 ECS-1 和 ECS-2
export DEPLOY_SERVER_IP=<NAT_EIP>
export DEPLOY_ECS2_IP=<ECS-2内网IP>
cd D:\ClaudeCode\HuoMa
bash deploy/deploy.sh prod
```

### 手动备份

```bash
# ECS-1 上执行
/opt/HuoMa/scripts/backup.sh
ls -lh /opt/HuoMa/backups/
```

### 故障转移（阶段一手动切换）

```bash
# ECS-1 宕机时，在阿里云控制台操作：
# 1. 解绑 NAT 网关 DNAT 端口 80/443 → ECS-1
# 2. 新建 DNAT 端口 80/443 → ECS-2 内网 IP
# 3. 验证: curl http://<NAT_EIP>/actuator/health
```

---

## 七、文件清单

### ECS 上关键文件

| 路径 | 用途 |
|------|------|
| `/opt/HuoMa/app.jar` | Spring Boot 应用 |
| `/opt/HuoMa/scripts/backup.sh` | 数据库备份脚本 |
| `/opt/HuoMa/backups/` | 备份文件存储目录 |
| `/etc/systemd/system/huoma.env` | 环境变量 |
| `/etc/systemd/system/huoma.service` | systemd 服务定义 |
| `/etc/nginx/conf.d/huoma.conf` | Nginx 反代配置 |
| `/var/log/huoma/` | 应用日志目录 |

### 本机项目文件

| 路径 | 用途 |
|------|------|
| `deploy/deploy.sh` | 双机部署脚本 |
| `pom.xml` | Maven 构建配置 |
| `src/main/java/.../config/RedisConfig.java` | Redis 配置 |
| `docs/superpowers/specs/2026-06-21-aliyun-hot-standby-deployment-design.md` | 架构设计文档 |
| `docs/superpowers/specs/2026-06-21-aliyun-deployment-runbook.md` | 本操作手册 |
