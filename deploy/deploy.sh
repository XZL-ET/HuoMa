#!/bin/bash
# XX书店 · 活码管理平台 部署脚本
# 用法: ./deploy.sh [prod|dev]

set -e

PROFILE=${1:-prod}
SERVER_IP="<YOUR_SERVER_IP>"
SERVER_USER="root"
APP_DIR="/opt/bookstore-qrcode"
JAR_NAME="bookstore-qrcode-0.1.0.jar"

echo "========================================="
echo " XX书店 · 活码管理平台 部署"
echo " 环境: ${PROFILE} | 目标: ${SERVER_IP}"
echo "========================================="

# 1. 本地编译
echo "[1/4] 编译项目..."
cd "$(dirname "$0")/.."
mvn clean package -DskipTests -P${PROFILE}
echo "  ✅ 编译完成"

# 2. 上传 jar
echo "[2/4] 上传到服务器..."
scp "target/${JAR_NAME}" "${SERVER_USER}@${SERVER_IP}:${APP_DIR}/app.jar"
echo "  ✅ 上传完成"

# 3. 服务器端操作
echo "[3/4] 重启服务..."
ssh "${SERVER_USER}@${SERVER_IP}" << 'EOF'
    # 创建目录
    mkdir -p /opt/bookstore-qrcode/logs

    # 安装 systemd service（首次部署）
    if [ ! -f /etc/systemd/system/bookstore-qrcode.service ]; then
        cp /opt/bookstore-qrcode/bookstore-qrcode.service /etc/systemd/system/
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

# 4. 健康检查
echo "[4/4] 健康检查..."
sleep 3
STATUS=$(curl -s -o /dev/null -w "%{http_code}" "http://${SERVER_IP}:8080/" 2>/dev/null || echo "000")
if [ "$STATUS" = "200" ] || [ "$STATUS" = "302" ]; then
    echo "  ✅ 健康检查通过 (HTTP ${STATUS})"
else
    echo "  ⚠️ 健康检查异常 (HTTP ${STATUS}), 请检查日志"
fi

echo "========================================="
echo " 部署完成！"
echo "========================================="
