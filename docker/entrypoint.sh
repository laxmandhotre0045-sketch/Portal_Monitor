#!/bin/sh
# Builds application.properties from the environment (the VPS .env, via compose
# env_file) and starts the server. Secrets live only in .env (mode 600, not in git).
#
#   (no arguments)   -> server mode, as in production
#   any arguments    -> passed to portalmonitor.App instead, after the file is written,
#                       e.g. "--test-db --config /run/portal-monitor/application.properties"
#                       (deploy.sh uses this to test the database with a new image).
set -eu

: "${DB_HOST:?DB_HOST is required}"
: "${DB_NAME:?DB_NAME is required}"
: "${DB_USERNAME:?DB_USERNAME is required}"
: "${DB_PASSWORD:?DB_PASSWORD is required}"
: "${SMTP_HOST:?SMTP_HOST is required}"
: "${SMTP_FROM:?SMTP_FROM is required}"
: "${ALERT_DEFAULT_EMAIL:?ALERT_DEFAULT_EMAIL is required}"
: "${API_TOKEN:?API_TOKEN is required}"

SERVER_BIND="${SERVER_BIND:-0.0.0.0}"
SERVER_PORT="${SERVER_PORT:-8085}"
DB_PORT="${DB_PORT:-3306}"
SMTP_PORT="${SMTP_PORT:-587}"
SMTP_SECURITY="${SMTP_SECURITY:-tls}"
SMTP_USER="${SMTP_USER:-}"
SMTP_PASSWORD="${SMTP_PASSWORD:-}"
SMTP_FROM_NAME="${SMTP_FROM_NAME:-Portal Monitor}"
CHECK_INTERVAL_SECONDS="${CHECK_INTERVAL_SECONDS:-60}"
CHECK_FAIL_THRESHOLD="${CHECK_FAIL_THRESHOLD:-3}"
CHECK_CONNECT_TIMEOUT_SECONDS="${CHECK_CONNECT_TIMEOUT_SECONDS:-5}"
CHECK_REQUEST_TIMEOUT_SECONDS="${CHECK_REQUEST_TIMEOUT_SECONDS:-10}"
CHECK_SUCCESS_CODES="${CHECK_SUCCESS_CODES:-200}"
CHECK_VERIFY_SSL="${CHECK_VERIFY_SSL:-true}"
AGENT_OFFLINE_AFTER_SECONDS="${AGENT_OFFLINE_AFTER_SECONDS:-180}"
LOG_RETENTION_DAYS="${LOG_RETENTION_DAYS:-30}"
APP_TIMEZONE="${APP_TIMEZONE:-Asia/Kolkata}"
DB_PARAMS="${DB_PARAMS:-allowPublicKeyRetrieval=true}"
JAVA_OPTS="${JAVA_OPTS:--Xms32m -Xmx192m}"

CONF_DIR=/run/portal-monitor
CONF="$CONF_DIR/application.properties"
mkdir -p "$CONF_DIR"
umask 077

cat > "$CONF" <<PROPERTIES
db.host=${DB_HOST}
db.port=${DB_PORT}
db.name=${DB_NAME}
db.user=${DB_USERNAME}
db.password=${DB_PASSWORD}
db.params=${DB_PARAMS}

smtp.host=${SMTP_HOST}
smtp.port=${SMTP_PORT}
smtp.security=${SMTP_SECURITY}
smtp.user=${SMTP_USER}
smtp.password=${SMTP_PASSWORD}
smtp.from=${SMTP_FROM}
smtp.fromName=${SMTP_FROM_NAME}

alert.defaultEmail=${ALERT_DEFAULT_EMAIL}

check.intervalSeconds=${CHECK_INTERVAL_SECONDS}
check.failThreshold=${CHECK_FAIL_THRESHOLD}
check.connectTimeoutSeconds=${CHECK_CONNECT_TIMEOUT_SECONDS}
check.requestTimeoutSeconds=${CHECK_REQUEST_TIMEOUT_SECONDS}
check.successCodes=${CHECK_SUCCESS_CODES}
check.verifySsl=${CHECK_VERIFY_SSL}

server.port=${SERVER_PORT}
server.bind=${SERVER_BIND}
api.token=${API_TOKEN}

agent.offlineAfterSeconds=${AGENT_OFFLINE_AFTER_SECONDS}
log.retentionDays=${LOG_RETENTION_DAYS}
app.timezone=${APP_TIMEZONE}
PROPERTIES

# shellcheck disable=SC2086  # JAVA_OPTS is a list of JVM flags
if [ "$#" -gt 0 ]; then
  exec java $JAVA_OPTS -cp /app/portal-monitor.jar portalmonitor.App "$@"
fi
exec java $JAVA_OPTS -cp /app/portal-monitor.jar portalmonitor.App \
  --mode server --config "$CONF"
