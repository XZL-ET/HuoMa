#!/bin/bash
# 活码风险修复验证脚本
# 用法: bash scripts/verify-fixes.sh

echo "=== 活码修复验证 ==="
echo ""

echo "1. 编译检查..."
./mvnw compile -q && echo "  ✅ 编译通过" || echo "  ❌ 编译失败"
echo ""

echo "2. 单元测试..."
./mvnw test -q 2>/dev/null && echo "  ✅ 测试通过" || echo "  ❌ 测试失败"
echo ""

echo "3. NOGROUP trim 检查（预期 0：NOGROUP 恢复中不再有 trim(0) 误删）..."
# 排除 replayDlq 中故意的 trim(0)（那是DLQ重放后清空，不是NOGROUP恢复）
count=$(grep -rn "trim.*0, true" src/main/java/com/bookstore/qrcode/worker/ src/main/java/com/bookstore/qrcode/config/ 2>/dev/null | grep -v "DLQ_STREAM_KEY" | wc -l)
if [ "$count" -eq 0 ]; then echo "  ✅ 通过"; else echo "  ❌ 仍有 $count 处"; fi
echo ""

echo "4. 企微同步 errcode 检查（预期 >=2：同步结果有校验）..."
c1=$(grep -c "errcode" src/main/java/com/bookstore/qrcode/service/AgentBindService.java 2>/dev/null || echo 0)
c2=$(grep -c "errcode" src/main/java/com/bookstore/qrcode/service/QrCodeService.java 2>/dev/null || echo 0)
count=$((c1 + c2))
if [ "$count" -ge 2 ]; then echo "  ✅ 通过 ($count 处)"; else echo "  ❌ 仅 $count 处"; fi
echo ""

echo "5. 轮换锁统一检查（预期 2：expand/preactivate 共用 :rotate）..."
count=$(grep -c "ROTATE_LOCK_PREFIX.*:rotate\"" src/main/java/com/bookstore/qrcode/service/AgentBindService.java 2>/dev/null || echo 0)
if [ "$count" -eq 2 ]; then echo "  ✅ 通过"; else echo "  ❌ 预期 2 实际 $count"; fi
echo ""

echo "6. access_token 读写锁检查（预期 >=1）..."
count=$(grep -c "ReentrantReadWriteLock" src/main/java/com/bookstore/qrcode/wecom/WecomApiClient.java 2>/dev/null || echo 0)
if [ "$count" -ge 1 ]; then echo "  ✅ 通过"; else echo "  ❌ 未使用读写锁"; fi
echo ""

echo "7. 回调 fallback 检查（预期 >=1）..."
count=$(grep -c "writeFallbackLog" src/main/java/com/bookstore/qrcode/wecom/WecomCallbackController.java 2>/dev/null || echo 0)
if [ "$count" -ge 1 ]; then echo "  ✅ 通过"; else echo "  ❌ 无 fallback"; fi
echo ""

echo "8. Stream trim 位置检查（预期 0：生产者侧无 trim）..."
count=$(grep -c "trim.*TAG_STREAM" src/main/java/com/bookstore/qrcode/worker/CallbackWorker.java 2>/dev/null)
count=${count:-0}
if [ "$count" -eq 0 ]; then echo "  ✅ 通过"; else echo "  ❌ 生产者仍有 $count 处"; fi
echo ""

echo "9. API 限流检查（预期 >=2：TagWorker + DataFillWorker）..."
c1=$(grep -c "Thread.sleep" src/main/java/com/bookstore/qrcode/worker/TagWorker.java 2>/dev/null || echo 0)
c2=$(grep -c "Thread.sleep" src/main/java/com/bookstore/qrcode/worker/DataFillWorker.java 2>/dev/null || echo 0)
count=$((c1 + c2))
if [ "$count" -ge 2 ]; then echo "  ✅ 通过 ($count 处)"; else echo "  ❌ 仅 $count 处"; fi
echo ""

echo "10. DailyReset 异步检查（预期 >=1）..."
count=$(grep -c "syncQrCodeToWechatAsync" src/main/java/com/bookstore/qrcode/worker/DailyResetWorker.java 2>/dev/null || echo 0)
if [ "$count" -ge 1 ]; then echo "  ✅ 通过"; else echo "  ❌ 未改为异步"; fi
echo ""

echo "11. bindAgents 池不足告警检查（预期 >=1）..."
count=$(grep -c "alertEmptyBackup" src/main/java/com/bookstore/qrcode/service/QrCodeService.java 2>/dev/null || echo 0)
if [ "$count" -ge 1 ]; then echo "  ✅ 通过"; else echo "  ❌ 无告警"; fi
echo ""

echo "12. Redis 健康检查（预期 >=1）..."
count=$(grep -c "redis_alive" src/main/java/com/bookstore/qrcode/controller/HealthController.java 2>/dev/null || echo 0)
if [ "$count" -ge 1 ]; then echo "  ✅ 通过"; else echo "  ❌ 无 Redis 检查"; fi
echo ""

echo "=== 验证完成 ==="
