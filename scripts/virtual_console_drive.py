#!/usr/bin/env python3
"""Pleos Virtual Console HTTP API 로 주행 상태를 재현한다 (표준 라이브러리만 사용).
  python3 scripts/virtual_console_drive.py --gear Drive --speed 25 --hold 60 [--watch-uxr]
  python3 scripts/virtual_console_drive.py --follow-gpx gis_data/sejong_costco_cn8_drive.gpx [--watch-uxr]
--follow-gpx: GPX 의 <speed>(m/s, 없으면 좌표로 추정)를 1Hz 로 Driving View 속도에 보낸다. 시작 Drive, 끝나면 Park.
adb forward tcp:8088 tcp:8088 를 직접 수행하고, 하트비트(1초)를 유지하며, 종료 시(Ctrl+C 포함) 항상 Park 으로 복귀한다.
"""
import argparse, glob, os, platform, re, subprocess, sys, threading, time, urllib.error, urllib.parse, urllib.request, uuid

def find_adb():
    exe = ".exe" if os.name == "nt" or "microsoft" in platform.release().lower() else ""
    cands = [os.environ.get("ANDROID_SDK_ROOT"), os.environ.get("ANDROID_HOME"),
             os.path.join(os.environ.get("LOCALAPPDATA", "/nonexistent"), "Android", "Sdk"),
             *glob.glob("/mnt/c/Users/*/AppData/Local/Android/Sdk"),
             os.path.expanduser("~/Android/Sdk")]
    for c in cands:
        if c and os.path.isfile(os.path.join(c, "platform-tools", "adb" + exe)):
            return os.path.join(c, "platform-tools", "adb" + exe)
    from shutil import which
    return which("adb")

def gpx_speeds_kph(path):
    """GPX 트랙포인트별 속도(km/h) 목록. <speed> 가 없으면 이웃 점의 거리/시간으로 추정한다. 1Hz 가정."""
    import math, xml.etree.ElementTree as ET
    pts = []
    for e in ET.parse(path).getroot().iter():
        if e.tag.endswith("trkpt"):
            sp = None
            for c in e.iter():
                if c.tag.endswith("speed") and c.text:
                    sp = float(c.text) * 3.6
            pts.append((float(e.attrib["lat"]), float(e.attrib["lon"]), sp))
    if not pts:
        raise SystemExit("[X] GPX 에 trkpt 가 없습니다: " + path)
    out = []
    for i, (la, lo, sp) in enumerate(pts):
        if sp is None:
            j = i - 1 if i > 0 else i
            k = i if i > 0 else min(1, len(pts) - 1)
            la1, lo1, _ = pts[j]; la2, lo2, _ = pts[k]
            dx = (lo2 - lo1) * 111320 * math.cos(math.radians(la)); dz = (la2 - la1) * 110574
            sp = math.hypot(dx, dz) * 3.6  # 1Hz 가정: 이웃 점 간 거리(m) = m/s
        out.append(sp)
    return out

def reexec_with_windows_python():
    """WSL(NAT)에서는 adb.exe 의 forward 가 Windows localhost 에만 열려 접근할 수 없다. Windows python 으로 재실행한다."""
    if os.name == "nt" or "microsoft" not in platform.release().lower() or os.environ.get("PLEOS_DRIVE_REEXEC"):
        return
    from shutil import which
    py = which("python.exe") or which("py.exe")
    if not py:
        return
    win = subprocess.run(["wslpath", "-w", os.path.abspath(__file__)], capture_output=True, text=True).stdout.strip()
    env = dict(os.environ, PLEOS_DRIVE_REEXEC="1", PYTHONIOENCODING="utf-8", PYTHONUTF8="1")
    import signal
    args = list(sys.argv[1:])
    for i, v in enumerate(args[:-1]):
        if v == "--follow-gpx":
            args[i + 1] = subprocess.run(["wslpath", "-w", os.path.abspath(args[i + 1])], capture_output=True, text=True).stdout.strip()
    child = subprocess.Popen([py, win, *args], env=env)
    def on_signal(signum, frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, on_signal)
    try:
        sys.exit(child.wait())
    except KeyboardInterrupt:
        # WSL 에서 보낸 인터럽트는 Windows 자식 프로세스의 finally 를 실행시키지 못할 수 있다. 직접 Park 복귀를 보장한다.
        print("\n중단됨 — Park 으로 복귀합니다.", flush=True)
        try: child.wait(timeout=3)
        except Exception: child.kill()
        subprocess.call([py, win, "--park-only"], env=env)
        sys.exit(130)

def summarize(txt):
    """응답 JSON 에서 기어·속도만 뽑아 한 줄로 요약한다."""
    import json
    try:
        d = json.loads(txt)
        g = d.get("gear", d)
        gear = g.get("actual", {}).get("value")
        ctl = d.get("controls", {})
        return f"gear={gear}" + (f" speed_kph={ctl.get('speed_kph')} brake_pct={ctl.get('brake_pct')}" if ctl else "")
    except Exception:
        return txt[:120]

def main():
    for st in (sys.stdout, sys.stderr):
        try: st.reconfigure(encoding="utf-8")
        except Exception: pass
    reexec_with_windows_python()
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--gear", default="Drive", choices=["Drive", "Park", "Reverse", "Neutral"])
    ap.add_argument("--speed", type=float, default=0.0, help="km/h")
    ap.add_argument("--hold", type=float, default=60.0, help="유지 시간(초)")
    ap.add_argument("--port", type=int, default=8088)
    ap.add_argument("--follow-gpx", metavar="GPX", help="GPX 의 속도를 1Hz 로 따라간다(--speed/--hold 무시)")
    ap.add_argument("--park-only", action="store_true", help="즉시 Park 으로 복귀하고 종료")
    ap.add_argument("--watch-uxr", action="store_true", help="CarUxRestrictions 값을 주기적으로 출력")
    a = ap.parse_args()
    adb = find_adb()
    if not adb: sys.exit("[X] adb 를 찾지 못했습니다.")
    base = f"http://127.0.0.1:{a.port}"
    client = "drive-" + uuid.uuid4().hex[:8]

    def adbrun(*args):
        return subprocess.run([adb, *args], capture_output=True, text=True, timeout=20).stdout.replace("\r", "")

    def call(method, path, **q):
        url = base + path + ("?" + urllib.parse.urlencode(q) if q else "")
        req = urllib.request.Request(url, method=method, data=b"" if method != "GET" else None)
        with urllib.request.urlopen(req, timeout=5) as r: return r.read().decode()

    adbrun("forward", f"tcp:{a.port}", f"tcp:{a.port}")
    stop = threading.Event()
    def beat():
        while not stop.is_set():
            try: call("POST", "/browser/session", client=client)
            except Exception: pass
            stop.wait(1.0)
    try: call("POST", "/browser/session", client=client)
    except Exception as e: sys.exit(f"[X] Virtual Console 에 연결할 수 없습니다 ({e}). 에뮬레이터가 실행 중인지 확인하세요.")
    threading.Thread(target=beat, daemon=True).start()

    def uxr():
        out = adbrun("shell", "dumpsys", "car_service", "--services", "CarUxRestrictionsManagerService")
        m = re.search(r"DO: (\w+)\s+UxR: (\d+)", out)
        return f"DO: {m.group(1)} UxR: {m.group(2)}" if m else "UxR 파싱 실패"

    if a.park_only: a.hold = 0; a.gear = "Park"; a.speed = 0; a.follow_gpx = None
    try:
        print("상태(시작):", summarize(call("GET", "/vehicle/state")))
        print("기어 설정:", summarize(call("PUT", "/vehicle/gear", gear=a.gear)))
        if a.follow_gpx:
            speeds = gpx_speeds_kph(a.follow_gpx)
            print(f"GPX 속도 추종: {len(speeds)}점, 최대 {max(speeds):.1f}km/h")
            t0 = time.time()
            for i, v in enumerate(speeds):
                call("PUT", "/vehicle/controls", speed=int(round(v)), accelerator=0, brake=0)
                if a.watch_uxr and i % 5 == 0: print(f"[{i:3d}s] {v:5.1f}km/h {uxr()}", flush=True)
                time.sleep(max(0.0, t0 + i + 1 - time.time()))
            a.hold = 0
        elif a.speed > 0: print("속도 설정:", summarize(call("PUT", "/vehicle/controls", speed=int(round(a.speed)), accelerator=0, brake=0)))
        end = time.time() + a.hold
        while time.time() < end:
            if a.watch_uxr: print(f"[{a.hold - (end - time.time()):5.1f}s] {uxr()}", flush=True)
            time.sleep(min(1.0, max(0.0, end - time.time())))
        print("상태(종료 직전):", summarize(call("GET", "/vehicle/state")))
    except KeyboardInterrupt:
        print("\n중단됨 — Park 으로 복귀합니다.")
    finally:
        for _ in range(3):
            try:
                call("PUT", "/vehicle/controls", speed=0, accelerator=0, brake=100)
                call("PUT", "/vehicle/gear", gear="Park"); break
            except Exception: time.sleep(0.5)
        else: print("[!] Park 복귀 실패 — 수동 확인 필요", file=sys.stderr)
        stop.set()
        time.sleep(1.5)
        if a.watch_uxr: print("[복귀 후]", uxr())
        print("Park 복귀 완료")

if __name__ == "__main__":
    main()
