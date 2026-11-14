#!/usr/bin/env bash
# Pleos 에뮬레이터 부팅 → APK 설치 → 앱 실행 → GPX 재생.
#   bash scripts/run_emulator_demo.sh [--apk 경로] [--inject] [--gpx 파일] [--no-create] [--no-start] [--dry-run]
#                                     [--install-image] [--fuel ICE|EV] [--drive-gpx] [--drive "--gear Drive --speed 25 --hold 60"] [--user 10]
# 기본 위치 재생은 앱 내장 GPX 재생이다. --inject 면 GPX 를 파싱해 `adb emu geo fix` 를 1Hz 로 보낸다.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK=""; INJECT=0; GPX="$ROOT/gis_data/sejong_costco_cn8_drive.gpx"; CREATE=1; START=1; DRY=0; GPX_SET=0; INSTALL_IMAGE=0; FUEL=""; DRIVE_GPX=0; DRIVE=""; APPUSER=10
while [ $# -gt 0 ]; do
  case "$1" in
    --apk) APK="$2"; shift 2;;
    --inject) INJECT=1; shift;;
    --gpx) GPX="$2"; GPX_SET=1; shift 2;;
    --no-create) CREATE=0; shift;;
    --no-start) START=0; shift;;
    --dry-run) DRY=1; shift;;
    --install-image) INSTALL_IMAGE=1; shift;;
    --drive) DRIVE="${2:---gear Drive --speed 25 --hold 60}"; shift 2;;
    --user) APPUSER="$2"; shift 2;;
    --fuel) FUEL="$(echo "$2" | tr a-z A-Z)"; shift 2;;
    --drive-gpx) DRIVE_GPX=1; shift;;
    -h|--help) sed -n 2,8p "$0"; exit 0;;
    *) echo "알 수 없는 옵션: $1" >&2; exit 2;;
  esac
done
[ "$FUEL" = EV ] && [ "$GPX_SET" = 0 ] && GPX="$ROOT/gis_data/sejong_costco_ev_drive.gpx"
# shellcheck source=_pleos_env.sh
source "$ROOT/scripts/_pleos_env.sh"
echo "[i] SDK=$SDK  AVD=$AVD_NAME"

if [ -z "$APK" ]; then
  APK="$(ls -t "$ROOT"/apk/*_release.apk 2>/dev/null | head -1 || true)"
  [ -n "$APK" ] || APK="$(ls -t "$ROOT"/android_pleos_app/app/build/outputs/apk/debug/*.apk 2>/dev/null | head -1 || true)"
fi

# 0) 이미 실행 중인 에뮬레이터는 재사용한다 (끄거나 재시작하지 않는다)
RUNNING=0
if [ "$DRY" = 0 ] && "$ADB" devices 2>/dev/null | tr -d '\r' | grep -q 'emulator-.*device$'; then RUNNING=1; echo "[i] 실행 중인 에뮬레이터 재사용"; fi

# 1) 이미지 확인 (없으면 안내, --install-image 면 공식 zip 설치)
IMG_DIR="$SDK/system-images/android-34/pleos_car_emulator/x86_64"
if [ "$RUNNING" = 0 ] && [ ! -d "$IMG_DIR" ]; then
  if [ "$INSTALL_IMAGE" = 1 ]; then
    python3 "$ROOT/scripts/pleos_avd_tool.py" install-image "$(winpath "$SDK")" || exit $?
  else
    cat >&2 <<MSG
[X] Pleos 시스템 이미지가 없습니다: $IMG_DIR
  방법 A) Android Studio > Settings > Languages & Frameworks > Android SDK > SDK Update Sites 탭 > Add 에
          Pleos 공식 사이트 URL(Pleos 개발 가이드의 에뮬레이터 설치 절차에 안내된 URL)을 추가한 뒤
          SDK Platforms/SDK Tools 에서 Pleos Connect v2.1.0 이미지를 설치한다.
  방법 B) 이 스크립트에 --install-image 를 주면 공식 매니페스트의 zip(약 4GB, 디스크 25GB 이상 필요)을
          받아 SHA-1 검증·압축해제·package.xml 작성까지 수행한다. 42dot SDK License 동의로 간주된다.
          (sdkmanager 는 커스텀 사이트를 인식하지 못할 수 있다.)
MSG
    exit 3
  fi
fi

# 2) AVD 확인/생성 + 설정 덮어쓰기 (2560x1440 landscape, RAM 4096, cold boot ...)
find_avd_dir() { find "$HOME/.android/avd" /mnt/c/Users/*/.android/avd -maxdepth 1 -name "$AVD_NAME.avd" 2>/dev/null | head -1; }
if [ "$CREATE" = 1 ] && [ "$RUNNING" = 0 ]; then
  if [ "$DRY" = 1 ]; then echo "[dry] AVD 없으면 avdmanager create avd -n $AVD_NAME -k \"$IMG\" 후 config.ini 덮어쓰기"
  else
    if ! "$EMULATOR" -list-avds | tr -d '\r' | grep -qx "$AVD_NAME"; then
      echo "[i] AVD 생성: $AVD_NAME"
      echo no | run_avdmanager create avd -n "$AVD_NAME" -k "\"$IMG\"" --force
    fi
    AVD_DIR="$(find_avd_dir)"
    if [ -n "$AVD_DIR" ]; then python3 "$ROOT/scripts/pleos_avd_tool.py" config "$(winpath "$AVD_DIR/config.ini")"
    else echo "[!] AVD 폴더를 찾지 못해 config.ini 덮어쓰기를 건너뜀"; fi
  fi
fi

# 3') 부팅 (cold boot: Quick boot 면 VHAL 사용 불가)
if [ "$START" = 1 ] && [ "$RUNNING" = 0 ]; then
  if [ "$DRY" = 1 ]; then echo "[dry] $EMULATOR -avd $AVD_NAME -no-snapshot-load -gpu host"
  else
    "$ADB" start-server >/dev/null 2>&1 || true
    nohup "$EMULATOR" -avd "$AVD_NAME" -no-snapshot-load -gpu host >/tmp/pleos_emulator.log 2>&1 &
    echo "[i] 에뮬레이터 시작 (로그 /tmp/pleos_emulator.log, 부팅 약 77초)"
  fi
fi

# 3) 부팅 대기
if [ "$DRY" = 0 ]; then
  "$ADB" wait-for-device
  for i in $(seq 1 180); do
    [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
    sleep 2
  done
  [ "$("$ADB" shell getprop sys.boot_completed | tr -d '\r')" = "1" ] || { echo "[X] 부팅 시간 초과" >&2; exit 4; }
  echo "[OK] 부팅 완료"
fi

# 4) 설치·실행
if [ -z "$APK" ] || [ ! -f "$APK" ]; then
  echo "[!] APK 없음 — 설치·실행을 건너뜁니다. (bash scripts/build_release_apk.sh 로 먼저 빌드)"
else
  echo "[i] APK: $APK (user $APPUSER)"
  if [ "$DRY" = 1 ]; then echo "[dry] adb install --user $APPUSER -r -g <apk>; adb shell am start --user $APPUSER -n $ACTIVITY"; else
    "$ADB" install --user "$APPUSER" -r -g "$(winpath "$APK")"
    for p in android.permission.ACCESS_FINE_LOCATION pleos.car.permission.FUSED_LOCATION pleos.car.permission.CAR_ENERGY pleos.car.permission.NAVI_ROUTE; do
      "$ADB" shell pm grant --user "$APPUSER" "$PKG" "$p" >/dev/null 2>&1 || echo "[i] 권한 부여 생략/실패: $p"
    done
  fi
fi
PKGS="$("$ADB" shell pm list packages --user "$APPUSER" 2>/dev/null | tr -d '\r' || true)"
if [ "$DRY" = 0 ] && grep -qx "package:$PKG" <<<"$PKGS"; then
  EXTRAS=""
  [ "$INJECT" = 1 ] && EXTRAS="$EXTRAS --es source fused"
  [ -n "$FUEL" ] && EXTRAS="$EXTRAS --es fuel $FUEL"
  "$ADB" shell am force-stop --user "$APPUSER" "$PKG"
  "$ADB" logcat -c
  # shellcheck disable=SC2086
  "$ADB" shell am start --user "$APPUSER" -n "$ACTIVITY" $EXTRAS
  echo "[i] 앱 실행 extras:${EXTRAS:- (없음)}"
fi

# 5) 위치 주입(옵션)
if [ "$INJECT" = 1 ]; then
  [ -f "$GPX" ] || { echo "[X] GPX 없음: $GPX" >&2; exit 6; }
  echo "[i] GPX 주입(1Hz): $GPX"
  python3 - "$GPX" <<'PY' > /tmp/pleos_geo_points.txt
import sys, xml.etree.ElementTree as ET
r = ET.parse(sys.argv[1]).getroot()
for e in r.iter():
    if e.tag.endswith('trkpt'):
        print(e.attrib['lon'], e.attrib['lat'])
PY
  if [ "$DRY" = 1 ]; then echo "[dry] 포인트 $(wc -l < /tmp/pleos_geo_points.txt)개 → adb emu geo fix"; else
    ( while read -r lon lat; do "$ADB" emu geo fix "$lon" "$lat" </dev/null >/dev/null; sleep 1; done < /tmp/pleos_geo_points.txt ) &
    INJECT_PID=$!
    echo "[i] 주입 백그라운드 시작 (pid $INJECT_PID, $(wc -l < /tmp/pleos_geo_points.txt)점)"
  fi
else
  if grep -q '<keywords>simulated</keywords>' "$GPX" 2>/dev/null; then LABEL="GPX 주행 재생 (시뮬레이션)"; else LABEL="실차 GPX 주행 재생"; fi
  echo "[i] 앱 내장 GPX 재생(\"$LABEL\", $(basename "$GPX"))을 사용합니다. 앱이 실행되면 바로 재생을 시작합니다."
fi

# 5b) --drive-gpx: 앱 재생(또는 주입)과 함께 Driving View 속도를 GPX 에 맞춘다
if [ "$DRIVE_GPX" = 1 ]; then
  [ -f "$GPX" ] || { echo "[X] GPX 없음: $GPX" >&2; exit 6; }
  if [ "$DRY" = 1 ]; then echo "[dry] virtual_console_drive.py --follow-gpx $GPX"
  else python3 "$ROOT/scripts/virtual_console_drive.py" --follow-gpx "$GPX" --watch-uxr; fi
fi

if [ -n "${INJECT_PID:-}" ]; then wait "$INJECT_PID" && echo "[OK] 주입 완료"; fi

# 6) Virtual Console 주행 재현(옵션). 종료 시 항상 Park 복귀.
if [ -n "$DRIVE" ]; then
  if [ "$DRY" = 1 ]; then echo "[dry] python3 scripts/virtual_console_drive.py $DRIVE --watch-uxr"
  else python3 "$ROOT/scripts/virtual_console_drive.py" $DRIVE --watch-uxr; fi
fi
