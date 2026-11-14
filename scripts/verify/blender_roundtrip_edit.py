# blender.exe --background --python scripts/verify/blender_roundtrip_edit.py -- <in.obj> <out.obj> <report.json>
# sejong_costco_300m.obj 를 기본 Import(Forward -Z, Up Y) 로 읽고 Path_03 을 동쪽(+X) 5m 이동, Apply 후 Export.
import json
import sys

import bpy
from mathutils import Vector

argv = sys.argv[sys.argv.index("--") + 1:]
src, dst, rep = argv[0], argv[1], argv[2]

bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.wm.obj_import(filepath=src)   # 기본값: forward_axis=NEGATIVE_Z, up_axis=Y


def center(o):
    pts = [o.matrix_world @ Vector(c) for c in o.bound_box]
    xs, ys, zs = [p.x for p in pts], [p.y for p in pts], [p.z for p in pts]
    return [(min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2, (min(zs) + max(zs)) / 2]


report = {"names": sorted(o.name for o in bpy.data.objects), "version": bpy.app.version_string}
report["Path_01_center_blender_xyz"] = center(bpy.data.objects["Path_01"])
report["Path_03_before_blender_xyz"] = center(bpy.data.objects["Path_03"])
report["Pin_Barrier_01_center_blender_xyz"] = center(bpy.data.objects["Pin_Barrier_01"])
report["ORIGIN_RAMP_MARKER_center_blender_xyz"] = center(bpy.data.objects["ORIGIN_RAMP_MARKER"])

o = bpy.data.objects["Path_03"]
o.location.x += 5.0
bpy.ops.object.select_all(action="DESELECT")
o.select_set(True)
bpy.context.view_layer.objects.active = o
bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)   # Ctrl+A
report["Path_03_after_blender_xyz"] = center(o)

bpy.ops.wm.obj_export(filepath=dst, export_materials=False)   # 기본 축: forward -Z, up Y
with open(rep, "w", encoding="utf-8") as f:
    json.dump(report, f, indent=1)
print("ROUNDTRIP_EDIT_DONE")
