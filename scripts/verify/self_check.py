#!/usr/bin/env python3
# 산출물 독립 검증 (추출 스크립트의 함수를 import 하지 않는다). 실패하면 종료코드 1.
import json, math, os, re, sys
import xml.etree.ElementTree as ET
import numpy as np
try:
    from shapely.geometry import Polygon
except ImportError:
    Polygon = None

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
G = os.path.join(ROOT, "gis_data")
fails = []
skipped = []
def skip(name, why):
    print("SKIP " + name + " : " + why)
    skipped.append(name)
def check(name, ok, detail=""):
    print(("PASS " if ok else "FAIL ") + name + (" : " + detail if detail else ""))
    if not ok: fails.append(name)

zone = json.load(open(os.path.join(G, "sejong_costco_zone.json"), encoding="utf-8"))
lat0, lon0 = zone["origin"]["lat"], zone["origin"]["lon"]
kx = 111320 * math.cos(math.radians(lat0))
def to_xz(lat, lon): return (lon - lon0) * kx, (lat - lat0) * 110574

# 1. 금지어 grep
forb = re.compile(r"UFID|명칭|용도|도엽|EPSG|출처|/mnt/|[A-Z]:\\|N3[AL]_|\.shp|\.dbf|36710011")
hits = 0
files = [os.path.join(G, f) for f in ("sejong_costco_zone.json", "sejong_costco_300m.obj", "sejong_costco_custom.obj")]
files += [os.path.join(ROOT, "android_pleos_app/app/src/main/assets", f) for f in ("sejong_costco_zone.json", "sejong_costco_custom.obj")]
files += [os.path.join(ROOT, "unity_tactical_module/Assets/Resources/sejong_costco_zone.json"),
          os.path.join(ROOT, "unity_tactical_module/Assets/Models/sejong_costco_300m.obj"),
          os.path.join(ROOT, "unity_tactical_module/Assets/Models/sejong_costco_custom.obj")]
for f in files:
    for i, ln in enumerate(open(f, encoding="utf-8"), 1):
        if forb.search(ln): hits += 1; print("   금지어:", f, i, ln.strip()[:80])
check("JSON·OBJ 금지어 grep 0건 (%d 파일)" % len(files), hits == 0, "hits=%d" % hits)

# 중첩 배열 금지
def nested(o):
    if isinstance(o, list): return any(isinstance(x, list) or nested(x) for x in o)
    if isinstance(o, dict): return any(nested(v) for v in o.values())
    return False
check("JSON 중첩 배열 없음", not nested(zone))

# 2. OBJ 객체
def parse(path):
    objs, order, verts, cur = {}, [], [], None
    faces = 0
    for ln in open(path, encoding="utf-8"):
        t = ln.split()
        if not t: continue
        if t[0] == "o": cur = t[1]; objs[cur] = set(); order.append(cur)
        elif t[0] == "v": verts.append((float(t[1]), float(t[2]), float(t[3])))
        elif t[0] == "f":
            faces += 1
            for x in t[1:]: objs[cur].add(int(x.split("/")[0]) - 1)
    return objs, order, verts, faces
objs, order, verts, nfaces = parse(os.path.join(G, "sejong_costco_300m.obj"))
need = ["ORIGIN_RAMP_MARKER", "TARGET_COSTCO_BUILDING", "SURROUND_BUILDINGS", "ROAD_SURFACE_300M", "Pin_Barrier_01", "Pin_NoTurn_01", "Pin_WaitLane_01"]
need += ["Path_%02d" % i for i in range(1, 9)] + [p["id"] for p in zone["evGuidePathPoints"]]
miss = [n for n in need if n not in objs]
check("5.3 오브젝트 전부 존재(%d개)" % len(need), not miss, "누락=%s" % miss)
check("전체 면 수 <= 6000", nfaces <= 6000, "faces=%d" % nfaces)
def center(name):
    ids = sorted(objs[name]); xs = [verts[i][0] for i in ids]; zs = [-verts[i][2] for i in ids]
    return (min(xs) + max(xs)) / 2, (min(zs) + max(zs)) / 2
cx, cz = center("ORIGIN_RAMP_MARKER")
check("원점 마커 중심 (0,0)", abs(cx) < 1e-6 and abs(cz) < 1e-6, "(%.4f, %.4f)" % (cx, cz))
bad = [p["id"] for p in zone["guidePathPoints"] + zone["evGuidePathPoints"] + zone["hazardPins"]
       if math.dist(center(p["id"]), (p["x"], p["z"])) > 0.002]
check("OBJ Path/Pin 바운딩박스 중심 == JSON 좌표", not bad, str(bad))
t_ids = sorted(objs["TARGET_COSTCO_BUILDING"]); ys = [verts[i][1] for i in t_ids]
T_H = zone["buildings"][0]["heightM"]
check("본관 높이 14m (OBJ == JSON heightM)", abs(max(ys) - 14.0) < 0.01 and abs(T_H - 14.0) < 1e-9, "OBJ %.1f / JSON %.1f" % (max(ys), T_H))
tf = zone["buildings"][0]["footprint"]; xs = [p["x"] for p in tf]; zs = [p["z"] for p in tf]
area = 0.5 * abs(sum(xs[i] * zs[(i + 1) % len(xs)] - xs[(i + 1) % len(xs)] * zs[i] for i in range(len(xs))))
print("   본관 범위 x[%.0f, %.0f] z[%.0f, %.0f] 꼭짓점 %d, 면적 %.0f㎡" % (min(xs), max(xs), min(zs), max(zs), len(tf), area))
check("본관이 잘리지 않고 전체 포함 (면적 >= 15,000㎡, 판매시설 폴리곤 약 15,3xx㎡)", area >= 15000, "%.0f㎡" % area)

# 2-1. RAMP_MAIN (선택 객체, 4차 추가 지시 2)
if "RAMP_MAIN" not in objs:
    check("RAMP_MAIN 존재", False)
else:
    check("RAMP_MAIN 존재", True)
    rids = sorted(objs["RAMP_MAIN"])
    rf = 0; cur = None
    for ln in open(os.path.join(G, "sejong_costco_300m.obj"), encoding="utf-8"):
        t = ln.split()
        if t and t[0] == "o": cur = t[1]
        elif t and t[0] == "f" and cur == "RAMP_MAIN": rf += 1
    check("RAMP_MAIN 면 수 <= 60", rf <= 60, "faces=%d" % rf)
    RV = np.array([(verts[i][0], verts[i][1], -verts[i][2]) for i in rids])
    low = RV[np.abs(RV[:, 1]) < 1e-6]; top_y = RV[:, 1].max(); high = RV[np.abs(RV[:, 1] - top_y) < 1e-6]
    # 낮은 끝 = 높이 0 이면서 윗면에 속한 두 점(높은 끝 반대쪽). 지면 꼭짓점 중 높은 끝에서 가장 먼 두 점.
    hc = high[:, [0, 2]].mean(axis=0)
    lo2 = low[np.argsort(-np.hypot(low[:, 0] - hc[0], low[:, 2] - hc[1]))[:2]]
    lc = lo2[:, [0, 2]].mean(axis=0)
    ent = zone["entries"][0]["position"]; e = np.array([ent["x"], ent["z"]])
    u = (hc - lc) / np.linalg.norm(hc - lc); n = np.array([u[1], -u[0]])
    print("   RAMP_MAIN 낮은 끝 (%.2f, %.2f) y=0, 높은 끝 (%.2f, %.2f) y=%.2f, 수평 %.1fm, 경사 %.1f%%" % (
        lc[0], lc[1], hc[0], hc[1], top_y, np.linalg.norm(hc - lc), 100 * top_y / np.linalg.norm(hc - lc)))
    check("RAMP_MAIN 낮은 끝 y=0", np.all(np.abs(lo2[:, 1]) < 1e-6))
    check("RAMP_MAIN 낮은 끝 중심이 입구에서 1m 이내", np.linalg.norm(lc - e) <= 1.0, "%.3fm" % np.linalg.norm(lc - e))
    check("RAMP_MAIN 높은 끝 y == 본관 높이", abs(top_y - T_H) < 0.01, "%.2f vs %.2f" % (top_y, T_H))
    if Polygon is None:
        skip("RAMP_MAIN 높은 끝 중심이 본관 외곽선 안 또는 2m 이내", "shapely 없음")
    else:
        from shapely.geometry import Point as _Pt
        tp = Polygon([(p["x"], p["z"]) for p in tf])
        hd = tp.exterior.distance(_Pt(*hc)); hin = tp.contains(_Pt(*hc))
        check("RAMP_MAIN 높은 끝 중심이 본관 외곽선 안 또는 2m 이내", hin or hd <= 2.0, "%s, 외곽선까지 %.2fm" % ("안쪽" if hin else "바깥", hd))

# 3. 좌표 역변환
fx = (0 / kx + lon0); fz = lat0 + 0 / 110574
x, z = to_xz(fz, fx)
check("로컬 (0,0) -> 위경도 -> 로컬 원점 일치", abs(x) < 1e-6 and abs(z) < 1e-6, "lat=%.7f lon=%.7f" % (fz, fx))
e = zone["entries"][0]["position"]
check("MAIN_PARKING 입구 (0,0)", abs(e["x"]) < 1e-6 and abs(e["z"]) < 1e-6)
check("MAIN_PARKING label '옥상 주차장 램프 입구'", zone["entries"][0]["label"] == "옥상 주차장 램프 입구", zone["entries"][0]["label"])
sn = zone["staticNotices"]; bp = next(p for p in zone["hazardPins"] if p["id"] == "Pin_Barrier_01")
check("현장 확인 반영: heightLimitM 2.1, barrierNote '차단기 없음', Pin_Barrier_01 label '높이 제한 2.1m'",
      sn["heightLimitM"] == 2.1 and "차단기 없음" in sn["barrierNote"] and bp["label"] == "높이 제한 2.1m",
      "%s / %s / %s" % (sn["heightLimitM"], sn["barrierNote"], bp["label"]))
check("위험 핀 전부 fieldVerified=false (위치 미측량)", all(p["fieldVerified"] is False for p in zone["hazardPins"]))
ev = zone["entries"][1]["position"]; print("   EV 입구 (%.1f, %.1f)" % (ev["x"], ev["z"]))

# 4. 스플라인 (독립 구현)
def crom(ctrl, alpha=0.5, ds=2.0):
    P = [np.array(p, float) for p in ctrl]; ext = [2*P[0]-P[1]] + P + [2*P[-1]-P[-2]]; out = []
    for i in range(1, len(ext)-2):
        p0,p1,p2,p3 = ext[i-1:i+3]
        t0 = 0.0; t1 = t0 + np.linalg.norm(p1-p0)**alpha; t2 = t1 + np.linalg.norm(p2-p1)**alpha; t3 = t2 + np.linalg.norm(p3-p2)**alpha
        for t in np.linspace(t1, t2, 80, endpoint=(i == len(ext)-3)):
            a1=(t1-t)/(t1-t0)*p0+(t-t0)/(t1-t0)*p1; a2=(t2-t)/(t2-t1)*p1+(t-t1)/(t2-t1)*p2; a3=(t3-t)/(t3-t2)*p2+(t-t2)/(t3-t2)*p3
            b1=(t2-t)/(t2-t0)*a1+(t-t0)/(t2-t0)*a2; b2=(t3-t)/(t3-t1)*a2+(t-t1)/(t3-t1)*a3
            out.append((t2-t)/(t2-t1)*b1+(t-t1)/(t2-t1)*b2)
    d = np.array(out); c = np.concatenate([[0], np.cumsum(np.hypot(*np.diff(d, axis=0).T))]); ss = np.arange(0, c[-1], ds)
    return np.column_stack([np.interp(ss, c, d[:,0]), np.interp(ss, c, d[:,1])])
def p2poly(pts, poly):
    a=poly[:-1][None]; b=poly[1:][None]; p=pts[:,None]; ab=b-a
    t=np.clip(((p-a)*ab).sum(2)/np.maximum((ab*ab).sum(2),1e-12),0,1)
    return np.sqrt(((p-(a+ab*t[:,:,None]))**2).sum(2)).min(1)

# 5. GPX
def read_gpx(fn):
    ns = {"g": "http://www.topografix.com/GPX/1/1"}
    r = ET.parse(os.path.join(G, fn)).getroot()
    kw = r.find("g:metadata/g:keywords", ns).text; desc = r.find("g:metadata/g:desc", ns).text
    rows = []
    for tp in r.iter("{http://www.topografix.com/GPX/1/1}trkpt"):
        from datetime import datetime
        t = datetime.strptime(tp.find("g:time", ns).text, "%Y-%m-%dT%H:%M:%SZ").timestamp()
        x, z = to_xz(float(tp.get("lat")), float(tp.get("lon")))
        sp = float(tp.find("g:extensions/g:speed", ns).text); crs = float(tp.find("g:extensions/g:course", ns).text)
        rows.append((t, x, z, sp, crs))
    return kw, desc, np.array(rows)

for fn, ctrl_key, must90 in (("sejong_costco_cn8_drive.gpx", "guidePathPoints", True), ("sejong_costco_ev_drive.gpx", "evGuidePathPoints", False)):
    print("\n=== %s ===" % fn)
    kw, desc, R = read_gpx(fn)
    if kw != "simulated":
        # import_real_gpx.py 로 교체한 실주행 로그. 합성 프로파일 전용 검사는 건너뛴다.
        check(fn + " 실주행 로그 keywords=real-drive, simulated 없음", kw == "real-drive", "keywords=%s" % kw)
        check(fn + " 실주행 로그 1Hz", np.all(np.diff(R[:, 0]) == 1.0), "n=%d" % len(R))
        ent = zone["entries"][0 if must90 else 1]["position"]
        dist0 = np.hypot(R[:, 1] - ent["x"], R[:, 2] - ent["z"])
        dur = R[-1, 0] - R[0, 0]
        print("   재생 길이 %.0fs, 시작 입구 직선거리 %.1fm, 입구 최근접 %.1fm" % (dur, dist0[0], dist0.min()))
        check(fn + " 시작 입구 직선거리 >= 300m (Phase 1->2 전환 관찰)", dist0[0] >= 300.0, "%.1fm" % dist0[0])
        check(fn + " 입구 15m 이내 도달", dist0.min() <= 15.0, "%.1fm" % dist0.min())
        check(fn + " 재생 길이 <= 125초 (시연 2분)", dur <= 125, "%.0fs" % dur)
        ctrl = np.array([[p["x"], p["z"]] for p in zone[ctrl_key]]); sp = crom(ctrl)
        d = p2poly(R[:, 1:3], sp)
        # Phase 2 구간: 300m 진입 ~ 입구 15m 도달 (도착 후 꼬리 제외)
        i3 = int(np.argmax(dist0 <= 300)); ia = int(np.argmax(dist0 <= 15))
        ph = np.zeros(len(R), bool); ph[i3:ia + 1] = True
        check(fn + " Phase2 구간 스냅(<=15m) 비율 >= 98%", (d[ph] <= 15).mean() >= 0.98, "%.1f%% (%d점)" % (100*(d[ph] <= 15).mean(), ph.sum()))
        check(fn + " Phase2 구간 오경고(>=20m) 0", (d[ph] >= 20).sum() == 0, "최대 이격 %.2fm, 평균 %.2fm" % (d[ph].max(), d[ph].mean()))
        for nm in ("metadata desc 시뮬레이션 명시", "시작 입구 직선거리 500~506m", "시작 속도 50km/h",
                   "Phase2 유지 구간 최고 속도 <= 25km/h", "도착 정차 >= 5초", "노이즈 없는 기준 궤적과 스플라인 편차 <= 5m",
                   "기준 궤적 속도 == GPX 속도 필드"):
            skip(fn + " " + nm, "합성 GPX 전용 항목 (실주행 로그)")
        continue
    check(fn + " metadata keywords=simulated, desc 에 시뮬레이션/비실주행 명시", kw == "simulated" and "시뮬레이션" in desc and "실주행 로그가 아니" in desc)
    dt = np.diff(R[:, 0])
    check(fn + " 1Hz (모든 간격 1.0s)", np.all(dt == 1.0), "n=%d" % len(R))
    ent = zone["entries"][0 if must90 else 1]["position"]
    dist0 = np.hypot(R[:, 1] - ent["x"], R[:, 2] - ent["z"])
    check(fn + " 시작 입구 직선거리 >= 500m", dist0[0] >= 500.0, "%.1fm" % dist0[0])
    check(fn + " 시작 속도 50km/h", abs(R[0, 3]*3.6 - 50) < 0.5, "%.1fkm/h" % (R[0, 3]*3.6))
    dur = R[-1, 0] - R[0, 0]
    print("   총 소요(측정값) %.0fs, 시작 입구 직선거리 %.1fm" % (dur, np.hypot(R[0, 1] - ent["x"], R[0, 2] - ent["z"])))
    if must90: check(fn + " 시작 입구 직선거리 500~506m", 500 <= dist0[0] <= 506, "%.1fm" % dist0[0])
    else: check(fn + " 시작 입구 직선거리 >= 500m (50km/h 주행 확보를 위해 길어질 수 있음)", dist0[0] >= 500, "%.1fm" % dist0[0])
    sp_k = R[:, 3] * 3.6
    i3 = int(np.argmax(dist0 <= 300)); k25 = i3 + int(np.argmax(sp_k[i3:] <= 25.01))
    check(fn + " Phase2 유지 구간(25km/h 도달 후) 최고 속도 <= 25km/h", sp_k[k25:].max() <= 25.01, "300m 진입 t=%ds(%.1fkm/h), 25km/h 도달 t=%ds, 이후 최고 %.2fkm/h" % (R[i3, 0]-R[0, 0], sp_k[i3], R[k25, 0]-R[0, 0], sp_k[k25:].max()))
    stop = int(np.sum(R[:, 3] < 0.05))
    check(fn + " 도착 정차 >= 5초(속도 0)", stop >= 5, "%d s" % stop)
    ctrl = np.array([[p["x"], p["z"]] for p in zone[ctrl_key]]); sp = crom(ctrl)
    d = p2poly(R[:, 1:3], sp)
    i300 = int(np.argmax(dist0 <= 300)); print("   300m 진입 t=%ds, 속도 %.1f -> " % (R[i300, 0]-R[0, 0], R[i300, 3]*3.6), end="")
    sp5 = R[min(i300+5, len(R)-1), 3]*3.6; print("5초 뒤 %.1fkm/h, 평균 감속 %.2f m/s²" % (sp5, (R[i300, 3]-R[min(i300+5, len(R)-1), 3])/5))
    ph = (dist0 <= 300)
    ip = os.path.join(ROOT, ".team", "gis_ideal_traj.json")
    if os.path.exists(ip):
        I = np.array(json.load(open(ip))["main" if must90 else "ev"])
        di = p2poly(I[ph[:len(I)], :2], sp)
        check(fn + " 노이즈 없는 기준 궤적(1Hz)과 스플라인 최대 편차 <= 5m (Phase2 구간)", di.max() <= 5.0, "max=%.2fm" % di.max())
        check(fn + " 기준 궤적 속도 == GPX 속도 필드", np.allclose(I[:, 2], R[:, 3], atol=0.01))
    check(fn + " Phase2 구간 GPX 점의 스플라인 이격 최대 <15m (노이즈 포함)", d[ph].max() < 15, "max=%.2fm 평균=%.2fm" % (d[ph].max(), d[ph].mean()))
    check(fn + " Phase2 구간 스냅(<=15m) 비율 >= 98%", (d[ph] <= 15).mean() >= 0.98, "%.1f%%" % (100*(d[ph] <= 15).mean()))
    check(fn + " 오경고(>=20m) 0", (d[ph] >= 20).sum() == 0)
    print("   %5s %9s %8s %9s %9s" % ("t(s)", "입구까지m", "km/h", "누적m", "이격m"))
    cum = np.concatenate([[0], np.cumsum(np.hypot(*np.diff(R[:, 1:3], axis=0).T))])
    for k in list(range(0, len(R), 10)) + [len(R)-1]:
        print("   %5d %9.1f %8.1f %9.0f %9.2f" % (R[k, 0]-R[0, 0], dist0[k], R[k, 3]*3.6, cum[k], d[k]))
    # 노이즈 제거한 이상 궤적과의 편차는 extract 로그의 '최대 편차' 참조
    est = np.hypot(*np.diff(R[:, 1:3], axis=0).T)
    print("   추정속도(좌표차) 최대 %.1fkm/h, 정차 구간 평균 %.2fkm/h" % (est.max()*3.6, est[-stop:].mean()*3.6 if stop else 0))
    ends = R[-1, 1:3]; tgt = zone["entries"][0 if must90 else 1]["position"]
    print("   마지막 점 입구까지 %.1fm (GNSS 노이즈 포함)" % math.dist(ends, (tgt["x"], tgt["z"])))

# 6. 스플라인 이상궤적 편차 / Path_01 거리
m = zone["guidePathPoints"]
check("Path_01 입구 직선거리 >= 355m", math.hypot(m[0]["x"], m[0]["z"]) >= 355, "%.1fm" % math.hypot(m[0]["x"], m[0]["z"]))
e0 = zone["evGuidePathPoints"][0]; dev0 = math.dist((e0["x"], e0["z"]), (ev["x"], ev["z"]))
check("PathEV_01 EV 입구 직선거리 >= 355m", dev0 >= 355, "%.1fm" % dev0)
last = m[-1]; check("Path_08 == MAIN_PARKING 입구", math.hypot(last["x"], last["z"]) < 0.01)
evl = zone["evGuidePathPoints"][-1]; check("PathEV 마지막 == EV 입구", math.dist((evl["x"], evl["z"]), (ev["x"], ev["z"])) < 0.01)
print("   현장 확인된 핀 %d / %d" % (sum(1 for p in zone["hazardPins"] if p["fieldVerified"]), len(zone["hazardPins"])))
_pv = os.path.join(ROOT, ".team/gis_preview.png")
if os.path.isfile(_pv): check("preview PNG 존재", os.path.getsize(_pv) > 10000)
else: print("SKIP preview PNG (지도 재생성을 하지 않은 사본)")
if skipped:
    print("\n건너뛴 항목 %d개:" % len(skipped))
    for nm in skipped: print("   - " + nm)
print("\nSELF_CHECK", "FAIL %s" % fails if fails else "ALL PASS")
sys.exit(1 if fails else 0)
