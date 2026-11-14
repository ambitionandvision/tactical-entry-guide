#!/usr/bin/env python3
"""실차 주행 로그(GPX)를 앱용 GPX 로 교체한다. 표준 라이브러리만 사용한다.

  python3 scripts/import_real_gpx.py --main 주차장입구.gpx
  python3 scripts/import_real_gpx.py --ev 충전소입구.gpx
  python3 scripts/import_real_gpx.py --main a.gpx --ev b.gpx --dry-run    # 검사만, 파일 변경 없음
  python3 scripts/import_real_gpx.py --restore all                        # 시뮬레이션 GPX 로 되돌림

하는 일
  1. 어떤 GPX 로거 파일이든 읽는다 (GPX 1.0/1.1, 여러 세그먼트, 불규칙 간격, 시간대 오프셋).
  2. 중복 시각과 GPS 튐을 제거한다.
  3. 도착 지점 기준으로 구간을 자른다. 집 등 구간 밖 위치는 저장소에 들어가지 않는다.
  4. 1Hz 로 정규화하고, 작성자·기기·계정 메타데이터를 버리고 새로 쓴다.
  5. Phase 전이, 유도선 흡착, 이탈 경고를 미리 계산해 품질을 판정한다.
  6. 기존 파일을 백업한 뒤 gis_data/ 와 앱 assets/ 를 교체하고, docs/ 에 검증 보고서를 쓴다.

교체된 파일에는 'simulated' 키워드가 없으므로 앱 라벨이 자동으로 "실차 GPX 주행 재생" 으로 바뀐다.

입구 좌표가 실제와 다르면 궤적이 입구에 닿지 않는다. 이 경우 교체하지 않고(종료 코드 2)
궤적이 멈춘 좌표와 함께 extract_shp_to_tactical.py --origin 명령을 알려 준다.

종료 코드: 0 성공(경고 포함), 1 입력 오류, 2 품질 기준 미달로 교체 거부(--force 로 강행).
"""
import argparse
import json
import math
import os
import re
import shutil
import statistics
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone
from xml.sax.saxutils import escape

K_LON = 111320.0
K_LAT = 110574.0
MAX_SPEED_MS = 55.0      # 약 200km/h. 이보다 빠른 이동은 GPS 튐으로 본다.
STATIONARY_MS = 0.5
MAX_GAP_WARN_S = 5.0
MAX_GAP_FAIL_S = 30.0
ENTRY_REACH_FAIL_M = 60.0
DEMO_LIMIT_S = 150.0

KINDS = {
    "main": {
        "entry": "MAIN_PARKING",
        "gpx": "sejong_costco_cn8_drive.gpx",
        "path": "guidePathPoints",
        "title": "MAIN_PARKING 실주행",
        "label": "주차장 입구행",
    },
    "ev": {
        "entry": "EV_CHARGING_ENTRY",
        "gpx": "sejong_costco_ev_drive.gpx",
        "path": "evGuidePathPoints",
        "title": "EV_CHARGING_ENTRY 실주행",
        "label": "충전소 입구행",
    },
}

KST = timezone(timedelta(hours=9))
TIME_RE = re.compile(
    r"^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?\s*(Z|[+-]\d{2}(?::?\d{2})?)?$"
)


class InputError(Exception):
    """입력 파일을 쓸 수 없다."""


class Point:
    __slots__ = ("t", "lat", "lon", "ele", "x", "z", "d", "v", "course")

    def __init__(self, t, lat, lon, ele=None):
        self.t, self.lat, self.lon, self.ele = t, lat, lon, ele
        self.x = self.z = self.d = self.v = self.course = 0.0


def utf8_stdout():
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")


def parse_time(text):
    m = TIME_RE.match(text.strip())
    if not m:
        raise ValueError(text)
    y, mo, d, h, mi, s = (int(v) for v in m.groups()[:6])
    frac = float("0." + m.group(7)) if m.group(7) else 0.0
    offset = 0
    zone = m.group(8)
    if zone and zone != "Z":
        digits = zone[1:].replace(":", "")
        offset = int(digits[:2]) * 3600 + int(digits[2:4] or 0) * 60
        if zone[0] == "-":
            offset = -offset
    return datetime(y, mo, d, h, mi, s, tzinfo=timezone.utc).timestamp() + frac - offset


def local_name(tag):
    return tag.rsplit("}", 1)[-1]


def read_track(path):
    if not os.path.isfile(path):
        raise InputError("파일이 없습니다: %s" % path)
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as e:
        raise InputError("GPX(XML) 형식이 아닙니다: %s (%s)" % (path, e))
    points, no_time, bad_time = [], 0, 0
    for el in root.iter():
        if local_name(el.tag) != "trkpt":
            continue
        t = ele = None
        for child in el:
            name = local_name(child.tag)
            if name == "time" and child.text:
                try:
                    t = parse_time(child.text)
                except ValueError:
                    bad_time += 1
            elif name == "ele" and child.text:
                try:
                    ele = float(child.text)
                except ValueError:
                    pass
        if t is None:
            no_time += 1
            continue
        points.append(Point(t, float(el.get("lat")), float(el.get("lon")), ele))
    if not points:
        raise InputError(
            "시각이 있는 트랙 점이 없습니다 (<trkpt> 시각 없음 %d, 형식 오류 %d). "
            "로거 앱에서 GPX 트랙으로 내보냈는지 확인하세요." % (no_time, bad_time)
        )
    return points


class Frame:
    """TEAM_WORKSPACE 5.1 의 단일 수식."""

    def __init__(self, lat0, lon0):
        self.lat0, self.lon0 = lat0, lon0
        self.cos0 = math.cos(math.radians(lat0))

    def xz(self, lat, lon):
        return (lon - self.lon0) * K_LON * self.cos0, (lat - self.lat0) * K_LAT


def clean(points, frame):
    points = sorted(points, key=lambda p: p.t)
    kept, dup, jump = [], 0, 0
    for p in points:
        p.x, p.z = frame.xz(p.lat, p.lon)
        if kept:
            prev = kept[-1]
            dt = p.t - prev.t
            if dt <= 0:
                dup += 1
                continue
            if math.hypot(p.x - prev.x, p.z - prev.z) / dt > MAX_SPEED_MS:
                jump += 1
                continue
        kept.append(p)
    if len(kept) < len(points) * 0.5:
        raise InputError(
            "유효한 점이 절반 미만입니다 (전체 %d, 중복 %d, GPS 튐 %d). 로그 첫 점이 튄 값일 수 있습니다."
            % (len(points), dup, jump)
        )
    return kept, dup, jump


def select_window(points, entry, start_dist, tail_s, arrive_m, trim):
    """주행 구간 [start, end] 를 고른다. 반환: (start, end, arrival, reached)."""
    for p in points:
        p.d = math.hypot(p.x - entry[0], p.z - entry[1])

    arrival, reached, far = None, False, 0.0
    for i, p in enumerate(points):
        far = max(far, p.d)
        if p.d <= arrive_m and far >= 300.0:
            arrival, reached = i, True
            break
    if arrival is None:
        # 입구에 닿지 못한 궤적. 충분히 멀어진 뒤의 최근접점을 쓴다.
        begin = next((i for i, p in enumerate(points) if p.d >= 300.0), 0)
        arrival = min(range(begin, len(points)), key=lambda i: points[i].d)

    if not trim:
        return 0, len(points) - 1, arrival, reached

    start = None
    for i in range(arrival, -1, -1):
        if points[i].d >= start_dist:
            start = i
            break
    if start is None:
        start = 0
        while start < arrival:
            a, b = points[start], points[start + 1]
            if math.hypot(b.x - a.x, b.z - a.z) / (b.t - a.t) > 1.0:
                break
            start += 1

    limit = points[arrival].t + tail_s
    end = arrival
    while end + 1 < len(points) and points[end + 1].t <= limit:
        end += 1
    return start, end, arrival, reached


def resample_1hz(points, frame):
    t0, t1 = math.ceil(points[0].t), math.floor(points[-1].t)
    out, j = [], 0
    for t in range(int(t0), int(t1) + 1):
        while j + 2 < len(points) and points[j + 1].t <= t:
            j += 1
        a, b = points[j], points[min(j + 1, len(points) - 1)]
        u = 0.0 if b.t == a.t else min(1.0, max(0.0, (t - a.t) / (b.t - a.t)))
        ele = None
        if a.ele is not None and b.ele is not None:
            ele = a.ele + (b.ele - a.ele) * u
        p = Point(float(t), a.lat + (b.lat - a.lat) * u, a.lon + (b.lon - a.lon) * u, ele)
        p.x, p.z = frame.xz(p.lat, p.lon)
        out.append(p)
    course = 0.0
    for i, p in enumerate(out):
        a, b = out[max(0, i - 1)], out[min(len(out) - 1, i + 1)]
        span = max(b.t - a.t, 1.0)
        dx, dz = b.x - a.x, b.z - a.z
        p.v = math.hypot(dx, dz) / span
        if p.v >= STATIONARY_MS:
            course = math.degrees(math.atan2(dx, dz)) % 360.0
        p.course = course
    return out


def catmull_rom(ctrl, ds=2.0):
    """centripetal Catmull-Rom (alpha 0.5). 양 끝은 끝점 반사. 호길이 ds 재샘플. (5.4)"""
    pts = [(float(x), float(z)) for x, z in ctrl]
    if len(pts) < 2:
        return pts
    ext = [(2 * pts[0][0] - pts[1][0], 2 * pts[0][1] - pts[1][1])] + pts
    ext.append((2 * pts[-1][0] - pts[-2][0], 2 * pts[-1][1] - pts[-2][1]))

    def knot(a, b):
        return max(math.hypot(b[0] - a[0], b[1] - a[1]), 1e-6) ** 0.5

    def lerp(a, b, ta, tb, t):
        w = (t - ta) / (tb - ta)
        return a[0] + (b[0] - a[0]) * w, a[1] + (b[1] - a[1]) * w

    dense = []
    for i in range(1, len(ext) - 2):
        p0, p1, p2, p3 = ext[i - 1], ext[i], ext[i + 1], ext[i + 2]
        t0 = 0.0
        t1 = t0 + knot(p0, p1)
        t2 = t1 + knot(p1, p2)
        t3 = t2 + knot(p2, p3)
        for k in range(60):
            t = t1 + (t2 - t1) * k / 60.0
            a1 = lerp(p0, p1, t0, t1, t)
            a2 = lerp(p1, p2, t1, t2, t)
            a3 = lerp(p2, p3, t2, t3, t)
            dense.append(lerp(lerp(a1, a2, t0, t2, t), lerp(a2, a3, t1, t3, t), t1, t2, t))
    dense.append(pts[-1])

    out, acc = [dense[0]], 0.0
    for a, b in zip(dense, dense[1:]):
        acc += math.hypot(b[0] - a[0], b[1] - a[1])
        if acc >= ds:
            out.append(b)
            acc = 0.0
    out.append(pts[-1])
    return out


def lateral_offset(x, z, poly):
    best = float("inf")
    for (ax, az), (bx, bz) in zip(poly, poly[1:]):
        dx, dz = bx - ax, bz - az
        den = dx * dx + dz * dz
        u = 0.0 if den == 0 else max(0.0, min(1.0, ((x - ax) * dx + (z - az) * dz) / den))
        best = min(best, math.hypot(x - (ax + dx * u), z - (az + dz * u)))
    return best


def preview(points, entry, trig, poly):
    """상태머신 규칙으로 미리 계산한다. 최종 판정은 JVM 리플레이 테스트다."""
    phase, hold = 1, 0.0
    t_p2 = t_cam = t_p3 = None
    offsets, warned, warnings = [], False, 0
    for i, p in enumerate(points):
        d = math.hypot(p.x - entry[0], p.z - entry[1])
        kmh = p.v * 3.6
        if phase == 1 and (
            d <= trig["tacticalDistM"] or (d <= trig["tacticalSlowDistM"] and kmh <= trig["tacticalSpeedKmh"])
        ):
            phase, t_p2 = 2, i
        if phase == 2:
            off = lateral_offset(p.x, p.z, poly)
            offsets.append(off)
            if off >= trig["offRouteWarnDistM"]:
                if not warned:
                    warnings += 1
                warned = True
            elif off <= trig["snapDistM"]:
                warned = False
            if t_cam is None and d <= trig["followViewDistM"]:
                t_cam = i
            if d <= trig["arrivedDistM"]:
                phase, t_p3 = 3, i
            elif d <= trig["arrivedStopDistM"] and kmh < trig["arrivedStopSpeedKmh"]:
                hold += 1.0
                if hold >= trig["arrivedStopHoldSec"]:
                    phase, t_p3 = 3, i
            else:
                hold = 0.0
        elif phase == 3:
            # JVM 리플레이 테스트는 도착(Phase 3) 이후 프레임의 이탈 경고도 센다. 도착 후 꼬리(--tail)가
            # 유도선 끝에서 20m 이상 멀어지면 여기서 잡힌다.
            off = lateral_offset(p.x, p.z, poly)
            if off >= trig["offRouteWarnDistM"]:
                if not warned:
                    warnings += 1
                warned = True
            elif off <= trig["snapDistM"]:
                warned = False
    attached = sum(1 for o in offsets if o <= trig["snapDistM"])
    return {
        "t_p2": t_p2,
        "t_cam": t_cam,
        "t_p3": t_p3,
        "phase2_frames": len(offsets),
        "snap_ratio": attached / len(offsets) if offsets else 0.0,
        "warnings": warnings,
        "offsets": offsets,
    }


def percentile(values, q):
    data = sorted(values)
    if not data:
        return 0.0
    return data[min(len(data) - 1, int(round(q * (len(data) - 1))))]


def write_gpx(path, points, name, desc):
    fmt = "%Y-%m-%dT%H:%M:%SZ"
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<gpx version="1.1" creator="tactical-entry-guide" xmlns="http://www.topografix.com/GPX/1/1">',
        "  <metadata><name>%s</name><desc>%s</desc><keywords>real-drive</keywords></metadata>"
        % (escape(name), escape(desc)),
        "  <trk><name>%s</name><trkseg>" % escape(name),
    ]
    for p in points:
        stamp = datetime.fromtimestamp(p.t, timezone.utc).strftime(fmt)
        lines.append(
            '    <trkpt lat="%.7f" lon="%.7f"><ele>%.1f</ele><time>%s</time>'
            "<extensions><speed>%.2f</speed><course>%.1f</course></extensions></trkpt>"
            % (p.lat, p.lon, p.ele if p.ele is not None else 0.0, stamp, p.v, p.course)
        )
    lines += ["  </trkseg></trk>", "</gpx>", ""]
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines))


def is_simulated(path):
    with open(path, encoding="utf-8") as f:
        head = f.read(4096)
    m = re.search(r"<keywords>(.*?)</keywords>", head)
    return bool(m and "simulated" in m.group(1))


class Paths:
    def __init__(self, root, report_dir=None):
        self.gis = os.path.join(root, "gis_data")
        self.assets = os.path.join(root, "android_pleos_app", "app", "src", "main", "assets")
        self.docs = report_dir or os.path.join(root, "docs")
        self.backup = os.path.join(self.gis, "backup_simulated")
        self.zone = os.path.join(self.gis, "sejong_costco_zone.json")

    def report(self, kind):
        return os.path.join(self.docs, "real_drive_report_%s.md" % kind)


def backup_current(paths, name):
    current = os.path.join(paths.gis, name)
    if not os.path.isfile(current):
        return
    os.makedirs(paths.backup, exist_ok=True)
    if is_simulated(current):
        target = os.path.join(paths.backup, name)
        if not os.path.isfile(target):
            shutil.copyfile(current, target)
    else:
        shutil.copyfile(current, os.path.join(paths.backup, name + ".prev_real"))


def restore(paths, which):
    restored = 0
    for kind in ("main", "ev") if which == "all" else (which,):
        name = KINDS[kind]["gpx"]
        src = os.path.join(paths.backup, name)
        if not os.path.isfile(src):
            print("[%s] 교체된 적이 없어 복원할 것이 없습니다." % kind)
            continue
        restored += 1
        shutil.copyfile(src, os.path.join(paths.gis, name))
        shutil.copyfile(src, os.path.join(paths.assets, name))
        if os.path.isfile(paths.report(kind)):
            os.remove(paths.report(kind))
        print("[%s] 시뮬레이션 GPX 복원 완료: %s (앱 반영은 재빌드 필요)" % (kind, name))
    return 0 if restored else 1


def fmt_t(value):
    return "-" if value is None else "%ds" % value


def judge(result, start_d, reached, closest, gap, duration):
    """(fatal, warn) 메시지 목록."""
    fatal, warn = [], []
    if start_d < 300.0:
        fatal.append("시작이 입구에서 %.0fm 로 너무 가깝습니다. Phase 1→2 전환을 볼 수 없습니다." % start_d)
    if not reached:
        if closest > ENTRY_REACH_FAIL_M:
            fatal.append("궤적이 입구에 닿지 않습니다 (최근접 %.0fm). 입구 좌표가 실제와 다를 가능성이 큽니다." % closest)
        else:
            warn.append("입구 15m 안에 들어오지 않았습니다 (최근접 %.0fm). 정차 판정으로 도착할 수 있습니다." % closest)
    if result["t_p3"] is None:
        (fatal if closest > ENTRY_REACH_FAIL_M else warn).append("Phase 3 도착이 판정되지 않았습니다.")
    if gap > MAX_GAP_FAIL_S:
        fatal.append("GPS 기록이 %.0f초 끊겼습니다." % gap)
    elif gap > MAX_GAP_WARN_S:
        warn.append("GPS 기록이 최대 %.0f초 끊겼습니다 (선형 보간됨)." % gap)
    if 300.0 <= start_d < 500.0:
        warn.append("시작이 입구에서 %.0fm 입니다. 브리프의 500m 밖 구간이 짧습니다." % start_d)
    if result["phase2_frames"] and result["snap_ratio"] < 0.98:
        warn.append(
            "Phase 2 유도선 흡착 %.1f%% (기준 98%%). 실제 경로가 유도선과 다릅니다. Blender 에서 Path_* 를 옮기세요."
            % (result["snap_ratio"] * 100)
        )
    if result["warnings"]:
        warn.append("이탈 경고 %d회 예상 (기준 0회). 유도선 수정이 필요합니다. "
                    "도착 후 꼬리에서만 나면 --tail 을 줄이세요." % result["warnings"])
    if duration > DEMO_LIMIT_S:
        warn.append("구간이 %.0f초로 깁니다. 2분 데모에 넣으려면 --start-dist 를 줄이거나 앱의 2배속을 쓰세요." % duration)
    return fatal, warn


def report_md(kind, args, info):
    spec = KINDS[kind]
    res = info["result"]
    off = res["offsets"]
    rows = [
        ("로그 일자", info["date"]),
        ("차량·기록 장치", "%s / %s" % (args.vehicle, args.device)),
        ("구간", "입구 직선거리 %.0fm 지점 → 입구" % info["start_d"]),
        ("주행 시간 / 점 수", "%.0f초 / %d점 (1Hz 정규화)" % (info["duration"], info["count"])),
        ("Phase 순서", info["phases"]),
        ("Phase 2 흡착 비율", "%.1f%%" % (res["snap_ratio"] * 100)),
        ("이탈 경고", "%d회" % res["warnings"]),
    ]
    if off:
        rows.append(
            (
                "유도선 횡거리",
                "평균 %.1fm, 중앙값 %.1fm, 95%% %.1fm, 최대 %.1fm"
                % (statistics.mean(off), statistics.median(off), percentile(off, 0.95), max(off)),
            )
        )
    body = "\n".join("| %s | %s |" % row for row in rows)
    return (
        "# 실주행 로그 검증 — %s\n\n"
        "`scripts/import_real_gpx.py` 가 생성했다. Phase 값은 상태머신 규칙으로 계산한 미리보기이고, "
        "최종 판정은 `testDebugUnitTest` 의 GPX 리플레이 테스트다.\n\n"
        "| 항목 | 결과 |\n|---|---|\n%s\n\n"
        "- 기록 장치가 스마트폰이면 차량 GNSS 측위가 아니다. 기획서에도 기록 장치를 그대로 적는다.\n"
        "- 횡거리는 GNSS 오차와 실제 차선 위치의 합이다. GNSS 오차만의 값이 아니다.\n"
        % (spec["label"], body)
    )


def process(kind, src, paths, zone, frame, args):
    spec = KINDS[kind]
    print("\n=== %s: %s ===" % (spec["label"], os.path.basename(src)))
    entry_info = next((e for e in zone["entries"] if e["id"] == spec["entry"]), None)
    if entry_info is None:
        raise InputError("zone JSON 에 입구 %s 가 없습니다." % spec["entry"])
    entry = (entry_info["position"]["x"], entry_info["position"]["z"])
    trig = zone["triggers"]

    raw = read_track(src)
    cleaned, dup, jump = clean(raw, frame)
    start, end, arrival, reached = select_window(
        cleaned, entry, args.start_dist, args.tail, trig["arrivedDistM"], not args.no_trim
    )
    window = cleaned[start : end + 1]
    if len(window) < 3:
        raise InputError("주행 구간이 너무 짧습니다 (%d점)." % len(window))
    removed = len(cleaned) - len(window)
    gap = max(b.t - a.t for a, b in zip(window, window[1:]))
    points = resample_1hz(window, frame)
    duration = points[-1].t - points[0].t

    ctrl = [(p["x"], p["z"]) for p in zone[spec["path"]]]
    poly = catmull_rom(ctrl)
    result = preview(points, entry, trig, poly)

    start_d = math.hypot(points[0].x - entry[0], points[0].z - entry[1])
    closest = min(math.hypot(p.x - entry[0], p.z - entry[1]) for p in points)
    phases = "1"
    if result["t_p2"] is not None:
        phases += "→2"
    if result["t_p3"] is not None:
        phases += "→3"
    date = datetime.fromtimestamp(points[0].t, KST).strftime("%Y-%m-%d %H:%M KST")
    interval = duration / max(1, len(window) - 1)

    print("  입력 %d점 → 중복 %d, GPS 튐 %d 제거 → 구간 밖 %d점 제거 → %d점(1Hz)" % (len(raw), dup, jump, removed, len(points)))
    print("  평균 원본 간격 %.2f초, 최대 끊김 %.1f초" % (interval, gap))
    print("  일시 %s, 시작 거리 %.0fm, 입구 최근접 %.1fm, 주행 %.0f초" % (date, start_d, closest, duration))
    print("  최고 %.0fkm/h" % (max(p.v for p in points) * 3.6))
    print(
        "  미리보기: Phase %s | P2 %s, 카메라 전환 %s, P3 %s | P2 %d프레임, 흡착 %.1f%%, 이탈 경고 %d회"
        % (
            phases,
            fmt_t(result["t_p2"]),
            fmt_t(result["t_cam"]),
            fmt_t(result["t_p3"]),
            result["phase2_frames"],
            result["snap_ratio"] * 100,
            result["warnings"],
        )
    )

    fatal, warn = judge(result, start_d, reached, closest, gap, duration)
    if args.no_trim:
        warn.append("--no-trim: 구간 밖 위치(집 등)가 파일에 남습니다. 저장소·제출물에 넣기 전에 확인하세요.")
    for m in warn:
        print("  [경고] " + m)
    for m in fatal:
        print("  [거부] " + m)

    if closest > args.entry_tolerance and not reached:
        stop = min(points, key=lambda p: math.hypot(p.x - entry[0], p.z - entry[1]))
        last = points[-1]
        print("  궤적 끝 좌표: %.6f, %.6f / 입구 최근접 좌표: %.6f, %.6f" % (last.lat, last.lon, stop.lat, stop.lon))
        if kind == "main":
            print("  실제 입구가 궤적 끝이라면 다음을 실행한 뒤 이 스크립트를 다시 돌리세요:")
            print("    python3 scripts/extract_shp_to_tactical.py --origin %.6f %.6f" % (last.lat, last.lon))

    if fatal and not args.force:
        print("  → 교체하지 않습니다 (--force 로 강행 가능).")
        return 2
    if args.dry_run:
        print("  → --dry-run: 파일을 바꾸지 않았습니다.")
        return 0

    info = {
        "result": result,
        "date": date,
        "start_d": start_d,
        "duration": duration,
        "count": len(points),
        "phases": phases,
    }
    backup_current(paths, spec["gpx"])
    target = os.path.join(paths.gis, spec["gpx"])
    desc = "실주행 로그: %s, %s. 1Hz 정규화, 구간 밖 위치 제거." % (args.vehicle, args.device)
    write_gpx(target, points, spec["title"], desc)
    shutil.copyfile(target, os.path.join(paths.assets, spec["gpx"]))
    os.makedirs(paths.docs, exist_ok=True)
    with open(paths.report(kind), "w", encoding="utf-8", newline="\n") as f:
        f.write(report_md(kind, args, info))
    print("  → 교체 완료: gis_data/%s, assets/%s" % (spec["gpx"], spec["gpx"]))
    print("  → 보고서: %s" % os.path.relpath(paths.report(kind), args.root))
    return 0


def main():
    utf8_stdout()
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--main", metavar="GPX", help="주차장 입구행 실주행 로그")
    ap.add_argument("--ev", metavar="GPX", help="전기차 충전소 입구행 실주행 로그")
    ap.add_argument("--restore", choices=("main", "ev", "all"), help="시뮬레이션 GPX 로 되돌린다")
    ap.add_argument("--dry-run", action="store_true", help="검사만 하고 파일을 바꾸지 않는다")
    ap.add_argument("--force", action="store_true", help="품질 기준 미달이어도 교체한다")
    ap.add_argument("--start-dist", type=float, default=520.0, help="구간 시작: 입구 직선거리 m (기본 520)")
    ap.add_argument("--tail", type=float, default=8.0, help="도착 후 남길 초 (기본 8)")
    ap.add_argument("--no-trim", action="store_true", help="구간을 자르지 않는다. 구간 밖 위치가 남는다")
    ap.add_argument("--entry-tolerance", type=float, default=30.0, help="입구 좌표 불일치 안내 기준 m")
    ap.add_argument("--vehicle", default="아반떼 CN8", help="보고서·메타데이터의 차량 표기")
    ap.add_argument("--device", default="스마트폰 GPS 로거", help="기록 장치 표기. 실명·계정명을 넣지 말 것")
    ap.add_argument("--report-dir", default=None, help="검증 보고서 폴더 (기본 <root>/docs)")
    ap.add_argument("--root", default=os.path.abspath(os.path.join(os.path.dirname(__file__), "..")), help=argparse.SUPPRESS)
    args = ap.parse_args()

    paths = Paths(args.root, args.report_dir)
    if args.restore:
        return restore(paths, args.restore)
    if not args.main and not args.ev:
        ap.error("--main, --ev, --restore 중 하나는 필요합니다")

    with open(paths.zone, encoding="utf-8") as f:
        zone = json.load(f)
    frame = Frame(zone["origin"]["lat"], zone["origin"]["lon"])

    code = 0
    replaced = False
    for kind, src in (("main", args.main), ("ev", args.ev)):
        if not src:
            continue
        try:
            rc = process(kind, src, paths, zone, frame, args)
        except InputError as e:
            print("  [오류] %s" % e)
            rc = 1
        code = max(code, rc)
        replaced = replaced or (rc == 0 and not args.dry_run)

    if replaced:
        print("\n다음 단계")
        print("  1. python3 scripts/verify/self_check.py")
        print("  2. bash scripts/gradle_win.sh testDebugUnitTest      # 실제 궤적으로 리플레이 판정")
        print("  3. bash scripts/run_emulator_demo.sh                 # 앱 라벨이 '실차 GPX 주행 재생' 인지 확인")
        print("  되돌리기: python3 scripts/import_real_gpx.py --restore all")
    return code


if __name__ == "__main__":
    sys.exit(main())
