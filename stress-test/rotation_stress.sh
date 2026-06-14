#!/bin/bash
# ============================================================
# 活码人员轮转压力测试
#
# 模拟大批用户扫码涌入 → 员工日限达到 → 自动下码 →
# 全局池补人上码 → 企微同步 的完整链路。
#
# 【安全设计】
#   - 所有操作仅针对专用测试活码 (schoolId=STRESS_TEST_000)
#   - 注入的 external_userid 使用 stress_ 前缀，与真实客户隔离
#   - 测试代理 dailyMax 设置为 5（小值，快速触发轮转）
#   - TagWorker 处理 fake 客户时会失败但不影响核心轮转链路
#
# 用法:
#   bash rotation_stress.sh <命令> [参数]
#
# 命令:
#   init [N]      创建测试活码 + 绑定 N 个代理 (默认 5, dailyMax=5)
#   inject [M]    注入模拟回调事件 (M=每代理额外客户数, 默认 6→超过 dailyMax=5)
#   watch [S]     实时监控轮转 (刷新间隔秒, 默认 2)
#   cleanup       清理测试活码及全部关联数据
#   full [N] [M]  完整流程: init → inject → watch(自动) → report → cleanup
#
# 级别预设 (通过 full 的 N 参数控制):
#   L1: N=5,  M=6   → 5 代理满 → 5 次轮转
#   L2: N=10, M=6   → 10 代理满 → 10 次轮转
#   L3: N=20, M=6   → 20 代理满 → 20 次轮转
#
# 示例:
#   bash rotation_stress.sh init 5          # 创建测试活码 + 5代理
#   bash rotation_stress.sh inject 6        # 每代理注入6个客户(超过dailyMax=5)
#   bash rotation_stress.sh watch           # 监控轮转过程
#   bash rotation_stress.sh full 10 6       # L2: 10代理完整压测
# ============================================================

set -e
set +H  # 禁用历史扩展，防止密码中 ! 被解析

# ============================================================
# 配置
# ============================================================
BASE_URL="http://localhost:8080"
COOKIE_JAR="/tmp/huoma_stress_cookies.txt"
RESULT_DIR="/tmp/huoma_rotation_results"
TIMESTAMP=$(date '+%Y%m%d_%H%M%S')
TEST_SCHOOL_NAME="__ROTATION_STRESS__"
TEST_SCHOOL_ID="STRESS_TEST_000"
TEST_DAILY_MAX=5                     # 测试代理日限（小值，快速触发轮转）
REDIS_STREAM="wecom:callback:stream"

MYSQL_PASS='<YOUR_DB_PASSWORD>'
MYSQL_USER='bookstore'
MYSQL_DB='bookstore_qrcode'
REDIS_CMD="redis-cli"

mkdir -p "$RESULT_DIR"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

# ============================================================
# 参数解析
# ============================================================
CMD="${1:-help}"
ARG_N="${2:-5}"
ARG_M="${3:-6}"

# ============================================================
# 工具函数
# ============================================================

log_info()  { echo -e "${GREEN}[INFO]${NC}  $(date '+%H:%M:%S') $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC}  $(date '+%H:%M:%S') $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $(date '+%H:%M:%S') $*"; }
log_title() {
    echo -e "\n${CYAN}════════════════════════════════════════════════════════${NC}"
    echo -e "${CYAN}  $*${NC}"
    echo -e "${CYAN}════════════════════════════════════════════════════════${NC}"
}
log_metric() { echo -e "  ${BOLD}$1${NC}: $2"; }

mysql_q()  { mysql -u "$MYSQL_USER" -p"$MYSQL_PASS" "$MYSQL_DB" -N -e "$1" 2>/dev/null; }
mysql_t()  { mysql -u "$MYSQL_USER" -p"$MYSQL_PASS" "$MYSQL_DB" -e "$1" 2>/dev/null; }
redis_cmd() { $REDIS_CMD "$@" 2>/dev/null; }

# 测试活码
get_test_qr_id() {
    mysql_q "SELECT id FROM qr_code WHERE school_id = '${TEST_SCHOOL_ID}' LIMIT 1;"
}

# 池中可用的健康代理（已激活，非封号，非离职）
get_healthy_standby() {
    local count=$1
    mysql_q "
        SELECT p.agent_userid FROM global_agent_pool p
        JOIN employee e ON e.userid = p.agent_userid
        LEFT JOIN agent a ON a.userid = p.agent_userid
        WHERE p.status = 'standby'
          AND e.active = 1
          AND (e.wechat_status IS NULL OR e.wechat_status = 1)
          AND (a.overall_status IS NULL OR a.overall_status NOT IN ('blocked', 'melted'))
        ORDER BY p.sort_order ASC
        LIMIT ${count};
    "
}

# HTTP 请求
do_create_test_qr() {
    local svc_userid=$1
    curl -s -o /dev/null -w "%{http_code}" \
        -X POST "${BASE_URL}/qrcodes/create" \
        -d "schoolName=${TEST_SCHOOL_NAME}" \
        -d "schoolId=${TEST_SCHOOL_ID}" \
        -d "regionCity=压测市" \
        -d "regionDistrict=压测区" \
        -d "studentCount=100" \
        -d "initialAgentCount=1" \
        -d "serviceTeacherUserid=${svc_userid}" \
        -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
        --connect-timeout 10 --max-time 60
}

do_add_agent() {
    curl -s -o /dev/null -w "%{http_code}" \
        -X POST "${BASE_URL}/qrcodes/$1/agents" \
        -d "agentUserid=$2" \
        -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
        --connect-timeout 5 --max-time 30
}

# ============================================================
# init: 创建测试活码 + 绑定代理
# ============================================================
cmd_init() {
    local agent_count=${ARG_N:-5}
    log_title "初始化轮转压测环境"

    # 1. 检查测试活码是否已存在
    local qr_id=$(get_test_qr_id)
    if [ -n "$qr_id" ]; then
        log_info "测试活码已存在: ID=${qr_id}，先清理..."
        cmd_cleanup
        sleep 1
    fi

    # 2. 取健康代理（多取 1 个作为服务老师）
    local need_count=$((agent_count + 1))
    log_info "从全局池取 ${need_count} 个健康代理 (1 服务老师 + ${agent_count} 接待员)..."
    local all_agents=$(get_healthy_standby "$need_count")
    local actual_count=$(echo "$all_agents" | wc -l)
    if [ "$actual_count" -lt 2 ]; then
        log_error "可用代理不足: 需要至少 2 个, 实际 ${actual_count}"
        exit 1
    fi

    # 第一个作为服务老师
    local svc_userid=$(echo "$all_agents" | head -1)
    log_info "服务老师: ${svc_userid}"

    # 3. 创建测试活码（带上服务老师）
    log_info "创建测试活码..."
    local http_code=$(do_create_test_qr "$svc_userid")
    sleep 3
    qr_id=$(get_test_qr_id)
    if [ -z "$qr_id" ]; then
        log_error "创建测试活码失败 (HTTP ${http_code})"
        # 检查是否有错误消息
        curl -s -L -c /tmp/cookies.txt "http://localhost:8080/qrcodes/create" \
            -d "schoolName=${TEST_SCHOOL_NAME}" \
            -d "schoolId=${TEST_SCHOOL_ID}" \
            -d "regionCity=压测市" \
            -d "regionDistrict=压测区" \
            -d "studentCount=100" \
            -d "initialAgentCount=1" \
            -d "serviceTeacherUserid=${svc_userid}" 2>&1 | grep -A2 'alert-danger' | tail -3
        exit 1
    fi
    log_info "测试活码已创建: ID=${qr_id}"

    # 4. 绑定剩余接待员到测试活码
    local remaining_agents=$(echo "$all_agents" | tail -n +2)
    local added=0
    log_info "绑定 ${agent_count} 个接待员 (dailyMax=${TEST_DAILY_MAX})..."
    while IFS= read -r userid; do
        [ -z "$userid" ] && continue
        http_code=$(do_add_agent "$qr_id" "$userid")
        if [ "$http_code" = "302" ]; then
            added=$((added + 1))
            log_info "  ✅ ${userid}"
        else
            log_warn "  ❌ ${userid} (HTTP ${http_code})"
        fi
        sleep 0.3
    done <<< "$remaining_agents"

    # 5. 更新所有代理 dailyMax 为测试值
    log_info "设置代理 dailyMax=${TEST_DAILY_MAX}..."
    mysql_q "
        UPDATE qr_agent SET daily_max = ${TEST_DAILY_MAX}
        WHERE qr_code_id = ${qr_id} AND status = 'active';
    "
    # 同时更新全局池中的 dailyMax
    for uid in $all_agents; do
        [ -z "$uid" ] && continue
        mysql_q "UPDATE global_agent_pool SET daily_max = ${TEST_DAILY_MAX} WHERE agent_userid = '${uid}';"
    done

    echo ""
    log_info "初始化完成:"
    log_metric "测试活码 ID" "${qr_id}"
    log_metric "服务老师"   "${svc_userid}"
    log_metric "接待员数"   "${added}"
    log_metric "dailyMax"   "${TEST_DAILY_MAX}"
    log_metric "预计触发轮转" "每代理接收 ${TEST_DAILY_MAX}+ 客户即满"

    # 显示当前状态
    echo ""
    mysql_t "
        SELECT a.agent_userid, a.role, a.daily_max, a.daily_current, a.status
        FROM qr_agent a WHERE a.qr_code_id = ${qr_id} AND a.status = 'active'
        ORDER BY a.sort_order;
    "
}

# ============================================================
# inject: 注入模拟回调事件
# ============================================================
cmd_inject() {
    local events_per_agent=${ARG_M:-6}
    local qr_id=$(get_test_qr_id)

    if [ -z "$qr_id" ]; then
        log_error "测试活码不存在，请先执行: bash rotation_stress.sh init"
        exit 1
    fi

    # 获取测试活码上的活跃代理
    local agents=$(mysql_q "
        SELECT agent_userid FROM qr_agent
        WHERE qr_code_id = ${qr_id} AND status = 'active'
        ORDER BY sort_order;
    ")
    if [ -z "$agents" ]; then
        log_error "测试活码上没有活跃代理"
        exit 1
    fi

    local agent_count=$(echo "$agents" | wc -l)
    local total_events=$((agent_count * events_per_agent))

    log_title "注入模拟回调事件"
    log_info "目标活码: ID=${qr_id} (${TEST_SCHOOL_ID})"
    log_info "代理数: ${agent_count} | 每代理事件: ${events_per_agent} | 总事件: ${total_events}"
    log_info "每日限: ${TEST_DAILY_MAX} | 预计每人满后触发轮转"

    # 记录注入前状态
    local stream_before=$(redis_cmd XLEN "$REDIS_STREAM")
    local pend_before=$(redis_cmd XPENDING "$REDIS_STREAM" callback-worker-group 2>/dev/null | head -1 | grep -oE '^[0-9]+' || echo 0)
    local rotate_before=$(mysql_q "SELECT COUNT(*) FROM qr_rotate_log;")

    echo ""
    log_info "注入前状态: Stream=${stream_before} Pending=${pend_before} 轮转日志=${rotate_before}"
    echo ""

    # 逐个代理注入事件
    local total_injected=0
    local start_time=$(date +%s)

    while IFS= read -r userid; do
        [ -z "$userid" ] && continue
        log_info "注入 ${userid}: ${events_per_agent} 个模拟客户..."

        result=$(redis_cmd --eval stress-test/inject_rotation.lua \
            "$userid" "$TEST_SCHOOL_ID" "$events_per_agent")

        echo "  ${result}"
        total_injected=$((total_injected + events_per_agent))

        # 短暂等待，让 Worker 有时间消费
        sleep 0.5
    done <<< "$agents"

    local end_time=$(date +%s)
    local elapsed=$((end_time - start_time))

    echo ""
    log_info "注入完成: ${total_injected} 事件 / ${elapsed}s"
    log_info "等待 Worker 消费和轮转触发..."

    # 等待消费（最多 60 秒）
    local waited=0
    while [ $waited -lt 60 ]; do
        local pend_now=$(redis_cmd XPENDING "$REDIS_STREAM" callback-worker-group 2>/dev/null | head -1 | grep -oE '^[0-9]+' || echo 0)
        local stream_now=$(redis_cmd XLEN "$REDIS_STREAM")
        local full_count=$(mysql_q "SELECT COUNT(*) FROM qr_agent WHERE qr_code_id = ${qr_id} AND status = 'full';")
        local active_count=$(mysql_q "SELECT COUNT(*) FROM qr_agent WHERE qr_code_id = ${qr_id} AND status = 'active';")
        local rotate_now=$(mysql_q "SELECT COUNT(*) FROM qr_rotate_log;")

        printf "\r  等待 %2ds | Pending: %4s | Stream: %4s | 活跃: %2s | 满员: %2s | 轮转: %2s" \
            "$waited" "$pend_now" "$stream_now" "$active_count" "$full_count" "$rotate_now"

        if [ "${pend_now:-0}" -le 0 ] && [ $waited -gt 5 ]; then
            echo ""
            log_info "✅ 全部消费完成"
            break
        fi
        sleep 2
        waited=$((waited + 2))
    done
    printf "\r%-70s\r" " "

    # 最终状态
    echo ""
    log_title "注入后状态报告"

    local final_active=$(mysql_q "SELECT COUNT(*) FROM qr_agent WHERE qr_code_id = ${qr_id} AND status = 'active';")
    local final_full=$(mysql_q "SELECT COUNT(*) FROM qr_agent WHERE qr_code_id = ${qr_id} AND status = 'full';")
    local final_rotate=$(mysql_q "SELECT COUNT(*) FROM qr_rotate_log;")
    local rotations=$((final_rotate - rotate_before))

    log_metric "活跃代理" "${final_active}"
    log_metric "满员代理" "${final_full}"
    log_metric "触发轮转" "${rotations}"
    log_metric "注入事件" "${total_injected}"

    # 轮转详情
    if [ "$rotations" -gt 0 ]; then
        echo ""
        echo ">>> 轮转日志 (最近 ${rotations} 条):"
        mysql_t "
            SELECT id, to_userid AS 上码, reason AS 原因, created_at
            FROM qr_rotate_log
            ORDER BY id DESC LIMIT ${rotations};
        "

        echo ""
        echo ">>> 当前活码代理状态:"
        mysql_t "
            SELECT agent_userid, role, daily_max, daily_current, status,
                   CASE status WHEN 'full' THEN CONCAT('→ 被 ', COALESCE(replaced_by, '?'), ' 替换')
                               WHEN 'active' THEN '✅ 在线上'
                   END AS 说明
            FROM qr_agent WHERE qr_code_id = ${qr_id}
            ORDER BY sort_order;
        "
    else
        log_warn "未触发轮转（可能 dailyMax 未达到或 Worker 未处理完）"
    fi
}

# ============================================================
# watch: 实时监控
# ============================================================
cmd_watch() {
    local interval=${ARG_N:-2}
    local qr_id=$(get_test_qr_id)

    log_title "实时轮转监控 (${interval}s 刷新, Ctrl+C 退出)"

    if [ -z "$qr_id" ]; then
        log_warn "测试活码不存在，仅监控全局状态"
    fi

    while true; do
        clear
        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━ $(date '+%H:%M:%S') ━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

        # Redis Stream
        local stream_len=$(redis_cmd XLEN "$REDIS_STREAM")
        local pend=$(redis_cmd XPENDING "$REDIS_STREAM" callback-worker-group 2>/dev/null | head -1 | grep -oE '^[0-9]+' || echo 0)
        echo ""
        echo "═══ Redis Stream ═══"
        echo "  callback 长度 : ${stream_len:-?}"
        echo "  Pending       : ${pend:-0}"

        # 测试活码状态
        if [ -n "$qr_id" ]; then
            echo ""
            echo "═══ 测试活码 (ID=${qr_id}) ═══"
            local active=$(mysql_q "SELECT COUNT(*) FROM qr_agent WHERE qr_code_id = ${qr_id} AND status = 'active';")
            local full=$(mysql_q "SELECT COUNT(*) FROM qr_agent WHERE qr_code_id = ${qr_id} AND status = 'full';")
            local rotate_total=$(mysql_q "SELECT COUNT(*) FROM qr_rotate_log WHERE qr_code_id = ${qr_id};")
            echo "  active : ${active}  |  full : ${full}  |  轮转次数 : ${rotate_total}"

            # 显示代理状态
            echo ""
            mysql_t "
                SELECT agent_userid, daily_current AS 今日, daily_max AS 日限,
                       CASE status WHEN 'active' THEN '🟢在线' WHEN 'full' THEN '🔴满员' ELSE status END AS 状态
                FROM qr_agent WHERE qr_code_id = ${qr_id}
                ORDER BY sort_order LIMIT 15;
            " 2>/dev/null
        fi

        # 全局池状态
        echo ""
        echo "═══ 全局池 ═══"
        mysql_t "SELECT status, COUNT(*) AS cnt FROM global_agent_pool GROUP BY status;" 2>/dev/null

        # 最近轮转
        echo ""
        echo "═══ 最近轮转 (5条) ═══"
        mysql_t "
            SELECT r.id, r.qr_code_id, r.to_userid, r.reason, r.created_at
            FROM qr_rotate_log r ORDER BY r.id DESC LIMIT 5;
        " 2>/dev/null

        echo ""
        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
        sleep "$interval"
    done
}

# ============================================================
# cleanup: 清理测试数据
# ============================================================
cmd_cleanup() {
    log_title "清理轮转压测数据"

    local qr_id=$(get_test_qr_id)
    if [ -z "$qr_id" ]; then
        log_info "无测试活码，无需清理"
        return
    fi

    log_warn "即将清理测试活码 ID=${qr_id} 及关联数据:"
    local agent_count=$(mysql_q "SELECT COUNT(*) FROM qr_agent WHERE qr_code_id = ${qr_id};")
    local rotate_count=$(mysql_q "SELECT COUNT(*) FROM qr_rotate_log WHERE qr_code_id = ${qr_id};")
    local customer_count=$(mysql_q "SELECT COUNT(*) FROM customer WHERE external_userid LIKE 'stress_ext_%';")

    echo "  - qr_agent: ${agent_count} 条"
    echo "  - qr_rotate_log: ${rotate_count} 条"
    echo "  - fake 客户: ${customer_count} 条"
    echo "  - qr_code 记录"

    # 恢复全局池中被标记为 full 的代理（重置为 standby）
    local full_agents=$(mysql_q "
        SELECT agent_userid FROM qr_agent
        WHERE qr_code_id = ${qr_id} AND status = 'full';
    ")
    for uid in $full_agents; do
        mysql_q "UPDATE global_agent_pool SET status = 'standby' WHERE agent_userid = '${uid}';"
        log_info "恢复池状态: ${uid} → standby"
    done

    # 删除关联数据
    mysql_q "DELETE FROM qr_rotate_log WHERE qr_code_id = ${qr_id};"
    mysql_q "DELETE FROM qr_agent WHERE qr_code_id = ${qr_id};"
    mysql_q "DELETE FROM qr_code WHERE id = ${qr_id};"
    mysql_q "DELETE FROM customer WHERE external_userid LIKE 'stress_ext_%';"

    log_info "✅ 清理完成"
}

# ============================================================
# report: 生成报告
# ============================================================
cmd_report() {
    local qr_id=$(get_test_qr_id)
    log_title "轮转压测报告"

    echo ""
    echo ">>> 轮转汇总:"
    mysql_t "
        SELECT
            r.qr_code_id,
            COUNT(*) AS 总轮转次数,
            MIN(r.created_at) AS 首次轮转,
            MAX(r.created_at) AS 末次轮转
        FROM qr_rotate_log r
        WHERE r.qr_code_id = ${qr_id:-0}
        GROUP BY r.qr_code_id;
    "

    echo ""
    echo ">>> 上码员工分布 (谁被从池中取上码):"
    mysql_t "
        SELECT r.to_userid, COUNT(*) AS 被取次数
        FROM qr_rotate_log r
        WHERE r.qr_code_id = ${qr_id:-0}
        GROUP BY r.to_userid
        ORDER BY 被取次数 DESC LIMIT 10;
    "

    echo ""
    echo ">>> 轮转时间线:"
    mysql_t "
        SELECT id, to_userid AS 上码员工, reason AS 原因, created_at
        FROM qr_rotate_log
        WHERE qr_code_id = ${qr_id:-0}
        ORDER BY id ASC;
    "

    # 检查轮转公平性：同一个员工不应被重复取（公平轮转生效）
    echo ""
    echo ">>> 公平性检查:"
    local duplicate=$(mysql_q "
        SELECT to_userid, COUNT(*) AS cnt
        FROM qr_rotate_log
        WHERE qr_code_id = ${qr_id:-0}
        GROUP BY to_userid HAVING cnt > 1;
    ")
    if [ -z "$duplicate" ]; then
        log_info "✅ 无重复上码 — 公平轮转生效"
    else
        log_warn "⚠️ 存在重复上码: ${duplicate}"
    fi
}

# ============================================================
# full: 完整流程
# ============================================================
cmd_full() {
    local agent_count=${ARG_N:-5}
    local events_per=${ARG_M:-6}

    echo "╔══════════════════════════════════════════════════════════╗"
    echo "║     活码人员轮转压力测试 (完整流程)                         ║"
    echo "║                                                          ║"
    echo "║     代理: ${agent_count}  日限: ${TEST_DAILY_MAX}  每代理事件: ${events_per}                  ║"
    echo "║     开始: $(date '+%Y-%m-%d %H:%M:%S')                               ║"
    echo "╚══════════════════════════════════════════════════════════╝"

    # Phase 1: Init
    cmd_init
    echo "" && sleep 2

    # Phase 2: Inject
    cmd_inject
    echo "" && sleep 2

    # Phase 3: Report
    cmd_report

    echo ""
    log_info "完整流程结束"
    log_info "测试数据保留，如需清理: bash rotation_stress.sh cleanup"
}

# ============================================================
# help
# ============================================================
cmd_help() {
    echo "╔══════════════════════════════════════════════════════════╗"
    echo "║     活码人员轮转压力测试                                    ║"
    echo "╚══════════════════════════════════════════════════════════╝"
    echo ""
    echo -e "${BOLD}命令:${NC}"
    echo "  init [N]      创建测试活码 + 绑定 N 个代理 (默认 5, dailyMax=5)"
    echo "  inject [M]    注入模拟回调事件 (M=每代理额外客户数, 默认 6)"
    echo "  watch [S]     实时监控轮转 (刷新秒, 默认 2)"
    echo "  report        生成轮转报告"
    echo "  cleanup       清理测试活码及全部关联数据"
    echo "  full [N] [M]  完整流程: init → inject → report"
    echo ""
    echo -e "${BOLD}链路:${NC}"
    echo "  注入 add_external_contact → CallbackWorker → incrementDailyCount"
    echo "  → checkAndRotate → expandQrCodeUsers → takeStandby"
    echo "  → afterCommit 同步企微 → 完成一次上下码"
    echo ""
    echo -e "${BOLD}示例:${NC}"
    echo "  bash rotation_stress.sh init 5       # 创建测试活码 + 5代理"
    echo "  bash rotation_stress.sh inject 6     # 每代理注入6客户 → 触发轮转"
    echo "  bash rotation_stress.sh watch        # 实时监控"
    echo "  bash rotation_stress.sh full 10 6    # L2级完整压测"
    echo "  bash rotation_stress.sh cleanup      # 清理"
    echo ""
    echo -e "${BOLD}级别参考:${NC}"
    echo "  L1 轻量: N=5   M=6   → 5代理×6事件=30 事件, ~5 次轮转"
    echo "  L2 中等: N=10  M=6   → 10代理×6事件=60 事件, ~10 次轮转"
    echo "  L3 重度: N=20  M=6   → 20代理×6事件=120 事件, ~20 次轮转"
}

# ============================================================
# 主入口
# ============================================================
case "$CMD" in
    init)    cmd_init ;;
    inject)  cmd_inject ;;
    watch)   cmd_watch ;;
    report)  cmd_report ;;
    cleanup) cmd_cleanup ;;
    full)    cmd_full ;;
    help|*)  cmd_help ;;
esac

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
