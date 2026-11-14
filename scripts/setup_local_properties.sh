#!/usr/bin/env bash
# local.properties(sdk.dir) 를 자동 생성한다. WSL / Git-Bash / Linux / macOS 지원.
# 사용: bash scripts/setup_local_properties.sh   (ANDROID_SDK_ROOT 로 덮어쓰기 가능)
# JDK 17 은 저장소에 경로를 남기지 않는다. scripts/find_jdk17.sh 가 실행 시 찾는다.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP="$ROOT/android_pleos_app"
JDK_OVERRIDE=""
[ "${1:-}" = "--jdk" ] && JDK_OVERRIDE="${2:-}"

is_wsl() { grep -qi microsoft /proc/version 2>/dev/null; }

SDK=""
cands=()
[ -n "${ANDROID_SDK_ROOT:-}" ] && cands+=("$ANDROID_SDK_ROOT")
[ -n "${ANDROID_HOME:-}" ] && cands+=("$ANDROID_HOME")
if is_wsl; then
  for u in /mnt/c/Users/*; do cands+=("$u/AppData/Local/Android/Sdk"); done
fi
cands+=("$HOME/Android/Sdk" "$HOME/Library/Android/sdk" "${LOCALAPPDATA:-/nonexistent}/Android/Sdk")
for c in "${cands[@]}"; do
  [ -n "$c" ] && [ -d "$c/platform-tools" ] && { SDK="$c"; break; }
done
[ -n "$SDK" ] || { echo "[X] Android SDK 를 찾지 못했습니다. ANDROID_SDK_ROOT 를 지정하세요." >&2; exit 1; }

# Gradle 은 Windows 프로세스이므로 WSL 이면 Windows 경로(이스케이프)로 기록한다.
if is_wsl && command -v wslpath >/dev/null; then
  SDK_OUT="$(wslpath -w "$SDK")"
else
  SDK_OUT="$SDK"
fi
SDK_OUT="${SDK_OUT//\\/\\\\}"; SDK_OUT="${SDK_OUT//:/\\:}"
printf 'sdk.dir=%s\n' "$SDK_OUT" > "$APP/local.properties"
echo "[OK] $APP/local.properties -> sdk.dir=$SDK_OUT"

if [ -n "$JDK_OVERRIDE" ]; then
  echo "[i] JDK 는 PLEOS_JDK17 환경변수로 지정합니다: export PLEOS_JDK17='$JDK_OVERRIDE'"
elif JDK="$(bash "$ROOT/scripts/find_jdk17.sh")"; then
  echo "[OK] JDK 17: $JDK (gradle_win 이 실행 시 JAVA_HOME 으로 지정)"
fi
