#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# Use Kafka's own client libraries or the classpath generated from backend/common.
: "${KAFKA_CLIENT_CLASSPATH:?Set KAFKA_CLIENT_CLASSPATH to the Kafka client dependency classpath}"
exec java --class-path "$KAFKA_CLIENT_CLASSPATH" "$root/infra/scripts/DltReplay.java" "$@"
