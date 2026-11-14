#!/usr/bin/env python3
"""import_real_gpx.py 검증. 저장소 파일은 건드리지 않고 임시 폴더에서 돌린다.

실제 스마트폰 로거 파일을 흉내 낸 입력을 만든다: 자택에서 출발, 실명이 든 메타데이터, 불규칙 간격,
밀리초·+09:00 시각, 중복 시각, GPS 튐, 세그먼트 분할, 도착 후 주차장 안 주행.

  python3 scripts/verify/test_import_real_gpx.py
"""
import filecmp
import math
import os
import random
import re
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime, timedelta, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
SCRIPT = os.path.join(ROOT, "scripts", "import_real_gpx.py")
# 원점은 zone JSON 에서 읽는다 (4차: 원점 = MAIN_PARKING 램프 입구)
import json as _json
with open(os.path.join(ROOT, "gis_data", "sejong_costco_zone.json"), encoding="utf-8") as _f:
    _origin = _json.load(_f)["origin"]
LAT0, LON0 = _origin["lat"], _origin["lon"]
COS0 = math.cos(math.radians(LAT0))
HOME = (36.4695, 127.2373)  # 약 3km 서쪽
NAME = "홍길동"
fails = []


def check(name, ok, detail=""):
    print(("PASS " if ok else "FAIL ") + name + (" : " + str(detail) if detail != "" else ""))
    if not ok:
        fails.append(name)


def source_gpx(name):
    backup = os.path.join(ROOT, "gis_data", "backup_simulated", name)
    return backup if os.path.isfile(backup) else os.path.join(ROOT, "gis_data", name)


def read_points(path):
    text = open(path, encoding="utf-8").read()
    return [(float(a), float(b)) for a, b in re.findall(r'<trkpt lat="([\d.]+)" lon="([\d.]+)"', text)]


def dist(lat, lon):
    return math.hypot((lon - LON0) * 111320 * COS0, (lat - LAT0) * 110574)


def phone_gpx(path, track, ns="http://www.topografix.com/GPX/1/1", with_time=True):
    rng = random.Random(7)
    t = datetime(2026, 11, 8, 10, 0, 0, tzinfo=timezone(timedelta(hours=9)))
    rows = []

    for _ in range(30):  # 집에서 대기
        rows.append((HOME[0], HOME[1], t))
        t += timedelta(seconds=1)
    for i in range(240):  # 집 → 궤적 시작
        u = i / 240.0
        rows.append((HOME[0] + (track[0][0] - HOME[0]) * u, HOME[1] + (track[0][1] - HOME[1]) * u, t))
        t += timedelta(seconds=1)
    last = track[0]
    for lat, lon in track:  # 불규칙 간격
        step = rng.uniform(0.5, 1.5)
        rows.append(((last[0] + lat) / 2, (last[1] + lon) / 2, t))
        t += timedelta(seconds=step / 2)
        rows.append((lat, lon, t))
        t += timedelta(seconds=1.0 - step / 2)
        last = (lat, lon)
    for i in range(60):  # 도착 후 주차장 안
        rows.append((last[0] + 0.00002 * i, last[1], t))
        t += timedelta(seconds=1)

    k = 270 + (len(rows) - 270) // 2
    rows.insert(k, (rows[k][0] + 0.02, rows[k][1], rows[k][2] + timedelta(milliseconds=200)))  # GPS 튐 약 2km
    rows.insert(100, rows[100])  # 중복 시각

    def trkpt(row):
        lat, lon, when = row
        time_el = "<time>%s</time>" % when.isoformat(timespec="milliseconds") if with_time else ""
        return '<trkpt lat="%.7f" lon="%.7f"><ele>80.0</ele>%s</trkpt>' % (lat, lon, time_el)

    half = len(rows) // 2
    first = "".join(trkpt(r) for r in rows[:half])
    second = "".join(trkpt(r) for r in rows[half:])
    xml = (
        '<?xml version="1.0"?><gpx version="1.1" creator="Geo Tracker %s" xmlns="%s">'
        '<metadata><author><name>%s</name><email id="gildong" domain="example.com"/></author></metadata>'
        "<trk><name>%s의 드라이브</name><trkseg>%s</trkseg><trkseg>%s</trkseg></trk></gpx>"
        % (NAME, ns, NAME, NAME, first, second)
    )
    with open(path, "w", encoding="utf-8") as f:
        f.write(xml)


def run(sandbox, *args):
    env = dict(os.environ, PYTHONIOENCODING="utf-8", PYTHONUTF8="1")
    p = subprocess.run(
        [sys.executable, SCRIPT, "--root", sandbox] + list(args),
        capture_output=True,
        text=True,
        encoding="utf-8",
        env=env,
    )
    return p.returncode, p.stdout + p.stderr


def sandbox_dir(tmp):
    box = os.path.join(tmp, "box")
    gis = os.path.join(box, "gis_data")
    assets = os.path.join(box, "android_pleos_app", "app", "src", "main", "assets")
    os.makedirs(gis)
    os.makedirs(assets)
    os.makedirs(os.path.join(box, "docs"))
    shutil.copy(os.path.join(ROOT, "gis_data", "sejong_costco_zone.json"), gis)
    for n in ("sejong_costco_cn8_drive.gpx", "sejong_costco_ev_drive.gpx"):
        shutil.copy(source_gpx(n), gis)
        shutil.copy(source_gpx(n), assets)
    return box


def main():
    tmp = tempfile.mkdtemp(prefix="gpx_import_")
    try:
        box = sandbox_dir(tmp)
        gis_file = os.path.join(box, "gis_data", "sejong_costco_cn8_drive.gpx")
        asset_file = os.path.join(
            box, "android_pleos_app", "app", "src", "main", "assets", "sejong_costco_cn8_drive.gpx"
        )
        original = os.path.join(tmp, "original.gpx")
        shutil.copy(gis_file, original)
        track = read_points(gis_file)[:-5]  # 정차 구간 제외: 차량이 계속 움직이는 로그처럼

        phone = os.path.join(tmp, "phone.gpx")
        phone_gpx(phone, track)

        rc, out = run(box, "--main", phone, "--dry-run")
        check("dry-run 은 파일을 바꾸지 않는다", rc == 0 and filecmp.cmp(gis_file, original, shallow=False), rc)

        rc, out = run(box, "--main", phone)
        print(out)
        text = open(gis_file, encoding="utf-8").read()
        pts = read_points(gis_file)
        stamps = [
            datetime.strptime(s, "%Y-%m-%dT%H:%M:%SZ") for s in re.findall(r"<time>([^<]+)</time>", text)
        ]
        ds = [dist(a, b) for a, b in pts]
        check("교체 성공", rc == 0, rc)
        check("실명·기기명 제거", NAME not in text and "Geo Tracker" not in text and "gildong" not in text)
        check("simulated 키워드 없음", "simulated" not in text)
        check("real-drive 키워드", "<keywords>real-drive</keywords>" in text)
        check("1Hz 정규화", all((b - a).total_seconds() == 1.0 for a, b in zip(stamps, stamps[1:])), len(stamps))
        check("자택 위치 제거 (최대 거리 ≤ 560m)", max(ds) <= 560.0, "%.0fm" % max(ds))
        check("시작 거리 500m 이상", ds[0] >= 500.0, "%.0fm" % ds[0])
        check("입구까지 도달", min(ds) <= 15.0, "%.1fm" % min(ds))
        check("Phase 1→2→3", "Phase 1→2→3" in out)
        check("assets 도 같은 파일", filecmp.cmp(gis_file, asset_file, shallow=False))
        backup = os.path.join(box, "gis_data", "backup_simulated", "sejong_costco_cn8_drive.gpx")
        check("합성 GPX 백업", os.path.isfile(backup) and filecmp.cmp(backup, original, shallow=False))
        report = os.path.join(box, "docs", "real_drive_report_main.md")
        check("보고서 생성, 실명 없음", os.path.isfile(report) and NAME not in open(report, encoding="utf-8").read())

        rc, out = run(box, "--main", phone)
        check("재교체 시 이전 실로그 보관", rc == 0 and os.path.isfile(backup + ".prev_real"), rc)
        check("합성 백업은 유지", filecmp.cmp(backup, original, shallow=False))

        rc, out = run(box, "--restore", "all")
        check(
            "--restore 로 바이트 동일 복원",
            filecmp.cmp(gis_file, original, shallow=False) and filecmp.cmp(asset_file, original, shallow=False),
            rc,
        )
        check("복원 시 보고서 제거", not os.path.isfile(report))

        short = os.path.join(tmp, "short.gpx")
        phone_gpx(short, [p for p in track if dist(*p) >= 188.0])
        before = open(gis_file, "rb").read()
        rc, out = run(box, "--main", short)
        check("입구에 못 닿는 궤적은 거부 (코드 2)", rc == 2, rc)
        check("거부 시 파일 불변", open(gis_file, "rb").read() == before)
        check("거부 시 --origin 명령 안내", "--origin" in out and "extract_shp_to_tactical.py" in out)

        rc, out = run(box, "--main", short, "--force")
        check("--force 는 강행", rc == 0, rc)
        run(box, "--restore", "main")

        untimed = os.path.join(tmp, "untimed.gpx")
        phone_gpx(untimed, track, with_time=False)
        rc, out = run(box, "--main", untimed)
        check("시각 없는 파일은 입력 오류 (코드 1)", rc == 1, rc)

        v10 = os.path.join(tmp, "v10.gpx")
        phone_gpx(v10, track, ns="http://www.topografix.com/GPX/1/0")
        rc, out = run(box, "--main", v10, "--dry-run")
        check("GPX 1.0 네임스페이스 처리", rc == 0, rc)

        bad = os.path.join(tmp, "bad.gpx")
        with open(bad, "w", encoding="utf-8") as f:
            f.write("not xml")
        rc, out = run(box, "--main", bad)
        check("깨진 파일은 입력 오류 (코드 1)", rc == 1, rc)

        rc, out = run(box, "--main", os.path.join(tmp, "missing.gpx"))
        check("없는 파일은 입력 오류 (코드 1)", rc == 1, rc)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    print("\nTEST_IMPORT_REAL_GPX", "FAIL %s" % fails if fails else "ALL PASS")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
