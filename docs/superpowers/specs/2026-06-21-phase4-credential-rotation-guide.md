# Phase 4：凭据轮换操作手册

> 版本：1.0 | 日期：2026-06-21 | 操作人：运维负责人
> **此操作需短暂停机（约 2-5 分钟），请安排在业务低峰时段（建议凌晨 2-4 点）。**

---

## 0. 轮换前准备

### 0.1 信息收集清单

在开始前，确保已获取以下访问权限和信息：

| 项目 | 来源 | 确认（✓） |
|------|------|-----------|
| 企业微信管理后台登录 | https://work.weixin.qq.com | ☐ |
| 阿里云 RDS 控制台 | https://rds.console.aliyun.com | ☐ |
| 阿里云 Redis（Tair）控制台 | https://redis.console.aliyun.com | ☐ |
| 生产服务器 SSH | `ssh huoma@<SERVER_IP>` | ☐ |

### 0.2 通知相关方

```
发信对象：开发团队、客服团队
主题：火马平台定期凭据轮换 — 预计停机 <5 分钟

计划于 [日期] [时间] 对火马平台执行凭据轮换。届时服务将短暂不可用。

影响范围：
- 企微活码添加客户功能（短暂中断）
- 管理后台登录（需重启后恢复）
- 已生成二维码可正常扫码，后续入库和打标在恢复后自动补处理

预计恢复时间：操作开始后 5 分钟内。
```

### 0.3 备份当前凭据

```bash
# SSH 到生产服务器
ssh huoma@<SERVER_IP>

# 备份当前 huoma.env
sudo cp /etc/systemd/system/huoma.env /etc/systemd/system/huoma.env.bak.$(date +%Y%m%d_%H%M%S)

# 确认备份成功
ls -la /etc/systemd/system/huoma.env*
```

---

## 1. 企业微信凭据轮换

### 1.1 CorpSecret（核心操作，立即生效）

**风险等级：🔴 高** — 新 Secret 生成后旧 Secret 立即失效，必须立刻更新服务。

**步骤：**

**① 登录企业微信管理后台**
```
URL: https://work.weixin.qq.com
路径: 应用管理 → 应用 → 火马平台 → 查看 Secret
```

**② 生成新 CorpSecret**

点击「重新获取」按钮。企业微信会弹出确认提示：
> "重新获取后，旧的 Secret 将立即失效，确认？"

**③ 复制新 Secret**，格式示例：`xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx`

**④ 更新 huoma.env**

```bash
ssh huoma@<SERVER_IP>
sudo vi /etc/systemd/system/huoma.env
```

修改此行：
```ini
WECOM_CORP_SECRET=<新生成的Secret>
```

**⑤ 重启服务并验证**

```bash
sudo systemctl restart bookstore-qrcode
sleep 8
# 检查 access_token 获取是否正常
sudo journalctl -u bookstore-qrcode --since "1 min ago" | grep -i "access_token"
# 预期输出：看到 "access_token 已刷新" 或正常的 API 调用日志，无 40001/41001 错误
```

**⑥ 验证回调**

在管理后台 → 应用 → 火马平台 → 回调配置 → 点击「验证回调 URL」。

> 如果验证失败，先检查 Token 和 EncodingAESKey 是否与 huoma.env 一致（见 1.2/1.3）。

**回滚方案：**

如果新 Secret 导致 access_token 获取持续失败（连续 3 次），回滚至备份：
```bash
sudo cp /etc/systemd/system/huoma.env.bak.<timestamp> /etc/systemd/system/huoma.env
sudo systemctl restart bookstore-qrcode
# 注意：此时 Secret 已失效，需要先在管理后台重新生成并更新 bak 文件
```

---

### 1.2 Callback Token

**风险等级：🟡 中** — 不影响 API 调用，仅影响回调验证。可在 1.1 的同时更新。

**步骤：**

**① 生成新 Token**

使用密码管理器生成一个 **32 位随机字符串**：
```bash
# 在本地执行（不安装密码管理器的替代方案）
openssl rand -hex 16
# 或
cat /dev/urandom | tr -dc 'a-zA-Z0-9' | fold -w 32 | head -n 1
```

推荐格式：仅包含字母和数字，长度 32 字符。

**② 更新企业微信管理后台**

```
路径: 应用管理 → 应用 → 火马平台 → 接收消息 → 设置 API 接收
→ Token 字段 → 填入新 Token → 保存
```

**③ 更新 huoma.env**

```bash
sudo vi /etc/systemd/system/huoma.env
```

修改此行：
```ini
WECOM_CALLBACK_TOKEN=<新生成的Token>
```

**④ 验证**

在管理后台点击「验证回调 URL」→ 应返回 "验证成功"。

**⑤ 重启服务**

```bash
sudo systemctl restart bookstore-qrcode
```

---

### 1.3 Callback EncodingAESKey

**风险等级：🟡 中** — 与 Token 类似，仅影响回调加解密。

**步骤：**

**① 生成新 EncodingAESKey**

```bash
# 企微要求：43 位字符，由 a-zA-Z0-9 组成
cat /dev/urandom | tr -dc 'a-zA-Z0-9' | fold -w 43 | head -n 1
```

推荐在企业微信管理后台直接点击「随机生成」按钮获取合规密钥。

**② 更新企业微信管理后台**

```
路径: 应用管理 → 应用 → 火马平台 → 接收消息 → 设置 API 接收
→ EncodingAESKey → 点击「随机生成」→ 保存
```

**③ 更新 huoma.env**

```bash
sudo vi /etc/systemd/system/huoma.env
```

修改此行：
```ini
WECOM_CALLBACK_AES_KEY=<新生成的43位密钥>
```

**④ 验证**

在管理后台点击「验证回调 URL」→ 应返回 "验证成功"。

> **提示：Token 和 EncodingAESKey 可以在一次管理后台操作中同时更新，然后一次性更新 huoma.env + 重启服务。**

---

## 2. 数据库凭据轮换（阿里云 RDS MySQL）

### 2.1 DB_PASSWORD

**风险等级：🔴 高** — 密码变更期间应用无法连接数据库。建议凭据变更与重启紧密衔接。

**步骤：**

**① 生成新密码**

```bash
# 在本地或生产服务器执行
cat /dev/urandom | tr -dc 'a-zA-Z0-9!@#$%^&*' | fold -w 24 | head -n 1
```

要求：至少 16 位，包含大小写字母、数字、特殊字符。

**② 更新阿里云 RDS 控制台**

```
URL: https://rds.console.aliyun.com
路径: 实例列表 → <实例ID> → 账号管理 → bookstore 账号 → 重置密码
→ 输入新密码 → 确认
```

**③ 停止服务**

```bash
# 先停服务，防止旧密码连接失败产生大量错误日志
sudo systemctl stop bookstore-qrcode
```

**④ 更新 huoma.env**

```bash
sudo vi /etc/systemd/system/huoma.env
```

修改此行：
```ini
DB_PASSWORD=<新的数据库密码>
```

**⑤ 启动并验证**

```bash
sudo systemctl start bookstore-qrcode
sleep 8

# 验证数据库连接
sudo journalctl -u bookstore-qrcode --since "30 sec ago" | grep -i "datasource\|hikari\|connection"
# 预期：无 "Access denied" 或 "CommunicationsException" 错误

# 健康检查
curl -s http://127.0.0.1:8080/actuator/health
# 预期返回: {"status":"UP",...,"db":{"status":"UP"}}
```

**回滚方案：**

如果 DB 连接失败：
```bash
# 1. 在 RDS 控制台将密码改回旧值
# 2. 还原 huoma.env
sudo cp /etc/systemd/system/huoma.env.bak.<timestamp> /etc/systemd/system/huoma.env
# 3. 启动服务
sudo systemctl start bookstore-qrcode
```

---

### 2.2 DB_USERNAME（可选）

**风险等级：🟠 中-高** — 需要创建新用户、迁移权限，建议仅在发现异常访问时执行。

如需轮换，步骤与密码轮换类似，额外需要：
1. 在 RDS 控制台创建新用户 + 授权
2. 更新 huoma.env 的 `DB_USERNAME`
3. 重启服务
4. 验证后删除旧用户

---

## 3. Redis 凭据轮换（阿里云 Tair/Redis）

### 3.1 REDIS_PASSWORD

**风险等级：🔴 高** — 密码变更期间应用无法连接 Redis。Stream/Callback/Tag 等核心功能依赖 Redis。

**步骤：**

**① 生成新密码**

```bash
cat /dev/urandom | tr -dc 'a-zA-Z0-9!@' | fold -w 20 | head -n 1
```

> 注意：部分 Redis 版本不支持密码中的特殊字符，建议仅使用 `a-zA-Z0-9!@`。

**② 更新阿里云 Redis 控制台**

```
URL: https://redis.console.aliyun.com
路径: 实例列表 → <实例ID> → 账号管理 → 重置密码 → 输入新密码 → 确认
```

**③ 停止服务**

```bash
sudo systemctl stop bookstore-qrcode
```

**④ 更新 huoma.env**

```bash
sudo vi /etc/systemd/system/huoma.env
```

修改此行：
```ini
REDIS_PASSWORD=<新的Redis密码>
```

**⑤ 启动并验证**

```bash
sudo systemctl start bookstore-qrcode
sleep 8

# 验证 Redis 连接
sudo journalctl -u bookstore-qrcode --since "30 sec ago" | grep -i "redis\|Jedis\|Lettuce"
# 预期：无 "NOAUTH" 或 "unable to connect" 错误

# 验证 Stream 消费者组恢复
sudo journalctl -u bookstore-qrcode --since "30 sec ago" | grep -i "Worker 已启动"
# 预期：看到 CallbackWorker/TagWorker/DataFillWorker 启动成功日志

# 健康检查（检查 Redis 组件）
curl -s http://127.0.0.1:8080/actuator/health | python3 -m json.tool
# 预期：各组件 status 均为 "UP"
```

**回滚方案：**

```bash
# 1. 在 Redis 控制台将密码改回旧值
# 2. 还原 huoma.env
sudo cp /etc/systemd/system/huoma.env.bak.<timestamp> /etc/systemd/system/huoma.env
# 3. 启动服务
sudo systemctl start bookstore-qrcode
```

---

## 4. 管理员密码轮换

### 4.1 ADMIN_DEFAULT_PASSWORD

**风险等级：🟢 低** — 不影响业务功能，仅影响管理后台登录。

**步骤：**

**① 生成新密码**

```bash
cat /dev/urandom | tr -dc 'a-zA-Z0-9!@#$%^&*' | fold -w 16 | head -n 1
```

**② 更新 huoma.env**

```bash
sudo vi /etc/systemd/system/huoma.env
```

修改此行：
```ini
ADMIN_DEFAULT_PASSWORD=<新密码>
```

**③ 重启并验证**

```bash
sudo systemctl restart bookstore-qrcode
sleep 5
# 打开浏览器访问管理后台，用新密码登录验证
```

> **注意：** `ADMIN_DEFAULT_PASSWORD` 仅用于应用启动时自动创建管理员账号。如果数据库中已存在 admin 用户，修改此值不会覆盖已有密码。如需更改已有 admin 的密码，请通过管理后台修改。

---

## 5. 推荐执行顺序（总时间估算：5 分钟）

```
时间轴：

T+0min  ☐ SSH 登录生产服务器，备份 huoma.env
T+1min  ☐ 1.2 Callback Token     ──┐
        ☐ 1.3 EncodingAESKey      ──┤ 可并行在管理后台操作
        ☐ 1.1 CorpSecret 生成      ──┘
T+2min  ☐ systemctl stop bookstore-qrcode
T+2min  ☐ 更新 huoma.env 所有 3 项企微凭据 (1.1/1.2/1.3)
        ☐ 同时更新 DB_PASSWORD     ──┐
        ☐ 同时更新 REDIS_PASSWORD  ──┤ 可一次性编辑 huoma.env
        ☐ 同时更新 ADMIN_PASSWORD  ──┘
T+3min  ☐ 在云控制台确认 DB/Redis 密码变更已生效
T+3min  ☐ systemctl start bookstore-qrcode
T+4min  ☐ 企微管理后台「验证回调 URL」
        ☐ curl /actuator/health 检查
        ☐ 管理员密码登录测试
T+5min  ☐ 清理备份文件（确认稳定后，保留 7 天）
```

## 6. 验证检查清单

| 检查项 | 命令/方式 | 预期结果 |
|--------|----------|---------|
| ① 服务进程运行 | `systemctl status bookstore-qrcode` | active (running) |
| ② access_token 正常 | `journalctl -u bookstore-qrcode \| grep "access_token"` | 无 40001/41001 错误 |
| ③ DB 连接正常 | `curl -s localhost:8080/actuator/health \| python3 -m json.tool` | db.status=UP |
| ④ Redis 连接正常 | 同上 | redis.status=UP |
| ⑤ Worker 启动 | `journalctl -u bookstore-qrcode \| grep "Worker 已启动"` | Callback/Tag/DataFill 3 个 |
| ⑥ 回调验证成功 | 企微管理后台 → 点击「验证回调 URL」 | 验证成功 |
| ⑦ 管理后台登录 | 浏览器登录 | 新密码登录成功 |
| ⑧ 客户添加功能 | 扫码添加企微客户 | 正常入库 + 打标 |

## 7. 紧急回滚总流程

如果变更后持续故障超过 5 分钟，执行全量回滚：

```bash
# 1. 停止服务
sudo systemctl stop bookstore-qrcode

# 2. 全量还原凭据文件
sudo cp /etc/systemd/system/huoma.env.bak.<timestamp> /etc/systemd/system/huoma.env

# 3. 启动服务
sudo systemctl start bookstore-qrcode

# 4. 验证
sleep 5
curl -s http://127.0.0.1:8080/actuator/health
```

**注意：** 如果 CorpSecret 已轮换且旧 Secret 已失效，仅还原 huoma.env 不足以恢复企微 API 功能。此时需要：
1. 在企微管理后台重新生成 CorpSecret
2. 将新 Secret 写入 huoma.env
3. 重启服务

## 8. 事后清理

轮换完成并稳定运行 7 天后：

```bash
# 清理备份文件
sudo rm -f /etc/systemd/system/huoma.env.bak.*
```

## 9. 关联文档

- 环境变量模板：`deploy/huoma.env.template`
- 应用配置：`src/main/resources/application.yml`
- Nginx 配置：`deploy/nginx.conf`
- 部署脚本：`deploy/deploy.sh`
- 综合风险修复方案：`docs/superpowers/specs/2026-06-20-comprehensive-risk-fix.md`
