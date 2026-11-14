#!/usr/bin/env python3
"""Pleos 에뮬레이터 보조 도구 (표준 라이브러리만 사용).
  python3 pleos_avd_tool.py config <config.ini>          AVD 설정 덮어쓰기
  python3 pleos_avd_tool.py install-image <sdk_dir>      공식 zip 다운로드·SHA-1 검증·압축해제·package.xml 작성
"""
import hashlib, os, shutil, sys, urllib.request, zipfile

URL = "https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp_car_emulator.zip"
SHA1 = "b4f858d5bcc6c71506d7edfc872bcb9e5a11acaa"
SIZE = 4087751964
MIN_FREE_GB = 25
TOP = "crp_avd_out_x86_64"
CONFIG = {
    "hw.lcd.width": "2560", "hw.lcd.height": "1440", "hw.lcd.density": "160",
    "hw.initialOrientation": "landscape", "hw.ramSize": "4096",
    "hw.gpu.enabled": "yes", "hw.gpu.mode": "host", "hw.keyboard": "yes",
    "fastboot.forceColdBoot": "yes", "fastboot.forceFastBoot": "no",
    "hw.cpu.ncore": "6", "hw.gps": "yes", "disk.dataPartition.size": "12G",
    "hw.device.name": "Pleos_Connect_v2_0",
}
PACKAGE_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<ns2:repository xmlns:ns2="http://schemas.android.com/repository/android/common/02" xmlns:ns3="http://schemas.android.com/repository/android/common/01" xmlns:ns5="http://schemas.android.com/sdk/android/repo/sys-img2/03"><localPackage path="system-images;android-34;pleos_car_emulator;x86_64" obsolete="false"><type-details xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="ns5:sysImgDetailsType"><api-level>34</api-level><extension-level>0</extension-level><base-extension>true</base-extension><tag><id>android-automotive</id><display>Pleos Connect v2.1.0 APIs</display></tag><vendor><id>pleos</id><display>Pleos Connect v2.1.0</display></vendor><abi>x86_64</abi></type-details><revision><major>2</major></revision><display-name>Pleos Connect v2.1.0 APIs Intel x86_64 Atom System Image</display-name></localPackage></ns2:repository>
"""

def apply_config(path):
    lines = open(path, encoding="utf-8").read().splitlines()
    out = [l for l in lines if l.split("=", 1)[0].strip() not in CONFIG]
    out += [f"{k}={v}" for k, v in CONFIG.items()]
    open(path, "w", encoding="utf-8", newline="\n").write("\n".join(out) + "\n")
    print(f"[OK] {path} 에 {len(CONFIG)}개 설정 적용")

def install_image(sdk):
    dest = os.path.join(sdk, "system-images", "android-34", "pleos_car_emulator", "x86_64")
    if os.path.isfile(os.path.join(dest, "system.img")) or os.path.isfile(os.path.join(dest, "package.xml")):
        print("[i] 이미지가 이미 있음:", dest); return 0
    print("""
[라이선스 안내] Pleos Connect 에뮬레이터 이미지·SDK 는 42dot SDK License 의 적용을 받는다.
이미지를 설치·사용하면 해당 라이선스에 동의한 것으로 간주된다. 동의하지 않으면 Ctrl+C 로 중단하라.
(디컴파일·재배포 금지. 이미지를 저장소에 넣지 마라.)
""")
    os.makedirs(sdk, exist_ok=True)
    free = shutil.disk_usage(sdk).free / 1e9
    if free < MIN_FREE_GB:
        print(f"[X] 디스크 여유 {free:.1f}GB < {MIN_FREE_GB}GB. 설치를 중단한다."); return 3
    tmp = os.path.join(sdk, "crp_car_emulator.zip.part")
    zpath = os.path.join(sdk, "crp_car_emulator.zip")
    if not os.path.isfile(zpath):
        print("[i] 다운로드:", URL)
        done = 0
        with urllib.request.urlopen(URL) as r, open(tmp, "wb") as f:
            while True:
                b = r.read(1 << 20)
                if not b: break
                f.write(b); done += len(b)
                if done % (256 << 20) < (1 << 20): print(f"  {done/1e9:.2f}/{SIZE/1e9:.2f} GB", flush=True)
        os.replace(tmp, zpath)
    h = hashlib.sha1()
    with open(zpath, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""): h.update(b)
    if h.hexdigest() != SHA1 or os.path.getsize(zpath) != SIZE:
        print(f"[X] SHA-1/크기 불일치: {h.hexdigest()} (기대 {SHA1}). zip 을 삭제하고 다시 시도하라."); return 4
    print("[OK] SHA-1 검증 통과")
    stage = os.path.join(sdk, "_pleos_extract")
    shutil.rmtree(stage, ignore_errors=True)
    with zipfile.ZipFile(zpath) as z: z.extractall(stage)
    src = os.path.join(stage, TOP)
    if not os.path.isdir(src):
        print("[X] zip 최상위 폴더", TOP, "없음"); return 5
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    shutil.move(src, dest)
    shutil.rmtree(stage, ignore_errors=True)
    open(os.path.join(dest, "package.xml"), "w", encoding="utf-8", newline="\n").write(PACKAGE_XML)
    os.remove(zpath)
    print("[OK] 설치 완료:", dest); return 0

if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "config": apply_config(sys.argv[2])
    elif len(sys.argv) == 3 and sys.argv[1] == "install-image": sys.exit(install_image(sys.argv[2]))
    else: print(__doc__); sys.exit(2)
