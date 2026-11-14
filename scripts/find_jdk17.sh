#!/usr/bin/env bash
# JDK 17 을 찾아 Windows 경로를 출력한다 (WSL·Git Bash 공용). source 하지 말고 실행한다.
#   bash scripts/find_jdk17.sh
# 순서: PLEOS_JDK17 → 사용자 .jdks → Adoptium/Microsoft/Oracle 설치 경로.
# Android Studio 내장 JBR 은 버전이 달라 AGP 8.8 에 쓸 수 없으므로 찾지 않는다.
set -u

drive="/mnt/c"
[ -d "$drive" ] || drive="/c"

is_jdk17() {
  [ -f "$1/release" ] && grep -q '^JAVA_VERSION="17' "$1/release"
}

to_win() {
  if command -v wslpath >/dev/null 2>&1; then
    wslpath -w "$1"
  elif command -v cygpath >/dev/null 2>&1; then
    cygpath -w "$1"
  else
    printf '%s\n' "$1"
  fi
}

if [ -n "${PLEOS_JDK17:-}" ]; then
  printf '%s\n' "$PLEOS_JDK17"
  exit 0
fi

for c in "$drive"/Users/*/.jdks/*17* "$drive"/Program\ Files/Eclipse\ Adoptium/jdk-17* \
         "$drive"/Program\ Files/Microsoft/jdk-17* "$drive"/Program\ Files/Java/jdk-17*; do
  if [ -d "$c" ] && is_jdk17 "$c"; then
    to_win "$c"
    exit 0
  fi
done

echo "[X] JDK 17 을 찾지 못했습니다. Temurin 17 을 설치하거나 PLEOS_JDK17 에 Windows 경로를 지정하세요." >&2
exit 1
