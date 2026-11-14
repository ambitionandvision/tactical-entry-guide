#!/usr/bin/env bash
# assembleRelease 후 apk/<팀명>_TacticalEntryGuide_release.apk 로 복사한다.
#   TEAM_NAME=팀명 bash scripts/build_release_apk.sh      (미지정 시 ambitionandvision)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEAM_NAME="${TEAM_NAME:-ambitionandvision}"
bash "$ROOT/scripts/setup_local_properties.sh" >/dev/null
bash "$ROOT/scripts/gradle_win.sh" assembleRelease
SRC="$(ls -t "$ROOT"/android_pleos_app/app/build/outputs/apk/release/*.apk | head -1)"
mkdir -p "$ROOT/apk"
cp "$SRC" "$ROOT/apk/${TEAM_NAME}_TacticalEntryGuide_release.apk"
echo "[OK] apk/${TEAM_NAME}_TacticalEntryGuide_release.apk"
