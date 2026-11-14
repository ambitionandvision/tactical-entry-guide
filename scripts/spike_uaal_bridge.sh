#!/usr/bin/env bash
# UaaL(Unity as a Library) 브리지 스파이크 — Go/No-go 판정.
#
#   bash scripts/spike_uaal_bridge.sh              # 판정만 (기존 Unity 로그 사용)
#   bash scripts/spike_uaal_bridge.sh --compile    # Unity batchmode 컴파일을 새로 시도한 뒤 판정 (수 분 소요)
#
# 판정 항목 (모두 충족해야 Go):
#   1) Unity 2020.3.6f1 Editor 존재
#   2) Android Build Support(PlaybackEngines/AndroidPlayer) 설치
#   3) Assets/Scripts 컴파일 성공 — Unity batchmode 로그 기준. 라이선스로 막히면 그 사실을 그대로 출력하고,
#      참고용으로 Unity 내장 Roslyn csc 컴파일(라이선스 불필요) 결과를 함께 보인다(이것만으로 Go 가 되지 않는다).
#   4) UaaL 산출물(unityLibrary 모듈 또는 AAR) 존재
# 종료 코드: 0 = Go, 2 = No-go (No-go 면 앱은 네이티브 Tactical3DFallbackView 를 쓴다. UnityBridgeManager 가 자동 위임).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJ="$ROOT/unity_tactical_module"
EDITOR_DIR="${UNITY_EDITOR_DIR:-/mnt/c/Program Files/Unity/Hub/Editor/2020.3.6f1/Editor}"
UNITY_EXE="$EDITOR_DIR/Unity.exe"
DATA="$EDITOR_DIR/Data"
LOG="$PROJ/Logs/batch_compile.log"
DO_COMPILE=0
[ "${1:-}" = "--compile" ] && DO_COMPILE=1

winpath() { if command -v wslpath >/dev/null 2>&1; then wslpath -w "$1"; else echo "$1"; fi; }

ok=1
say() { printf '%-28s %s\n' "$1" "$2"; }

echo "== UaaL 스파이크 ($(date '+%F %T')) =="

# 1) Editor
if [ -f "$UNITY_EXE" ]; then
    say "[1] Unity Editor" "OK  $UNITY_EXE"
else
    say "[1] Unity Editor" "NO  ($UNITY_EXE 없음)"
    ok=0
fi

# 2) Android Build Support
if [ -d "$DATA/PlaybackEngines/AndroidPlayer" ]; then
    say "[2] Android Build Support" "OK"
else
    installed="$(ls "$DATA/PlaybackEngines" 2>/dev/null | tr '\n' ' ')"
    say "[2] Android Build Support" "NO  (PlaybackEngines: ${installed:-없음})"
    ok=0
fi

# 3) 스크립트 컴파일
if [ $DO_COMPILE -eq 1 ] && [ -f "$UNITY_EXE" ]; then
    mkdir -p "$PROJ/Logs"
    echo "    Unity batchmode 컴파일 시도 중… (로그: $LOG)"
    timeout 1200 "$UNITY_EXE" -batchmode -nographics -quit -projectPath "$(winpath "$PROJ")" -logFile "$(winpath "$LOG")"
    echo "    Unity 종료 코드: $?"
fi
unity_compile="미실행"
if [ -f "$LOG" ]; then
    if grep -qiE "No valid Unity Editor license|License is not active|not been activated|Failed to activate|Unity has not been activated|com\.unity\.editor\.headless|LICENSE SYSTEM.*(error|fail)|Token not found in cache" "$LOG"; then
        unity_compile="라이선스로 차단"
    elif grep -qE "error CS[0-9]+" "$LOG"; then
        unity_compile="컴파일 오류 ($(grep -cE 'error CS[0-9]+' "$LOG")건)"
    elif [ -f "$PROJ/Library/ScriptAssemblies/Assembly-CSharp.dll" ]; then
        unity_compile="성공 (Library/ScriptAssemblies/Assembly-CSharp.dll)"
    else
        unity_compile="판정 불가 (로그에 오류·산출물 모두 없음)"
    fi
fi
case "$unity_compile" in
    성공*) say "[3] Unity 스크립트 컴파일" "OK  $unity_compile" ;;
    *)     say "[3] Unity 스크립트 컴파일" "NO  $unity_compile  (로그: $LOG)"; ok=0 ;;
esac

# 3-참고) 내장 Roslyn csc 로 라이선스 없이 컴파일 (판정에는 반영하지 않음)
CSC="$DATA/Tools/Roslyn/csc.exe"
API="$DATA/MonoBleedingEdge/lib/mono/4.7.1-api"
if [ -f "$CSC" ]; then
    tmp="$(mktemp -d)"
    refs=( "-r:$(winpath "$API/mscorlib.dll")" "-r:$(winpath "$API/System.dll")" "-r:$(winpath "$API/System.Core.dll")"
           "-r:$(winpath "$API/Facades/netstandard.dll")" "-r:$(winpath "$DATA/Managed/UnityEngine/UnityEngine.CoreModule.dll")"
           "-r:$(winpath "$DATA/Managed/UnityEngine/UnityEngine.JSONSerializeModule.dll")" )
    srcs=(); for f in "$PROJ"/Assets/Scripts/*.cs; do srcs+=("$(winpath "$f")"); done
    # 결과 DLL 은 Windows 쪽 임시 폴더에 쓴다 (WSL /tmp 는 csc 가 쓰지 못할 수 있음)
    outdll="$(winpath "$PROJ/Logs")\\csc_check.dll"
    if "$CSC" -nologo -noconfig -nostdlib+ -langversion:8 -target:library -warnaserror+ -nowarn:1701,1702 \
         -define:"UNITY_2020_3_OR_NEWER;UNITY_ANDROID" -out:"$outdll" "${refs[@]}" "${srcs[@]}" >"$tmp/csc.txt" 2>&1; then
        say "    (참고) Roslyn csc" "OK  ${#srcs[@]}개 파일, 경고 0 (UnityEngine.CoreModule 참조, C# 8)"
    else
        say "    (참고) Roslyn csc" "실패"
        sed 's/^/        /' "$tmp/csc.txt" | head -20
    fi
    rm -f "$PROJ/Logs/csc_check.dll" "$PROJ/Logs/csc_check.pdb"
    rm -rf "$tmp"
fi

# 4) UaaL 산출물
artifact="$(find "$PROJ" "$ROOT/android_pleos_app" -maxdepth 4 \( -path '*/unityLibrary/build.gradle' -o -name 'unityLibrary*.aar' -o -name 'unity-classes.jar' \) 2>/dev/null | head -1)"
if [ -n "$artifact" ]; then
    say "[4] UaaL 산출물" "OK  $artifact"
else
    say "[4] UaaL 산출물" "NO  (unityLibrary 모듈/AAR 없음 — Android Export 가 선행돼야 함)"
    ok=0
fi

echo
if [ $ok -eq 1 ]; then
    echo "판정: GO — UaaL 통합 가능. UnityBridgeManager.attachUnityView() 로 Unity 화면을 붙인다."
    exit 0
else
    echo "판정: NO-GO — 네이티브 폴백(Tactical3DFallbackView, Canvas 3D)을 사용한다."
    echo "      UnityBridgeManager 는 com.unity3d.player.UnityPlayer 가 없으면 모든 호출을 폴백에 위임한다."
    exit 2
fi
