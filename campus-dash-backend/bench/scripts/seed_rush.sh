#!/usr/bin/env bash
# 可重复初始化P9活动：先精确清理90001活动的Redis键，再执行MySQL种子脚本。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-dash-mysql}"
REDIS_CONTAINER="${REDIS_CONTAINER:-dash-redis}"
DB="${DB:-campus_dash}"
CAMPAIGN_ID=90001

REQUEST_IDS=$(docker exec -e MYSQL_PWD=dash123456 "$MYSQL_CONTAINER" \
  mysql -uroot -D "$DB" -N -B \
  -e "SELECT request_id FROM rush_request WHERE campaign_id=$CAMPAIGN_ID" || true)

{
  docker exec "$REDIS_CONTAINER" redis-cli --scan --pattern "rush:{$CAMPAIGN_ID}:*" || true
  if [ -n "$REQUEST_IDS" ]; then
    printf '%s\n' "$REQUEST_IDS" | awk 'NF{print "rush:request-index:" $0}'
  fi
} | awk 'NF{print "DEL " $0}' \
  | docker exec -i "$REDIS_CONTAINER" redis-cli --pipe >/dev/null 2>&1 || true

docker exec -i -e MYSQL_PWD=dash123456 "$MYSQL_CONTAINER" \
  mysql -uroot -D "$DB" < "$SCRIPT_DIR/seed_rush.sql"

echo "P9活动90001已重置：quota=500，窗口=当前时间起60分钟。"
