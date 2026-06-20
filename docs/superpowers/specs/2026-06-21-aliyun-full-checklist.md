# 火马（HuoMa）阿里云上线全流程清单

> 编制：2026-06-21 · 最后更新：2026-06-21  
> 当前阶段：阶段一完成 · 企微回调待配

---

## 环境速查卡

| 资源 | 地址 | 状态 |
|------|------|:--:|
| NAT EIP | <YOUR_SERVER_IP> | ✅ |
| ECS-1 内网 | 10.0.0.231 | ✅ |
| ECS-2 内网 | 10.0.0.232 | ✅ |
| RDS | rm-0jl9lbcy46x479oc6.mysql.rds.aliyuncs.com | ✅ |
| Redis | r-0jlxfoa4mit1ore7cc.redis.rds.aliyuncs.com | ✅ |
| 域名 | 待配 DNS | ⬜ |

---

## 一、明天立即完成 — 企微回调跑通

- [ ] **1. 配 DNS**
  - 域名 A 记录 → `<YOUR_SERVER_IP>`
  - 验证：`nslookup 域名` 返回 `<YOUR_SERVER_IP>`

- [ ] **2. 加企微 API IP 白名单**
  - 企微后台 → 应用管理 → 自建应用 → 企业可信IP
  - 添加 `<YOUR_SERVER_IP>`
  - 验证：管理后台创建活码，不再报 60020

- [ ] **3. 配企微回调 URL**
  - 企微后台 → 应用管理 → 自建应用 → 接收消息 → 设置API接收
  - URL：`http://域名/api/wecom/callback`
  - Token：同 `huoma.env` 中 `WECOM_CALLBACK_TOKEN`
  - EncodingAESKey：同 `huoma.env` 中 `WECOM_CALLBACK_AES_KEY`
  - 点保存，企微自动验证

- [ ] **4. 端到端测试**
  - 管理后台创建活码
  - 微信扫码测试
  - 查看回调日志：`journalctl -u huoma -n 20 --no-pager | grep -i callback`

- [ ] **5. 检查 ECS-2 状态**
  - `ssh root@10.0.0.232 "curl -s http://127.0.0.1:8080/actuator/health"`
  - 预期：`{"status":"UP"}`

- [ ] **6. 确认自动备份**
  - `cat /var/log/huoma/backup.log`
  - `ls -lh /opt/HuoMa/backups/`

---

## 二、短期 — 等账号主人（RAM 子账号 + OSS）

- [ ] **7. 创建 RAM 子账号**
  - 阿里云控制台 → RAM 访问控制 → 用户 → 创建用户
  - 勾选 OpenAPI 调用访问（生成 AccessKey）
  - 权限策略：`AliyunOSSFullAccess`

- [ ] **8. 取得子账号 AccessKey**
  - AccessKey ID + AccessKey Secret
  - 告知 ECS-1 配置使用

- [ ] **9. 配置 OSS 备份上传**
  - 创建 OSS Bucket
  - 配置生命周期（30 天自动删除）
  - 修改 backup.sh 增加 ossutil 上传步骤
  - 验证：备份文件出现在 OSS

- [ ] **10. 全流程功能测试**
  - 活码创建
  - 扫码添加客户
  - 员工日上限触发轮换
  - 标签自动打标
  - 客户信息补全
  - 管理后台统计数据核对

---

## 三、阶段二 — SLB + 域名 + HTTPS 正式上线

- [ ] **11. 购买 SLB**
  - 阿里云 → 负载均衡 → 私网型
  - 同 VPC，同交换机

- [ ] **12. SLB 绑定公网 IP**
  - 方案 A：EIP 从 NAT 解绑改绑 SLB
  - 方案 B：SLB 自带公网 IP

- [ ] **13. 添加后端服务器**
  - ECS-1：10.0.0.231:8080
  - ECS-2：10.0.0.232:8080
  - 权重：1:1，加权轮询

- [ ] **14. 配置健康检查**
  - 检查路径：`/actuator/health`
  - 健康阈值：2，不健康阈值：2
  - 间隔：5 秒

- [ ] **15. DNS 切到 SLB IP**
  - 域名 A 记录改指 SLB 公网 IP
  - 验证：`nslookup 域名`

- [ ] **16. 申请免费 SSL 证书**
  - 阿里云 → SSL 证书 → 免费 DV
  - 域名验证

- [ ] **17. 配置 HTTPS**
  - 证书部署到 SLB 或 ECS Nginx
  - 验证：`https://域名` 正常访问

- [ ] **18. 企微回调切 HTTPS**
  - URL 改为：`https://域名/api/wecom/callback`
  - 企微后台重新验证

- [ ] **19. 故障转移测试**
  - 手动停 ECS-1：`systemctl stop huoma`
  - 验证管理后台仍能访问（SLB 自动切 ECS-2）
  - 恢复 ECS-1，验证恢复

- [ ] **20. ICP 备案**（如需新域名）
  - 阿里云 ICP 备案提交
  - 主体必须与企微企业一致
  - 预计 1-3 周

---

## 四、运维加固 — 上线后长期持续

- [ ] **21. 阿里云监控告警**
  - ECS：CPU > 80%、内存 > 85%
  - RDS：连接数 > 80%、IOPS > 80%
  - Redis：连接数 > 80%、内存 > 80%
  - 通知方式：短信/钉钉

- [ ] **22. 日志轮转**
  - `/var/log/huoma/` 配置 logrotate
  - 每天轮转，保留 30 天

- [ ] **23. 安全更新**
  - `apt unattended-upgrades` 确认开启
  - 每月手动 `apt update && apt upgrade`

- [ ] **24. Nginx 安全加固**
  - 限流：单 IP 100 req/s
  - 添加安全头（HSTS、X-Frame-Options 等）

- [ ] **25. 备份恢复演练**
  - 选一天拉取最新备份
  - 导入到本地测试库
  - 验证数据完整性

- [ ] **26. 测试环境（staging）**
  - 阿里云低配 ECS 或本地
  - 连 RDS staging 库 `bookstore_qrcode_staging`
  - 部署前先在 staging 验证

---

## 日常运维命令速查

```bash
# 应用管理
systemctl status huoma                 # 查看状态
systemctl restart huoma                # 重启
journalctl -u huoma -f                 # 实时日志
journalctl -u huoma -n 50 --no-pager   # 最近 50 行

# 部署（本机 Git Bash）
export DEPLOY_SERVER_IP=<YOUR_SERVER_IP>
export DEPLOY_ECS2_IP=10.0.0.232
bash deploy/deploy.sh prod

# 备份
/opt/HuoMa/scripts/backup.sh           # 手动备份
ls -lh /opt/HuoMa/backups/             # 查看备份文件
cat /var/log/huoma/backup.log          # 备份日志

# 健康检查
curl http://127.0.0.1:8080/actuator/health           # ECS-1 本地
ssh root@10.0.0.232 "curl http://127.0.0.1:8080/actuator/health"  # ECS-2

# 故障转移（阶段一手动）
# 阿里云控制台 → NAT 网关 → DNAT → 改端口映射到 ECS-2
```

---

## 进度总览

```
阶段     进度                    关键路径

一         ████████████████████  ✅ 双机热备 + DB + Redis
回调       ░░░░░░░░░░░░░░░░░░░░  ⬜ 明天 (DNS + 白名单 + 回调)
OSS        ░░░░░░░░░░░░░░░░░░░░  ⬜ 等账号主人
二         ░░░░░░░░░░░░░░░░░░░░  ⬜ SLB + 域名 + HTTPS
运维       ░░░░░░░░░░░░░░░░░░░░  ⬜ 上线后

总体       ████████░░░░░░░░░░░░  ~40%
```
