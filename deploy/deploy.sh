#!/bin/bash
# 火马平台 部署脚本
# 用法: ./deploy.sh [prod|dev]
#
# 环境变量:
#   DEPLOY_SERVER_IP    目标服务器 IP（必填）
#   DEPLOY_SERVER_USER  目标服务器用户（默认 huoma）

set -e

PROFILE=${1:-prod}
SERVER_IP="${DEPLOY_SERVER_IP:?请设置 DEPLOY_SERVER_IP 环境变量}"
SERVER_USER="${DEPLOY_SERVER_USER:huoma}"
APP_DIR="/opt/HuoMa"
JAR_NAME="bookstore-qrcode-0.1.0.jar"

echo "========================================="
echo " 火马平台 部署"
echo " 环境: ${PROFILE} | 目标: ${SERVER_IP}"
echo "========================================="

# 1. 本地编译
echo "[1/5] 编译项目..."
cd "$(dirname "$0")/.."
./mvnw clean package -DskipTests -P${PROFILE}
echo "  ✅ 编译完成"

# 2. 上传前先停止服务
echo "[2/5] 停止服务..."
ssh "${SERVER_USER}@${SERVER_IP}" "systemctl stop bookstore-qrcode || true"
echo "  ✅ 服务已停止"

# 3. 上传 jar
echo "[3/5] 上传到服务器..."
ssh "${SERVER_USER}@${SERVER_IP}" "mkdir -p ${APP_DIR}/target"
scp "target/${JAR_NAME}" "${SERVER_USER}@${SERVER_IP}:${APP_DIR}/target/${JAR_NAME}"
echo "  ✅ 上传完成"

# 4. 服务器端操作
echo "[4/5] 重启服务..."
ssh "${SERVER_USER}@${SERVER_IP}" << 'EOF'
    # 创建日志目录
    mkdir -p /var/log/huoma

    # 安装 systemd service（首次部署）
    if [ ! -f /etc/systemd/system/bookstore-qrcode.service ]; then
        cp /opt/HuoMa/bookstore-qrcode.service /etc/systemd/system/
        systemctl daemon-reload
        systemctl enable bookstore-qrcode
    fi

    # 重启
    systemctl restart bookstore-qrcode

    # 等待启动
    sleep 5
    systemctl status bookstore-qrcode --no-pager
EOF
echo "  ✅ 服务已重启"

# 5. 健康检查（通过 SSH 本地检查，不暴露端口到公网）
echo "[5/5] 健康检查..."
sleep 3
STATUS=$(ssh "${SERVER_USER}@${SERVER_IP}" \
    "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health" 2>/dev/null || echo "000")
if [ "$STATUS" = "200" ]; then
    echo "  ✅ 健康检查通过 (HTTP ${STATUS})"
else
    echo "  ⚠️ 健康检查异常 (HTTP ${STATUS}), 请检查日志"
fi

echo "========================================="
echo " 部署完成！"
echo "========================================="
