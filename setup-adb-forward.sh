#!/usr/bin/env bash
# Create adb forward so desktop localhost can reach DebugKit on device.
# Usage: ./setup-adb-forward.sh [device-id] [port]
set -euo pipefail

PORT="${2:-4939}"
DEVICE_ID="${1:-}"

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found. Install Android platform-tools and retry."
  exit 1
fi

if [[ -z "$DEVICE_ID" ]]; then
  DEVICE_ID="$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
fi

if [[ -z "$DEVICE_ID" ]]; then
  echo "No online device found. Check 'adb devices'."
  exit 1
fi

echo "Using device: $DEVICE_ID"
adb -s "$DEVICE_ID" forward "tcp:$PORT" "tcp:$PORT"

echo "Forward created: localhost:$PORT -> $DEVICE_ID:$PORT"
echo "Current forwards:"
adb forward --list

echo "Probe localhost:$PORT ..."
if nc -z 127.0.0.1 "$PORT"; then
  echo "OK: localhost:$PORT is reachable."
else
  echo "Failed: localhost:$PORT is still not reachable. Ensure app is running and DebugKit.install(...) executed."
  exit 2
fi

