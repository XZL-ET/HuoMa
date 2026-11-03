# 告警降噪上线步骤与核对清单

- 对应设计：`2026-10-29-alert-noise-reduction-design.md`
- 数据库：生产 RDS `bookstore_qrcode`（`ddl-auto: none`，DDL 必须手工执行）
- 部署形态：ALB 后两台 ECS 双活（`10.0.0.231` / `10.0.0.232`）
- **所有 SQL、备份、部署与重启均由用户执行**；本文只给命令与核对项，不代表已执行。
- 核对 SQL：`docs/ops-scripts/2026-10-29-alert-dedup-verify.sql`

> 顺序不能颠倒：**先备份 → 先 ALTER（新增列可空/有默认值，旧版本代码不受影响）→ 回填 → 再部署新版本**。
> 新版本代码依赖 `last_seen_at` / `occurrence_count` 两列。`ddl-auto: none` 不做启动校验，未 ALTER 也能启动并通过健康检查，问题要到运行期才暴露：`/alerts` 读取时 `Unknown column` 报 500，维护/巡检写告警时同样报错（部分被 catch 记为日志，不会让进程退出）。

## 0. 上线前基线（只读，随时可跑）

在 RDS 执行核对脚本里的「上线前：基线」两段查询，记录：

- `agent_alert` 中 `status='open'` 的总数（预期约 2,140）；
- 各 `alert_type` 的 open 分布（用于和上线后对比）。

```sql
SELECT alert_type, COUNT(*) FROM agent_alert WHERE status = 'open'
GROUP BY alert_type ORDER BY 2 DESC;
```

## 1. 备份 `agent_alert`

```bash
mysqldump -h <RDS_HOST> -u <USER> -p bookstore_qrcode agent_alert > agent_alert_$(date +%Y%m%d).sql
```

- 不把真实主机名/账号/密码写进仓库或聊天记录，执行时再填。
- 确认备份文件非空且大小合理后，再做下一步。

## 2. 执行 ALTER（用户执行，可先于部署）

先判断字段是否已加过，避免重复执行报 `Duplicate column name`：

```sql
SHOW COLUMNS FROM agent_alert LIKE 'last_seen_at';
SHOW COLUMNS FROM agent_alert LIKE 'occurrence_count';
SHOW INDEX  FROM agent_alert WHERE Key_name = 'idx_type_status_key';
```

三者都为空时执行：

```sql
ALTER TABLE agent_alert
  ADD COLUMN last_seen_at DATETIME NULL AFTER resolved_at,
  ADD COLUMN occurrence_count INT NOT NULL DEFAULT 1 AFTER last_seen_at,
  ADD INDEX idx_type_status_key (alert_type, status, agent_userid, qr_code_id);
```

要点：

- 两列均可空或有默认值，**旧版本代码不读取它们，不受影响**，因此可以先于部署执行。
- 新增复合索引主要供 upsert 按键查找使用（每小时维护走的是 `status='open'` 扫描，用不上以 `alert_type` 打头的索引）；不加索引，告警量增长后 upsert 会逐条全表查。

## 3. 回填 `last_seen_at`

```sql
UPDATE agent_alert SET last_seen_at = created_at WHERE last_seen_at IS NULL;
```

- 历史行保持 `occurrence_count = 1`（默认值），符合“过去每次一条”的事实。
- 回填完成后再部署，页面的「最近发生」列才有值可展示。

## 4. 部署两台节点（用户执行）

按常规流程部署到 **两台**（ALB 双活，只发一台会造成新旧版本混跑）：

```bash
DEPLOY_SERVER_IP=<NAT网关EIP> DEPLOY_ECS2_IP=10.0.0.232 ./deploy/deploy.sh prod
```

- 两台都要确认新 jar 已生效（`app.jar` mtime 与预期版本一致）。
- 部署/重启由用户执行，不代跑。

## 5. 观察启动日志并跑核对 SQL

新版本启动完成后会立即执行一次告警维护（`ApplicationReadyEvent`），此后每小时 `:20` 再跑一次。检查两台日志（任一台持锁执行）：

```bash
grep '告警维护: 合并' /var/log/huoma/application.log | tail -5
```

应看到：

```text
告警维护: 合并 M 条、归档 N 条
```

然后在 RDS 跑核对脚本的「首轮维护后」查询，确认：

- `status='open'` 总数从约 2,140 降到 **< 50**；
- 全局类（`inheritance_*`、`*_stream_backlog`、`transfer_dlq_backlog`、`daily_reset_failure` 等）每种 **≤ 1**；
- 同类型+同合并键不再有重复 open（`HAVING COUNT(*) > 1` 返回 0 行）；
- `service_teacher_anomaly` / `anomaly_replace_shortfall` / `anomaly_replace_blocked` 等**真实待办仍在**（预期保留 2–4 条，指向仍需人工处理的员工/活码）。

## 6. 页面验收 `/alerts`

打开 `/alerts`，逐项确认：

- [ ] 默认只显示未处理（`status=open`）；清空状态筛选才看全部历史。
- [ ] 顶部「待处理事项」卡片区出现，只显示需要人工处理的类型；每卡有影响数量、「最近发生」、建议动作。
- [ ] 卡片与明细的「去处理 →」深链可跳转（活码类 → 活码详情/异常员工、继承类 → 活码列表、员工类 → 员工管理、DLQ/流 → 总览、删除报告 → 删除记录）。
- [ ] 明细「最近发生」「次数」两列有值。
- [ ] 条件类告警按钮文案为「✔ 标记已处理」，悬停提示“条件仍在时下一轮会重新出现”。
- [ ] 顶部「🧹 归档可自动恢复/过期项」按钮及确认框文案正确；**不再有「一键解决全部」**。

## 7. 条件恢复演练

制造一次真实异常（例如停用某活码触发 `qr_agent_anomaly`），修复后：

- 巡检类应在 **≤ 5 分钟（巡检任务每 5 分钟一轮）** 内自动转 `auto_resolved`（`resolved_by='auto:condition-cleared'`）；
- 验证 SQL：

```sql
SELECT id, alert_type, status, resolved_by, resolved_at
FROM agent_alert
WHERE alert_type = 'qr_agent_anomaly'
ORDER BY id DESC LIMIT 5;
```

## 8. 验证 48002 收敛

触发一次欢迎语发送失败，然后确认：

```sql
SELECT id, detail, occurrence_count, last_seen_at
FROM agent_alert WHERE alert_type = 'wechat_48002' ORDER BY id DESC LIMIT 3;
```

- 当天只有 **一条** open 的 `wechat_48002`；
- 每次命中 `occurrence_count` 递增、`detail` 中“今日 N 次”随之增长；
- 文案说明“企微接口 48002（本企业未开通家校沟通能力）……不是员工权限问题”，不再逐员工提示；
- 历史 358 条旧文案（`无客户联系权限`）已全部 `auto_resolved`。

## 9. ⚠️ 必须同批上线提醒

**T4（每小时归档可自动恢复/过期告警）与 T9（48002 改系统级 `wechat_48002`）必须在同一版本发布。**

原因：T4 会把旧文案的 `wechat_unavailable`（`detail LIKE '%无客户联系权限%'`）在一小时内全部归档。若 T9 未同批上线，旧版本生产者仍在持续写入该旧文案，会被 T4 反复清掉，导致 48002 信息凭空消失。

## 10. 回滚

- 应用可直接回退到上一版本 jar；新增两列与索引可保留，**旧代码忽略它们，无需 `DROP`**。
- 回滚后噪声会重新积累；已 `auto_resolved` 的历史不会自动重开。
- 若需回退 DDL（一般不必）：`ALTER TABLE agent_alert DROP INDEX idx_type_status_key, DROP COLUMN occurrence_count, DROP COLUMN last_seen_at;`（执行前仍需备份）。

## 上线后 24 小时观察项

1. 次日各类型的 open 新增量显著低于当前约 20 条/天/类型（按 `created_at` 对比）。
2. 两机 24h 内无重复维护任务（日志中同一时段只应有一台出现「告警维护」汇总）。
3. 合并存活行是最早创建的那条，`次数` 为组内求和、「最近发生」为组内最大。
4. 无 `agent_alert` 写入异常；`/alerts` 首屏只出现真实待办分组。
