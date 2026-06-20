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

echo "========================================="
echo " 火马平台 双机热备部署"
echo " 环境: ${PROFILE} | ECS-1: ${SERVER_IP}"
[ -n "$ECS2_IP" ] && echo " ECS-2: ${ECS2_IP} (内网)"
echo "========================================="

# 1. 本地编译
echo ""
echo "[1/5] 编译项目..."
cd "$(dirname "$0")/.."
./mvnw clean package -DskipTests 2>&1 | tail -5
echo "  ✅ 编译完成"

# 2. 停止 ECS-1
echo ""
echo "[2/5] 停止 ECS-1 服务..."
ssh "${SERVER_USER}@${SERVER_IP}" "systemctl stop huoma || true"
echo "  ✅ ECS-1 已停止"

# 3. 上传到 ECS-1
echo ""
echo "[3/5] 上传 JAR 到 ECS-1..."
ssh "${SERVER_USER}@${SERVER_IP}" "mkdir -p ${APP_DIR}"
scp "target/${JAR_NAME}" "${SERVER_USER}@${SERVER_IP}:${APP_DIR}/app.jar"
echo "  ✅ 上传完成"

# 4. ECS-1 重启 + 分发到 ECS-2
echo ""
echo "[4/5] 重启 ECS-1 + 同步 ECS-2..."

ssh "${SERVER_USER}@${SERVER_IP}" << ENDSSH
set -e

# 权限
chown huoma:huoma ${APP_DIR}/app.jar

# 重启 ECS-1
systemctl restart huoma
sleep 5

# 如果配了 ECS-2
if [ -n "${ECS2_IP}" ]; then
    echo "  → 同步到 ECS-2 (${ECS2_IP})..."

    # 停 ECS-2
    ssh -o StrictHostKeyChecking=no -o ConnectTimeout=5 root@${ECS2_IP} "systemctl stop huoma || true"

    # 传 JAR
    scp -o StrictHostKeyChecking=no ${APP_DIR}/app.jar root@${ECS2_IP}:${APP_DIR}/

    # 传配置（如果本地有更新）
    scp -o StrictHostKeyChecking=no /etc/systemd/system/huoma.env root@${ECS2_IP}:/etc/systemd/system/
    scp -o StrictHostKeyChecking=no /etc/systemd/system/huoma.service root@${ECS2_IP}:/etc/systemd/system/
    scp -o StrictHostKeyChecking=no /etc/nginx/conf.d/huoma.conf root@${ECS2_IP}:/etc/nginx/conf.d/

    # 启 ECS-2
    ssh -o StrictHostKeyChecking=no root@${ECS2_IP} "
        chown huoma:huoma ${APP_DIR}/app.jar
        systemctl daemon-reload
        systemctl restart huoma
        sleep 5
        systemctl status huoma --no-pager -l | head -5
    "
    echo "  ✅ ECS-2 已重启"
fi

# ECS-1 状态
systemctl status huoma --no-pager -l | head -5
ENDSSH

echo "  ✅ 服务已重启"

# 5. 健康检查
echo ""
echo "[5/5] 健康检查..."
sleep 8

check_health() {
    local host=$1
    local label=$2
    local status=$(ssh "${SERVER_USER}@${host}" \
        "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health" 2>/dev/null || echo "000")
    if [ "$status" = "200" ]; then
        echo "  ✅ ${label} 健康 (HTTP ${status})"
    else
        echo "  ⚠️  ${label} 异常 (HTTP ${status}), 请检查日志"
    fi
}

check_health "${SERVER_IP}" "ECS-1"

if [ -n "${ECS2_IP}" ]; then
    c2_status=$(ssh "${SERVER_USER}@${SERVER_IP}" \
        "ssh -o ConnectTimeout=5 root@${ECS2_IP} \
        \"curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health\"" 2>/dev/null || echo "000")
    if [ "$c2_status" = "200" ]; then
        echo "  ✅ ECS-2 健康 (HTTP ${c2_status})"
    else
        echo "  ⚠️  ECS-2 异常 (HTTP ${c2_status}), 请检查日志"
    fi
fi

echo ""
echo "========================================="
echo " 部署完成！"
echo " 管理后台: http://${SERVER_IP}/"
echo "========================================="
