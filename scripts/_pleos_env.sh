# 공용 환경 탐색 (source 용). WSL 이면 Windows 쪽 SDK 도구(.exe)를 쓴다.
PKG="io.github.ambitionandvision.tactical"
ACTIVITY="$PKG/.MainActivity"
AVD_NAME="${AVD_NAME:-Pleos_Connect_API34}"
IMG="system-images;android-34;pleos_car_emulator;x86_64"

if grep -qi microsoft /proc/version 2>/dev/null; then IS_WSL=1; EXE=".exe"; else IS_WSL=0; EXE=""; fi

find_sdk() {
  local c
  for c in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" /mnt/c/Users/*/AppData/Local/Android/Sdk "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
    [ -n "$c" ] && [ -d "$c/platform-tools" ] && { echo "$c"; return 0; }
  done
  return 1
}
SDK="$(find_sdk)" || { echo "[X] Android SDK 를 찾지 못했습니다. ANDROID_SDK_ROOT 를 지정하세요." >&2; return 1 2>/dev/null || exit 1; }
ADB="$SDK/platform-tools/adb$EXE"
EMULATOR="$SDK/emulator/emulator$EXE"
if [ "$IS_WSL" = 1 ]; then
  AVDMANAGER_WIN="$(wslpath -w "$SDK/cmdline-tools/latest/bin/avdmanager.bat")"
else
  AVDMANAGER="$SDK/cmdline-tools/latest/bin/avdmanager"
fi
winpath() { if [ "$IS_WSL" = 1 ]; then wslpath -w "$1"; else echo "$1"; fi; }
# cmd.exe 는 세미콜론을 구분자로 보므로 패키지 문자열은 반드시 따옴표로 감싼다.
run_avdmanager() {
  if [ "$IS_WSL" = 1 ]; then
    local jdk
    jdk="$(bash "$(dirname "${BASH_SOURCE[0]}")/find_jdk17.sh")" || return 1
    # WSL 은 cmd.exe /c 인자 안의 따옴표를 \" 로 넘겨 cmd 가 명령을 찾지 못한다. 임시 .bat 에 써서 실행한다.
    local tmpw tmpu
    tmpw="$(cmd.exe /c 'echo %TEMP%' 2>/dev/null | tr -d '\r')\\pleos_avdmanager_$$.bat"
    tmpu="$(wslpath -u "$tmpw")"
    printf '@echo off\r\nset "JAVA_HOME=%s"\r\ncall "%s" %s\r\n' "$jdk" "$AVDMANAGER_WIN" "$*" > "$tmpu"
    cmd.exe /c "$tmpw" | tr -d '\r'
    local rc=${PIPESTATUS[0]}
    rm -f "$tmpu"
    return "$rc"
  else
    "$AVDMANAGER" "$@"
  fi
}
