#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Blender 에서 수정해 내보낸 custom.obj 의 Path / PathEV / Pin 위치를 Zone JSON 에 반영하고 앱·Unity 로 복사한다.

사용:
  python3 scripts/sync_blender_to_app.py                 # gis_data/sejong_costco_custom.obj -> gis_data/sejong_costco_zone.json
  python3 scripts/sync_blender_to_app.py --dry-run       # 변경 내역만 출력

좌표 계약: OBJ 파일은 `v <x동> <y위> <-z북>`. 오브젝트의 바운딩 박스 중심이 위치다.
  JSON x = 중심 X, JSON z = -중심 Z.
Blender 접미사(`Path_03.001`)는 허용한다. 같은 이름이 둘 이상이면 접미사가 없는 것을 우선한다.
OBJ 에 없는 객체는 기존 JSON 값을 유지하고 경고를 출력한다.
"""
import argparse
import json
import os
import re
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOL_M = 0.01   # Blender 내보내기 반올림(소수 6자리 이하)에 의한 잡음은 무시


def parse_obj(path):
    """-> {오브젝트 이름: [(x,y,z) 월드 좌표(z=북)]}. 면이 참조한 정점만 쓰고, 면이 없으면 선언된 정점을 쓴다."""
    verts = []
    objs = {}
    order = []
    cur = None
    has_o = False
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for raw in fh:
            ln = raw.strip()
            if not ln or ln.startswith("#"):
                continue
            tok = ln.split()
            tag = tok[0]
            if tag == "v":
                verts.append((float(tok[1]), float(tok[2]), float(tok[3])))
                if cur is not None:
                    cur["declared"].append(len(verts) - 1)
            elif tag in ("o", "g"):
                if tag == "g" and has_o:
                    continue
                if tag == "o":
                    has_o = True
                name = " ".join(tok[1:]) if len(tok) > 1 else "unnamed"
                cur = {"name": name, "declared": [], "face_idx": set()}
                objs[len(order)] = cur
                order.append(cur)
            elif tag == "f" and cur is not None:
                for t in tok[1:]:
                    i = int(t.split("/")[0])
                    cur["face_idx"].add(i - 1 if i > 0 else len(verts) + i)
    res = {}
    for o in order:
        ids = sorted(o["face_idx"]) if o["face_idx"] else o["declared"]
        if not ids:
            continue
        pts = [(verts[i][0], verts[i][1], -verts[i][2]) for i in ids]  # z = -Z_file = 북
        res.setdefault(o["name"], []).extend(pts)
    return res


def bbox_center_xz(pts):
    xs = [p[0] for p in pts]
    zs = [p[2] for p in pts]
    return (min(xs) + max(xs)) / 2.0, (min(zs) + max(zs)) / 2.0


_SUFFIX = re.compile(r"^(.*?)(\.\d{3,})$")


def resolve(objs):
    """Blender 접미사 허용: {기본이름: 위치(x,z)}. 접미사 없는 이름이 우선."""
    out = {}
    exact = set()
    for name, pts in objs.items():
        m = _SUFFIX.match(name)
        base = m.group(1) if m else name
        pos = bbox_center_xz(pts)
        if not m:
            out[base] = pos
            exact.add(base)
        elif base not in exact and base not in out:
            out[base] = pos
    return out


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


def sync(zone, pos):
    """zone(dict) 을 제자리에서 갱신. -> (변경 목록, 경고 목록)"""
    changes, warns = [], []

    def upd(item, label):
        p = pos.get(item["id"])
        if p is None:
            warns.append("OBJ 에 '%s' 가 없다 -> 기존 값 유지 (%.3f, %.3f)" % (item["id"], item["x"], item["z"]))
            return
        nx, nz = round(p[0], 3), round(p[1], 3)
        if abs(nx - item["x"]) > TOL_M or abs(nz - item["z"]) > TOL_M:
            changes.append("%s %s: (%.3f, %.3f) -> (%.3f, %.3f)  Δx=%+.3f Δz=%+.3f" % (
                label, item["id"], item["x"], item["z"], nx, nz, nx - item["x"], nz - item["z"]))
            item["x"], item["z"] = nx, nz
    for it in zone.get("guidePathPoints", []):
        upd(it, "Path  ")
    for it in zone.get("evGuidePathPoints", []):
        upd(it, "PathEV")
    for it in zone.get("hazardPins", []):
        upd(it, "Pin   ")
    return changes, warns


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--obj", default=os.path.join(ROOT, "gis_data", "sejong_costco_custom.obj"))
    ap.add_argument("--json", default=os.path.join(ROOT, "gis_data", "sejong_costco_zone.json"))
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--no-copy", action="store_true", help="assets / Unity 로 복사하지 않는다")
    args = ap.parse_args()

    if not os.path.exists(args.obj):
        sys.exit("OBJ 가 없다: %s" % args.obj)
    objs = parse_obj(args.obj)
    pos = resolve(objs)
    with open(args.json, "r", encoding="utf-8") as fh:
        zone = json.load(fh)
    changes, warns = sync(zone, pos)
    for w in warns:
        print("[경고]", w)
    if changes:
        print("[변경 %d 건]" % len(changes))
        for c in changes:
            print("  " + c)
    else:
        print("[변경 없음] Path/PathEV/Pin 위치가 JSON 과 같다 (허용오차 %.2fm)" % TOL_M)
    if args.dry_run:
        print("[dry-run] 파일을 쓰지 않았다")
        return
    if changes:
        with open(args.json, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(pretty(zone) + "\n")
    if not args.no_copy:
        assets = os.path.join(ROOT, "android_pleos_app", "app", "src", "main", "assets")
        models = os.path.join(ROOT, "unity_tactical_module", "Assets", "Models")
        res = os.path.join(ROOT, "unity_tactical_module", "Assets", "Resources")
        for d in (assets, models, res):
            os.makedirs(d, exist_ok=True)
        shutil.copyfile(args.json, os.path.join(assets, "sejong_costco_zone.json"))
        shutil.copyfile(args.obj, os.path.join(assets, "sejong_costco_custom.obj"))
        shutil.copyfile(args.obj, os.path.join(models, "sejong_costco_custom.obj"))
        shutil.copyfile(args.json, os.path.join(res, "sejong_costco_zone.json"))
        print("[복사] assets/sejong_costco_{zone.json,custom.obj}, Unity Models/Resources 갱신")


if __name__ == "__main__":
    main()
