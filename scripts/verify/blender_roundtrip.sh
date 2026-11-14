#!/usr/bin/env bash
# Blender 왕복 검증: sejong_costco_300m.obj -> (Blender: Path_03 +5m 동쪽 Apply) -> sejong_costco_custom.obj -> sync -> JSON 확인 -> 원복
set -u
cd "$(dirname "$0")/../.."
BL="/mnt/c/Program Files/Blender Foundation/Blender 4.2/blender.exe"
G=gis_data; T=$G/_rt_tmp
mkdir -p $T
cp $G/sejong_costco_custom.obj $T/custom.orig.obj
cp $G/sejong_costco_zone.json $T/zone.orig.json
cp $G/sejong_costco_custom.obj $T/custom.before_edit.obj
"$BL" --background --python "$(wslpath -w scripts/verify/blender_roundtrip_edit.py)" -- \
  "$(wslpath -w $G/sejong_costco_300m.obj)" "$(wslpath -w $T/edited.obj)" "$(wslpath -w $T/report.json)" > $T/blender.log 2>&1
tail -3 $T/blender.log
[ -f $T/edited.obj ] || { echo "Blender export 실패"; cat $T/blender.log | tail -20; exit 1; }
cp $T/edited.obj $G/sejong_costco_custom.obj
python3 scripts/sync_blender_to_app.py
python3 - <<'PY'
import json
o=json.load(open("gis_data/_rt_tmp/zone.orig.json")); n=json.load(open("gis_data/sejong_costco_zone.json"))
rep=json.load(open("gis_data/_rt_tmp/report.json"))
def pts(z): 
    d={p["id"]:(p["x"],p["z"]) for p in z["guidePathPoints"]+z["evGuidePathPoints"]}
    d.update({p["id"]:(p["x"],p["z"]) for p in z["hazardPins"]}); return d
a,b=pts(o),pts(n)
ok=True
for k in a:
    dx,dz=b[k][0]-a[k][0],b[k][1]-a[k][1]
    if k=="Path_03":
        good=abs(dx-5)<0.01 and abs(dz)<0.01
        print("Path_03  원본 (%.3f,%.3f) -> 갱신 (%.3f,%.3f) Δx=%+.4f Δz=%+.4f  %s"%(*a[k],*b[k],dx,dz,"OK" if good else "FAIL"))
    else:
        good=abs(dx)<1e-9 and abs(dz)<1e-9
    ok&=good
others=sum(1 for k in a if k!="Path_03")
print("다른 점 %d 개 불변: %s"%(others, all(abs(b[k][0]-a[k][0])<1e-9 and abs(b[k][1]-a[k][1])<1e-9 for k in a if k!="Path_03")))
# 북쪽 +Y 확인: Blender 의 Path_01 world 좌표 (x, y) 와 JSON (x, z)
bp=rep["Path_01_center_blender_xyz"]; jp=a["Path_01"]
print("Blender Path_01 world (x=%.3f, y=%.3f) vs JSON (x=%.3f, z=%.3f) -> 북쪽=+Y %s"%(bp[0],bp[1],jp[0],jp[1],"OK" if abs(bp[0]-jp[0])<0.01 and abs(bp[1]-jp[1])<0.01 else "FAIL"))
bm=rep["ORIGIN_RAMP_MARKER_center_blender_xyz"]; print("ORIGIN_RAMP_MARKER Blender (%.3f,%.3f)"%(bm[0],bm[1]))
print("Blender", rep["version"], " 오브젝트", len(rep["names"]))
print("ROUNDTRIP", "PASS" if ok else "FAIL")
PY
# 원복
cp $T/custom.orig.obj $G/sejong_costco_custom.obj
cp $T/zone.orig.json $G/sejong_costco_zone.json
echo "--- 원복 후 sync (변경 없음이어야 함)"
python3 scripts/sync_blender_to_app.py
cmp $G/sejong_costco_custom.obj $T/custom.before_edit.obj && echo "custom.obj 원복 확인(바이트 동일)"
cmp $G/sejong_costco_zone.json $T/zone.orig.json && echo "zone.json 원복 확인(바이트 동일)"
rm -rf $T
