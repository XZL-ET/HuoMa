#!/bin/bash
# ============================================================
# 漏转客户补偿脚本：对指定活码逐个调用「全量补转」接口
# ============================================================
# 背景：排查出 891 个历史客户「接待员已接待、但从未转移到服务老师」。
#   修复通过应用侧 POST /qrcodes/{id}/transfer/backfill 完成，该接口把
#   接待员名下「since 之后添加的 active 客户」XADD 进转移流，由
#   TransferService.initiate 去重：已有 pending_confirm/confirmed 记录的
#   客户自动跳过，幂等安全。
#
# 用法：
#   1) 先用 data-repair-20260920-missed-transfer-backfill.sql 的 Step 1
#      取出需补转的 qr_code_id 列表，写入一个文件（每行一个 id）。
#   2) 从浏览器 DevTools 复制已登录管理后台会话的 JSESSIONID，通过
#      COOKIE 传入；BASE_URL 传管理后台地址。
#   3) 执行：
#        BASE_URL=http://<域名或IP> \
#        COOKIE='JSESSIONID=xxxx' \
#        bash scripts/backfill-missed-transfer.sh qr_ids.txt
#
#   可选限速（默认每 10 个活码停顿 3 秒）：
#        THROTTLE_BATCH=20 THROTTLE_DELAY=5 bash ...   # 每 20 个停顿 5 秒
#        THROTTLE_DELAY=0 bash ...                      # 关闭限速
#
#   可选窗口起点（默认 2026-08-21，与 SQL 30 天下界对齐）：
#        SINCE=2026-08-21 bash ...
#
# 注意：
#   * 返回 {"error":"该活码未配置服务老师"} 的活码会计入失败，需先配好服务老师再重跑。
#   * 请先在测试/低峰期小范围验证，再全量执行。
# ============================================================

set -u

BASE_URL="${BASE_URL:-http://localhost:8080}"
COOKIE="${COOKIE:-}"
IDS_FILE="${1:-qr_ids.txt}"

# 限速：每处理 THROTTLE_BATCH 个活码后暂停 THROTTLE_DELAY 秒，
# 避免一次性 XADD 数万条事件给 TransferWorker / 企微 API 造成瞬时压力。
THROTTLE_BATCH="${THROTTLE_BATCH:-10}"
THROTTLE_DELAY="${THROTTLE_DELAY:-3}"
# 设为 0 可完全关闭限速
if [ "$THROTTLE_DELAY" = "0" ]; then
    THROTTLE_BATCH=0
fi

# 补偿窗口起点（yyyy-MM-dd），与 data-repair SQL 的 30 天下界对齐。
# 接口只补转 since 之后添加的 active 客户，避免把 30 天前的历史客户一并补转。
SINCE="${SINCE:-2026-08-21}"

if [ ! -f "$IDS_FILE" ]; then
    echo "错误: 找不到活码 ID 清单文件: $IDS_FILE" >&2
    echo "用法: BASE_URL=... COOKIE='JSESSIONID=...' $0 qr_ids.txt" >&2
    exit 2
fi
if [ -z "$COOKIE" ]; then
    echo "错误: 缺少 COOKIE 环境变量（已登录会话的 JSESSIONID）" >&2
    exit 2
fi

total=0
failed=0
while IFS= read -r id; do
    id="$(echo "$id" | tr -d '[:space:]')"
    [ -z "$id" ] && continue
    # 跳过注释行
    case "$id" in \#*) continue ;; esac

    resp="$(curl -sS -X POST -H "Cookie: ${COOKIE}" \
        "${BASE_URL}/qrcodes/${id}/transfer/backfill?since=${SINCE}" 2>&1)" || {
        echo "qr_code_id=${id} -> 请求失败: ${resp}"
        failed=$((failed+1))
        continue
    }
    # 接口返回 error（如「未配置服务老师」）时计入失败，便于执行后人工复核
    if echo "$resp" | grep -q '"error"'; then
        echo "qr_code_id=${id} -> ${resp}"
        failed=$((failed+1))
        continue
    fi
    echo "qr_code_id=${id} -> ${resp}"
    total=$((total+1))
    # 限速：每 N 个活码停顿一次
    if [ "$THROTTLE_BATCH" -gt 0 ] && [ $((total % THROTTLE_BATCH)) -eq 0 ]; then
        echo "  ... 已处理 ${total} 个，暂停 ${THROTTLE_DELAY} 秒 ..."
        sleep "$THROTTLE_DELAY"
    fi
done < "$IDS_FILE"

echo "完成：共成功触发 ${total} 个活码，失败 ${failed} 个。"
