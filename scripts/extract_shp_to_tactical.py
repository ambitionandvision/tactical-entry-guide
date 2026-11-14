#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""수치지도 SHP -> 마이크로 진입로 3D 전술 가이드 산출물 생성 파이프라인.

산출물 (기본 gis_data/):
  sejong_costco_zone.json        앱·Unity 공용 Zone 데이터 (TEAM_WORKSPACE 5.2)
  sejong_costco_300m.obj / sejong_costco_custom.obj   Blender 정합용 메쉬 (5.3). custom 은 300m 의 복사본으로 초기화
  sejong_costco_cn8_drive.gpx    MAIN_PARKING 합성 주행 GPX (시뮬레이션)
  sejong_costco_ev_drive.gpx     EV_CHARGING_ENTRY 합성 주행 GPX (시뮬레이션)
  (검토용 지도는 .team/gis_preview.png, 기준 궤적은 .team/gis_ideal_traj.json)

좌표 계약 (5.1): x = (lon - lon0) * 111320 * cos(lat0), z = (lat - lat0) * 110574.
OBJ 파일은 `v <x> <y> <-z>` 로 쓴다.

사용 예:
  python3 scripts/extract_shp_to_tactical.py
  python3 scripts/extract_shp_to_tactical.py --origin 36.467786 127.269966 --radius 300 --seed 42
기본 원점은 MAIN_PARKING 입구(램프 오르막 시작점)다. 경로 힌트는 HINT_ORIGIN 기준이라 원점을 바꿔도 같은 실제 위치를 가리킨다.
"""
import argparse
import heapq
import json
import math
import os
import random
import shutil
import subprocess
import sys
from datetime import datetime, timedelta, timezone

try:
    import numpy as np
    import shapefile
    import shapely
    from pyproj import CRS, Transformer
    from shapely.geometry import LineString, Point, Polygon, MultiPolygon, shape
    from shapely.geometry.polygon import orient
except ImportError:
    print("[setup] 의존성 설치: pip install --user pyshp pyproj shapely numpy matplotlib", flush=True)
    subprocess.check_call([sys.executable, "-m", "pip", "install", "--user", "pyshp", "pyproj",
                           "shapely", "numpy", "matplotlib"])
    import numpy as np
    import shapefile
    import shapely
    from pyproj import CRS, Transformer
    from shapely.geometry import LineString, Point, Polygon, MultiPolygon, shape
    from shapely.geometry.polygon import orient

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# 기본 원점 = MAIN_PARKING 입구 = 실주행 로그(2026-11-13)에서 램프 오르막이 시작되는 지점
DEFAULT_ORIGIN = (36.467786, 127.269966)
# 아래 경로 힌트는 모두 이 기준 원점(1~3차 원점)의 로컬 좌표다. 원점을 바꿔도 힌트가 가리키는 실제 위치는
# 움직이지 않도록 hints_to_abs() 가 이 기준 원점으로 절대좌표(EPSG:5186)를 만든 뒤 현재 프레임으로 다시 바꾼다.
HINT_ORIGIN = (36.469512, 127.270845)
MAIN_ENTRY_LL = DEFAULT_ORIGIN   # MAIN_PARKING 입구 위경도 (램프 오르막 시작점)
M_PER_DEG_LON_COS = 111320.0   # x = dlon * 111320 * cos(lat0)
M_PER_DEG_LAT = 110574.0       # z = dlat * 110574
FACE_BUDGET = 6000

L_BUILDING = "N3A_B0010000"
L_ROADSURF = "N3A_A0010000"
L_CENTER = "N3L_A0020000"
L_CHARGER = "N3A_C0423365"


def _hint_xz(lat, lon):
    """위경도 -> 힌트 기준 원점(HINT_ORIGIN) 로컬 좌표 (5.1 수식)."""
    kx = M_PER_DEG_LON_COS * math.cos(math.radians(HINT_ORIGIN[0]))
    return ((lon - HINT_ORIGIN[1]) * kx, (lat - HINT_ORIGIN[0]) * M_PER_DEG_LAT)


MAIN_ENTRY_HINT = _hint_xz(*MAIN_ENTRY_LL)   # 약 (-78.7, -190.9)

# ---------------------------------------------------------------- 경로 힌트 (HINT_ORIGIN 기준 로컬 좌표)
# 'G' = 도로 중심선 그래프에 흡착해 따라간다, 'P' = 그 점을 그대로 지난다.
# 북서 대로 -> 남북도로 진입까지는 1~3차와 같다. 남북도로 이후는 2026-11-13 실주행 로그(1Hz)의 경유점이다.
MAIN_HINTS = [
    ("G", (-306, 416)), ("G", (-159, 220)), ("G", (-133, 190)), ("G", (-89, 173)), ("G", (40, 140)),
    ("P", (86, 139)),   # 지표 중심선이 끊긴 교차로 내부 직접 연결 후 우회전
    ("G", (84, 120)),
    # 남북도로 남하
    ("G", (76, 109)), ("G", (63, 53)), ("G", (51, -3)), ("G", (45, -30)), ("G", (36, -68)),
    # 우회전 (실주행 약 (26,-77)). 이 힌트는 중심선에서 9.8m 떨어져 직선 처리되며 모서리를 대각선으로 깎았다.
    # 실주행 회전 꼭짓점에 맞춘 (36,-79) 를 직접 지나게 해 회전부 실주행-합성 궤적 차이를 약 2.7m 로 줄였다.
    ("P", (36, -79)),
    # 동서도로 서진
    ("G", (3, -77)), ("G", (-31, -72)), ("G", (-69, -65)), ("G", (-103, -56)), ("G", (-131, -48)), ("G", (-162, -42)),
    ("G", (-176, -55)),   # 좌회전 (실주행은 (-177,-60) 에서 8초 정지 후 좌회전)
    # 남하 후 동쪽으로 굽음
    ("G", (-185, -81)), ("G", (-189, -100)), ("G", (-181, -115)), ("G", (-162, -125)),
    # 본관 북측 도로 동진
    ("G", (-138, -133)), ("G", (-114, -140)), ("G", (-93, -146)), ("G", (-78, -149)),
    ("P", (-68, -158)),   # 최종 우회전: 지도 도로 중심선에서 벗어나는 지점
    ("P", (-74, -179)),   # 램프 진입 (중심선 없음, 직접 연결)
    ("P", MAIN_ENTRY_HINT),
]
# EV 경로: 저작(가정) 경로. 위 경로의 (-89,173) 에서 분기해 남하 -> 동서 생활도로 -> 후보 출입구 -> 동측 -> 충전소
EV_HINTS_TAIL = [
    ("G", (-181, -112)), ("G", (-61, -140)), ("P", (-58, -158)), ("P", (-52, -190)),
    ("P", (-52, -262)), ("P", (-80, -283)), ("P", (-118, -286)),
]
EV_BRANCH_INDEX = 4  # MAIN_HINTS[:4] 까지 공유 ((-89,173) 포함)
EV_ENTRY_HINT = (-118.0, -286.0)
# 램프 형상 근거: 2026-11-13 실주행 로그의 램프 구간 점 (HINT_ORIGIN 기준 로컬 좌표). 마지막 점 = 꼭대기(높은 끝 중심).
RAMP_LOG_HINTS = [(-78.7, -190.9), (-86, -211), (-94, -234), (-101, -252), (-108, -269), (-114, -279)]
RAMP_WIDTH_M = 6.0
RAMP_SEGMENTS = 6         # 윗면 길이 방향 칸 수 (4~8)
# 본관 높이: 실주행 로그에서 옥상 주차면이 도로면보다 약 14m 높다 (4차 추가 지시 2). 1~3차의 층수x5m=20m 는 가정값이었다.
TARGET_HEIGHT_M = 14.0
# 현장 확인 사실 (사용자, 2026-11-13): 램프 입구에 차단기·발권기 없음, 높이 제한 표지 2.1m
HEIGHT_LIMIT_M = 2.1
# right2 = 위험 핀 기준 모서리 (4차: 최종 우회전 지점으로 이동)
CORNER_HINTS_MAIN = {"right1": (86, 139), "right_ns_to_ew": (36, -79), "left": (-176, -55), "right2": (-68, -158)}


class Frame:
    """5.1 의 단일 수식. cos 인자는 라디안."""

    def __init__(self, lat0, lon0):
        self.lat0, self.lon0 = lat0, lon0
        self.kx = M_PER_DEG_LON_COS * math.cos(math.radians(lat0))

    def to_xz(self, lon, lat):
        return (np.asarray(lon) - self.lon0) * self.kx, (np.asarray(lat) - self.lat0) * M_PER_DEG_LAT

    def to_ll(self, x, z):
        return self.lat0 + np.asarray(z) / M_PER_DEG_LAT, self.lon0 + np.asarray(x) / self.kx


# ---------------------------------------------------------------- 입력
def default_shp_dir():
    local = os.path.join(ROOT, "source_shp")
    # 프로젝트의 source_shp/ 가 기본이다. 다른 곳에 있으면 --shp-dir 로 준다.
    return local


def find_layer(root, code):
    hits = []
    for dp, _dn, fn in os.walk(root):
        for f in fn:
            if f.lower() == code.lower() + ".shp":
                hits.append(os.path.join(dp, f[:-4]))
    return sorted(hits)


def detect_crs(base, sample_shapes):
    """.prj 우선, 실패하면 좌표 범위로 판별."""
    prj = base + ".prj"
    if os.path.exists(prj):
        try:
            txt = open(prj, "r", encoding="utf-8", errors="ignore").read()
            crs = CRS.from_wkt(txt)
            epsg = crs.to_epsg()
            if epsg:
                return CRS.from_epsg(epsg), "prj(EPSG:%d)" % epsg
            if "Korea_2000_Central_Belt_2010" in txt or ("false_easting\",200000" in txt.replace(" ", "")
                                                          and "false_northing\",600000" in txt.replace(" ", "")):
                return CRS.from_epsg(5186), "prj(Korea 2000 Central Belt 2010 -> 5186)"
            return crs, "prj(wkt)"
        except Exception:
            pass
    pts = [p for s in sample_shapes[:20] for p in s.points[:5]]
    if pts:
        x, y = pts[0]
        if 120 < x < 135 and 30 < y < 40:
            return CRS.from_epsg(4326), "range(WGS84)"
        if 1e5 < x < 3e5 and 4e5 < y < 7e5:
            return CRS.from_epsg(5186), "range(5186)"
    return CRS.from_epsg(5186), "default(5186)"


def read_layer(paths, frame, label):
    """-> list of (shapely geom in local x,z , record dict). 여러 도엽이면 합친다."""
    out = []
    for base in paths:
        r = shapefile.Reader(base, encoding="euc-kr", encodingErrors="replace")
        shps = r.shapes()
        crs, how = detect_crs(base, shps)
        tr = Transformer.from_crs(crs, CRS.from_epsg(4326), always_xy=True)
        names = [f[0] for f in r.fields[1:]]
        print("  [%s] %s: %d 개, CRS=%s" % (label, os.path.basename(base), len(shps), how))
        for s, rec in zip(shps, r.records()):
            if s.shapeType == 0 or not s.points:
                continue
            g = shape(s.__geo_interface__)
            if not g.is_valid:
                g = g.buffer(0)
            if g.is_empty:
                continue

            def fn(c, tr=tr):
                lon, lat = tr.transform(c[:, 0], c[:, 1])
                x, z = frame.to_xz(lon, lat)
                return np.column_stack([x, z])
            out.append((shapely.transform(g, fn), dict(zip(names, rec))))
    return out


def src_hint_to_local(frame, h_abs):
    lon, lat = _T5186_TO_LL.transform(h_abs[0], h_abs[1])
    x, z = frame.to_xz(lon, lat)
    return float(x), float(z)


_T5186_TO_LL = Transformer.from_crs(CRS.from_epsg(5186), CRS.from_epsg(4326), always_xy=True)
_TLL_TO_5186 = Transformer.from_crs(CRS.from_epsg(4326), CRS.from_epsg(5186), always_xy=True)


def hints_to_abs(hints):
    f0 = Frame(*HINT_ORIGIN)
    out = []
    for kind, (x, z) in hints:
        lat, lon = f0.to_ll(x, z)
        e, n = _TLL_TO_5186.transform(float(lon), float(lat))
        out.append((kind, (round(e, 3), round(n, 3))))
    return out


# ---------------------------------------------------------------- 도로 그래프
class RoadGraph:
    def __init__(self, lines, step=4.0, join=2.0):
        self.pts = []      # node -> (x,z)
        self.adj = []      # node -> [(node, w)]
        self.attr = []     # node -> (lanes, width)
        for geom, lanes, width in lines:
            segs = geom.geoms if hasattr(geom, "geoms") else [geom]
            for ls in segs:
                if ls.length < 0.5:
                    continue
                n = max(1, int(math.ceil(ls.length / step)))
                prev = None
                for i in range(n + 1):
                    p = ls.interpolate(ls.length * i / n)
                    k = len(self.pts)
                    self.pts.append((p.x, p.y))
                    self.adj.append([])
                    self.attr.append((lanes, width))
                    if prev is not None:
                        d = math.dist(self.pts[prev], self.pts[k])
                        self.adj[prev].append((k, d))
                        self.adj[k].append((prev, d))
                    prev = k
        arr = np.array(self.pts)
        self.arr = arr
        cell = {}
        for i, (x, z) in enumerate(self.pts):
            cell.setdefault((int(x // join), int(z // join)), []).append(i)
        for i, (x, z) in enumerate(self.pts):
            cx, cz = int(x // join), int(z // join)
            for dx in (-1, 0, 1):
                for dz in (-1, 0, 1):
                    for j in cell.get((cx + dx, cz + dz), []):
                        if j > i:
                            d = math.dist(self.pts[i], self.pts[j])
                            if d <= join:
                                self.adj[i].append((j, d + 0.01))
                                self.adj[j].append((i, d + 0.01))

    def nearest(self, p):
        d = np.hypot(self.arr[:, 0] - p[0], self.arr[:, 1] - p[1])
        i = int(np.argmin(d))
        return i, float(d[i])

    def path(self, a, b):
        dist = {a: 0.0}
        prev = {}
        pq = [(0.0, a)]
        while pq:
            d, u = heapq.heappop(pq)
            if u == b:
                break
            if d > dist.get(u, 1e18):
                continue
            for v, w in self.adj[u]:
                nd = d + w
                if nd < dist.get(v, 1e18):
                    dist[v] = nd
                    prev[v] = u
                    heapq.heappush(pq, (nd, v))
        if b not in dist:
            return None
        out = [b]
        while out[-1] != a:
            out.append(prev[out[-1]])
        return [self.pts[i] for i in reversed(out)]


def follow_hints(graph, hints_local, snap_max=8.0):
    """힌트 목록 -> 중심 경로 폴리라인. 반환: (pts, notes)"""
    pts, notes = [], []
    snapped = []
    for kind, p in hints_local:
        if kind == "G":
            i, d = graph.nearest(p)
            if d <= snap_max:
                snapped.append(("G", p, i))
                continue
            notes.append("힌트 %s 는 중심선에서 %.1fm 떨어져 직선 처리" % (tuple(round(v) for v in p), d))
        snapped.append(("P", p, None))
    for k, (kind, p, node) in enumerate(snapped):
        if k == 0:
            pts.append(graph.pts[node] if kind == "G" else p)
            continue
        pk, _pp, pnode = snapped[k - 1]
        if kind == "G" and pk == "G":
            seg = graph.path(pnode, node)
            if seg is None:
                notes.append("도로 그래프 연결 실패 -> 직선: %s -> %s" % (tuple(round(v) for v in _pp), tuple(round(v) for v in p)))
                pts.append(graph.pts[node])
            else:
                pts.extend(seg[1:])
        else:
            pts.append(graph.pts[node] if kind == "G" else p)
    return pts, notes


# ---------------------------------------------------------------- 경로 기하
def dedupe(pts, eps=0.05):
    out = [pts[0]]
    for p in pts[1:]:
        if math.dist(p, out[-1]) > eps:
            out.append(p)
    return out


def heading_vec(a, b):
    d = math.dist(a, b)
    return ((b[0] - a[0]) / d, (b[1] - a[1]) / d) if d > 1e-9 else (0.0, 1.0)


def lane_offset(lanes, width):
    """우측 끝 차로 중심의 중심선 대비 우측 오프셋(m)."""
    per_dir = max(1, int(lanes) // 2)
    lane_w = 3.5
    off = (per_dir - 0.5) * lane_w
    if width and width > 0:
        off = min(off, max(0.0, width / 2.0 - lane_w / 2.0))
    return off


def attr_offsets(graph, pts, max_dist=10.0):
    offs = []
    for p in pts:
        i, d = graph.nearest(p)
        if d > max_dist:
            offs.append(0.0)
        else:
            lanes, width = graph.attr[i]
            offs.append(lane_offset(lanes, width))
    return offs


def fillet_path(pts, offs, radius=9.0, min_turn_deg=6.0, soft_radius=45.0, sharp_deg=50.0):
    """꼭짓점을 필렛한다. 최종 우측 차로 궤적의 곡률 반경이 radius 가 되도록 중심선 반경을 보정한다.
    반환: (점 목록, 점별 오프셋 목록)"""
    pts = dedupe(pts)
    n = len(pts)
    if n < 3:
        return pts, offs[:n]
    out, out_off = [pts[0]], [offs[0]]
    for i in range(1, n - 1):
        a, b, c = pts[i - 1], pts[i], pts[i + 1]
        u = heading_vec(a, b)
        v = heading_vec(b, c)
        cross = u[0] * v[1] - u[1] * v[0]          # >0 좌회전(x동,z북 기준 반시계)
        dot = max(-1.0, min(1.0, u[0] * v[0] + u[1] * v[1]))
        theta = math.acos(dot)
        if math.degrees(theta) < min_turn_deg:
            out.append(b)
            out_off.append(offs[i])
            continue
        right_turn = cross < 0
        off = offs[i]
        # 직각에 가까운 교차로 코너는 반경 radius(8~10m), 완만한 꺾임(도로 굴곡)은 soft_radius 로 부드럽게
        rr = radius if math.degrees(theta) >= sharp_deg else soft_radius
        rc = rr + off if right_turn else max(2.0, rr - off)
        t = rc * math.tan(theta / 2.0)
        lim = 0.45 * min(math.dist(a, b), math.dist(b, c))
        if t > lim:
            t = lim
            rc = t / math.tan(theta / 2.0)
        p1 = (b[0] - u[0] * t, b[1] - u[1] * t)
        p2 = (b[0] + v[0] * t, b[1] + v[1] * t)
        sgn = 1.0 if cross > 0 else -1.0
        nrm = (-u[1] * sgn, u[0] * sgn)               # 회전 중심 방향(왼쪽 법선이 좌회전 중심)
        cen = (p1[0] + nrm[0] * rc, p1[1] + nrm[1] * rc)
        a0 = math.atan2(p1[1] - cen[1], p1[0] - cen[0])
        steps = max(3, int(math.ceil(rc * theta / 1.0)))
        for k in range(steps + 1):
            ang = a0 + sgn * theta * k / steps
            out.append((cen[0] + rc * math.cos(ang), cen[1] + rc * math.sin(ang)))
            out_off.append(offs[i])
    out.append(pts[-1])
    out_off.append(offs[-1])
    return out, out_off


def resample(pts, ds=1.0, offs=None):
    ls = LineString(pts)
    n = max(2, int(round(ls.length / ds)) + 1)
    ss = np.linspace(0, ls.length, n)
    out = [ls.interpolate(float(s)) for s in ss]
    res = [(p.x, p.y) for p in out]
    if offs is None:
        return res, ss
    cum = np.concatenate([[0], np.cumsum([math.dist(pts[i], pts[i + 1]) for i in range(len(pts) - 1)])])
    o = np.interp(ss, cum, offs)
    return res, ss, o


def smooth(vals, win):
    k = np.ones(win) / win
    pad = win // 2
    v = np.pad(vals, (pad, pad), mode="edge")
    return np.convolve(v, k, mode="valid")[: len(vals)]


def build_trajectory(graph, hints_local, ramp_fade_m=15.0, fillet_r=9.0):
    """힌트 -> (중심 경로, 우측 차로 궤적(1m 간격), 메모)"""
    pts, notes = follow_hints(graph, hints_local)
    pts = dedupe(pts)
    offs = attr_offsets(graph, pts)
    # 마지막 구간(램프/주차장 진입)은 차로 오프셋을 0 으로 수렴
    fp, fo = fillet_path(pts, offs, radius=fillet_r)
    dense, ss, o = resample(fp, 1.0, fo)
    o = smooth(np.asarray(o), 21)
    total = ss[-1]
    fade = np.clip((total - ss) / ramp_fade_m, 0.0, 1.0)
    o = o * fade
    dense = np.array(dense)
    d = np.gradient(dense, axis=0)
    d = np.array([smooth(d[:, 0], 5), smooth(d[:, 1], 5)]).T
    nrm = np.hypot(d[:, 0], d[:, 1])
    nrm[nrm < 1e-9] = 1.0
    d = d / nrm[:, None]
    right = np.column_stack([d[:, 1], -d[:, 0]])
    traj = dense + right * o[:, None]
    traj[-1] = dense[-1]
    # 곡선 재샘플 (오프셋으로 간격이 변함)
    tr2, _ = resample([tuple(p) for p in traj], 1.0)
    return dense, np.array(tr2), notes


def ensure_start_distance(traj, ref, min_dist=500.0, margin=5.0):
    """궤적 시작점의 입구(ref) 직선거리가 min_dist 미만이면 진행 반대 방향 직선으로 연장."""
    d0 = math.hypot(traj[0][0] - ref[0], traj[0][1] - ref[1])
    if d0 >= min_dist:
        return traj, 0.0
    u = heading_vec(traj[5], traj[0])
    need = (min_dist + margin) - d0
    ext = []
    L = 0.0
    p = traj[0]
    while L < need * 3 and math.hypot(p[0] - ref[0], p[1] - ref[1]) < min_dist + margin:
        L += 1.0
        p = (traj[0][0] + u[0] * L, traj[0][1] + u[1] * L)
        ext.append(p)
    return np.vstack([np.array(ext[::-1]), traj]), L


def start_candidates(traj, ref, target=504.5):
    """GPX 시작 후보: 입구 직선거리가 target(500m 이상, 505m 근처)인 지점부터 시작점을 뒤로 5m 씩 물린다."""
    dd = np.hypot(traj[:, 0] - ref[0], traj[:, 1] - ref[1])
    k0 = int(np.argmax(dd <= target))
    return list(range(k0, -1, -5)) or [0]


# ---------------------------------------------------------------- 스플라인 (5.4)
def catmull_rom(ctrl, alpha=0.5, ds=2.0):
    """centripetal Catmull-Rom. 양 끝은 끝점 반사. 호길이 ds 간격 재샘플."""
    P = [np.array(p, dtype=float) for p in ctrl]
    if len(P) == 2:
        return np.array([P[0] + (P[1] - P[0]) * t for t in np.linspace(0, 1, max(2, int(np.linalg.norm(P[1] - P[0]) / ds) + 1))])
    ext = [2 * P[0] - P[1]] + P + [2 * P[-1] - P[-2]]
    dense = []
    for i in range(1, len(ext) - 2):
        p0, p1, p2, p3 = ext[i - 1], ext[i], ext[i + 1], ext[i + 2]
        t0 = 0.0
        t1 = t0 + max(np.linalg.norm(p1 - p0), 1e-6) ** alpha
        t2 = t1 + max(np.linalg.norm(p2 - p1), 1e-6) ** alpha
        t3 = t2 + max(np.linalg.norm(p3 - p2), 1e-6) ** alpha
        for t in np.linspace(t1, t2, 60, endpoint=(i == len(ext) - 3)):
            a1 = (t1 - t) / (t1 - t0) * p0 + (t - t0) / (t1 - t0) * p1
            a2 = (t2 - t) / (t2 - t1) * p1 + (t - t1) / (t2 - t1) * p2
            a3 = (t3 - t) / (t3 - t2) * p2 + (t - t2) / (t3 - t2) * p3
            b1 = (t2 - t) / (t2 - t0) * a1 + (t - t0) / (t2 - t0) * a2
            b2 = (t3 - t) / (t3 - t1) * a2 + (t - t1) / (t3 - t1) * a3
            dense.append((t2 - t) / (t2 - t1) * b1 + (t - t1) / (t2 - t1) * b2)
    dense = np.array(dense)
    seg = np.hypot(*np.diff(dense, axis=0).T)
    cum = np.concatenate([[0], np.cumsum(seg)])
    n = max(2, int(round(cum[-1] / ds)) + 1)
    ss = np.linspace(0, cum[-1], n)
    return np.column_stack([np.interp(ss, cum, dense[:, 0]), np.interp(ss, cum, dense[:, 1])])


def point_to_poly_dist(pts, poly):
    """pts (N,2) 각각에서 폴리라인 poly (M,2) 까지의 최소 거리."""
    a = poly[:-1][None, :, :]
    b = poly[1:][None, :, :]
    p = pts[:, None, :]
    ab = b - a
    t = np.clip(np.sum((p - a) * ab, axis=2) / np.maximum(np.sum(ab * ab, axis=2), 1e-12), 0, 1)
    proj = a + ab * t[:, :, None]
    return np.sqrt(np.sum((p - proj) ** 2, axis=2)).min(axis=1)


def spline_deviation(ctrl, traj_part):
    sp = catmull_rom(ctrl)
    d1 = point_to_poly_dist(traj_part, sp)
    d2 = point_to_poly_dist(sp, traj_part)
    return max(d1.max(), d2.max()), d1.max(), d2.max()


def choose_controls(traj, ref, n_target=None, max_dev=3.0, n_min=8, n_max=16, direct_dist=365.0):
    """궤적 위에서 제어점 선택. Path_01 은 활성 입구(ref) 직선거리 ≈365m(>=355m) 지점.
    n_target 이 주어지면 정확히 그 개수, 아니면 편차가 max_dev 이하가 될 때까지(n_min..n_max)."""
    dist = np.hypot(traj[:, 0] - ref[0], traj[:, 1] - ref[1])
    i1 = int(np.argmax(dist <= direct_dist))
    part = traj[i1:]
    n = len(part)
    idx = [0, n - 1]

    def dev(ix):
        return spline_deviation(part[sorted(ix)], part)[0]

    target = n_target or n_max
    while len(idx) < target:
        sp = catmull_rom(part[sorted(idx)])
        d = point_to_poly_dist(part, sp)
        # 이미 선택된 점은 제외하고 편차가 가장 큰 곳 추가
        d[sorted(idx)] = -1
        k = int(np.argmax(d))
        idx.append(k)
        if n_target is None and len(idx) >= n_min and dev(idx) <= max_dev:
            break
    idx = sorted(idx)
    # 좌표하강으로 최대 편차 줄이기 (양 끝은 고정)
    best = dev(idx)
    for _ in range(6):
        improved = False
        for j in range(1, len(idx) - 1):
            for step in (-12, -8, -5, -3, -2, -1, 1, 2, 3, 5, 8, 12):
                cand = idx[j] + step
                if cand <= idx[j - 1] + 2 or cand >= idx[j + 1] - 2:
                    continue
                trial = idx[:j] + [cand] + idx[j + 1:]
                dv = dev(trial)
                if dv < best - 1e-3:
                    best, idx, improved = dv, trial, True
        if not improved:
            break
    return part[idx], i1, [i1 + k for k in idx], best


def choose_controls_phase2(traj, ref, n=8, direct_dist=365.0, phase2_dist=300.0, min_corner_span=5):
    """주 경로 제어점 (4차). Path_01 = 입구 직선거리 365m(>=355m) 지점, 마지막 = 입구.
    목표 = Phase 2 구간(입구 직선거리 <= 300m) 궤적 점에서 스플라인까지 최대 거리. Phase 1 쪽(300~365m)과 반대 방향
    (스플라인 -> 궤적) 최대 거리는 0.5배 가중으로 함께 묶어 스플라인이 궤적에서 벗어나지 않게 한다.
    1) 회전 지점(방위 변화가 큰 구간, 짧은 잔떨림 제외)으로 시드를 만들고 2) 인덱스 좌표하강 3) 평면 위치 미세 조정.
    반환: (제어점 배열, i1, Phase 2 최대 편차, 전체 구간 최대 편차)"""
    dist = np.hypot(traj[:, 0] - ref[0], traj[:, 1] - ref[1])
    i1 = int(np.argmax(dist <= direct_dist))
    part = traj[i1:]
    m = len(part)
    p2 = part[np.hypot(part[:, 0] - ref[0], part[:, 1] - ref[1]) <= phase2_dist]

    def obj_c(c):
        sp = catmull_rom(c)
        return max(point_to_poly_dist(p2, sp).max(),
                   0.5 * point_to_poly_dist(part, sp).max(), 0.5 * point_to_poly_dist(sp, part).max())

    def obj(idx):
        return obj_c(part[idx])
    hd = np.unwrap(np.arctan2(np.gradient(part[:, 1]), np.gradient(part[:, 0])))
    turn = np.array([hd[min(m - 1, i + 6)] - hd[max(0, i - 6)] for i in range(m)])
    corners, i = [], 0
    while i < m:
        if abs(turn[i]) > 0.35:
            j = i
            while j < m and abs(turn[j]) > 0.35:
                j += 1
            if j - i >= min_corner_span:
                corners.append((i + int(np.argmax(abs(turn[i:j]))), abs(turn[i:j]).max()))
            i = j
        else:
            i += 1
    cs = sorted(k for k, _a in sorted(corners, key=lambda t: -t[1])[: n - 2])

    def fit(lst):
        """내부 점이 n-2 개가 되도록: 모자라면 가장 긴 간격의 중점을 넣고, 넘치면 가장 가까운 두 점을 합친다."""
        lst = sorted(set(int(v) for v in lst if 0 < v < m - 1))
        while len(lst) < n - 2:
            full = [0] + lst + [m - 1]
            g = int(np.argmax(np.diff(full)))
            lst = sorted(set(lst + [(full[g] + full[g + 1]) // 2]))
        while len(lst) > n - 2:
            g = int(np.argmin(np.diff(lst)))
            lst = lst[:g] + [(lst[g] + lst[g + 1]) // 2] + lst[g + 2:]
        return [0] + lst + [m - 1]
    seeds = [fit(cs)]
    if cs:
        seeds.append(fit([max(1, cs[0] - 15), cs[0] + 15] + cs[1:]))
        seeds.append(fit([max(1, cs[0] - 15), cs[0] + 15] + cs[1:] + [(cs[0] + cs[1]) // 2] if len(cs) > 1 else cs))
    _c, _i1, gidx, _d = choose_controls(traj, ref, n_target=n, direct_dist=direct_dist)
    seeds.append([k - i1 for k in gidx])
    best = None
    for sd in seeds:
        idx = sorted(set(int(v) for v in sd))
        if len(idx) != n:
            continue
        v = obj(idx)
        for st0 in (16, 8, 4, 2, 1):
            imp = True
            while imp:
                imp = False
                for j in range(1, n - 1):
                    for st in (-st0, st0):
                        c = idx[j] + st
                        if c <= idx[j - 1] + 1 or c >= idx[j + 1] - 1:
                            continue
                        t = idx[:j] + [c] + idx[j + 1:]
                        w = obj(t)
                        if w < v - 1e-3:
                            v, idx, imp = w, t, True
        if best is None or v < best[0]:
            best = (v, idx)
    ctrl = part[best[1]].astype(float)
    v = best[0]
    for step in (2.0, 1.0, 0.5):
        imp = True
        while imp:
            imp = False
            for j in range(1, n - 1):
                for dx, dz in ((step, 0), (-step, 0), (0, step), (0, -step), (step, step), (-step, -step), (step, -step), (-step, step)):
                    t = ctrl.copy()
                    t[j] += (dx, dz)
                    w = obj_c(t)
                    if w < v - 1e-3:
                        v, ctrl, imp = w, t, True
    sp = catmull_rom(ctrl)
    return ctrl, i1, float(point_to_poly_dist(p2, sp).max()), float(point_to_poly_dist(part, sp).max())


# ---------------------------------------------------------------- 속도 프로파일 / GPX
def speed_profile(traj, ref, v0=50 / 3.6, vhold=25 / 3.6, a_dec=1.5, a_acc=1.0, a_lat=2.0, dwell=6, tuning=None):
    """1m 간격 궤적 -> 시각 t_i (s) 와 속도 v_i (m/s). s300 = 직선거리가 300m 로 처음 들어오는 지점."""
    tuning = tuning or {}
    a_acc = tuning.get("a_acc", a_acc)
    vhold = tuning.get("vhold", vhold)
    a_lat = tuning.get("a_lat", a_lat)
    n = len(traj)
    ds = np.hypot(*np.diff(traj, axis=0).T)
    s = np.concatenate([[0], np.cumsum(ds)])
    dist = np.hypot(traj[:, 0] - ref[0], traj[:, 1] - ref[1])
    i300 = int(np.argmax(dist <= 300.0))
    cap = np.full(n, v0)
    for i in range(i300, n):
        cap[i] = max(vhold, math.sqrt(max(v0 * v0 - 2 * a_dec * (s[i] - s[i300]), 0.0)))
    if "_hold_from" in tuning:
        cap[tuning["_hold_from"]:] = vhold
    # 곡률 기반 코너 제한
    hd = np.unwrap(np.arctan2(np.gradient(traj[:, 1]), np.gradient(traj[:, 0])))
    w = 4
    kappa = np.zeros(n)
    for i in range(n):
        a, b = max(0, i - w), min(n - 1, i + w)
        L = s[b] - s[a]
        kappa[i] = abs(hd[b] - hd[a]) / L if L > 0 else 0
    # 반경 30m 이상의 완만한 굴곡(도로 곡선)은 감속하지 않고, 교차로 코너(반경 약 9m)만 횡가속도 제한을 적용
    vc = np.where(kappa > 1.0 / 30.0, np.sqrt(a_lat / np.maximum(kappa, 1e-4)), 1e9)
    v = np.minimum(cap, np.maximum(vc, 3.0))
    v[-1] = 0.0
    for i in range(n - 2, -1, -1):
        v[i] = min(v[i], math.sqrt(v[i + 1] ** 2 + 2 * a_dec * (s[i + 1] - s[i])))
    v[0] = min(v[0], v0)
    for i in range(n - 1):
        v[i + 1] = min(v[i + 1], math.sqrt(v[i] ** 2 + 2 * a_acc * (s[i + 1] - s[i])))
    v[:-1] = np.maximum(v[:-1], 0.8)
    # 300m 진입 후 속도가 한 번 25km/h 이하로 내려가면(감속 완료 또는 코너 감속) 그 뒤로는 25km/h 유지 상한을 둔다.
    # 감속 곡선 상한만 두면 코너를 빠져나오며 25km/h 를 넘겨 재가속한다. (그런 경우가 없으면 결과 변화 없음)
    if "_hold_from" not in tuning:
        below = np.nonzero(v[i300:] <= vhold + 1e-6)[0]
        if len(below) and v[i300 + below[0]:].max() > vhold + 1e-6:
            return speed_profile(traj, ref, v0=v0, vhold=vhold, a_dec=a_dec, a_acc=a_acc, a_lat=a_lat, dwell=dwell,
                                 tuning=dict(tuning, _hold_from=i300 + int(below[0])))
    t = np.zeros(n)
    for i in range(n - 1):
        t[i + 1] = t[i] + 2 * (s[i + 1] - s[i]) / (v[i] + v[i + 1])
    return s, t, v, i300, t[-1] + dwell


def sample_1hz(traj, s, t, v, total_t, dwell_end=True):
    """1Hz 샘플: (x, z, speed m/s, course deg, i). 도착 후 정차 구간은 마지막 점 유지."""
    n = len(traj)
    out = []
    heading = np.arctan2(np.gradient(traj[:, 0]), np.gradient(traj[:, 1]))  # 북 기준 시계 방향
    k = 0
    for sec in range(int(math.floor(total_t)) + 1):
        if sec >= t[-1]:
            out.append((traj[-1][0], traj[-1][1], 0.0, math.degrees(heading[-2]) % 360.0, n - 1))
            continue
        ss = float(np.interp(sec, t, s))
        sp = float(np.interp(sec, t, v))
        x = float(np.interp(ss, s, traj[:, 0]))
        z = float(np.interp(ss, s, traj[:, 1]))
        i = int(np.searchsorted(s, ss))
        out.append((x, z, sp, math.degrees(heading[min(i, n - 1)]) % 360.0, i))
    return out


def ar1_noise(rng, n, sigma, phi=0.97, clip=4.5):
    e = np.zeros((n, 2))
    inn = sigma * math.sqrt(1 - phi * phi)
    e[0] = [rng.gauss(0, sigma), rng.gauss(0, sigma)]
    for i in range(1, n):
        e[i] = phi * e[i - 1] + np.array([rng.gauss(0, inn), rng.gauss(0, inn)])
    return np.clip(e, -clip, clip)


def is_real_gpx(path):
    """import_real_gpx.py 로 교체한 실주행 로그인가. 합성 GPX 는 keywords 에 simulated 가 있다."""
    if not os.path.isfile(path):
        return False
    with open(path, encoding="utf-8") as f:
        return "<keywords>simulated</keywords>" not in f.read(4096)


def write_gpx(path, frame, samples, noise, desc, t0, name):
    pts = []
    for k, (x, z, sp, crs, _i) in enumerate(samples):
        nx, nz = x + noise[k][0], z + noise[k][1]
        lat, lon = frame.to_ll(nx, nz)
        ts = (t0 + timedelta(seconds=k)).strftime("%Y-%m-%dT%H:%M:%SZ")
        pts.append('      <trkpt lat="%.7f" lon="%.7f"><ele>0.0</ele><time>%s</time>'
                   '<extensions><speed>%.2f</speed><course>%.1f</course></extensions></trkpt>' % (lat, lon, ts, sp, crs))
    xml = ('<?xml version="1.0" encoding="UTF-8"?>\n'
           '<gpx version="1.1" creator="tactical-entry-guide-sim" xmlns="http://www.topografix.com/GPX/1/1">\n'
           '  <metadata><name>%s</name><desc>%s</desc><keywords>simulated</keywords></metadata>\n'
           '  <trk><name>%s</name><trkseg>\n%s\n  </trkseg></trk>\n</gpx>\n') % (name, desc, name, "\n".join(pts))
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(xml)


# ---------------------------------------------------------------- 메쉬
class ObjBuilder:
    def __init__(self):
        self.objects = []   # (name, verts[(x,y,z_north)], faces[[idx0-based]])

    def add(self, name, verts, faces):
        self.objects.append((name, verts, faces))

    def face_count(self):
        return sum(len(f) for _n, _v, f in self.objects)

    def write(self, path):
        lines = ["# axis: v <x east> <y up> <-z north>  (right-handed, Y-up; Blender default import/export)"]
        base = 0
        for name, verts, faces in self.objects:
            lines.append("o %s" % name)
            for x, y, z in verts:
                lines.append("v %.3f %.3f %.3f" % (x, y, -z))
            for f in faces:
                lines.append("f " + " ".join(str(base + i + 1) for i in f))
            base += len(verts)
        with open(path, "w", encoding="utf-8", newline="\n") as fh:
            fh.write("\n".join(lines) + "\n")


def box_mesh(cx, cz, wx, wz, y0, y1):
    hx, hz = wx / 2.0, wz / 2.0
    v = [(cx - hx, y0, cz - hz), (cx + hx, y0, cz - hz), (cx + hx, y0, cz + hz), (cx - hx, y0, cz + hz),
         (cx - hx, y1, cz - hz), (cx + hx, y1, cz - hz), (cx + hx, y1, cz + hz), (cx - hx, y1, cz + hz)]
    # 바닥이 (x,z북) 평면에서 반시계 순서(0,1,2,3). 벽은 외향 법선.
    f = [[3, 2, 1, 0][::-1][::-1], [4, 5, 6, 7], [0, 1, 5, 4], [1, 2, 6, 5], [2, 3, 7, 6], [3, 0, 4, 7]]
    f[0] = [0, 3, 2, 1]  # 아래 면(법선 -y)
    return v, f


def tri_list(poly):
    """다각형(구멍 허용) -> CCW 삼각형 좌표 목록."""
    try:
        res = shapely.constrained_delaunay_triangles(poly)
        tris = list(res.geoms)
    except Exception:
        tris = []
    out = []
    for t in tris:
        if t.area < 1e-6:
            continue
        t = orient(t, 1.0)
        out.append(list(t.exterior.coords)[:3])
    return out


def surface_mesh(polys, y):
    verts, faces, key = [], [], {}

    def vid(p):
        k = (round(p[0], 4), round(p[1], 4))
        if k not in key:
            key[k] = len(verts)
            verts.append((p[0], y, p[1]))
        return key[k]
    for poly in polys:
        for tri in tri_list(poly):
            faces.append([vid(p) for p in tri])
    return verts, faces


def extrude_mesh(polys_heights):
    verts, faces = [], []
    for poly, h in polys_heights:
        poly = orient(Polygon(poly.exterior), 1.0)
        ring = list(poly.exterior.coords)[:-1]
        n = len(ring)
        b0 = len(verts)
        for p in ring:
            verts.append((p[0], 0.0, p[1]))
        for p in ring:
            verts.append((p[0], h, p[1]))
        for i in range(n):
            j = (i + 1) % n
            faces.append([b0 + i, b0 + j, b0 + n + j, b0 + n + i])
        key = {(round(p[0], 4), round(p[1], 4)): b0 + n + i for i, p in enumerate(ring)}
        for tri in tri_list(poly):
            ids = []
            for p in tri:
                k = (round(p[0], 4), round(p[1], 4))
                if k not in key:
                    key[k] = len(verts)
                    verts.append((p[0], h, p[1]))
                ids.append(key[k])
            faces.append(ids)
    return verts, faces


def ramp_mesh(frame, tgeom, entry, height, width=None, nseg=None):
    """RAMP_MAIN: 경사 쐐기 (윗면 + 양 옆 벽 + 높은 쪽 끝 벽, 바닥 없음). 감기는 extrude_mesh 와 같다(바깥쪽).
    4차 추가 지시 3: 실제 위치 그대로. 낮은 끝 중심 = MAIN_PARKING 입구(y=0), 높은 끝 중심 = 실주행 램프 꼭대기점
    (RAMP_LOG_HINTS 마지막 점, y=본관 높이). 직선 경사. 본관 외곽선 안쪽을 지나도 된다 (본관 메쉬는 깎지 않는다)."""
    width = width or RAMP_WIDTH_M
    nseg = nseg or RAMP_SEGMENTS
    top = src_hint_to_local(frame, hints_to_abs([("P", RAMP_LOG_HINTS[-1])])[0][1])
    lo = np.array(entry, dtype=float)
    hi = np.array(top, dtype=float)
    length = float(np.hypot(*(hi - lo)))
    u = (hi - lo) / length
    nrm = np.array([u[1], -u[0]])
    # 윗면 꼭짓점: 왼쪽(L)·오른쪽(R) 가장자리를 nseg 칸으로 나눈다
    h = nrm * width / 2.0
    side_a = [lo + h + u * length * k / nseg for k in range(nseg + 1)]
    side_b = [lo - h + u * length * k / nseg for k in range(nseg + 1)]
    ys = [height * k / nseg for k in range(nseg + 1)]
    verts = []
    for p, y in zip(side_a, ys):
        verts.append((float(p[0]), y, float(p[1])))
    for p, y in zip(side_b, ys):
        verts.append((float(p[0]), y, float(p[1])))
    A = lambda k: k
    B = lambda k: nseg + 1 + k
    # 지면 꼭짓점 (높이가 0 이 아닌 곳만)
    gA = {0: A(0)}
    gB = {0: B(0)}
    for k in range(1, nseg + 1):
        gA[k] = len(verts); verts.append((float(side_a[k][0]), 0.0, float(side_a[k][1])))
        gB[k] = len(verts); verts.append((float(side_b[k][0]), 0.0, float(side_b[k][1])))
    # 평면(x,z) 에서 side_a -> side_b 순서가 반시계인지 확인해 감기 결정
    ccw = Polygon([side_a[0], side_a[-1], side_b[-1], side_b[0]]).exterior.is_ccw
    faces = []

    def add(f):
        faces.append(f)
    for k in range(nseg):
        # 윗면: extrude_mesh 지붕과 같이 위에서 볼 때 (x,z) 반시계
        q = [A(k), A(k + 1), B(k + 1), B(k)]
        add(q[::-1] if not ccw else q)
    # 외곽 링 (x,z 반시계) 순서로 벽: [bot_i, bot_j, top_j, top_i]
    ring_top = [A(k) for k in range(nseg + 1)] + [B(k) for k in range(nseg, -1, -1)]
    ring_bot = [gA[k] for k in range(nseg + 1)] + [gB[k] for k in range(nseg, -1, -1)]
    if not ccw:
        ring_top, ring_bot = ring_top[::-1], ring_bot[::-1]
    m = len(ring_top)
    for i in range(m):
        j = (i + 1) % m
        ti, tj, bi, bj = ring_top[i], ring_top[j], ring_bot[i], ring_bot[j]
        if ti == bi and tj == bj:
            continue                      # 낮은 쪽 끝 (높이 0): 벽 없음
        if ti == bi:
            add([bi, bj, tj])
        elif tj == bj:
            add([bi, bj, ti])
        else:
            add([bi, bj, tj, ti])
    cl = LineString([tuple(lo), tuple(hi)])
    inner = cl.intersection(tgeom)
    info = {"low": (float(lo[0]), float(lo[1])), "high": (float(hi[0]), float(hi[1])), "length": length,
            "bearing": math.degrees(math.atan2(u[0], u[1])), "faces": len(faces),
            "inside_len": float(inner.length), "inside_from": float(cl.project(Point(*inner.coords[0]))) if not inner.is_empty and hasattr(inner, "coords") else None,
            "high_dist": float(tgeom.exterior.distance(Point(*hi))), "high_in": bool(tgeom.contains(Point(*hi)))}
    return verts, faces, info


def extrude_faces(poly):
    ring = len(poly.exterior.coords) - 1
    return ring + max(0, len(tri_list(Polygon(poly.exterior))))


# ---------------------------------------------------------------- JSON 출력 도우미
def _scalar_dict(v):
    return isinstance(v, dict) and all(not isinstance(x, (dict, list)) for x in v.values())


def pretty(o, ind=0, step=2):
    sp = " " * ind
    if isinstance(o, dict):
        if _scalar_dict(o):
            return json.dumps(o, ensure_ascii=False)
        items = ['%s%s: %s' % (" " * (ind + step), json.dumps(k, ensure_ascii=False), pretty(v, ind + step, step))
                 for k, v in o.items()]
        return "{\n" + ",\n".join(items) + "\n" + sp + "}"
    if isinstance(o, list):
        if not o:
            return "[]"
        if all(not isinstance(x, (dict, list)) for x in o):
            return json.dumps(o, ensure_ascii=False)
        items = [" " * (ind + step) + pretty(x, ind + step, step) for x in o]
        return "[\n" + ",\n".join(items) + "\n" + sp + "]"
    return json.dumps(o, ensure_ascii=False)


def r3(v):
    return round(float(v), 3)


def pt(p):
    return {"x": r3(p[0]), "z": r3(p[1])}


# ---------------------------------------------------------------- 본체
def poly_parts(g):
    if isinstance(g, Polygon):
        return [g]
    if isinstance(g, MultiPolygon):
        return list(g.geoms)
    if hasattr(g, "geoms"):
        return [p for x in g.geoms for p in poly_parts(x)]
    return []


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--shp-dir", default=default_shp_dir(), help="SHP 폴더(재귀 스캔)")
    ap.add_argument("--origin", nargs=2, type=float, metavar=("LAT", "LON"), default=list(DEFAULT_ORIGIN))
    ap.add_argument("--radius", type=float, default=300.0)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--out-dir", default=os.path.join(ROOT, "gis_data"))
    ap.add_argument("--no-copy", action="store_true", help="assets / Unity 폴더로 복사하지 않는다")
    ap.add_argument("--preview", default=os.path.join(ROOT, ".team", "gis_preview.png"))
    ap.add_argument("--gnss-sigma", type=float, default=2.0)
    ap.add_argument("--overwrite-real-gpx", action="store_true", help="실주행 로그도 합성 GPX 로 덮어쓴다")
    args = ap.parse_args()

    if not hasattr(shapely, "constrained_delaunay_triangles"):
        # shapely < 2.1 (예: Ubuntu apt 2.0.3) 에는 이 함수가 없어 도로면·지붕 삼각형이 조용히 0 개가 된다.
        msg = ("shapely %s 에는 constrained_delaunay_triangles 가 없다 (2.1 이상 필요). 도로면·건물 지붕 면이 빠진다. "
               "Windows Python 3.11(shapely 2.2) 등으로 실행하라." % shapely.__version__)
        if not args.no_copy or os.path.abspath(args.out_dir) == os.path.join(ROOT, "gis_data"):
            sys.exit("[오류] " + msg)
        print("[경고] " + msg + " (--no-copy 이고 gis_data 밖으로 쓰므로 계속: 미리보기·검토용)")
    lat0, lon0 = args.origin
    R = args.radius
    frame = Frame(lat0, lon0)
    out = args.out_dir
    os.makedirs(out, exist_ok=True)
    print("[입력] shp-dir=%s origin=(%.6f, %.6f) radius=%.0fm seed=%d" % (args.shp_dir, lat0, lon0, R, args.seed))

    def layer(code, label):
        paths = find_layer(args.shp_dir, code)
        if not paths:
            print("  [%s] 레이어 %s 없음" % (label, code))
            return []
        return read_layer(paths, frame, label)

    bld = layer(L_BUILDING, "건물")
    roadsurf = layer(L_ROADSURF, "도로면")
    center = layer(L_CENTER, "도로중심선")
    charger = layer(L_CHARGER, "충전소")
    circle = Point(0, 0).buffer(R, 64)
    augment = []

    # ---- 건물
    bpolys = []   # (Polygon exterior-only, floors, use)
    for g, rec in bld:
        for p in poly_parts(g):
            q = Polygon(p.exterior)
            if q.is_valid and q.area > 1.0:
                bpolys.append((q, int(rec.get("층수") or 0), str(rec.get("용도") or "")))
    # 본관: 판매시설 최대 폴리곤
    sales = [b for b in bpolys if "판매" in b[2]]
    target = max(sales, key=lambda b: b[0].area) if sales else None
    if target is None or target[0].area < 3000:
        print("[보강] 판매시설 본관 폴리곤을 찾지 못했다 -> 코스트코 세종점 규격(약 145x160m)으로 보강")
        # 본관 중심 (-148,-221) 은 HINT_ORIGIN 기준 -> 현재 프레임으로 변환
        cx, cz = src_hint_to_local(frame, hints_to_abs([("P", (-148.0, -221.0))])[0][1])
        target = (Polygon([(cx - 72, cz - 80), (cx + 72, cz - 80), (cx + 72, cz + 80), (cx - 72, cz + 80)]), 4, "보강")
        augment.append("TARGET_COSTCO_BUILDING")
        is_aug_target = True
    else:
        is_aug_target = False
        print("[본관] 판매시설 최대 폴리곤 면적 %.0f㎡, 중심 (%.0f, %.0f), 층수 %d" % (
            target[0].area, target[0].centroid.x, target[0].centroid.y, target[1]))
    tgeom = target[0].simplify(0.3, preserve_topology=True)
    if tgeom.geom_type != "Polygon" or tgeom.area < 0.5 * target[0].area:
        tgeom = target[0]
    tgeom = orient(tgeom, 1.0)
    t_floors = max(1, target[1])
    t_height = TARGET_HEIGHT_M   # 실주행 로그 기반 (보강 시에도 같은 값)
    # 충전소(코스트코) 확인 + EV 출입구 위치
    ev_entry = EV_ENTRY_HINT
    for g, rec in charger:
        if "코스트코" in str(rec.get("명칭", "")):
            c = g.centroid
            ev_entry = (c.x, c.y)
            d = tgeom.distance(Point(c.x, c.y))
            print("[확인] 충전소 '코스트코세종점'(%s) 로컬 (%.0f, %.0f), 본관과 거리 %.1fm -> 본관이 코스트코임을 확인" % (
                rec.get("종류"), c.x, c.y, d))
    else:
        pass
    if ev_entry == EV_ENTRY_HINT:
        # 기본 원점이 아니면 힌트를 현재 프레임으로 변환
        ab = hints_to_abs([("P", EV_ENTRY_HINT)])[0][1]
        ev_entry = src_hint_to_local(frame, ab)
        if not charger:
            print("[보강] 충전소 레이어가 비어 EV 입구는 힌트 좌표를 사용")
            augment.append("EV_ENTRY")

    surround = []
    for q, fl, use in bpolys:
        if q.equals(target[0]) or q.distance(tgeom) < 0.01 and q.area >= target[0].area * 0.9:
            continue
        if q.intersects(circle) and q.area > 4.0:
            surround.append((q, max(1, fl) * 3.5))
    print("[건물] 반경 내 주변 건물 %d 동 (통째 포함)" % len(surround))

    # ---- 도로면
    road_polys = []
    for g, _rec in roadsurf:
        c = g.intersection(circle)
        for p in poly_parts(c):
            if p.area > 2.0:
                road_polys.append(p)
    print("[도로면] 원으로 클립한 폴리곤 %d 개" % len(road_polys))

    # ---- 중심선
    lines = []
    for g, rec in center:
        lines.append((g, int(rec.get("차로수") or 2), float(rec.get("도로폭") or 0.0)))
    graph = RoadGraph([(g, l, w) for g, l, w in lines])

    # ---- 경로
    main_abs = hints_to_abs(MAIN_HINTS)
    ev_hints_all = MAIN_HINTS[:EV_BRANCH_INDEX] + EV_HINTS_TAIL
    ev_abs = hints_to_abs(ev_hints_all)
    main_local = [(k, src_hint_to_local(frame, p)) for k, p in main_abs]
    ev_local = [(k, src_hint_to_local(frame, p)) for k, p in ev_abs]

    print("[경로] MAIN_PARKING 궤적 생성")
    m_center, m_traj, notes = build_trajectory(graph, main_local)
    for n_ in notes:
        print("  주의:", n_)
    m_traj, m_ext = ensure_start_distance(m_traj, frame.to_xz(MAIN_ENTRY_LL[1], MAIN_ENTRY_LL[0]))
    print("[경로] EV_CHARGING_ENTRY 궤적 생성 (저작 경로 — 가정)")
    e_center, e_traj, notes = build_trajectory(graph, ev_local)
    for n_ in notes:
        print("  주의:", n_)
    e_traj, e_ext = ensure_start_distance(e_traj, ev_entry)

    # 입구 위치: MAIN 은 MAIN_ENTRY_LL (기본 원점이면 계약상 정확히 (0,0)), EV 는 충전소 위치
    mx, mz = frame.to_xz(MAIN_ENTRY_LL[1], MAIN_ENTRY_LL[0])
    main_entry = (float(mx), float(mz))
    if abs(frame.lat0 - MAIN_ENTRY_LL[0]) < 1e-9 and abs(frame.lon0 - MAIN_ENTRY_LL[1]) < 1e-9:
        main_entry = (0.0, 0.0)
    m_traj[-1] = main_entry
    e_traj[-1] = (ev_entry[0], ev_entry[1])

    if os.environ.get("TEG_DUMP"):
        np.save(os.environ["TEG_DUMP"] + "_main.npy", m_traj)
        np.save(os.environ["TEG_DUMP"] + "_ev.npy", e_traj)
    # ---- 스플라인 제어점
    m_ctrl, m_i1, m_dev, m_dev_all = choose_controls_phase2(m_traj, main_entry, n=8)
    e_ctrl, e_i1, e_idx, e_dev = choose_controls(e_traj, ev_entry, n_target=None, max_dev=3.0, n_min=8, n_max=16)
    m_ctrl = np.round(m_ctrl, 3)
    e_ctrl = np.round(e_ctrl, 3)
    m_ctrl[-1] = main_entry
    print("[스플라인] Path 8점, Phase 2(입구 300m 이내) 최대 편차 %.2fm (Path_01~ 전체 %.2fm) | PathEV %d점, 최대 편차 %.2fm" % (
        m_dev, m_dev_all, len(e_ctrl), e_dev))
    print("  Path_01 입구 직선거리 %.1fm, PathEV_01 EV 입구 직선거리 %.1fm" % (math.dist(m_ctrl[0], main_entry), math.dist(e_ctrl[0], ev_entry)))

    # ---- 핀
    ms = np.concatenate([[0], np.cumsum(np.hypot(*np.diff(m_traj, axis=0).T))])
    # 4차 임시 규칙 (위치는 측량하지 않음, fieldVerified=false): 기준 모서리 right2 = 최종 우회전 지점
    #   WaitLane = 우회전 60m 전 우측 차로, NoTurn = 우회전 후 경로상 15m,
    #   Pin_Barrier_01 = 우회전 후 경로상 30m 의 램프 입구 높이 제한 핀 (현장 확인: 차단기 없음, 높이 제한 표지 2.1m).
    #   객체 이름·type BARRIER 는 5.3 계약과 HazardType enum 때문에 유지하고 label 로 의미를 표시한다.
    corner = np.array(src_hint_to_local(frame, hints_to_abs([("P", CORNER_HINTS_MAIN["right2"])])[0][1]))
    ic = int(np.argmin(np.hypot(m_traj[:, 0] - corner[0], m_traj[:, 1] - corner[1])))

    def at_s(s):
        s = min(max(s, 0), ms[-1])
        return np.array([np.interp(s, ms, m_traj[:, 0]), np.interp(s, ms, m_traj[:, 1])])
    barrier = at_s(ms[ic] + 30.0)
    waitlane = at_s(ms[ic] - 60.0)
    noturn = at_s(ms[ic] + 15.0)
    print("[핀] 기준 모서리 (%.1f, %.1f), 우회전 후 입구까지 경로 %.1fm, 높이 제한 핀->입구 %.1fm" % (
        m_traj[ic][0], m_traj[ic][1], ms[-1] - ms[ic], ms[-1] - ms[ic] - 30.0))
    pins = [("Pin_Barrier_01", "BARRIER", barrier, "높이 제한 %.1fm" % HEIGHT_LIMIT_M),
            ("Pin_NoTurn_01", "NO_TURN", noturn, "회차불가"),
            ("Pin_WaitLane_01", "WAIT_LANE", waitlane, "대기차로")]

    # ---- 면 수 예산 + OBJ
    ramp_v, ramp_f, ramp_info = ramp_mesh(frame, tgeom, main_entry, t_height)
    print("[램프] RAMP_MAIN 낮은 끝 (%.2f, %.2f) y=0 -> 높은 끝 (%.2f, %.2f) y=%.1f, 수평 %.1fm, 방위 %.1f도, 경사 %.1f%%, 면 %d" % (
        *ramp_info["low"], *ramp_info["high"], t_height, ramp_info["length"], ramp_info["bearing"],
        100.0 * t_height / ramp_info["length"], len(ramp_f)))
    print("[램프] 실제 위치 그대로 배치: 중심선 중 본관 외곽선 안 구간 %.1fm (낮은 끝에서 %.1fm 지점부터), 높은 끝은 외곽선 %s %.2fm" % (
        ramp_info["inside_len"], ramp_info["inside_from"] or 0.0, "안쪽" if ramp_info["high_in"] else "바깥", ramp_info["high_dist"]))
    fixed_faces = 6 + extrude_faces(tgeom) + 6 * (8 + len(e_ctrl) + 3 + 1) + len(ramp_f)
    road_s = 0.4
    surround_s = 0.5
    while True:
        rp = [orient(p.simplify(road_s, preserve_topology=True), 1.0) for p in road_polys]
        rp = [p for p in rp if p.area > 1.0 and not p.is_empty and p.geom_type == "Polygon"]
        rfaces = sum(len(tri_list(p)) for p in rp)
        sp_list = []
        for q, h in sorted(surround, key=lambda b: -b[0].area):
            s_ = q.simplify(surround_s, preserve_topology=True)
            if s_.geom_type != "Polygon" or s_.area < 1.0:
                s_ = q
            sp_list.append((orient(s_, 1.0), h))
        sfaces = sum(extrude_faces(p) for p, _h in sp_list)
        total = fixed_faces + rfaces + sfaces
        if total <= FACE_BUDGET:
            break
        if road_s < 1.5:
            road_s += 0.3
        elif surround_s < 2.0:
            surround_s += 0.4
        else:
            sp_list = sp_list[:-1]
            surround = surround[:-1]
            sfaces = sum(extrude_faces(p) for p, _h in sp_list)
            if fixed_faces + rfaces + sfaces <= FACE_BUDGET:
                break
    print("[메쉬] 면 예산 %d: 본관+고정 %d + 도로 %d + 주변건물 %d (simplify 도로 %.1f / 건물 %.1f)" % (
        FACE_BUDGET, fixed_faces, rfaces, sfaces, road_s, surround_s))

    ob = ObjBuilder()
    v, f = box_mesh(0.0, 0.0, 3.0, 3.0, 0.0, 0.3)
    ob.add("ORIGIN_RAMP_MARKER", v, f)
    v, f = extrude_mesh([(tgeom, t_height)])
    ob.add("TARGET_COSTCO_BUILDING", v, f)
    v, f = extrude_mesh(sp_list)
    ob.add("SURROUND_BUILDINGS", v, f)
    ob.add("RAMP_MAIN", ramp_v, ramp_f)
    v, f = surface_mesh(rp, 0.0)
    ob.add("ROAD_SURFACE_300M", v, f)
    for i, p in enumerate(m_ctrl):
        v, f = box_mesh(float(p[0]), float(p[1]), 1.0, 1.0, 0.1, 0.6)
        ob.add("Path_%02d" % (i + 1), v, f)
    for i, p in enumerate(e_ctrl):
        v, f = box_mesh(float(p[0]), float(p[1]), 1.0, 1.0, 0.1, 0.6)
        ob.add("PathEV_%02d" % (i + 1), v, f)
    pins_r = []
    for pid, _t, p, _l in pins:
        p = (r3(p[0]), r3(p[1]))
        pins_r.append(p)
        v, f = box_mesh(p[0], p[1], 1.2, 1.2, 0.0, 3.5)
        ob.add(pid, v, f)
    nf = ob.face_count()
    assert nf <= FACE_BUDGET, "면 수 초과 %d" % nf
    obj300 = os.path.join(out, "sejong_costco_300m.obj")
    ob.write(obj300)
    shutil.copyfile(obj300, os.path.join(out, "sejong_costco_custom.obj"))
    print("[OBJ] 총 면 %d (<= %d), 오브젝트 %d 개 -> sejong_costco_300m.obj, sejong_costco_custom.obj" % (nf, FACE_BUDGET, len(ob.objects)))

    # ---- JSON
    roads_json = []
    rid = 0
    cc = circle
    for g, lanes, width in lines:
        c = g.intersection(cc)
        segs = c.geoms if hasattr(c, "geoms") else [c]
        for ls in segs:
            if ls.geom_type != "LineString" or ls.length < 5.0:
                continue
            ls = ls.simplify(0.3)
            rid += 1
            roads_json.append({"id": "R%04d" % rid, "widthM": round(width if width > 0 else 3.0, 1), "lanes": max(1, lanes),
                               "centerline": [pt(p) for p in ls.coords]})
    zone = {
        "schemaVersion": 1, "zoneId": "sejong_costco", "displayName": "코스트코 세종점",
        "origin": {"lat": lat0, "lon": lon0}, "radiusM": float(R),
        "entries": [
            {"id": "MAIN_PARKING", "label": "옥상 주차장 램프 입구", "fuelKinds": ["ICE", "UNKNOWN"],
             "position": pt(main_entry), "recommendedLane": "우측 끝 차로"},
            {"id": "EV_CHARGING_ENTRY", "label": "전기차 충전소 인접 입구", "fuelKinds": ["EV", "PHEV"],
             "position": pt(ev_entry), "recommendedLane": "우측 끝 차로"}],
        "buildings": [{"id": "TARGET_COSTCO_BUILDING", "role": "TARGET", "heightM": float(t_height),
                       "footprint": [pt(p) for p in list(tgeom.exterior.coords)[:-1]]}] + [
            {"id": "B%04d" % (i + 1), "role": "SURROUND", "heightM": float(h),
             "footprint": [pt(p) for p in list(q.exterior.coords)[:-1]]} for i, (q, h) in enumerate(sp_list)],
        "roads": roads_json,
        "guidePathPoints": [{"id": "Path_%02d" % (i + 1), "x": r3(p[0]), "z": r3(p[1])} for i, p in enumerate(m_ctrl)],
        "evGuidePathPoints": [{"id": "PathEV_%02d" % (i + 1), "x": r3(p[0]), "z": r3(p[1])} for i, p in enumerate(e_ctrl)],
        "hazardPins": [{"id": pid, "type": t, "x": r3(p[0]), "z": r3(p[1]), "label": lb, "fieldVerified": False}
                       for (pid, t, _pp, lb), p in zip(pins, pins_r)],
        "staticNotices": {"heightLimitM": HEIGHT_LIMIT_M, "barrierNote": "우회전 후 30m 램프 진입 · 차단기 없음"},
        "triggers": {"tacticalDistM": 300, "tacticalSlowDistM": 350, "tacticalSpeedKmh": 30, "tacticalExitDistM": 400,
                     "followViewDistM": 100, "followViewExitDistM": 115, "arrivedDistM": 15, "arrivedStopDistM": 30,
                     "arrivedStopSpeedKmh": 3, "arrivedStopHoldSec": 3, "snapDistM": 15, "offRouteWarnDistM": 20},
    }
    zpath = os.path.join(out, "sejong_costco_zone.json")
    with open(zpath, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(pretty(zone) + "\n")
    print("[JSON] %s (roads %d, buildings %d)" % (zpath, len(roads_json), len(zone["buildings"])))

    # ---- GPX
    t0 = datetime(2026, 11, 5, 9, 0, 0, tzinfo=timezone.utc)
    gpx_info = {}
    ideal = {}
    for tag, traj, ref, fname, seed_off, title in (
            ("main", m_traj, main_entry, "sejong_costco_cn8_drive.gpx", 0, "MAIN_PARKING 합성 주행"),
            ("ev", e_traj, ev_entry, "sejong_costco_ev_drive.gpx", 1, "EV_CHARGING_ENTRY 합성 주행")):
        tuning = {}
        # 유지속도는 25km/h 고정 (자동 튜닝 없음). 총 소요는 측정값을 그대로 보고한다.
        # 시작은 500m 이상·505m 근처로 줄이되, 첫 코너 전에 50km/h 로 달릴 직선 구간이 없으면 시작점을 뒤로 물린다.
        for k in start_candidates(traj, ref):
            s, t, v, i300, total = speed_profile(traj[k:], ref, dwell=5)
            if v[0] >= 50 / 3.6 - 0.05:
                break
        print("  [시작점] %s: 입구 직선거리 %.1fm (후보 %d 번째)" % (tag, math.dist(traj[k], ref), k))
        traj = traj[k:]
        samples = sample_1hz(traj, s, t, v, total)
        rng = random.Random(args.seed + seed_off)
        noise = ar1_noise(rng, len(samples), args.gnss_sigma)
        desc = ("시뮬레이션(합성) 궤적: 실주행 로그가 아니며 도로 위 실제 궤적이 아니다. 도로 중심선 기반 우측 차로 경로에 "
                "속도 프로파일(시작 50km/h, 입구 직선거리 300m 에서 1.5m/s² 감속, 코너 감속, 입구 정차)과 시드 %d 의 "
                "AR(1) GNSS 노이즈(σ≈%.1fm)를 합성했다. 고도는 측정값이 아니다." % (
                    args.seed + seed_off, args.gnss_sigma))
        gpx_path = os.path.join(out, fname)
        if is_real_gpx(gpx_path) and not args.overwrite_real_gpx:
            print("  [보호] %s 는 실주행 로그다. 덮어쓰지 않는다 (--overwrite-real-gpx 로 강제)." % fname)
        else:
            write_gpx(gpx_path, frame, samples, noise, desc, t0, title)
        ideal[tag] = [[round(float(x), 3), round(float(z), 3), round(float(sp), 3)] for x, z, sp, _c, _i in samples]
        gpx_info[tag] = (fname, samples, noise, total, i300, tuning, traj, s, t, v)
        print("[GPX] %s: %d 점(1Hz), 총 %.0f초, 경로 %.0fm, 튜닝=%s" % (fname, len(samples), total, s[-1], tuning or "기본"))

    os.makedirs(os.path.join(ROOT, ".team"), exist_ok=True)
    with open(os.path.join(ROOT, ".team", "gis_ideal_traj.json"), "w", encoding="utf-8") as f:
        json.dump({"note": "노이즈 없는 기준 궤적 1Hz [x,z,speed m/s] (검증용, 앱 미사용)", **ideal}, f)
    # ---- 미리보기 PNG
    try:
        make_preview(args.preview, circle, rp, sp_list, tgeom, lines, m_traj, e_traj,
                     m_ctrl, e_ctrl, pins, main_entry, ev_entry, R)
    except Exception as ex:
        print("[경고] 미리보기 생성 실패:", ex)

    # ---- 복사
    if not args.no_copy:
        assets = os.path.join(ROOT, "android_pleos_app", "app", "src", "main", "assets")
        models = os.path.join(ROOT, "unity_tactical_module", "Assets", "Models")
        res = os.path.join(ROOT, "unity_tactical_module", "Assets", "Resources")
        for d in (assets, models, res):
            os.makedirs(d, exist_ok=True)
        shutil.copyfile(zpath, os.path.join(assets, "sejong_costco_zone.json"))
        for fn in ("sejong_costco_cn8_drive.gpx", "sejong_costco_ev_drive.gpx"):
            shutil.copyfile(os.path.join(out, fn), os.path.join(assets, fn))
        shutil.copyfile(os.path.join(out, "sejong_costco_custom.obj"), os.path.join(assets, "sejong_costco_custom.obj"))
        shutil.copyfile(obj300, os.path.join(models, "sejong_costco_300m.obj"))
        shutil.copyfile(os.path.join(out, "sejong_costco_custom.obj"), os.path.join(models, "sejong_costco_custom.obj"))
        shutil.copyfile(zpath, os.path.join(res, "sejong_costco_zone.json"))
        print("[복사] assets, Unity Models, Unity Resources 완료")
    if augment:
        print("[보강 요약] 다음 항목은 실제 데이터가 없어 규격 기반으로 보강:", ", ".join(augment))
    print("[완료] EV 경로는 저작(가정) 경로이며 현장 확인이 필요하다. GPX 는 시뮬레이션이다.")


def make_preview(path1, circle, road_polys, sur, tgeom, lines, m_traj, e_traj, m_ctrl, e_ctrl, pins, m_entry, ev_entry, R):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    fig, ax = plt.subplots(figsize=(11, 14))
    ax.add_patch(plt.Circle((0, 0), R, fc="#1b1f24", ec="#556", lw=1.5, zorder=0))
    for p in road_polys:
        x, y = p.exterior.xy
        ax.fill(x, y, fc="#3a4048", ec="none", zorder=1)
    for p, _h in sur:
        x, y = p.exterior.xy
        ax.fill(x, y, fc="#7a8896", ec="#222", lw=0.4, alpha=0.8, zorder=2)
    x, y = tgeom.exterior.xy
    ax.fill(x, y, fc="#e8a33d", ec="#fff", lw=1.2, zorder=3)
    ax.text(tgeom.centroid.x, tgeom.centroid.y, "TARGET", ha="center", color="k", fontsize=9, zorder=9)
    for g, _l, _w in lines:
        for ls in (g.geoms if hasattr(g, "geoms") else [g]):
            xx, yy = ls.xy
            ax.plot(xx, yy, color="#778", lw=0.5, zorder=2)
    ax.plot(m_traj[:, 0], m_traj[:, 1], "-", color="#27d3ff", lw=1.8, zorder=5, label="MAIN trajectory (ICE)")
    ax.plot(e_traj[:, 0], e_traj[:, 1], "-", color="#7dff6a", lw=1.5, zorder=5, label="EV trajectory (assumed)")
    sp = catmull_rom(m_ctrl)
    ax.plot(sp[:, 0], sp[:, 1], "--", color="white", lw=1.0, zorder=6, label="spline Path")
    spe = catmull_rom(e_ctrl)
    ax.plot(spe[:, 0], spe[:, 1], "--", color="#bfffb0", lw=1.0, zorder=6)
    ax.scatter(m_ctrl[:, 0], m_ctrl[:, 1], c="#ff3b7f", s=22, zorder=7, label="Path_01..08")
    ax.scatter(e_ctrl[:, 0], e_ctrl[:, 1], c="#2aa84a", s=18, zorder=7, label="PathEV")
    for i, p in enumerate(m_ctrl):
        ax.annotate("%d" % (i + 1), p, color="#ff9bbd", fontsize=7, zorder=8)
    col = {"BARRIER": "#ff2020", "NO_TURN": "#ff9d00", "WAIT_LANE": "#ffee00"}
    for pid, t, p, _l in pins:
        ax.scatter([p[0]], [p[1]], marker="^", s=90, c=col[t], ec="k", zorder=8)
        ax.annotate(pid.replace("Pin_", ""), p, color=col[t], fontsize=8, xytext=(4, 4), textcoords="offset points", zorder=9)
    ax.scatter([m_entry[0]], [m_entry[1]], marker="o", s=80, c="white", ec="k", zorder=9)
    ax.scatter([ev_entry[0]], [ev_entry[1]], marker="*", s=160, c="#00e5a0", ec="k", zorder=9)
    ax.set_aspect("equal")
    allp = np.vstack([m_traj, e_traj])
    ax.set_xlim(min(-R, allp[:, 0].min()) - 20, max(R, allp[:, 0].max()) + 20)
    ax.set_ylim(min(-R, allp[:, 1].min()) - 20, max(R, allp[:, 1].max()) + 20)
    ax.set_facecolor("#0e1114")
    ax.grid(True, color="#334", lw=0.3)
    ax.set_title("Tactical entry zone (local frame: x=east, z=north, m)  - simulated GPX / assumed EV route")
    ax.legend(loc="upper right", fontsize=8)
    os.makedirs(os.path.dirname(path1), exist_ok=True)
    fig.savefig(path1, dpi=80)
    plt.close(fig)
    print("[PNG] %s" % path1)


if __name__ == "__main__":
    main()
