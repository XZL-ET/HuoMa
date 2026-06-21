#!/bin/bash
# ============================================================
# 活码人员上下码压力测试（安全版）
#
# 【安全设计】
#   - 所有写操作必须指定 --execute，否则只做只读分析
#   - 写操作自动创建专用测试活码 (__STRESS_TEST__)，不影响生产活码
#   - 测试完成后可用 --cleanup 清理
#
# 用法:
#   bash agent_bind_stress.sh <场景> [选项]
#
# 只读场景（安全，不影响任何数据）:
#   pool-scan      扫描全局池健康度（离职/不可用/封号占比）
#   rotation-audit 审计轮转公平性（池前30名的 sortOrder 分布）
#   bind-sim       模拟 bindAgents 选人过程（纯 SQL，不写入）
#   rotation-sim   轮转链路推演：模拟 N 轮上下码（纯 SQL）
#   qr-profile     分析活码人员构成（角色/日限/来源分布）
#   all-audit      依次执行上述所有只读分析
#
# 压测场景（需要 --execute，自动使用测试活码）:
#   http-add       串行添加代理，测单次延迟
#   http-add-para  并发添加代理，测事务竞争
#   http-remove    串行移除代理
#   http-mixed     混合负载（添加+移除交替）
#   bench-all      依次执行所有压测场景
#
# 管理命令:
#   --init         创建专用测试活码
#   --cleanup      清理测试活码及其所有关联数据
#   --status       查看测试活码状态
#
# 示例:
#   bash agent_bind_stress.sh pool-scan              # 只读：扫描池健康度
#   bash agent_bind_stress.sh rotation-audit          # 只读：审计轮转
#   bash agent_bind_stress.sh all-audit               # 只读：全部分析
#   bash agent_bind_stress.sh http-add --execute 20   # 压测：串行添加 20 人
#   bash agent_bind_stress.sh bench-all --execute     # 压测：全部场景
#   bash agent_bind_stress.sh --cleanup               # 清理
# ============================================================

set -e
set +H  # 禁用历史扩展，防止密码中 ! 被解析

# ============================================================
# 配置
# ============================================================
BASE_URL="http://localhost:8080"
COOKIE_JAR="/tmp/huoma_stress_cookies.txt"
RESULT_DIR="/tmp/huoma_stress_results"
TIMESTAMP=$(date '+%Y%m%d_%H%M%S')
TEST_SCHOOL_NAME="__STRESS_TEST__"
TEST_SCHOOL_ID="STRESS_TEST_000"

MYSQL_PASS="${MYSQL_PASS:-}"
MYSQL_USER="${MYSQL_USER:-bookstore}"
MYSQL_DB="${MYSQL_DB:-bookstore_qrcode}"

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
SCENARIO=""
EXECUTE=false
PARAM=""

for arg in "$@"; do
    case $arg in
        --execute) EXECUTE=true ;;
        --init)   SCENARIO="init" ;;
        --cleanup) SCENARIO="cleanup" ;;
        --status) SCENARIO="status" ;;
        *) [ -z "$SCENARIO" ] && SCENARIO="$arg" || PARAM="$arg" ;;
    esac
done

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

mysql_q() {
    mysql -u "$MYSQL_USER" -p"$MYSQL_PASS" "$MYSQL_DB" -N -e "$1" 2>/dev/null
}

mysql_table() {
    mysql -u "$MYSQL_USER" -p"$MYSQL_PASS" "$MYSQL_DB" -e "$1" 2>/dev/null
}

# 获取测试活码 ID
get_test_qr_id() {
    mysql_q "SELECT id FROM qr_code WHERE school_id = '${TEST_SCHOOL_ID}' AND status = 'active' LIMIT 1;"
}

# 获取生产活码 ID（排除测试活码）
get_prod_qr_ids() {
    mysql_q "SELECT id FROM qr_code WHERE school_id != '${TEST_SCHOOL_ID}' AND status = 'active' ORDER BY id DESC LIMIT 5;"
}

# HTTP 请求计时
time_curl() {
    local start_ns=$(date +%s%N)
    "$@" > /dev/null 2>&1
    local rc=$?
    local end_ns=$(date +%s%N)
    local elapsed_ms=$(( (end_ns - start_ns) / 1000000 ))
    echo "${elapsed_ms}|${rc}"
}

do_add_agent() {
    curl -s -o /dev/null -w "%{http_code}" \
        -X POST "${BASE_URL}/qrcodes/$1/agents" \
        -d "agentUserid=$2" \
        -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
        --connect-timeout 5 --max-time 30
}

do_remove_agent() {
    curl -s -o /dev/null -w "%{http_code}" \
        -X POST "${BASE_URL}/qrcodes/$1/agents/$2/remove" \
        -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
        --connect-timeout 5 --max-time 30
}

do_create_test_qr() {
    # 先从池取一个可用 userid 作为服务老师（企微 API 要求至少一个 user）
    local svc_userid=$(mysql_q "SELECT agent_userid FROM global_agent_pool WHERE status='standby' LIMIT 1;")
    curl -s -o /dev/null -w "%{http_code}" \
        -X POST "${BASE_URL}/qrcodes/create" \
        -d "schoolName=${TEST_SCHOOL_NAME}" \
        -d "schoolId=${TEST_SCHOOL_ID}" \
        -d "regionCity=测试市" \
        -d "regionDistrict=测试区" \
        -d "studentCount=100" \
        -d "initialAgentCount=1" \
        -d "serviceTeacherUserid=${svc_userid}" \
        -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
        --connect-timeout 10 --max-time 60
}

ensure_test_qr() {
    local qr_id=$(get_test_qr_id)
    if [ -z "$qr_id" ]; then
        log_warn "测试活码不存在，正在创建..."
        local http_code=$(do_create_test_qr)
        sleep 2
        qr_id=$(get_test_qr_id)
        if [ -z "$qr_id" ]; then
            log_error "创建测试活码失败 (HTTP ${http_code})"
            log_error "请检查应用是否正常运行: systemctl status huoma"
            exit 1
        fi
        log_info "测试活码已创建: ID=${qr_id}"
    else
        log_info "使用已有测试活码: ID=${qr_id}"
    fi
    echo "$qr_id"
}

# ============================================================
# 初始化/清理
# ============================================================
cmd_init() {
    log_title "创建测试活码"
    local existing=$(get_test_qr_id)
    if [ -n "$existing" ]; then
        log_info "测试活码已存在: ID=${existing}"
        log_info "如需重建请先执行 --cleanup"
        return
    fi
    local http_code=$(do_create_test_qr)
    sleep 2
    local qr_id=$(get_test_qr_id)
    if [ -n "$qr_id" ]; then
        log_info "✅ 测试活码创建成功: ID=${qr_id}"
        mysql_table "SELECT id, school_name, student_count, initial_agent_count, status FROM qr_code WHERE id = ${qr_id};"
    else
        log_error "❌ 创建失败 (HTTP ${http_code})"
    fi
}

cmd_cleanup() {
    log_title "清理测试活码"

    local qr_id=$(get_test_qr_id)
    if [ -z "$qr_id" ]; then
        log_info "没有测试活码，无需清理"
        return
    fi

    log_warn "即将清理测试活码 ID=${qr_id} 及其所有关联数据..."
    log_warn "这包括: qr_agent 记录、企微侧活码配置"

    # 1. 恢复 qr_agent 中被软删除的记录（改为 active，然后物理删除避免遗留）
    local agent_count=$(mysql_q "SELECT COUNT(*) FROM qr_agent WHERE qr_code_id = ${qr_id};")
    log_info "清理 ${agent_count} 条 qr_agent 记录..."
    mysql_q "DELETE FROM qr_agent WHERE qr_code_id = ${qr_id};"

    # 2. 删除（或标记删除）活码本身
    mysql_q "UPDATE qr_code SET status = 'no_agent' WHERE id = ${qr_id};"
    mysql_q "DELETE FROM qr_code WHERE id = ${qr_id};"

    log_info "✅ 清理完成"
}

cmd_status() {
    log_title "测试活码状态"
    local qr_id=$(get_test_qr_id)
    if [ -z "$qr_id" ]; then
        log_info "测试活码不存在，使用 --init 创建"
        return
    fi
    echo ""
    mysql_table "SELECT * FROM qr_code WHERE id = ${qr_id}\G"
    echo ""
    echo "关联联系人:"
    mysql_table "SELECT id, agent_userid, role, daily_max, daily_current, sort_order, status FROM qr_agent WHERE qr_code_id = ${qr_id} ORDER BY sort_order;"
}

# ============================================================
# 只读场景: 池健康度扫描
# ============================================================
scan_pool_health() {
    log_title "全局池健康度扫描"

    local total=$(mysql_q "SELECT COUNT(*) FROM global_agent_pool;")
    local standby=$(mysql_q "SELECT COUNT(*) FROM global_agent_pool WHERE status='standby';")
    local full=$(mysql_q "SELECT COUNT(*) FROM global_agent_pool WHERE status='full';")
    local blocked=$(mysql_q "SELECT COUNT(*) FROM global_agent_pool WHERE status='blocked';")

    # 池中不可用员工（离职/未激活/禁用/封号）
    local inactive=$(mysql_q "
        SELECT COUNT(*) FROM global_agent_pool p
        JOIN employee e ON e.userid = p.agent_userid
        WHERE e.active = 0;
    ")
    local wechat_bad=$(mysql_q "
        SELECT COUNT(*) FROM global_agent_pool p
        JOIN employee e ON e.userid = p.agent_userid
        WHERE e.wechat_status IS NOT NULL AND e.wechat_status != 1;
    ")
    local agent_blocked=$(mysql_q "
        SELECT COUNT(*) FROM global_agent_pool p
        JOIN agent a ON a.userid = p.agent_userid
        WHERE a.overall_status IN ('blocked', 'melted');
    ")

    # 企微状态明细
    local wx1=$(mysql_q "SELECT COUNT(*) FROM employee WHERE wechat_status = 1;")
    local wx2=$(mysql_q "SELECT COUNT(*) FROM employee WHERE wechat_status = 2;")
    local wx4=$(mysql_q "SELECT COUNT(*) FROM employee WHERE wechat_status = 4;")
    local wx5=$(mysql_q "SELECT COUNT(*) FROM employee WHERE wechat_status = 5;")
    local wx_null=$(mysql_q "SELECT COUNT(*) FROM employee WHERE wechat_status IS NULL;")

    echo ""
    log_info "池容量:"
    log_metric "总数"   "${total}"
    log_metric "standby" "${standby}"
    log_metric "full"    "${full}"
    log_metric "blocked" "${blocked}"

    echo ""
    log_info "池内隐患（将被懒清理）:"
    log_metric "离职 (active=0)"       "${inactive}"
    log_metric "企微不可用 (status≠1)" "${wechat_bad}"
    log_metric "封号/熔断"             "${agent_blocked}"

    echo ""
    log_info "全员企微状态分布:"
    log_metric "已激活 (1)"  "${wx1}"
    log_metric "禁用 (2)"    "${wx2}"
    log_metric "未激活 (4)"  "${wx4}"
    log_metric "已离职 (5)"  "${wx5}"
    log_metric "未同步"      "${wx_null}"

    local clean_ratio=0
    if [ "$total" -gt 0 ]; then
        clean_ratio=$(( (total - inactive - wechat_bad - agent_blocked) * 100 / total ))
    fi
    echo ""
    log_info "池可用率: ${clean_ratio}%"
    if [ "$clean_ratio" -lt 80 ]; then
        log_warn "⚠️ 可用率低于 80%，建议触发一次员工同步"
    fi
}

# ============================================================
# 只读场景: 轮转公平性审计
# ============================================================
audit_rotation() {
    log_title "轮转公平性审计"

    # 池前 30 名的 sortOrder 分布
    echo ""
    echo ">>> 全局池前 30 名（按 sortOrder 升序，值越小越优先）:"
    mysql_table "
        SELECT
            ROW_NUMBER() OVER (ORDER BY p.sort_order ASC) AS pos,
            p.agent_userid,
            p.sort_order,
            p.status,
            COALESCE(e.name, '?') AS name,
            e.wechat_status,
            CASE WHEN e.active = 0 THEN '⚠️离职' ELSE '' END AS flag
        FROM global_agent_pool p
        LEFT JOIN employee e ON e.userid = p.agent_userid
        WHERE p.status = 'standby'
        ORDER BY p.sort_order ASC
        LIMIT 30;
    "

    # sortOrder 分布统计
    local min_order=$(mysql_q "SELECT MIN(sort_order) FROM global_agent_pool WHERE status='standby';")
    local max_order=$(mysql_q "SELECT MAX(sort_order) FROM global_agent_pool WHERE status='standby';")
    local gap=$((max_order - min_order))
    local count=$(mysql_q "SELECT COUNT(*) FROM global_agent_pool WHERE status='standby';")
    local density=0
    [ "$gap" -gt 0 ] && density=$(( count * 100 / gap ))

    echo ""
    log_info "sortOrder 分布:"
    log_metric "范围" "${min_order} ~ ${max_order} (跨度 ${gap})"
    log_metric "人数" "${count}"
    log_metric "密度" "${density}% (100%=连续无空洞)"

    # 检查是否有员工卡在队首（可能被懒清理跳过）
    echo ""
    echo ">>> 队首 5 人详细状态:"
    local head5=$(mysql_q "SELECT agent_userid FROM global_agent_pool WHERE status='standby' ORDER BY sort_order ASC LIMIT 5;")
    for uid in $head5; do
        local info=$(mysql_q "
            SELECT CONCAT(
                COALESCE(e.name, '?'), ' | ',
                'active=', COALESCE(e.active, '?'), ' | ',
                'wx_status=', COALESCE(e.wechat_status, '?'), ' | ',
                'agent_status=', COALESCE(a.overall_status, '?')
            )
            FROM global_agent_pool p
            LEFT JOIN employee e ON e.userid = p.agent_userid
            LEFT JOIN agent a ON a.userid = p.agent_userid
            WHERE p.agent_userid = '${uid}';
        ")
        local sort=$(mysql_q "SELECT sort_order FROM global_agent_pool WHERE agent_userid='${uid}';")
        echo "  sort=${sort} | ${uid} | ${info}"
    done
}

# ============================================================
# 只读场景: 模拟 bindAgents 选人
# ============================================================
simulate_bind() {
    log_title "模拟 bindAgents 选人过程（纯 SQL，不写入）"

    local count=${PARAM:-10}
    local qr_id=$(get_prod_qr_ids | head -1)
    if [ -z "$qr_id" ]; then
        log_warn "无生产活码，跳过"
        return
    fi
    log_info "基于活码 ID=${qr_id} 模拟"

    # 活码上已有联系人的子查询
    local exclude_sql="SELECT a2.agent_userid FROM qr_agent a2 WHERE a2.qr_code_id = ${qr_id} AND a2.status = 'active'"

    echo ""
    echo ">>> 模拟 takeStandby 选 ${count} 人（已在活码上的排除）:"
    mysql_table "
        SELECT
            ROW_NUMBER() OVER (ORDER BY p.sort_order ASC) AS pick_order,
            p.agent_userid,
            p.sort_order,
            p.status,
            CASE WHEN e.active = 0 THEN '❌离职' WHEN e.wechat_status != 1 AND e.wechat_status IS NOT NULL THEN '❌企微不可用' WHEN a.overall_status IN ('blocked','melted') THEN '❌封号/熔断' ELSE '✅可用' END AS verdict
        FROM global_agent_pool p
        LEFT JOIN employee e ON e.userid = p.agent_userid
        LEFT JOIN agent a ON a.userid = p.agent_userid
        WHERE p.status = 'standby'
          AND p.agent_userid NOT IN (${exclude_sql})
        ORDER BY p.sort_order ASC
        LIMIT ${count};
    "

    # 统计会被跳过的
    local skip_inactive=$(mysql_q "
        SELECT COUNT(*) FROM global_agent_pool p
        JOIN employee e ON e.userid = p.agent_userid
        WHERE p.status = 'standby' AND e.active = 0
          AND p.agent_userid NOT IN (${exclude_sql})
        ORDER BY p.sort_order
        LIMIT ${count};
    ")
    local skip_wx=$(mysql_q "
        SELECT COUNT(*) FROM global_agent_pool p
        JOIN employee e ON e.userid = p.agent_userid
        WHERE p.status = 'standby' AND e.wechat_status IS NOT NULL AND e.wechat_status != 1
          AND p.agent_userid NOT IN (${exclude_sql})
        ORDER BY p.sort_order
        LIMIT ${count};
    ")

    echo ""
    log_info "在前 ${count} 名中会被懒清理跳过:"
    log_metric "离职"   "${skip_inactive:-0}"
    log_metric "企微不可用" "${skip_wx:-0}"
}

# ============================================================
# 只读场景: 轮转链路模拟
# ============================================================
simulate_rotation_chain() {
    local rounds=${PARAM:-10}
    log_title "轮转链路模拟（纯 SQL 推演 ${rounds} 轮上下码）"

    local qr_id=$(get_prod_qr_ids | head -1)
    if [ -z "$qr_id" ]; then
        log_warn "无生产活码，跳过"
        return
    fi

    local qr_name=$(mysql_q "SELECT school_name FROM qr_code WHERE id=${qr_id};")
    log_info "基于活码: ${qr_name} (ID=${qr_id})"
    log_info "模拟: 每轮取 1 人 → 满员 → 再取 1 人替换，共 ${rounds} 轮"

    # 取当前池的快照（按 sortOrder 排序，过滤不可用的）
    echo ""
    echo ">>> 全局池前 50 名健康度预览:"
    mysql_table "
        SELECT
            ROW_NUMBER() OVER (ORDER BY p.sort_order ASC) AS 队列位置,
            p.agent_userid AS 员工,
            p.sort_order AS 排序号,
            CASE WHEN e.wechat_status != 1 AND e.wechat_status IS NOT NULL
                 THEN CONCAT('❌ wx=', e.wechat_status)
                 WHEN a.overall_status IN ('blocked','melted')
                 THEN '❌ 封号/熔断'
                 ELSE '✅'
            END AS 健康状态,
            COALESCE(e.name, '?') AS 姓名
        FROM global_agent_pool p
        LEFT JOIN employee e ON e.userid = p.agent_userid
        LEFT JOIN agent a ON a.userid = p.agent_userid
        WHERE p.status = 'standby'
        ORDER BY p.sort_order ASC
        LIMIT 50;
    "

    # 模拟轮转链
    echo ""
    echo ">>> 轮转链路推演（每轮 = 1人满员 → 池取1人替换）:"
    echo ""

    local skip_count=0
    local take_count=0
    local prev_userid="(首个)"

    # 取池中前N个（含不可用的，以模拟真实遍历）
    local pool_snapshot=$(mysql_q "
        SELECT CONCAT(p.agent_userid, '|', p.sort_order, '|',
               COALESCE(e.name,'?'), '|',
               CASE WHEN e.wechat_status != 1 AND e.wechat_status IS NOT NULL THEN 'skip'
                    WHEN a.overall_status IN ('blocked','melted') THEN 'skip'
                    ELSE 'take' END)
        FROM global_agent_pool p
        LEFT JOIN employee e ON e.userid = p.agent_userid
        LEFT JOIN agent a ON a.userid = p.agent_userid
        WHERE p.status = 'standby'
        ORDER BY p.sort_order ASC;
    ")

    local round=0
    local skipped_list=""
    local taken_list=""

    while IFS='|' read -r uid sort name verdict; do
        [ -z "$uid" ] && continue

        if [ "$verdict" = "skip" ]; then
            skip_count=$((skip_count + 1))
            if [ $skip_count -le 5 ]; then
                echo "  ⏭️  跳过 #${skip_count}: ${uid}(${name}) sort=${sort} — 懒清理出池"
            fi
            if [ $skip_count -eq 6 ]; then
                echo "  ... (后续跳过省略，共 $(echo "$pool_snapshot" | grep -c 'skip$') 人将被懒清理)"
            fi
            continue
        fi

        # 这是一个可用的员工
        take_count=$((take_count + 1))
        round=$((round + 1))

        # 模拟：该员工被取走 → sortOrder 移至队尾
        local new_sort=$(( $(mysql_q "SELECT MAX(sort_order) FROM global_agent_pool;") + take_count ))

        if [ $round -le $rounds ]; then
            printf "  🔄 轮转 #%-2d: %s(%s) 上码 (sort=%s→%s) 替换 %s\n" \
                "$round" "$uid" "$name" "$sort" "$new_sort" "$prev_userid"
            prev_userid="${uid}(${name})"
        fi

        [ $round -ge $rounds ] && break
    done <<< "$pool_snapshot"

    echo ""
    log_info "推演结果:"

    local total_healthy=$(echo "$pool_snapshot" | grep -c 'take$')
    local total_skip=$(echo "$pool_snapshot" | grep -c 'skip$')

    log_metric "池中 standby 总数" "$(echo "$pool_snapshot" | wc -l)"
    log_metric "可用 (take)"   "${total_healthy}"
    log_metric "不可用 (skip)" "${total_skip}"
    log_metric "懒清理比例"   "$(( total_skip * 100 / (total_healthy + total_skip) )) %"
    log_metric "可支撑轮转"   "${total_healthy} 轮（${rounds} 轮模拟完成）"

    if [ "$total_skip" -gt 0 ]; then
        echo ""
        log_warn "队首有 ${total_skip} 个不可用员工会被懒清理，首次 takeStandby 需遍历跳过它们"
        log_info "建议: 触发员工同步清理企微侧不可用账号，减少无效遍历"
    fi

    if [ "$total_healthy" -lt "$((rounds * 2))" ]; then
        log_warn "⚠️ 可用员工不足支撑 ${rounds} 轮轮转的 2 倍余量"
    else
        log_info "✅ 池容量充足"
    fi
}

# ============================================================
# 只读场景: 活码人员构成分析
# ============================================================
analyze_qr_profile() {
    log_title "活码人员构成分析"

    for qr_id in $(get_prod_qr_ids); do
        local name=$(mysql_q "SELECT school_name FROM qr_code WHERE id=${qr_id};")
        local total=$(mysql_q "SELECT COUNT(*) FROM qr_agent WHERE qr_code_id=${qr_id} AND status='active';")
        local svc=$(mysql_q "SELECT COUNT(*) FROM qr_agent WHERE qr_code_id=${qr_id} AND status='active' AND role='service';")
        local rec=$(mysql_q "SELECT COUNT(*) FROM qr_agent WHERE qr_code_id=${qr_id} AND status='active' AND role='receptionist';")
        local dual=$(mysql_q "SELECT COUNT(*) FROM qr_agent WHERE qr_code_id=${qr_id} AND status='active' AND role='dual';")
        local removed=$(mysql_q "SELECT COUNT(*) FROM qr_agent WHERE qr_code_id=${qr_id} AND status='removed';")

        # 日限分布
        local dailymax_min=$(mysql_q "SELECT MIN(daily_max) FROM qr_agent WHERE qr_code_id=${qr_id} AND status='active';")
        local dailymax_max=$(mysql_q "SELECT MAX(daily_max) FROM qr_agent WHERE qr_code_id=${qr_id} AND status='active';")
        local dailymax_avg=$(mysql_q "SELECT ROUND(AVG(daily_max)) FROM qr_agent WHERE qr_code_id=${qr_id} AND status='active';")
        local full_count=$(mysql_q "SELECT COUNT(*) FROM qr_agent WHERE qr_code_id=${qr_id} AND status='full';")

        echo ""
        echo "━━━━━ ${name} (ID=${qr_id}) ━━━━━"
        log_metric "总活跃"   "${total} (服务:${svc} 接待:${rec} 双角色:${dual})"
        log_metric "已满"     "${full_count}"
        log_metric "已移除"   "${removed}"
        log_metric "日限范围" "${dailymax_min} ~ ${dailymax_max} (均值 ${dailymax_avg})"
    done
}

# ============================================================
# 压测场景（需要 --execute）
# ============================================================
require_execute() {
    if [ "$EXECUTE" != true ]; then
        echo ""
        log_error "此操作会修改数据，需要显式指定 --execute"
        echo ""
        echo "  示例: bash agent_bind_stress.sh http-add --execute 20"
        echo ""
        echo "  如果不确定影响范围，先运行只读分析:"
        echo "    bash agent_bind_stress.sh all-audit"
        exit 1
    fi
}

# HTTP 串行添加
test_http_add() {
    require_execute
    local count=${PARAM:-20}
    local qr_id=$(ensure_test_qr)
    log_title "HTTP 串行添加 (${count} 人 → 测试活码 ${qr_id})"

    # 从池中拿不在测试活码上的人
    local pool=$(mysql_q "
        SELECT p.agent_userid FROM global_agent_pool p
        WHERE p.status = 'standby'
          AND p.agent_userid NOT IN (
            SELECT a.agent_userid FROM qr_agent a
            WHERE a.qr_code_id = ${qr_id} AND a.status = 'active'
          )
        ORDER BY p.sort_order ASC LIMIT ${count};
    ")

    if [ -z "$pool" ]; then
        log_warn "池中可用代理不足 ${count}"
        count=$(echo "$pool" | wc -l)
        [ "$count" -eq 0 ] && { log_error "无可用代理"; return; }
    fi

    local total_ms=0 min_ms=999999 max_ms=0 success=0 fail=0
    local result_file="${RESULT_DIR}/http_add_${TIMESTAMP}.csv"
    echo "userid,latency_ms,http_code" > "$result_file"

    log_info "开始添加..."
    while IFS= read -r userid; do
        [ -z "$userid" ] && continue
        result=$(time_curl do_add_agent "$qr_id" "$userid")
        ms=$(echo "$result" | cut -d'|' -f1)
        http_code=$(echo "$result" | cut -d'|' -f2)

        echo "${userid},${ms},${http_code}" >> "$result_file"
        total_ms=$((total_ms + ms))
        [ "$ms" -lt "$min_ms" ] && min_ms=$ms
        [ "$ms" -gt "$max_ms" ] && max_ms=$ms

        if [ "$http_code" = "302" ]; then
            success=$((success + 1))
            printf "  ✅ %-20s %4dms\n" "$userid" "$ms"
        else
            fail=$((fail + 1))
            printf "  ❌ %-20s %4dms (HTTP %s)\n" "$userid" "$ms" "$http_code"
        fi
    done <<< "$pool"

    local n=$((success + fail))
    echo ""
    log_info "结果: ${success}/${n} 成功"
    echo "  平均: $(( total_ms / n ))ms  |  最小: ${min_ms}ms  |  最大: ${max_ms}ms"
    echo "  总耗时: ${total_ms}ms  |  QPS: $(( success * 1000 / (total_ms > 0 ? total_ms : 1) )) req/s"
}

# HTTP 并发添加
test_http_add_para() {
    require_execute
    local concurrency=${PARAM:-10}
    local qr_id=$(ensure_test_qr)
    log_title "HTTP 并发添加 (${concurrency} 并发 → 测试活码 ${qr_id})"

    local pool=$(mysql_q "
        SELECT p.agent_userid FROM global_agent_pool p
        WHERE p.status = 'standby'
          AND p.agent_userid NOT IN (
            SELECT a.agent_userid FROM qr_agent a
            WHERE a.qr_code_id = ${qr_id} AND a.status = 'active'
          )
        ORDER BY p.sort_order ASC LIMIT ${concurrency};
    ")

    if [ -z "$pool" ]; then
        log_warn "池中可用代理不足"
        return
    fi

    local start_ns=$(date +%s%N)
    local pids=()
    local result_file="${RESULT_DIR}/http_add_para_${TIMESTAMP}.csv"
    echo "userid,latency_ms,http_code" > "$result_file"

    while IFS= read -r userid; do
        [ -z "$userid" ] && continue
        (
            s_ns=$(date +%s%N)
            http_code=$(do_add_agent "$qr_id" "$userid")
            e_ns=$(date +%s%N)
            ms=$(( (e_ns - s_ns) / 1000000 ))
            echo "${userid},${ms},${http_code}" >> "$result_file"
        ) &
        pids+=($!)
    done <<< "$pool"

    log_info "等待 ${#pids[@]} 个并发请求..."
    for pid in "${pids[@]}"; do wait "$pid" 2>/dev/null; done

    local end_ns=$(date +%s%N)
    local total_ms=$(( (end_ns - start_ns) / 1000000 ))

    local success=$(grep -c ',302$' "$result_file" 2>/dev/null || echo 0)
    local total_lines=$(( $(wc -l < "$result_file") - 1 ))
    local fail=$(( total_lines - success ))

    echo ""
    log_info "并发添加结果 (${concurrency} 并发):"
    echo "  成功: ${success}  |  失败: ${fail}"
    echo "  总耗时: ${total_ms}ms  |  QPS: $(( success * 1000 / (total_ms > 0 ? total_ms : 1) )) req/s"

    # 延迟分布
    echo ""
    echo "  延迟分布:"
    awk -F',' 'NR>1 && $2 != "" {
        ms = $2 + 0
        sum += ms; count++
        if (ms < min || min == 0) min = ms
        if (ms > max) max = ms
        latencies[count] = ms
    }
    END {
        if (count > 0) {
            printf "    avg=%.0fms  min=%dms  max=%dms\n", sum/count, min, max
            asort(latencies)
            printf "    p50=%dms  p90=%dms  p99=%dms\n",
                latencies[int(count*0.5)], latencies[int(count*0.9)], latencies[int(count*0.99)]
        }
    }' "$result_file"

    if [ "$fail" -gt 0 ]; then
        log_warn "存在失败请求（可能事务冲突）:"
        grep -v ',302$' "$result_file" | head -5
    fi
}

# HTTP 串行移除
test_http_remove() {
    require_execute
    local count=${PARAM:-10}
    local qr_id=$(ensure_test_qr)
    log_title "HTTP 串行移除 (${count} 人 ← 测试活码 ${qr_id})"

    local agents=$(mysql_q "
        SELECT a.id, a.agent_userid FROM qr_agent a
        WHERE a.qr_code_id = ${qr_id} AND a.status = 'active' AND a.role != 'service'
        LIMIT ${count};
    ")

    if [ -z "$agents" ]; then
        log_warn "测试活码上没有可移除的接待员（先执行 http-add）"
        return
    fi

    local total_ms=0 min_ms=999999 max_ms=0 success=0 fail=0
    local result_file="${RESULT_DIR}/http_remove_${TIMESTAMP}.csv"
    echo "agent_id,userid,latency_ms,http_code" > "$result_file"

    while IFS=$'\t' read -r agent_id userid; do
        [ -z "$agent_id" ] && continue
        result=$(time_curl do_remove_agent "$qr_id" "$agent_id")
        ms=$(echo "$result" | cut -d'|' -f1)
        http_code=$(echo "$result" | cut -d'|' -f2)

        echo "${agent_id},${userid},${ms},${http_code}" >> "$result_file"
        total_ms=$((total_ms + ms))
        [ "$ms" -lt "$min_ms" ] && min_ms=$ms
        [ "$ms" -gt "$max_ms" ] && max_ms=$ms

        if [ "$http_code" = "302" ]; then
            success=$((success + 1))
            printf "  ✅ %-20s %4dms\n" "$userid" "$ms"
        else
            fail=$((fail + 1))
            printf "  ❌ %-20s %4dms (HTTP %s)\n" "$userid" "$ms" "$http_code"
        fi
    done <<< "$agents"

    local n=$((success + fail))
    echo ""
    log_info "结果: ${success}/${n} 成功"
    echo "  平均: $(( total_ms / n ))ms  |  最小: ${min_ms}ms  |  最大: ${max_ms}ms"
}

# HTTP 混合负载
test_http_mixed() {
    require_execute
    local rounds=${PARAM:-5}
    local qr_id=$(ensure_test_qr)
    log_title "HTTP 混合负载 (${rounds} 轮, 每轮 +2/-1 → 测试活码 ${qr_id})"

    local add_ok=0 add_fail=0 remove_ok=0 remove_fail=0

    for i in $(seq 1 $rounds); do
        echo "  ── 第 ${i}/${rounds} 轮 ──"

        # 添加 2 人
        local pool=$(mysql_q "
            SELECT p.agent_userid FROM global_agent_pool p
            WHERE p.status = 'standby'
              AND p.agent_userid NOT IN (
                SELECT a.agent_userid FROM qr_agent a
                WHERE a.qr_code_id = ${qr_id} AND a.status = 'active'
              )
            ORDER BY p.sort_order ASC LIMIT 2;
        ")
        while IFS= read -r userid; do
            [ -z "$userid" ] && continue
            http_code=$(do_add_agent "$qr_id" "$userid")
            if [ "$http_code" = "302" ]; then add_ok=$((add_ok + 1)); else add_fail=$((add_fail + 1)); fi
            echo "    + ${userid} → HTTP ${http_code}"
            sleep 0.2
        done <<< "$pool"

        # 移除 1 人
        local removable=$(mysql_q "
            SELECT a.id, a.agent_userid FROM qr_agent a
            WHERE a.qr_code_id = ${qr_id} AND a.status = 'active' AND a.role != 'service'
            LIMIT 1;
        ")
        if [ -n "$removable" ]; then
            local agent_id=$(echo "$removable" | awk '{print $1}')
            local rem_uid=$(echo "$removable" | awk '{print $2}')
            http_code=$(do_remove_agent "$qr_id" "$agent_id")
            if [ "$http_code" = "302" ]; then remove_ok=$((remove_ok + 1)); else remove_fail=$((remove_fail + 1)); fi
            echo "    - ${rem_uid} → HTTP ${http_code}"
        fi
        sleep 0.3
    done

    echo ""
    log_info "混合负载结果:"
    echo "  添加: ${add_ok} 成功 / ${add_fail} 失败"
    echo "  移除: ${remove_ok} 成功 / ${remove_fail} 失败"
}

# ============================================================
# 帮助
# ============================================================
show_help() {
    echo "╔══════════════════════════════════════════════════════════╗"
    echo "║     活码人员上下码压力测试（安全版）                         ║"
    echo "╚══════════════════════════════════════════════════════════╝"
    echo ""
    echo -e "${BOLD}只读分析（安全，不影响任何数据）:${NC}"
    echo "  pool-scan       扫描全局池健康度（离职/不可用/封号占比）"
    echo "  rotation-audit  审计轮转公平性（池前30名的 sortOrder 分布）"
    echo "  bind-sim        模拟 bindAgents 选人过程（纯 SQL）"
    echo "  rotation-sim [N] 轮转链路推演 N 轮上下码（默认 10）"
    echo "  qr-profile      分析活码人员构成（角色/日限/来源分布）"
    echo "  all-audit       依次执行上述所有只读分析"
    echo ""
    echo -e "${BOLD}压测场景（需要 --execute，自动使用测试活码）:${NC}"
    echo "  http-add [N]    串行添加 N 个代理 (默认 20)"
    echo "  http-add-para [N] 并发添加 N 个代理 (默认 10)"
    echo "  http-remove [N] 串行移除 N 个代理 (默认 10)"
    echo "  http-mixed [N]  混合负载 N 轮 (默认 5)"
    echo "  bench-all       依次执行所有压测场景"
    echo ""
    echo -e "${BOLD}管理命令:${NC}"
    echo "  --init          创建专用测试活码"
    echo "  --cleanup       清理测试活码及其所有关联数据"
    echo "  --status        查看测试活码状态"
    echo ""
    echo -e "${BOLD}示例:${NC}"
    echo "  bash agent_bind_stress.sh pool-scan"
    echo "  bash agent_bind_stress.sh all-audit"
    echo "  bash agent_bind_stress.sh http-add --execute 20"
    echo "  bash agent_bind_stress.sh bench-all --execute"
}

# ============================================================
# 主入口
# ============================================================

echo "╔══════════════════════════════════════════════════════════╗"
echo "║     活码人员上下码压力测试                                  ║"
echo "║     时间: $(date '+%Y-%m-%d %H:%M:%S')                               ║"
echo "╚══════════════════════════════════════════════════════════╝"

case ${SCENARIO} in
    # 管理命令
    init)       cmd_init; exit 0 ;;
    cleanup)    cmd_cleanup; exit 0 ;;
    status)     cmd_status; exit 0 ;;

    # 只读分析
    rotation-sim)   simulate_rotation_chain ;;
    pool-scan)      scan_pool_health ;;
    rotation-audit) audit_rotation ;;
    bind-sim)       simulate_bind ;;
    qr-profile)     analyze_qr_profile ;;
    all-audit)
        scan_pool_health
        echo "" && sleep 1
        audit_rotation
        echo "" && sleep 1
        simulate_bind
        echo "" && sleep 1
        simulate_rotation_chain
        echo "" && sleep 1
        analyze_qr_profile
        ;;

    # 压测（需要 --execute）
    http-add)       test_http_add ;;
    http-add-para)  test_http_add_para ;;
    http-remove)    test_http_remove ;;
    http-mixed)     test_http_mixed ;;
    bench-all)
        require_execute
        test_http_add
        echo "" && sleep 1
        test_http_add_para
        echo "" && sleep 1
        test_http_remove
        echo "" && sleep 1
        test_http_mixed
        ;;

    *) show_help; exit 1 ;;
esac

echo ""
log_info "完成。详细结果: ${RESULT_DIR}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
