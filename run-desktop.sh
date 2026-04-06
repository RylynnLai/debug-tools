#!/usr/bin/env bash
# 一键启动桌面端调试面板
# 用法: ./run-desktop.sh
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
echo "🖥  Starting Debug Tools Desktop..."
exec "$SCRIPT_DIR/gradlew" --project-dir "$SCRIPT_DIR/desktop" run

