#!/bin/bash
# ============================================================
# ECS MySQL 查询工具
# 用法:
#   ./scripts/ecs-mysql.sh "SELECT ..."          # 执行查询
#   ./scripts/ecs-mysql.sh -f query.sql           # 从文件执行
#   ./scripts/ecs-mysql.sh -i                     # 交互模式
# ============================================================

ECS_HOST="${ECS_HOST:-root@<你的ECS公网IP>}"
DB_HOST="${DB_HOST:-}"
DB_USER="${DB_USERNAME:-bookstore}"
DB_PASS="${DB_PASSWORD:-}"
DB_NAME="${DB_NAME:-bookstore_qrcode}"

if [[ -z "${DB_HOST}" || -z "${DB_PASS}" ]]; then
    echo "错误: 缺少 DB_HOST / DB_PASSWORD 环境变量" >&2
    echo "用法: DB_HOST=... DB_PASSWORD=... $0 \"SELECT ...\"" >&2
    exit 2
fi

MYSQL_CMD="mysql -h ${DB_HOST} -u ${DB_USER} -p${DB_PASS} -D ${DB_NAME} --default-character-set=utf8mb4"

run_query() {
    local sql="$1"
    ssh "${ECS_HOST}" "${MYSQL_CMD} -e \"${sql}\""
}

run_interactive() {
    ssh -t "${ECS_HOST}" "${MYSQL_CMD}"
}

run_file() {
    local file="$1"
    ssh "${ECS_HOST}" "${MYSQL_CMD}" < "$file"
}

case "${1:-}" in
    -i|--interactive)
        run_interactive
        ;;
    -f|--file)
        run_file "$2"
        ;;
    "")
        echo "用法: $0 [-i] [-f file.sql] <SQL语句>"
        echo ""
        echo "  环境变量:"
        echo "    ECS_HOST       ECS 服务器地址 (默认: root@<IP>)"
        echo "    DB_HOST        RDS 地址"
        echo "    DB_USERNAME    MySQL 用户名"
        echo "    DB_PASSWORD    MySQL 密码"
        echo "    DB_NAME        数据库名"
        exit 1
        ;;
    *)
        run_query "$*"
        ;;
esac
