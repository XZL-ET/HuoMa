#!/bin/bash
# 火马平台 双机热备部署脚本
# 用法: ./deploy.sh prod [--ecs2-ip=10.0.0.x]
#
# 环境变量:
#   DEPLOY_SERVER_IP    ECS-1 公网 IP（NAT 网关 EIP，必填）
#   DEPLOY_ECS2_IP      ECS-2 内网 IP（可选，部署到两台）

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

# systemd service（每次都同步）
scp "${SCRIPT_DIR}/bookstore-qrcode.service" \
    "${SERVER_USER}@${SERVER_IP}:/etc/systemd/system/huoma.service"

# nginx 配置（每次都同步）
scp "${SCRIPT_DIR}/nginx.conf" \
    "${SERVER_USER}@${SERVER_IP}:/etc/nginx/conf.d/huoma.conf"

# huoma.env — 仅首次创建，不覆盖已有配置
ssh "${SERVER_USER}@${SERVER_IP}" "
    if [ ! -f /etc/systemd/system/huoma.env ]; then
        echo '>>> 首次部署：创建 huoma.env 模板，请编辑填入真实凭据'
        cp /dev/null /etc/systemd/system/huoma.env
        echo '# 请编辑此文件，填入真实凭据后重试部署' >> /etc/systemd/system/huoma.env
        echo 'SCHOOL_ENTRY_URL=http://${SERVER_IP}/s' >> /etc/systemd/system/huoma.env
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

# 重新加载 systemd（service 文件可能已更新）
ssh "${SERVER_USER}@${SERVER_IP}" "systemctl daemon-reload"
echo "  ✅ 配置文件已同步"

# =============================================
# [3/6] 停止 ECS-1
# =============================================
echo ""
echo "[3/6] 停止 ECS-1 服务..."
ssh "${SERVER_USER}@${SERVER_IP}" "systemctl stop huoma || true"
echo "  ✅ ECS-1 已停止"

# =============================================
# [4/6] 上传 JAR 到 ECS-1
# =============================================
echo ""
echo "[4/6] 上传 JAR 到 ECS-1..."
ssh "${SERVER_USER}@${SERVER_IP}" "mkdir -p ${APP_DIR}"
scp "target/${JAR_NAME}" "${SERVER_USER}@${SERVER_IP}:${APP_DIR}/app.jar"
echo "  ✅ 上传完成"

# =============================================
# [5/6] 重启 ECS-1 + 同步 ECS-2
# =============================================
echo ""
echo "[5/6] 重启 ECS-1 + 同步 ECS-2..."

ssh "${SERVER_USER}@${SERVER_IP}" << ENDSSH
set -e

chown huoma:huoma ${APP_DIR}/app.jar

# 重启 ECS-1
systemctl restart huoma

# --- ECS-2 ---
if [ -n "${ECS2_IP}" ]; then
    echo "  → 同步到 ECS-2 (${ECS2_IP})..."

    # 停 ECS-2
    ssh -o StrictHostKeyChecking=no -o ConnectTimeout=5 root@${ECS2_IP} "systemctl stop huoma || true"

    # 传 JAR
    scp -o StrictHostKeyChecking=no ${APP_DIR}/app.jar root@${ECS2_IP}:${APP_DIR}/

    # 传配置
    scp -o StrictHostKeyChecking=no /etc/systemd/system/huoma.service root@${ECS2_IP}:/etc/systemd/system/
    scp -o StrictHostKeyChecking=no /etc/systemd/system/huoma.env    root@${ECS2_IP}:/etc/systemd/system/
    scp -o StrictHostKeyChecking=no /etc/nginx/conf.d/huoma.conf     root@${ECS2_IP}:/etc/nginx/conf.d/

    # 启 ECS-2
    ssh -o StrictHostKeyChecking=no root@${ECS2_IP} "
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
    local label=$2
    local http_code
    http_code=$(ssh "${SERVER_USER}@${host}" \
        "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health" 2>/dev/null || echo "000")
    echo "$http_code"
}

# 轮询 ECS-1（最多等 60 秒）
for i in $(seq 1 30); do
    STATUS1=$(check_health "${SERVER_IP}" "ECS-1")
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

# ECS-2 健康检查
if [ -n "${ECS2_IP}" ]; then
    for i in $(seq 1 30); do
        STATUS2=$(ssh "${SERVER_USER}@${SERVER_IP}" \
            "ssh -o ConnectTimeout=5 root@${ECS2_IP} \"curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health\"" 2>/dev/null || echo "000")
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
echo " 管理后台: http://${SERVER_IP}/"
[ -n "$ECS2_IP" ] && echo " ECS-2 后台: http://${ECS2_IP}/"
echo "========================================="
