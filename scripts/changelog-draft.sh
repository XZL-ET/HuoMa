#!/bin/bash
# 版本更新页草稿：把尚无人整理的 feat/fix 提交，输出成可直接粘贴进
# src/main/resources/release-notes.yml 的骨架（标题和措辞仍需人工改）。
# 用法: ./scripts/changelog-draft.sh
set -euo pipefail
cd "$(dirname "$0")/.."

NOTES="src/main/resources/release-notes.yml"
LATEST=""
if [ -f "$NOTES" ]; then
    # 取已整理过的最晚日期（covers 区间取结束日）
    LATEST=$(grep -E '^[[:space:]]*(-[[:space:]]*)?(date|covers):' "$NOTES" \
        | sed -E 's/.*~[[:space:]]*//; s/^[^:]*:[[:space:]]*//' \
        | grep -E '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' | sort | tail -1 || true)
fi

raw=$(git log --no-merges --date=short --pretty=format:'%cd%x09%s' \
    | awk -F'\t' -v since="$LATEST" '$1 != "" && (since == "" || $1 > since)' \
    | grep -E $'\t(feat|fix)(\\([^)]*\\))?[:：]' || true)

if [ -z "$raw" ]; then
    echo "versions:"
    echo "  # 没有待整理的提交"
    exit 0
fi

printf '%s\n' "$raw" | awk -F'\t' '
    {
        date = $1
        msg = $0
        sub(/^[^\t]*\t/, "", msg)
        kind = (msg ~ /^feat/) ? "新增" : "修复"
        sub(/^(feat|fix)(\([^)]*\))?[:：][ ]*/, "", msg)
        # 生成的是双引号字符串，反斜杠和引号必须先转义
        gsub(/\\/, "\\\\", msg)
        gsub(/"/, "\\\"", msg)
        if (max == "") max = date
        min = date
        items[count++] = kind "\t" msg
    }
    END {
        print "versions:"
        print "  - date: " max
        print "    title: TODO 一句话概括本次上线"
        print "    covers: " min " ~ " max
        print "    items:"
        for (i = 0; i < count; i++) {
            split(items[i], pair, "\t")
            print "      - kind: " pair[1]
            print "        text: \"" pair[2] "\""
        }
    }'
