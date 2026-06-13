#!/bin/bash
# ============================================================
# 活码系统压测 —— 实时监控脚本
#
# 用法:
#   bash monitor_stress.sh [刷新间隔_秒]
#
# 示例:
#   bash monitor_stress.sh         # 默认 1 秒刷新
#   bash monitor_stress.sh 2       # 2 秒刷新
# ============================================================

INTERVAL=${1:-1}

REDIS_HOST="127.0.0.1"
REDIS_PORT="6379"
# 如果生产环境 Redis 有密码，在这里设置
# REDIS_PASS="your_password"

REDIS_CMD="redis-cli -h $REDIS_HOST -p $REDIS_PORT"
if [ -n "$REDIS_PASS" ]; then
    REDIS_CMD="redis-cli -h $REDIS_HOST -p $REDIS_PORT -a $REDIS_PASS"
fi

echo "============================================"
echo " 活码系统压测监控 (${INTERVAL}s 刷新)"
echo " 按 Ctrl+C 退出"
echo "============================================"

while true; do
    clear
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ $(date '+%H:%M:%S') ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    # ─── Stream 长度 ───
    local xlen_cb=$($REDIS_CMD XLEN wecom:callback:stream 2>/dev/null || echo "ERR")
    local xlen_tag=$($REDIS_CMD XLEN wecom:tag:stream 2>/dev/null || echo "ERR")
    local xlen_df=$($REDIS_CMD XLEN wecom:datafill:stream 2>/dev/null || echo "ERR")
    local xlen_dlq=$($REDIS_CMD XLEN wecom:dlq:stream 2>/dev/null || echo "ERR")

    echo ""
    echo "═══ Stream 长度 ═══"
    echo "  callback : $xlen_cb"
    echo "  tag      : $xlen_tag  ← 瓶颈所在"
    echo "  datafill : $xlen_df"
    echo "  DLQ 死信 : $xlen_dlq  ← 有增长 = 有问题"

    # ─── Pending 积压 ───
    local pend_cb=$($REDIS_CMD XPENDING wecom:callback:stream callback-worker-group 2>/dev/null | awk '{print $1}')
    local pend_tag=$($REDIS_CMD XPENDING wecom:tag:stream tag-worker-group 2>/dev/null | awk '{print $1}')
    local pend_df=$($REDIS_CMD XPENDING wecom:datafill:stream datafill-worker-group 2>/dev/null | awk '{print $1}')

    echo ""
    echo "═══ Pending (未 ACK 积压) ═══"
    echo "  callback : ${pend_cb:-0}"
    echo "  tag      : ${pend_tag:-0}  ← 持续增长 = Worker 跟不上"
    echo "  datafill : ${pend_df:-0}"

    # ─── Redis 内存 ───
    local redis_mem=$($REDIS_CMD INFO memory 2>/dev/null | grep used_memory_human | cut -d: -f2 | tr -d '\r')

    echo ""
    echo "═══ Redis 内存 ═══"
    echo "  used_memory: ${redis_mem:--}"

    # ─── JVM 内存 (通过 actuator) ───
    local jvm_heap=$(curl -s --connect-timeout 2 http://localhost:8080/actuator/metrics/jvm.memory.used?tag=area:heap 2>/dev/null | grep -oP '"measurements":\[\{"value":\K[0-9.]+')
    local jvm_max=$(curl -s --connect-timeout 2 http://localhost:8080/actuator/metrics/jvm.memory.max?tag=area:heap 2>/dev/null | grep -oP '"measurements":\[\{"value":\K[0-9.]+')

    echo ""
    echo "═══ JVM 堆内存 ═══"
    if [ -n "$jvm_heap" ] && [ -n "$jvm_max" ]; then
        local heap_mb=$(echo "scale=0; $jvm_heap / 1048576" | bc 2>/dev/null || echo "?")
        local max_mb=$(echo "scale=0; $jvm_max / 1048576" | bc 2>/dev/null || echo "?")
        local pct=$(echo "scale=1; $jvm_heap / $jvm_max * 100" | bc 2>/dev/null || echo "?")
        echo "  heap: ${heap_mb}MB / ${max_mb}MB (${pct}%)"
    else
        echo "  (actuator 不可用)"
    fi

    # ─── 系统负载 ───
    local load=$(uptime | awk -F'load average:' '{print $2}' | tr -d ' ')

    echo ""
    echo "═══ 系统负载 ═══"
    echo "  load: ${load:--}"

    # ─── HuoMa 最近异常日志 ───
    local last_err=$(journalctl -u huoma --since "30 sec ago" 2>/dev/null | grep -ciE "ERROR|WARN" || echo "0")

    echo ""
    echo "═══ HuoMa 日志 (近30秒) ═══"
    echo "  ERROR/WARN: $last_err 条"

    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    sleep "$INTERVAL"
done
