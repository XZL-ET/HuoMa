#!/bin/bash
# ============================================================
# 活码系统分阶压力测试 —— 一键执行脚本
#
# 用法:
#   bash run_test.sh <级别>
#
# 级别:
#   L1  - 轻量: 100 条, 无间隔     (日常流量)
#   L2  - 中等: 500 条, 10ms 间隔  (高峰)
#   L3  - 重度: 2000 条, 1ms 间隔  (突发)
#
# 示例:
#   bash run_test.sh L1
#   bash run_test.sh L2
#   bash run_test.sh L3
# ============================================================

LEVEL=${1:-L1}

# 先查 Redis 连通性
if ! redis-cli PING > /dev/null 2>&1; then
    echo "❌ 无法连接 Redis，请检查"
    exit 1
fi

echo "============================================"
echo " 活码系统压力测试 - ${LEVEL}"
echo " 开始时间: $(date '+%Y-%m-%d %H:%M:%S')"
echo "============================================"

# ─── 测试前快照 ───
echo ""
echo ">>> 测试前状态..."

# 读取 Pending（未 ACK 的消息数），这才是真正的"积压"指标
# XPENDING 输出第一行是 total count
_PEND_CB0=$(redis-cli XPENDING wecom:callback:stream callback-worker-group 2>/dev/null | head -1)
_PEND_TAG0=$(redis-cli XPENDING wecom:tag:stream tag-worker-group 2>/dev/null | head -1)
_DLQ0=$(redis-cli XLEN wecom:dlq:stream 2>/dev/null)
_CB0=$(redis-cli XLEN wecom:callback:stream 2>/dev/null)
_TAG0=$(redis-cli XLEN wecom:tag:stream 2>/dev/null)

PEND_CB0=$(echo "$_PEND_CB0" | grep -oE '^[0-9]+' || echo 0)
PEND_TAG0=$(echo "$_PEND_TAG0" | grep -oE '^[0-9]+' || echo 0)
[ -z "$PEND_CB0" ] && PEND_CB0=0
[ -z "$PEND_TAG0" ] && PEND_TAG0=0

echo "  callback Pending: $PEND_CB0"
echo "  tag Pending:      $PEND_TAG0"
echo "  callback Stream:  $_CB0"
echo "  tag Stream:       $_TAG0"
echo "  DLQ Stream:       $_DLQ0"

# ─── 选择测试级别 ───
case $LEVEL in
    L1)
        COUNT=100
        DELAY=100
        ;;
    L2)
        COUNT=500
        DELAY=10
        ;;
    L3)
        COUNT=2000
        DELAY=1
        ;;
     *)
        echo "无效级别: $LEVEL (可选: L1 L2 L3)"
        exit 1
        ;;
esac

echo ""
echo ">>> 注入 ${COUNT} 条 noop 消息 (间隔 ${DELAY}ms)..."
echo "    消息类型: __stress_test__ (不调企微 API)"
echo ""

REPO_DIR="$(dirname "$(readlink -f "$0")")"
START_TIME=$(date +%s)

RESULT=$(redis-cli --eval "$REPO_DIR/inject_noop.lua" , "$COUNT" "$DELAY")

END_TIME=$(date +%s)
ELAPSED=$((END_TIME - START_TIME))

echo "$RESULT"
echo "总耗时: ${ELAPSED}s"

# ─── 等待消费（看 Pending 归零） ───
echo ""
echo ">>> 等待 Workers 消费（Pending 归零 = 全部消费完成）..."
WAIT_SEC=30
MAX_PEND=0
for i in $(seq $WAIT_SEC -1 1); do
    _PCB=$(redis-cli XPENDING wecom:callback:stream callback-worker-group 2>/dev/null | head -1)
    _PTAG=$(redis-cli XPENDING wecom:tag:stream tag-worker-group 2>/dev/null | head -1)
    _DLQ=$(redis-cli XLEN wecom:dlq:stream 2>/dev/null)
    # 安全转数字（非数字→0）
    pcb=$(echo "$_PCB" | grep -oE '^[0-9]+' || echo 0)
    ptag=$(echo "$_PTAG" | grep -oE '^[0-9]+' || echo 0)
    [ -z "$pcb" ] && pcb=0
    [ -z "$ptag" ] && ptag=0
    if [ "$pcb" -gt "$MAX_PEND" ]; then MAX_PEND=$pcb; fi
    printf "\r  等待 %2ds | callback Pending: %4d | tag Pending: %4d | dlq: %4s  " "$i" "$pcb" "$ptag" "$_DLQ"
    # Pending 归零就提前结束
    if [ "$pcb" -le 0 ] && [ "$i" -lt $((WAIT_SEC - 2)) ]; then
        echo ""
        echo "  ✅ Pending 已归零"
        break
    fi
    sleep 1
done
# 确保输出换行
printf "\r%-70s\r" " "

# ─── 测试后快照 ───
echo ""
echo ">>> 测试后状态..."
_PEND_CB1=$(redis-cli XPENDING wecom:callback:stream callback-worker-group 2>/dev/null | head -1)
_PEND_TAG1=$(redis-cli XPENDING wecom:tag:stream tag-worker-group 2>/dev/null | head -1)
_DLQ1=$(redis-cli XLEN wecom:dlq:stream 2>/dev/null)
_CB1=$(redis-cli XLEN wecom:callback:stream 2>/dev/null)
_TAG1=$(redis-cli XLEN wecom:tag:stream 2>/dev/null)

PEND_CB1=$(echo "$_PEND_CB1" | grep -oE '^[0-9]+' || echo 0)
PEND_TAG1=$(echo "$_PEND_TAG1" | grep -oE '^[0-9]+' || echo 0)
[ -z "$PEND_CB1" ] && PEND_CB1=0
[ -z "$PEND_TAG1" ] && PEND_TAG1=0

echo "  callback Pending: $PEND_CB1"
echo "  tag Pending:      $PEND_TAG1"
echo "  callback Stream:  $_CB1"
echo "  tag Stream:       $_TAG1"
echo "  DLQ Stream:       $_DLQ1"

# ─── 统计消费速度 ───
TOTAL_TIME=$(( $(date +%s) - START_TIME ))
if [ "$TOTAL_TIME" -gt 0 ] && [ "$MAX_PEND" -gt 0 ]; then
    CONSUME_RATE=$(( (COUNT) / TOTAL_TIME ))
else
    CONSUME_RATE="N/A"
fi

echo ""
echo "============================================"
echo " 测试总结"
echo "============================================"
echo " 注入: ${COUNT} 条  |  峰值 Pending: ${MAX_PEND}  |  总耗时: ${TOTAL_TIME}s"

# 判断结果（基于 Pending 是否归零）
if [ "$PEND_CB1" -eq 0 ]; then
    echo " ✅ CallbackWorker 全量消费完成，无积压"
    if [ "$TOTAL_TIME" -gt 0 ]; then
        echo "    消费速度: 约 $(( COUNT / TOTAL_TIME )) 条/秒"
    fi
elif [ "$PEND_CB1" -le "$((COUNT / 10))" ]; then
    echo " ⚠️ 少量未消费: $PEND_CB1 条 (${COUNT}条中的 $(( PEND_CB1 * 100 / COUNT ))%)"
else
    echo " ❌ 积压严重: $PEND_CB1 / $COUNT 条未消费"
fi

if [ "$PEND_TAG1" -gt "$PEND_TAG0" ]; then
    echo " ⚠️ tag stream Pending 增长: +$((PEND_TAG1 - PEND_TAG0))"
fi

_DLQ_DIFF=$((_DLQ1 - _DLQ0))
if [ "$_DLQ_DIFF" -gt 0 ] 2>/dev/null; then
    echo " ⚠️ DLQ 增长 +${_DLQ_DIFF}，有消息进入死信队列"
fi

echo ""
echo " 结束时间: $(date '+%Y-%m-%d %H:%M:%S')"
