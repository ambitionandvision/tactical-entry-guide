#!/usr/bin/env bash
# 원클릭 시연 녹화: 앱 재실행(--fuel) → Driving View 속도를 GPX 에 맞춰 주행 재현 → screenrecord → docs/demo/ 회수.
# 에뮬레이터 화면 녹화다. 궤적은 GPX 의 <keywords> 를 따른다(real-drive=실주행 로그, simulated=합성). 앱은 사용자 10 에서 실행한다.
#   bash scripts/record_demo.sh [--fuel ICE|EV] [--secs N(<=180)] [--size 1920x1080] [--bit-rate 6000000] [--user 10] [--out 파일명] [--no-drive] [--frames 폴더]
# 파일명 기본값: docs/demo/tactical_entry_guide_<ice|ev>.mp4. 비트레이트 기본 6Mbps(135초 약 77MB, GitHub 100MB 한도 아래). 길이 기본값: GPX 점 수 + 12초 (실주행 ICE 는 --secs 135 권장)
# --frames: 녹화 중 60초와 종료 3초 전에 adb screencap 을 받아 둔다 (ffmpeg 없이 영상 확인용)
# 녹화 후 logcat(TacticalMain)으로 재생 시작 1회·도착 1회·조작/onPause/슬라이더 0회인지 검사하고, 어긋나면 종료코드 7 로 끝낸다.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FUEL="ICE"; SECS=""; SIZE="1920x1080"; BITRATE="6000000"; APPUSER=10; OUT=""; DRIVE=1; FRAMES=""
while [ $# -gt 0 ]; do
  case "$1" in
    --frames) FRAMES="$2"; shift 2;;
    --fuel) FUEL="$(echo "$2" | tr a-z A-Z)"; shift 2;;
    --secs) SECS="$2"; shift 2;;
    --size) SIZE="$2"; shift 2;;
    --bit-rate) BITRATE="$2"; shift 2;;
    --user) APPUSER="$2"; shift 2;;
    --out) OUT="$2"; shift 2;;
    --no-drive) DRIVE=0; shift;;
    -h|--help) sed -n 2,7p "$0"; exit 0;;
    *) echo "알 수 없는 옵션: $1" >&2; exit 2;;
  esac
done
case "$FUEL" in ICE|EV) ;; *) echo "--fuel 은 ICE 또는 EV" >&2; exit 2;; esac
source "$ROOT/scripts/_pleos_env.sh"
LOW="$(echo "$FUEL" | tr A-Z a-z)"
[ -n "$OUT" ] || OUT="tactical_entry_guide_${LOW}.mp4"
if [ "$FUEL" = EV ]; then GPX="$ROOT/gis_data/sejong_costco_ev_drive.gpx"; else GPX="$ROOT/gis_data/sejong_costco_cn8_drive.gpx"; fi
[ -f "$GPX" ] || { echo "[X] GPX 없음: $GPX" >&2; exit 6; }
if [ -z "$SECS" ]; then SECS=$(( $(grep -c '<trkpt' "$GPX") + 12 )); [ "$SECS" -le 180 ] || SECS=180; fi
[ "$SECS" -le 180 ] || { echo "screenrecord 는 최대 180초입니다." >&2; exit 2; }
"$ADB" get-state >/dev/null 2>&1 || { echo "[X] 연결된 기기/에뮬레이터 없음" >&2; exit 3; }
mkdir -p "$ROOT/docs/demo"
DEV=/sdcard/pleos_demo.mp4
DRIVE_PID=""
cleanup() {
  [ -n "$DRIVE_PID" ] && kill -INT "$DRIVE_PID" 2>/dev/null && wait "$DRIVE_PID" 2>/dev/null || true
  python3 "$ROOT/scripts/virtual_console_drive.py" --park-only >/dev/null 2>&1 || true
}
trap cleanup EXIT

python3 "$ROOT/scripts/virtual_console_drive.py" --park-only >/dev/null 2>&1 || echo "[!] Virtual Console 접속 실패 — 속도 연동 없이 녹화합니다"
"$ADB" shell rm -f "$DEV" </dev/null
"$ADB" logcat -c </dev/null || true
echo "[i] 녹화 시작: ${SECS}s ${SIZE} ${BITRATE}bps fuel=$FUEL gpx=$(basename "$GPX")"
"$ADB" shell screenrecord --size "$SIZE" --bit-rate "$BITRATE" --time-limit "$SECS" "$DEV" </dev/null &
REC_PID=$!
sleep 2
"$ADB" shell am force-stop --user "$APPUSER" "$PKG" </dev/null
"$ADB" shell am start --user "$APPUSER" -n "$ACTIVITY" --es fuel "$FUEL" </dev/null
if [ "$DRIVE" = 1 ]; then
  python3 "$ROOT/scripts/virtual_console_drive.py" --follow-gpx "$GPX" > /tmp/record_drive.log 2>&1 &
  DRIVE_PID=$!
fi
if [ -n "$FRAMES" ]; then
  mkdir -p "$FRAMES"
  # 녹화 시작 기준 60초, 종료 3초 전. 입력은 보내지 않고 화면만 받는다.
  ( sleep 58; "$ADB" exec-out screencap -p </dev/null > "$FRAMES/${LOW}_rec_060s.png"
    sleep $(( SECS - 63 > 0 ? SECS - 63 : 0 )); "$ADB" exec-out screencap -p </dev/null > "$FRAMES/${LOW}_rec_$(printf %03d $((SECS - 3)))s.png" ) &
  FRAMES_PID=$!
fi
wait "$REC_PID" || true
[ -n "${FRAMES_PID:-}" ] && wait "$FRAMES_PID" 2>/dev/null || true
sleep 2
"$ADB" pull "$DEV" "$(winpath "$ROOT/docs/demo/$OUT")" </dev/null
"$ADB" shell rm -f "$DEV" </dev/null
echo "[OK] docs/demo/$OUT ($(du -h "$ROOT/docs/demo/$OUT" | cut -f1))"
# 재생 검사: 외부 입력이 끼면 재생이 흐트러진다
LOG="$("$ADB" logcat -d -s TacticalMain </dev/null | tr -d '\r' || true)"
n_start=$(grep -c '재생 시작 replayIndex=0' <<<"$LOG" || true)
n_arr=$(grep -c 'phase=PHASE_3_ARRIVED' <<<"$LOG" || true)
n_bad=$(grep -cE '조작:|onPause|거리 슬라이더' <<<"$LOG" || true)
echo "[i] logcat 검사: 재생 시작=$n_start 도착=$n_arr 조작/onPause/슬라이더=$n_bad"
grep -E '재생 시작|PHASE_|재생 끝' <<<"$LOG" | sed 's/^/    /' || true
if [ "$n_start" != 1 ] || [ "$n_arr" != 1 ] || [ "$n_bad" != 0 ]; then
  echo "[X] 재생 검사 실패 — 이 영상은 버리고 다시 녹화하세요" >&2; exit 7
fi
