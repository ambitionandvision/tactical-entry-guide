#!/usr/bin/env bash
# WSL 에서 Windows 쪽 Gradle 을 직렬로 실행한다. 팀 전원이 이 스크립트로만 Gradle 을 돌린다.
#
#   bash scripts/gradle_win.sh assembleDebug
#   bash scripts/gradle_win.sh testDebugUnitTest
#
# 왜 Windows 쪽인가: Android SDK 와 JDK 17 은 Windows 에만 있고, WSL 에는 java 가 없다.
# 왜 flock 인가: 같은 프로젝트에 Gradle 이 동시에 돌면 잠금 대기로 서로를 막는다.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP="$ROOT/android_pleos_app"
JDK_WIN="$(bash "$ROOT/scripts/find_jdk17.sh")"

[ -f "$APP/gradlew.bat" ] || { echo "[X] gradlew.bat 가 없습니다: $APP" >&2; exit 2; }

exec 9>/tmp/pleos_gradle.lock
flock 9

cd "$APP"
APP_WIN="$(wslpath -w "$APP")"
# gradlew.bat 은 상대 경로로 부른다. 프로젝트 경로에 괄호가 있으면 cmd.exe 가 명령 경로를 '(' 에서 자른다.
exec cmd.exe /c "cd /d $APP_WIN&& set JAVA_HOME=$JDK_WIN&& .\\gradlew.bat --console=plain $*"
