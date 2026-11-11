#!/usr/bin/env bash
# 本機直接啟動／停止 6 個後端服務（jar），給開發與 E2E 用。先用 docker compose 啟動資料庫、Redis、物件儲存。
# 用法：infra/scripts/local-backend.sh start|stop|restart
#   OBSERVE=1 infra/scripts/local-backend.sh start   → 同時把指標、追蹤、log 送到 compose 的 observability（Grafana :3000）
# log 在 $LOG_DIR（預設 backend/.local-logs）。需要先 ./mvnw -DskipTests package。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BACKEND="$ROOT/backend"
LOG_DIR="${LOG_DIR:-$BACKEND/.local-logs}"
SERVICES=(user product inventory order payment)

stop() {
  # jar 啟動中時 Windows 會鎖住它，重新打包前要先停
  if command -v powershell.exe >/dev/null 2>&1; then
    powershell.exe -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match '(api-gateway|-service)-0\.0\.1-SNAPSHOT\.jar' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }"
  else
    pkill -f '(api-gateway|-service)-0.0.1-SNAPSHOT.jar' || true
  fi
}

start() {
  mkdir -p "$LOG_DIR"
  if [ "${OBSERVE:-0}" = "1" ]; then
    export OBSERVABILITY_EXPORT_ENABLED=true OTEL_EXPORTER_OTLP_ENDPOINT="${OTEL_EXPORTER_OTLP_ENDPOINT:-http://localhost:4318}"
  fi
  # user-service 的 JWT 金鑰與管理員帳密在 secrets/dev.env（不進 git）
  ( cd "$BACKEND/spring-user-service" && set -a && . ./secrets/dev.env && set +a &&
    nohup java -jar target/user-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev > "$LOG_DIR/user.log" 2>&1 & )
  for s in product inventory order; do
    ( cd "$BACKEND/spring-$s-service" && nohup java -jar "target/$s-service-0.0.1-SNAPSHOT.jar" > "$LOG_DIR/$s.log" 2>&1 & )
  done
  # 沒有真的金流：E2E 需要 mock-callback（預設關閉，SEC-01）
  ( cd "$BACKEND/spring-payment-service" && PAYMENT_MOCK_CALLBACK_ENABLED=true \
    nohup java -jar target/payment-service-0.0.1-SNAPSHOT.jar > "$LOG_DIR/payment.log" 2>&1 & )
  ( cd "$BACKEND/spring-api-gateway" && nohup java -jar target/api-gateway-0.0.1-SNAPSHOT.jar > "$LOG_DIR/gateway.log" 2>&1 & )
  echo "已啟動（log：$LOG_DIR）；約 1 分鐘後 http://localhost:8080/actuator/health 變 UP"
}

case "${1:-}" in
  start) start ;;
  stop) stop ;;
  restart) stop; sleep 3; start ;;
  *) echo "用法：$0 start|stop|restart" >&2; exit 1 ;;
esac
