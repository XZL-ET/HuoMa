#!/bin/bash
# 火马平台 上传目录（卡片图/封面图）备份脚本
# 将 /opt/HuoMa/data/uploads 打包到 /opt/HuoMa/backups/，保留最近 14 份。
# 用法: bash backup-uploads.sh   （建议 crontab 每天凌晨 3:30，与 DB 备份错开）
set -euo pipefail

DATA_DIR="/opt/HuoMa/data/uploads"
BACKUP_DIR="/opt/HuoMa/backups"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="${BACKUP_DIR}/uploads-${STAMP}.tar.gz"

mkdir -p "${BACKUP_DIR}"

if [ ! -d "${DATA_DIR}" ]; then
    echo "上传目录不存在，跳过: ${DATA_DIR}"
    exit 0
fi

tar -C "$(dirname "${DATA_DIR}")" -czf "${OUT}" "$(basename "${DATA_DIR}")"
echo "已备份: ${OUT} ($(du -h "${OUT}" | cut -f1))"

# 保留最近 14 份，其余删除
ls -1t "${BACKUP_DIR}"/uploads-*.tar.gz 2>/dev/null | tail -n +15 | xargs -r rm -f

# TODO(OSS): 待 RAM 子账号 + AccessKey 配置后，取消注释以下两行，将备份上传到 OSS
# OSS_BUCKET="oss://huoma-backup"
# ossutil cp "${OUT}" "${OSS_BUCKET}/uploads/" || echo "OSS 上传失败（未配置）"
