#!/bin/bash
# SSH 上传诊断脚本 — 逐项测试找出卡死点
set -e

SERVER_IP="${DEPLOY_SERVER_IP:?请设置 DEPLOY_SERVER_IP}"
SERVER_USER="root"
SRC_FILE="deploy/bookstore-qrcode.service"
SSH_BASE="ssh -o StrictHostKeyChecking=accept-new -o ConnectTimeout=10"

echo "========================================="
echo " SSH 上传诊断"
echo " 目标: ${SERVER_USER}@${SERVER_IP}"
echo " 测试文件: ${SRC_FILE}"
echo "========================================="

# [Test 1] 确定认证方式
echo ""
echo "[1/6] 基础 SSH 连接..."
if ssh -o ConnectTimeout=10 -o StrictHostKeyChecking=accept-new -o BatchMode=yes "${SERVER_USER}@${SERVER_IP}" "echo ok" 2>&1; then
    echo "  ✅ 密钥认证可用"
    SSH_CMD="${SSH_BASE}"
elif [ -n "${SSHPASS}" ] && command -v sshpass &>/dev/null; then
    if sshpass -e ssh -o ConnectTimeout=10 -o StrictHostKeyChecking=accept-new "${SERVER_USER}@${SERVER_IP}" "echo ok" 2>&1; then
        echo "  ✅ sshpass 认证可用"
        SSH_CMD="sshpass -e ${SSH_BASE}"
    else
        echo "  ❌ sshpass 认证失败"
        exit 1
    fi
else
    echo "  ❌ 无可用认证方式（设置 SSHPASS 或配置密钥）"
    exit 1
fi

# [Test 2] ssh cat pipe — 小文本管道
echo ""
echo "[2/6] ssh cat pipe (小文本, 管道)..."
echo "hello test" | ${SSH_CMD} "${SERVER_USER}@${SERVER_IP}" "cat > /tmp/huoma-diag.txt" 2>&1 && \
    echo "  ✅ 管道传输成功" || echo "  ❌ 失败"

# [Test 3] ssh cat pipe — 文件重定向
echo ""
echo "[3/6] ssh cat pipe (文件重定向 <)..."
echo "=== test content ===" > /tmp/huoma-test-src.txt
${SSH_CMD} "${SERVER_USER}@${SERVER_IP}" "cat > /tmp/huoma-diag2.txt" < /tmp/huoma-test-src.txt 2>&1 && \
    echo "  ✅ 文件重定向成功" || echo "  ❌ 失败"

# [Test 4] ssh cat pipe — 实际部署文件
echo ""
echo "[4/6] ssh cat pipe (${SRC_FILE}, $(wc -c < "${SRC_FILE}") bytes)..."
echo "  ⏳ 运行中（卡住超过 10 秒请 Ctrl+C）..."
${SSH_CMD} "${SERVER_USER}@${SERVER_IP}" "cat > /tmp/huoma-diag3.txt" < "${SRC_FILE}" 2>&1 && \
    echo "  ✅ 实际文件传输成功" || echo "  ❌ 失败"

# [Test 5] ssh + base64 编码（绕过可能的 PTY/二进制问题）
echo ""
echo "[5/6] ssh cat pipe (base64 编码)..."
echo "  ⏳ 运行中..."
cat "${SRC_FILE}" | base64 | ${SSH_CMD} "${SERVER_USER}@${SERVER_IP}" "base64 -d > /tmp/huoma-diag4.txt" 2>&1 && \
    echo "  ✅ base64 传输成功" || echo "  ❌ 失败"

# [Test 6] 远程校验
echo ""
echo "[6/6] 远程文件校验..."
${SSH_CMD} "${SERVER_USER}@${SERVER_IP}" "
    for f in /tmp/huoma-diag.txt /tmp/huoma-diag2.txt /tmp/huoma-diag3.txt /tmp/huoma-diag4.txt; do
        [ -f \"\$f\" ] && echo \"  \$f: \$(wc -c < \$f) bytes\" || echo \"  \$f: MISSING\"
    done
    rm -f /tmp/huoma-diag*.txt /tmp/huoma-test-src.txt
"

echo ""
echo "========================================="
echo " 诊断完成"
echo "========================================="
