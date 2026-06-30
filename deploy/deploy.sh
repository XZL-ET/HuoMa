#!/bin/bash
# 火马平台 双机热备部署脚本
# 用法: ./deploy.sh prod [--ecs2-ip=10.0.0.x]
#
# 环境变量:
#   DEPLOY_SERVER_IP    ECS-1 公网 IP（NAT 网关 EIP，必填）
#   DEPLOY_ECS2_IP      ECS-2 内网 IP（可选，部署到两台）
#   SSHPASS             ECS 密码（可选，设置后全程无需输入）

set -e

PROFILE="prod"
ECS2_IP="${DEPLOY_ECS2_IP}"

# 解析参数
for arg in "$@"; do
    case $arg in
        --ecs2-ip=*) ECS2_IP="${arg#*=}" ;;
        stg|staging) PROFILE="staging" ;;
    esac
done

SERVER_IP="${DEPLOY_SERVER_IP:?请设置 DEPLOY_SERVER_IP 环境变量（NAT 网关 EIP）}"
SERVER_USER="root"
APP_DIR="/opt/HuoMa"
JAR_NAME="bookstore-qrcode-0.1.0.jar"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ── SSH 认证 ──
SSH_OPTS="-o StrictHostKeyChecking=accept-new -o ConnectTimeout=10 -o NumberOfPasswordPrompts=1"
CONTROL_PATH="/tmp/huoma-deploy-$$"

echo "🔑 建立 SSH 连接..."

if [ -n "${SSHPASS}" ] && command -v sshpass &>/dev/null; then
    # sshpass 模式：每条命令独立认证，不用 scp（Git Bash 下会卡死）
    USE_SSHPASS=true
    SSH_CMD="sshpass -e ssh ${SSH_OPTS}"
    upload() {
        local src=$1 dst=$2
        echo "  📤 $(basename "${src}")"
        ${SSH_CMD} "${SERVER_USER}@${SERVER_IP}" "cat > ${dst}" < "${src}"
    }
    if sshpass -e ssh -o ConnectTimeout=5 -o StrictHostKeyChecking=accept-new "${SERVER_USER}@${SERVER_IP}" "echo ok" >/dev/null 2>&1; then
        echo "  ✅ 连通性正常 (sshpass)"
    else
        echo "  ❌ 连通或认证失败"
        exit 1
    fi
elif ssh -o ConnectTimeout=5 -o StrictHostKeyChecking=accept-new -o BatchMode=yes "${SERVER_USER}@${SERVER_IP}" "echo ok" >/dev/null 2>&1; then
    # 密钥免密模式（不用 scp——Git Bash 下会卡死）
    USE_SSHPASS=false
    SSH_CMD="ssh ${SSH_OPTS}"
    upload() {
        local src=$1 dst=$2
        echo "  📤 $(basename "${src}")"
        ${SSH_CMD} "${SERVER_USER}@${SERVER_IP}" "cat > ${dst}" < "${src}"
    }
    echo "  ✅ 密钥认证"
else
    # 手动输密码 + ControlMaster 复用（不用 scp——Git Bash 下会卡死）
    USE_SSHPASS=false
    SSH_CTL="-o ControlMaster=auto -o ControlPath=${CONTROL_PATH} -o ControlPersist=300"
    SSH_CMD="ssh ${SSH_OPTS} ${SSH_CTL}"
    upload() {
        local src=$1 dst=$2
        echo "  📤 $(basename "${src}")"
        ${SSH_CMD} "${SERVER_USER}@${SERVER_IP}" "cat > ${dst}" < "${src}"
    }
    cleanup() {
        ssh ${SSH_OPTS} ${SSH_CTL} -O exit "${SERVER_USER}@${SERVER_IP}" 2>/dev/null || true
        rm -f "${CONTROL_PATH}"
    }
    trap cleanup EXIT
    echo "  ⚠️  需要输入密码..."
    ssh ${SSH_OPTS} ${SSH_CTL} -MNf "${SERVER_USER}@${SERVER_IP}" && \
        echo "  ✅ 主连接已建立" || \
        echo "  ⚠️  后续命令需各自认证"
fi

echo "========================================="
echo " 火马平台 双机热备部署"
echo " 环境: ${PROFILE} | ECS-1: ${SERVER_IP}"
[ -n "$ECS2_IP" ] && echo " ECS-2: ${ECS2_IP} (内网)"
echo "========================================="

# =============================================
# [1/6] 本地编译
# =============================================
echo ""
echo "[1/6] 编译项目..."
cd "${SCRIPT_DIR}/.."
./mvnw clean package -DskipTests
echo "  ✅ 编译完成"

# =============================================
# [2/6] 上传配置文件到 ECS-1
# =============================================
echo ""
echo "[2/6] 上传配置文件到 ECS-1..."

upload "${SCRIPT_DIR}/bookstore-qrcode.service" "/etc/systemd/system/huoma.service"
upload "${SCRIPT_DIR}/nginx.conf" "/etc/nginx/conf.d/huoma.conf"

# huoma.env — 仅首次创建，不覆盖已有配置
${SSH_CMD} "${SERVER_USER}@${SERVER_IP}" "
    if [ ! -f /etc/systemd/system/huoma.env ]; then
        echo '>>> 首次部署：创建 huoma.env 模板，请编辑填入真实凭据'
        cp /dev/null /etc/systemd/system/huoma.env
        echo '# 请编辑此文件，填入真实凭据后重试部署' >> /etc/systemd/system/huoma.env
        echo 'SCHOOL_ENTRY_URL=https://huoma.gsxhsd.com/s' >> /etc/systemd/system/huoma.env
        echo ''
        echo '========================================='
        echo ' ⚠️  检测到首次部署！'
        echo ' 请编辑 ECS-1 上的环境变量文件：'
        echo "   vim /etc/systemd/system/huoma.env"
        echo ' 参考模板: deploy/huoma.env.template'
        echo ' 填好后再重新运行 deploy.sh'
        echo '========================================='
        exit 1
    fi
"

${SSH_CMD} "${SERVER_USER}@${SERVER_IP}" "systemctl daemon-reload"
echo "  ✅ 配置文件已同步"

# =============================================
# [3/6] 停止 ECS-1
# =============================================
echo ""
echo "[3/6] 停止 ECS-1 服务..."
${SSH_CMD} "${SERVER_USER}@${SERVER_IP}" "systemctl stop huoma || true"
echo "  ✅ ECS-1 已停止"

# =============================================
# [4/6] 上传 JAR 到 ECS-1
# =============================================
echo ""
echo "[4/6] 上传 JAR 到 ECS-1..."
${SSH_CMD} "${SERVER_USER}@${SERVER_IP}" "mkdir -p ${APP_DIR}"
upload "target/${JAR_NAME}" "${APP_DIR}/app.jar"
echo "  ✅ 上传完成"

# =============================================
# [5/6] 重启 ECS-1 + 同步 ECS-2
# =============================================
echo ""
echo "[5/6] 重启 ECS-1 + 同步 ECS-2..."

# ECS-2 连接前缀（ECS-1 上执行）
ECS2_SSH_PREFIX=""
if [ -n "${ECS2_IP}" ] && ${USE_SSHPASS}; then
    ECS2_SSH_PREFIX="SSHPASS='${SSHPASS}' sshpass -e"
fi

${SSH_CMD} "${SERVER_USER}@${SERVER_IP}" << ENDSSH
set -e

chown huoma:huoma ${APP_DIR}/app.jar

# 重启 ECS-1
systemctl restart huoma

# --- ECS-2 ---
if [ -n "${ECS2_IP}" ]; then
    echo "  → 同步到 ECS-2 (${ECS2_IP})..."

    ${ECS2_SSH_PREFIX} ssh -o StrictHostKeyChecking=no -o ConnectTimeout=5 root@${ECS2_IP} "systemctl stop huoma || true"

    cat ${APP_DIR}/app.jar | ${ECS2_SSH_PREFIX} ssh -o StrictHostKeyChecking=no root@${ECS2_IP} "cat > ${APP_DIR}/app.jar"

    cat /etc/systemd/system/huoma.service | ${ECS2_SSH_PREFIX} ssh -o StrictHostKeyChecking=no root@${ECS2_IP} "cat > /etc/systemd/system/huoma.service"
    cat /etc/systemd/system/huoma.env    | ${ECS2_SSH_PREFIX} ssh -o StrictHostKeyChecking=no root@${ECS2_IP} "cat > /etc/systemd/system/huoma.env"
    cat /etc/nginx/conf.d/huoma.conf     | ${ECS2_SSH_PREFIX} ssh -o StrictHostKeyChecking=no root@${ECS2_IP} "cat > /etc/nginx/conf.d/huoma.conf"

    ${ECS2_SSH_PREFIX} ssh -o StrictHostKeyChecking=no root@${ECS2_IP} "
        chown huoma:huoma ${APP_DIR}/app.jar
        systemctl daemon-reload
        systemctl restart huoma
    "
    echo "  ✅ ECS-2 已重启"
fi
ENDSSH

echo "  ✅ 服务已重启"

# =============================================
# [6/6] 健康检查（轮询等待启动完成）
# =============================================
echo ""
echo "[6/6] 等待服务启动..."

check_health() {
    local host=$1
    local http_code
    http_code=$(${SSH_CMD} "${SERVER_USER}@${host}" \
        "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health" 2>/dev/null || echo "000")
    echo "$http_code"
}

for i in $(seq 1 30); do
    STATUS1=$(check_health "${SERVER_IP}")
    if [ "$STATUS1" = "200" ]; then
        echo "  ✅ ECS-1 健康 (${i}x2s)"
        break
    fi
    printf "  ⏳ 等待 ECS-1 启动... (%d/30)\r" "$i"
    sleep 2
done

if [ "$STATUS1" != "200" ]; then
    echo ""
    echo "  ⚠️  ECS-1 健康检查失败 (HTTP ${STATUS1})"
    echo "  查看日志: ssh root@${SERVER_IP} journalctl -u huoma -n 50"
fi

if [ -n "${ECS2_IP}" ]; then
    for i in $(seq 1 30); do
        STATUS2=$(${SSH_CMD} "${SERVER_USER}@${SERVER_IP}" \
            "${ECS2_SSH_PREFIX} ssh -o StrictHostKeyChecking=no -o ConnectTimeout=5 root@${ECS2_IP} \"curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health\"" 2>/dev/null || echo "000")
        if [ "$STATUS2" = "200" ]; then
            echo "  ✅ ECS-2 健康 (${i}x2s)"
            break
        fi
        printf "  ⏳ 等待 ECS-2 启动... (%d/30)\r" "$i"
        sleep 2
    done

    if [ "$STATUS2" != "200" ]; then
        echo ""
        echo "  ⚠️  ECS-2 健康检查失败 (HTTP ${STATUS2})"
        echo "  查看日志: ssh root@${ECS2_IP} journalctl -u huoma -n 50"
    fi
fi

echo ""
echo "========================================="
echo " 部署完成！"
echo " 管理后台: https://huoma.gsxhsd.com/"
[ -n "$ECS2_IP" ] && echo " ECS-2 后台: http://${ECS2_IP}/"
echo "========================================="
