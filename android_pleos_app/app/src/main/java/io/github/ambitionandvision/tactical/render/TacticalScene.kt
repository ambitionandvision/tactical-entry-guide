package io.github.ambitionandvision.tactical.render

import io.github.ambitionandvision.tactical.engine.ObjGroup
import io.github.ambitionandvision.tactical.model.HazardType
import io.github.ambitionandvision.tactical.model.ZoneData
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 렌더용 정적 장면. 메인 스레드에서 [build] 로 한 번 만들고, 매 프레임 [TacticalRenderer] 가 읽기만 한다.
 *
 * 그리기 순서(고정): 레이더 그리드 선 → 도로(GROUND) → 활성 리본 → 활성 입구 패드 → 흐르는 화살표 →
 * 입체(SOLID: 건물·램프·핀·회차불가 벽·차량)는 객체 단위 원거리→근거리 정렬 후 면 정렬.
 * 램프(OBJ 그룹 `RAMP_*`, 선택)는 본관 부피 안쪽에 놓이는 투시 표현이다. 항상 본관([rampTargets]) 뒤에 칠하고,
 * 입구 마커([rampOverObjs])·차량·입구 패드는 램프 뒤에 칠한다.
 */
class TacticalScene internal constructor(
    /** 정적 정점 x,y,z + 끝에 차량 템플릿 정점 영역 [vehicleVertexStart]..[vertexCount]. */
    val vertices: FloatArray,
    val vertexCount: Int,
    val vehicleVertexStart: Int,
    val faceStart: IntArray,
    val faceSize: IntArray,
    val faceIndex: IntArray,
    val faceColor: IntArray,
    val faceStroke: IntArray,
    val faceFlags: IntArray,
    val faceNormal: FloatArray,
    val faceCenter: FloatArray,
    val faceRadius: FloatArray,
    /** 리본 면: 경로상 호 길이(m). 그 외 0. */
    val faceArc: FloatArray,
    val objFaceStart: IntArray,
    val objFaceCount: IntArray,
    val objKind: IntArray,
    val objCenter: FloatArray,
    val objRadius: FloatArray,
    /** 레이더 그리드 선분 x0,z0,x1,z1 반복과 선 종류(0=격자, 1=거리 링). */
    val gridSegments: FloatArray,
    val gridClass: IntArray,
    val mainPath: SampledPath?,
    val evPath: SampledPath?,
    val labels: Array<String>,
    /** 라벨 기준점 x,y,z 와 종류(LABEL_*). */
    val labelAnchor: FloatArray,
    val labelKind: IntArray,
    val vehicleTemplate: FloatArray,
    /** 램프 객체 번호들. 램프가 없으면 빈 배열이고 그리기 순서는 기존과 같다. */
    val rampObjs: IntArray = IntArray(0),
    /** 본관 객체 번호들. 램프는 이 객체들보다 나중에 칠한다(본관 위로 비쳐 보임). */
    val rampTargets: IntArray = IntArray(0),
    /** 램프보다 나중에 칠할 정적 객체(입구 마커) 번호들. 차량은 렌더러가 따로 처리한다. */
    val rampOverObjs: IntArray = IntArray(0),
    /** 이 장면의 면 색을 만든 팔레트. 렌더러·뷰가 같은 벌을 쓴다. */
    val palette: Palette = Palette.DAY,
) {
    val faceCount: Int get() = faceSize.size
    val objectCount: Int get() = objKind.size
    val maxObjectFaces: Int = objFaceCount.maxOrNull() ?: 0
    val maxFaceSize: Int = faceSize.maxOrNull() ?: 3
    /** 화면 다각형 정점 용량. 오목 면은 근평면 클리핑으로 정점이 최대 1.5배까지 늘 수 있어 2배로 잡는다. */
    val polygonVertexBudget: Int = faceSize.sum() * 2 + faceSize.size

    /** 지형 면(도로·리본) 외 입체 면 수. 성능 로그용. */
    val solidFaceCount: Int

    init {
        var s = 0
        for (o in objKind.indices) if (objKind[o] == KIND_SOLID || objKind[o] == KIND_VEHICLE) s += objFaceCount[o]
        solidFaceCount = s
    }

    companion object {
        const val KIND_GROUND = 0
        const val KIND_RIBBON_MAIN = 1
        const val KIND_RIBBON_EV = 2
        const val KIND_PAD_MAIN = 3
        const val KIND_PAD_EV = 4
        const val KIND_SOLID = 5
        const val KIND_VEHICLE = 6

        const val FLAG_CULL_BACK = 1
        /** 리본 면: 지나간 구간이면 [Palette.RIBBON_PASSED] 로 바꿔 그린다. */
        const val FLAG_RIBBON_CORE = 2
        /** 같은 객체의 다른 면 위에 얹힌 표시(램프 쉐브론). 객체 안에서 항상 마지막에 칠한다. */
        const val FLAG_DECAL = 4

        const val LABEL_PIN = 0
        const val LABEL_ENTRY_MAIN = 1
        const val LABEL_ENTRY_EV = 2

        const val RIBBON_WIDTH_M = 3.6f
        const val RIBBON_GLOW_WIDTH_M = 8f
        const val RIBBON_Y = 0.15f

        /**
         * [groups] (OBJ, 월드 좌표) 와 [zone] 으로 장면을 만든다. 둘 다 없어도 빈 장면을 만든다.
         * OBJ 의 Path_* / PathEV_* / Pin_* 상자는 그리지 않는다 (위치는 zone JSON 이 기준).
         */
        fun build(groups: List<ObjGroup>?, zone: ZoneData?, palette: Palette = Palette.DAY): TacticalScene {
            val pal = palette
            val b = SceneBuilder(pal)

            // 1) 도로
            val roadGroups = groups?.filter { it.name.startsWith("ROAD_SURFACE") }.orEmpty()
            if (roadGroups.isNotEmpty()) {
                b.beginObject(KIND_GROUND)
                for (g in roadGroups) b.addGroupFlat(g, pal.ROAD)
                b.endObject()
            } else if (zone != null && zone.roads.isNotEmpty()) {
                b.beginObject(KIND_GROUND)
                for (r in zone.roads) {
                    val cx = FloatArray(r.centerline.size) { r.centerline[it].x }
                    val cz = FloatArray(r.centerline.size) { r.centerline[it].z }
                    b.addStrip(cx, cz, r.widthM, 0f, pal.ROAD, 0, null)
                }
                b.endObject()
            }

            // 2) 리본·입구 패드
            val mainPath = zone?.guidePathPoints?.takeIf { it.isNotEmpty() }?.let { pts ->
                CentripetalSpline.sample(FloatArray(pts.size) { pts[it].x }, FloatArray(pts.size) { pts[it].z })
            }
            val evPath = zone?.evGuidePathPoints?.takeIf { it.isNotEmpty() }?.let { pts ->
                CentripetalSpline.sample(FloatArray(pts.size) { pts[it].x }, FloatArray(pts.size) { pts[it].z })
            }
            for ((path, kind) in listOf(mainPath to KIND_RIBBON_MAIN, evPath to KIND_RIBBON_EV)) {
                if (path == null || path.count < 2) continue
                val px = FloatArray(path.count) { path.xz[it * 2] }
                val pz = FloatArray(path.count) { path.xz[it * 2 + 1] }
                b.beginObject(kind)
                b.addStrip(px, pz, RIBBON_GLOW_WIDTH_M, RIBBON_Y, pal.RIBBON_GLOW, 0, null)
                b.addStrip(px, pz, RIBBON_WIDTH_M, RIBBON_Y + 0.02f, pal.RIBBON_CORE, FLAG_RIBBON_CORE, path.s)
                b.endObject()
            }
            val labels = ArrayList<String>()
            val labelAnchor = ArrayList<Float>()
            val labelKind = ArrayList<Int>()
            zone?.entries?.forEach { e ->
                val kind = if (e.id == "EV_CHARGING_ENTRY") KIND_PAD_EV else KIND_PAD_MAIN
                b.beginObject(kind)
                b.addDisc(e.position.x, e.position.z, 7f, RIBBON_Y + 0.05f, 20, pal.ENTRY_PAD)
                b.endObject()
                labels.add(e.label)
                labelAnchor.add(e.position.x); labelAnchor.add(6f); labelAnchor.add(e.position.z)
                labelKind.add(if (kind == KIND_PAD_EV) LABEL_ENTRY_EV else LABEL_ENTRY_MAIN)
            }

            // 3) 건물 (OBJ 우선, 없으면 zone 외곽선 돌출)
            val target = groups?.filter { it.name.startsWith("TARGET_COSTCO_BUILDING") }.orEmpty()
            val surround = groups?.filter { it.name.startsWith("SURROUND_BUILDINGS") }.orEmpty()
            val marker = groups?.filter { it.name.startsWith("ORIGIN_RAMP_MARKER") }.orEmpty()
            val ramps = groups?.filter { it.name.startsWith(RAMP_PREFIX) }.orEmpty()
            val known = setOf("ROAD_SURFACE", "TARGET_COSTCO_BUILDING", "SURROUND_BUILDINGS", "ORIGIN_RAMP_MARKER", "Path_", "PathEV_", "Pin_", RAMP_PREFIX)
            val other = groups?.filter { g -> known.none { g.name.startsWith(it) } }.orEmpty()
            val targetObjs = ArrayList<Int>()
            if (target.isNotEmpty() || surround.isNotEmpty()) {
                val o0 = b.objectCount
                for (g in target) b.addGroupSolid(g, pal.TARGET, pal.TARGET_STROKE, splitComponents = false)
                for (o in o0 until b.objectCount) targetObjs.add(o)
                for (g in surround) b.addGroupSolid(g, pal.SURROUND, pal.SURROUND_STROKE, splitComponents = true)
            } else if (zone != null) {
                for (bd in zone.buildings) {
                    if (bd.footprint.size < 3) continue
                    val isTarget = bd.role == "TARGET"
                    b.beginObject(KIND_SOLID)
                    b.addExtrusion(
                        FloatArray(bd.footprint.size) { bd.footprint[it].x },
                        FloatArray(bd.footprint.size) { bd.footprint[it].z },
                        bd.heightM,
                        if (isTarget) pal.TARGET else pal.SURROUND,
                        if (isTarget) pal.TARGET_STROKE else pal.SURROUND_STROKE,
                    )
                    b.endObject()
                    if (isTarget && b.objectCount > 0) targetObjs.add(b.objectCount - 1)
                }
            }
            // 3-1) 램프 (선택 객체): 네온 반투명 쐐기 + 정적 오르막 쉐브론.
            val rampObjs = ArrayList<Int>()
            for (g in ramps) {
                val o = b.addRamp(g)
                if (o >= 0) rampObjs.add(o)
            }
            val markerStart = b.objectCount
            for (g in marker) b.addGroupSolid(g, pal.ENTRY_MARKER, 0, splitComponents = false)
            val markerObjs = IntArray(b.objectCount - markerStart) { markerStart + it }
            for (g in other) b.addGroupSolid(g, pal.SURROUND, pal.SURROUND_STROKE, splitComponents = true)

            // 4) 위험요소 핀 + 회차불가 벽
            zone?.hazardPins?.forEach { p ->
                val color = when (p.type) {
                    HazardType.BARRIER -> pal.PIN_BARRIER
                    HazardType.NO_TURN -> pal.PIN_NO_TURN
                    HazardType.WAIT_LANE -> pal.PIN_WAIT_LANE
                }
                b.beginObject(KIND_SOLID)
                b.addBox(p.x, p.z, 0.4f, 0f, PIN_POLE_H, color)
                b.addOctahedron(p.x, PIN_HEAD_Y, p.z, 1.6f, color)
                b.endObject()
                labels.add(p.label)
                labelAnchor.add(p.x); labelAnchor.add(PIN_HEAD_Y + 2.4f); labelAnchor.add(p.z)
                labelKind.add(LABEL_PIN)

                if (p.type == HazardType.NO_TURN) {
                    val path = mainPath ?: evPath
                    val tmp = FloatArray(4)
                    if (path != null && path.count >= 2) path.pointAt(path.nearestArc(p.x, p.z), tmp) else { tmp[2] = 0f; tmp[3] = 1f }
                    b.beginObject(KIND_SOLID)
                    b.addWall(p.x, p.z, tmp[2], tmp[3], NO_TURN_WALL_LEN_M, NO_TURN_WALL_H_M, pal.NO_TURN_WALL, pal.NO_TURN_WALL_STROKE)
                    b.endObject()
                }
            }

            // 5) 차량 템플릿 (매 프레임 위치·방위로 변환)
            return b.finish(
                mainPath, evPath, buildGrid(), labels.toTypedArray(),
                labelAnchor.toFloatArray(), labelKind.toIntArray(), vehicleTemplate(),
                rampObjs.toIntArray(),
                if (rampObjs.isEmpty()) IntArray(0) else targetObjs.toIntArray(),
                if (rampObjs.isEmpty()) IntArray(0) else markerObjs,
            )
        }

        const val RAMP_PREFIX = "RAMP_"
        const val PIN_POLE_H = 6f
        const val PIN_HEAD_Y = 7.6f
        const val NO_TURN_WALL_LEN_M = 24f
        const val NO_TURN_WALL_H_M = 3f
        const val GRID_RADIUS_M = 300f
        const val GRID_STEP_M = 50f
        const val RING_SEGMENTS = 72

        /** 300m 레이더 그리드: 50m 격자(원 안으로 자름) + 50m 간격 거리 링. */
        private fun buildGrid(): Pair<FloatArray, IntArray> {
            val seg = ArrayList<Float>()
            val cls = ArrayList<Int>()
            val r = GRID_RADIUS_M
            var v = -r
            while (v <= r + 1e-3f) {
                val h = sqrt(max(0f, r * r - v * v))
                if (h > 1f) {
                    seg.add(v); seg.add(-h); seg.add(v); seg.add(h); cls.add(0)
                    seg.add(-h); seg.add(v); seg.add(h); seg.add(v); cls.add(0)
                }
                v += GRID_STEP_M
            }
            var ring = GRID_STEP_M
            while (ring <= r + 1e-3f) {
                for (i in 0 until RING_SEGMENTS) {
                    val a0 = i * 2.0 * Math.PI / RING_SEGMENTS
                    val a1 = (i + 1) * 2.0 * Math.PI / RING_SEGMENTS
                    seg.add((ring * sin(a0)).toFloat()); seg.add((ring * cos(a0)).toFloat())
                    seg.add((ring * sin(a1)).toFloat()); seg.add((ring * cos(a1)).toFloat())
                    cls.add(1)
                }
                ring += GRID_STEP_M
            }
            return seg.toFloatArray() to cls.toIntArray()
        }

        /** 진행 방향 +along 으로 뾰족한 화살촉 프리즘 윤곽 (along, across). 높이 0..1.1m. */
        private fun vehicleTemplate(): FloatArray {
            val outline = floatArrayOf(2.8f, 0f, -1.9f, 1.5f, -1.0f, 0f, -1.9f, -1.5f)
            val out = FloatArray(8 * 3)
            for (i in 0 until 4) {
                out[i * 3] = outline[i * 2]; out[i * 3 + 1] = 0.2f; out[i * 3 + 2] = outline[i * 2 + 1]
                out[(i + 4) * 3] = outline[i * 2]; out[(i + 4) * 3 + 1] = 1.3f; out[(i + 4) * 3 + 2] = outline[i * 2 + 1]
            }
            return out
        }
    }
}

/**
 * 장면 색상(ARGB) 한 벌. 브랜드 색 아님. [DAY] 는 밝은 지도 바탕(주간), [NIGHT] 는 어두운 바탕(야간)이다.
 * 색은 장면을 만들 때 면에 구워 넣으므로, 팔레트를 바꾸려면 장면을 다시 만든다(메인 스레드, 프레임 밖).
 * 필드 이름은 기존 상수 이름을 그대로 쓴다.
 */
@Suppress("PropertyName")
class Palette(
    val BACKGROUND: Int,
    val GRID: Int,
    val GRID_RING: Int,
    val ROAD: Int,
    val RIBBON_GLOW: Int,
    val RIBBON_CORE: Int,
    val RIBBON_PASSED: Int,
    val ARROW: Int,
    val ENTRY_PAD: Int,
    val ENTRY_MARKER: Int,
    val TARGET: Int,
    val TARGET_STROKE: Int,
    val SURROUND: Int,
    /** 주변 건물 벽 외곽선. 0 이면 그리지 않는다. */
    val SURROUND_STROKE: Int,
    /** 램프: 유도 리본과 같은 계열. 경사 윗면이 옆 벽보다 밝고 더 불투명하다. */
    val RAMP_TOP: Int,
    val RAMP_SIDE: Int,
    val RAMP_STROKE: Int,
    val RAMP_CHEVRON: Int,
    val PIN_BARRIER: Int,
    val PIN_NO_TURN: Int,
    val PIN_WAIT_LANE: Int,
    val NO_TURN_WALL: Int,
    val NO_TURN_WALL_STROKE: Int,
    val VEHICLE_TOP: Int,
    val VEHICLE_SIDE: Int,
    /** 차량 마커 외곽선. 0 이면 그리지 않는다. */
    val VEHICLE_STROKE: Int,
    val LABEL: Int,
    val LABEL_BG: Int,
    /** 라벨 테두리. 0 이면 그리지 않는다. */
    val LABEL_BORDER: Int,
    /** 음영에서 빛을 전혀 받지 않는 면의 밝기 배율(0..1). 밝은 바탕에서는 높여서 그림자 쪽이 탁해지지 않게 한다. */
    val SHADE_MIN: Float,
    /** 빛을 받는 정도(0..1)에 곱해 더하는 밝기. 윗면(0.8)에서 SHADE_MIN + 0.8 × SHADE_GAIN. */
    val SHADE_GAIN: Float,
) {
    /** 고정 광원 Lambert 음영. 알파는 유지. 윗면(법선 +y)이 옆면보다 밝다. */
    fun shade(argb: Int, nx: Float, ny: Float, nz: Float): Int {
        // 광원 방향(정규화): 남서 상공
        val d = (nx * -0.42f + ny * 0.80f + nz * -0.43f).coerceAtLeast(0f)
        val k = (SHADE_MIN + SHADE_GAIN * d).coerceAtMost(1f)
        val a = argb ushr 24
        val r = (((argb shr 16) and 0xFF) * k).toInt().coerceIn(0, 255)
        val g = (((argb shr 8) and 0xFF) * k).toInt().coerceIn(0, 255)
        val bl = ((argb and 0xFF) * k).toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or bl
    }

    companion object {
        /** 주간: 밝은 회백색 바탕 + 파란 강조색 (5차 디자인 기준). */
        val DAY = Palette(
            BACKGROUND = 0xFFEEF1F5.toInt(),
            GRID = 0xFFDFE4EB.toInt(),
            GRID_RING = 0xFFCBD2DC.toInt(),
            ROAD = 0xFFB3BDCC.toInt(),
            RIBBON_GLOW = 0x331A73E8,
            RIBBON_CORE = 0xE61A73E8.toInt(),
            RIBBON_PASSED = 0x551A73E8,
            ARROW = 0xFFFFFFFF.toInt(),
            ENTRY_PAD = 0x8034A853.toInt(),
            ENTRY_MARKER = 0xE6188038.toInt(),
            TARGET = 0xE6F4B860.toInt(),
            TARGET_STROKE = 0xFFC98A1E.toInt(),
            SURROUND = 0xCCD3D9E2.toInt(),
            SURROUND_STROKE = 0xFFB9C1CD.toInt(),
            RAMP_TOP = 0xCC4C9AFF.toInt(),
            RAMP_SIDE = 0x801A73E8.toInt(),
            RAMP_STROKE = 0xFF1557B0.toInt(),
            RAMP_CHEVRON = 0xFFFFFFFF.toInt(),
            PIN_BARRIER = 0xFFF29900.toInt(),
            PIN_NO_TURN = 0xFFD93025.toInt(),
            PIN_WAIT_LANE = 0xFF7B61FF.toInt(),
            NO_TURN_WALL = 0x40D93025,
            NO_TURN_WALL_STROKE = 0xB0D93025.toInt(),
            VEHICLE_TOP = 0xFF1F2937.toInt(),
            VEHICLE_SIDE = 0xFF0B1220.toInt(),
            VEHICLE_STROKE = 0xFFFFFFFF.toInt(),
            LABEL = 0xFF111827.toInt(),
            LABEL_BG = 0xF2FFFFFF.toInt(),
            LABEL_BORDER = 0xFFC9D0DA.toInt(),
            // 윗면은 원색 그대로(0.86 + 0.175 × 0.8 = 1.0), 그늘 면도 0.86 까지만 어두워진다.
            SHADE_MIN = 0.86f,
            SHADE_GAIN = 0.175f,
        )

        /** 야간: 4차까지 쓰던 어두운 바탕 + 네온. 값과 음영은 그때와 같다. */
        val NIGHT = Palette(
            BACKGROUND = 0xFF0B0E12.toInt(),
            GRID = 0xFF1E242B.toInt(),
            GRID_RING = 0xFF353D47.toInt(),
            ROAD = 0xFF2B3139.toInt(),
            RIBBON_GLOW = 0x3300E5FF,
            RIBBON_CORE = 0xD000E5FF.toInt(),
            RIBBON_PASSED = 0x4000E5FF,
            ARROW = 0xFFE6FFFF.toInt(),
            ENTRY_PAD = 0x7000E676,
            ENTRY_MARKER = 0xE000E676.toInt(),
            TARGET = 0xD8FFB020.toInt(),
            TARGET_STROKE = 0xFFFFE6A0.toInt(),
            SURROUND = 0x507E8A99,
            SURROUND_STROKE = 0,
            RAMP_TOP = 0xC83DEBFF.toInt(),
            RAMP_SIDE = 0x7800A5C8,
            RAMP_STROKE = 0xE08CF4FF.toInt(),
            RAMP_CHEVRON = 0xFFF2FFFF.toInt(),
            PIN_BARRIER = 0xFFFFC107.toInt(),
            PIN_NO_TURN = 0xFFFF3B30.toInt(),
            PIN_WAIT_LANE = 0xFF2F80FF.toInt(),
            NO_TURN_WALL = 0x55FF3B30,
            NO_TURN_WALL_STROKE = 0xB0FF3B30.toInt(),
            VEHICLE_TOP = 0xFFFFFFFF.toInt(),
            VEHICLE_SIDE = 0xFFA9B6C2.toInt(),
            VEHICLE_STROKE = 0,
            LABEL = 0xFFF2F5F8.toInt(),
            LABEL_BG = 0xCC11161C.toInt(),
            LABEL_BORDER = 0,
            SHADE_MIN = 0.55f,
            SHADE_GAIN = 0.45f,
        )
    }
}

/** 장면 조립기. 빌드 시점(메인 스레드)에만 할당한다. */
internal class SceneBuilder(private val pal: Palette = Palette.DAY) {
    private val v = ArrayList<Float>()
    private val fStart = ArrayList<Int>()
    private val fSize = ArrayList<Int>()
    private val fIdx = ArrayList<Int>()
    private val fColor = ArrayList<Int>()
    private val fStroke = ArrayList<Int>()
    private val fFlags = ArrayList<Int>()
    private val fNormal = ArrayList<Float>()
    private val fArc = ArrayList<Float>()
    private val oStart = ArrayList<Int>()
    private val oCount = ArrayList<Int>()
    private val oKind = ArrayList<Int>()
    private var curKind = -1
    private var curStart = 0

    val objectCount: Int get() = oKind.size

    val vertexCount: Int get() = v.size / 3

    fun beginObject(kind: Int) {
        curKind = kind
        curStart = fSize.size
    }

    fun endObject() {
        val n = fSize.size - curStart
        if (n > 0) { oStart.add(curStart); oCount.add(n); oKind.add(curKind) }
        curKind = -1
    }

    fun addVertex(x: Float, y: Float, z: Float): Int {
        v.add(x); v.add(y); v.add(z)
        return v.size / 3 - 1
    }

    /**
     * 면 추가. [orientX..] 가 주어지면 그 점(부품 중심)에서 바깥을 향하도록 감기 방향을 맞춘다.
     * 법선은 Newell 방식(n각형·비평면 허용). 방향은 바깥 맞춤으로만 정해지므로 좌표계 손잡이와 무관하다.
     */
    fun addFace(idx: IntArray, color: Int, stroke: Int, flags: Int, arc: Float = 0f, shade: Boolean = true,
                orientX: Float = Float.NaN, orientY: Float = 0f, orientZ: Float = 0f) {
        var nx = 0f; var ny = 0f; var nz = 0f
        var cx = 0f; var cy = 0f; var cz = 0f
        val n = idx.size
        for (i in 0 until n) {
            val a = idx[i] * 3
            val b = idx[(i + 1) % n] * 3
            nx += (v[a + 1] - v[b + 1]) * (v[a + 2] + v[b + 2])
            ny += (v[a + 2] - v[b + 2]) * (v[a] + v[b])
            nz += (v[a] - v[b]) * (v[a + 1] + v[b + 1])
            cx += v[a]; cy += v[a + 1]; cz += v[a + 2]
        }
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        if (len < 1e-9f) return // 퇴화 면
        nx /= len; ny /= len; nz /= len
        cx /= n; cy /= n; cz /= n
        var order = idx
        if (!orientX.isNaN() && nx * (cx - orientX) + ny * (cy - orientY) + nz * (cz - orientZ) < 0f) {
            order = idx.reversedArray()
            nx = -nx; ny = -ny; nz = -nz
        }
        fStart.add(fIdx.size)
        fSize.add(n)
        for (i in order) fIdx.add(i)
        fColor.add(if (shade) pal.shade(color, nx, ny, nz) else color)
        fStroke.add(stroke)
        fFlags.add(flags)
        fNormal.add(nx); fNormal.add(ny); fNormal.add(nz)
        fArc.add(arc)
    }

    /** 지면 그룹: 뒷면 제거 없음, 음영 없음. */
    fun addGroupFlat(g: ObjGroup, color: Int) {
        val base = vertexCount
        for (i in 0 until g.vertexCount) addVertex(g.vertices[i * 3], g.vertices[i * 3 + 1], g.vertices[i * 3 + 2])
        var k = 0
        for (f in 0 until g.faceCount) {
            val n = g.faceSizes[f]
            addFace(IntArray(n) { base + g.faces[k + it] }, color, 0, 0, shade = false)
            k += n
        }
    }

    /**
     * 입체 그룹. [splitComponents] 면 같은 위치(1cm 양자화)를 공유하는 면끼리 묶어 건물 단위 객체로 나눈다.
     * 각 부품 중심 기준으로 면 감기를 바깥으로 맞춘 뒤 뒷면 제거를 켠다.
     */
    fun addGroupSolid(g: ObjGroup, color: Int, stroke: Int, splitComponents: Boolean) {
        val nv = g.vertexCount
        val nf = g.faceCount
        if (nf == 0) return
        val faceOff = IntArray(nf)
        run { var k = 0; for (f in 0 until nf) { faceOff[f] = k; k += g.faceSizes[f] } }

        val comp: IntArray
        val compCount: Int
        if (splitComponents) {
            // 위치 양자화 → 대표 정점
            val posId = IntArray(nv)
            val map = HashMap<Long, Int>(nv * 2)
            for (i in 0 until nv) {
                val qx = Math.round(g.vertices[i * 3] * 100.0)
                val qy = Math.round(g.vertices[i * 3 + 1] * 100.0)
                val qz = Math.round(g.vertices[i * 3 + 2] * 100.0)
                val key = (qx and 0x1FFFFF) or ((qy and 0x1FFFFF) shl 21) or ((qz and 0x1FFFFF) shl 42)
                posId[i] = map.getOrPut(key) { map.size }
            }
            val parent = IntArray(map.size) { it }
            fun find(a: Int): Int { var x = a; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
            for (f in 0 until nf) {
                val first = find(posId[g.faces[faceOff[f]]])
                for (j in 1 until g.faceSizes[f]) {
                    val o = find(posId[g.faces[faceOff[f] + j]])
                    if (o != first) parent[o] = first
                }
            }
            val rootToComp = HashMap<Int, Int>()
            comp = IntArray(nf) { f -> rootToComp.getOrPut(find(posId[g.faces[faceOff[f]]])) { rootToComp.size } }
            compCount = rootToComp.size
        } else {
            comp = IntArray(nf)
            compCount = 1
        }

        val base = vertexCount
        for (i in 0 until nv) addVertex(g.vertices[i * 3], g.vertices[i * 3 + 1], g.vertices[i * 3 + 2])
        // 부품별 정점 중심
        val sum = FloatArray(compCount * 4)
        for (f in 0 until nf) {
            val c = comp[f]
            for (j in 0 until g.faceSizes[f]) {
                val vi = g.faces[faceOff[f] + j]
                sum[c * 4] += g.vertices[vi * 3]; sum[c * 4 + 1] += g.vertices[vi * 3 + 1]; sum[c * 4 + 2] += g.vertices[vi * 3 + 2]
                sum[c * 4 + 3] += 1f
            }
        }
        val facesByComp = Array(compCount) { ArrayList<Int>() }
        for (f in 0 until nf) facesByComp[comp[f]].add(f)
        for (c in 0 until compCount) {
            val cnt = sum[c * 4 + 3]
            beginObject(TacticalScene.KIND_SOLID)
            for (f in facesByComp[c]) {
                val n = g.faceSizes[f]
                val before = fSize.size
                addFace(IntArray(n) { base + g.faces[faceOff[f] + it] }, color, stroke, TacticalScene.FLAG_CULL_BACK,
                    orientX = sum[c * 4] / cnt, orientY = sum[c * 4 + 1] / cnt, orientZ = sum[c * 4 + 2] / cnt)
                // 삼각분할된 지붕·바닥의 대각선이 보이지 않도록 외곽선은 벽면에만 둔다.
                if (stroke != 0 && fSize.size > before && kotlin.math.abs(fNormal[fNormal.size - 2]) > 0.5f) fStroke[fStroke.size - 1] = 0
            }
            endObject()
        }
    }

    /**
     * 램프 그룹(경사 쐐기)을 객체 하나로 추가한다. 감기는 정점 중심 기준 바깥으로 맞춘다.
     * 경사 윗면은 [Palette.RAMP_TOP] (음영 없음), 벽은 [Palette.RAMP_SIDE] (음영) + 외곽선.
     * 윗면에는 정적 오르막 쉐브론을 [TacticalScene.FLAG_DECAL] 면으로 얹는다.
     * @return 추가된 객체 번호, 면이 없으면 -1
     */
    fun addRamp(g: ObjGroup): Int {
        val nf = g.faceCount
        if (nf == 0) return -1
        val base = vertexCount
        for (i in 0 until g.vertexCount) addVertex(g.vertices[i * 3], g.vertices[i * 3 + 1], g.vertices[i * 3 + 2])
        var sx = 0f; var sy = 0f; var sz = 0f; var cnt = 0
        for (vi in g.faces) { sx += g.vertices[vi * 3]; sy += g.vertices[vi * 3 + 1]; sz += g.vertices[vi * 3 + 2]; cnt++ }
        if (cnt == 0) return -1
        sx /= cnt; sy /= cnt; sz /= cnt
        beginObject(TacticalScene.KIND_SOLID)
        val tops = ArrayList<FloatArray>()
        var k = 0
        for (f in 0 until nf) {
            val n = g.faceSizes[f]
            val before = fSize.size
            addFace(IntArray(n) { base + g.faces[k + it] }, pal.RAMP_SIDE, pal.RAMP_STROKE, TacticalScene.FLAG_CULL_BACK,
                orientX = sx, orientY = sy, orientZ = sz)
            if (fSize.size > before && fNormal[fNormal.size - 2] > RampGeometry.TOP_NY) {
                // 경사 윗면: 벽보다 밝게, 분할선이 보이지 않게 외곽선 없음
                fColor[fColor.size - 1] = pal.RAMP_TOP
                fStroke[fStroke.size - 1] = 0
                tops.add(FloatArray(n * 3) { g.vertices[g.faces[k + it / 3] * 3 + it % 3] })
            }
            k += n
        }
        for (poly in RampGeometry.chevrons(tops, TacticalRenderer.ARROW_SHAPE)) {
            val idx = IntArray(poly.size / 3) { addVertex(poly[it * 3], poly[it * 3 + 1], poly[it * 3 + 2]) }
            addFace(idx, pal.RAMP_CHEVRON, 0, TacticalScene.FLAG_CULL_BACK or TacticalScene.FLAG_DECAL, shade = false,
                orientX = sx, orientY = sy, orientZ = sz)
        }
        val before = oKind.size
        endObject()
        return if (oKind.size > before) oKind.size - 1 else -1
    }

    /** 중심선 폴리라인을 폭 [width] 의 사각형 띠로. [arcs] 가 있으면 면마다 구간 중앙 호 길이를 기록. */
    fun addStrip(cx: FloatArray, cz: FloatArray, width: Float, y: Float, color: Int, flags: Int, arcs: FloatArray?) {
        val n = cx.size
        if (n < 2) return
        val half = width / 2f
        val left = IntArray(n)
        val right = IntArray(n)
        for (i in 0 until n) {
            val a = if (i == 0) 0 else i - 1
            val b = if (i == n - 1) n - 1 else i + 1
            var tx = cx[b] - cx[a]
            var tz = cz[b] - cz[a]
            val l = sqrt(tx * tx + tz * tz)
            if (l > 1e-6f) { tx /= l; tz /= l } else { tx = 0f; tz = 1f }
            // 진행 방향 오른쪽 = (tz, -tx) (북쪽 진행 시 동쪽)
            right[i] = addVertex(cx[i] + tz * half, y, cz[i] - tx * half)
            left[i] = addVertex(cx[i] - tz * half, y, cz[i] + tx * half)
        }
        for (i in 0 until n - 1) {
            val arc = if (arcs != null) (arcs[i] + arcs[i + 1]) / 2f else 0f
            addFace(intArrayOf(left[i], right[i], right[i + 1], left[i + 1]), color, 0, flags, arc, shade = false)
        }
    }

    fun addDisc(x: Float, z: Float, r: Float, y: Float, segments: Int, color: Int) {
        val idx = IntArray(segments) {
            val a = it * 2.0 * Math.PI / segments
            addVertex(x + (r * sin(a)).toFloat(), y, z + (r * cos(a)).toFloat())
        }
        addFace(idx, color, 0, 0, shade = false)
    }

    /** 외곽선 돌출(지붕 n각형 + 벽 사각형). */
    fun addExtrusion(fx: FloatArray, fz: FloatArray, h: Float, color: Int, stroke: Int) {
        val n = fx.size
        val bottom = IntArray(n) { addVertex(fx[it], 0f, fz[it]) }
        val top = IntArray(n) { addVertex(fx[it], h, fz[it]) }
        var mx = 0f; var mz = 0f
        for (i in 0 until n) { mx += fx[i]; mz += fz[i] }
        mx /= n; mz /= n
        addFace(top, color, stroke, TacticalScene.FLAG_CULL_BACK, orientX = mx, orientY = h / 2f, orientZ = mz)
        for (i in 0 until n) {
            val j = (i + 1) % n
            addFace(intArrayOf(bottom[i], bottom[j], top[j], top[i]), color, stroke, TacticalScene.FLAG_CULL_BACK,
                orientX = mx, orientY = h / 2f, orientZ = mz)
        }
    }

    /** 축 정렬 기둥 상자 (바닥 제외 5면). */
    fun addBox(x: Float, z: Float, size: Float, y0: Float, y1: Float, color: Int) {
        val h = size / 2f
        val fx = floatArrayOf(x - h, x + h, x + h, x - h)
        val fz = floatArrayOf(z - h, z - h, z + h, z + h)
        val b = IntArray(4) { addVertex(fx[it], y0, fz[it]) }
        val t = IntArray(4) { addVertex(fx[it], y1, fz[it]) }
        val oy = (y0 + y1) / 2f
        addFace(t, color, 0, TacticalScene.FLAG_CULL_BACK, orientX = x, orientY = oy, orientZ = z)
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            addFace(intArrayOf(b[i], b[j], t[j], t[i]), color, 0, TacticalScene.FLAG_CULL_BACK, orientX = x, orientY = oy, orientZ = z)
        }
    }

    /** 핀 머리(정팔면체). */
    fun addOctahedron(x: Float, y: Float, z: Float, r: Float, color: Int) {
        val top = addVertex(x, y + r, z)
        val bot = addVertex(x, y - r, z)
        val ring = intArrayOf(addVertex(x + r, y, z), addVertex(x, y, z + r), addVertex(x - r, y, z), addVertex(x, y, z - r))
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            addFace(intArrayOf(top, ring[i], ring[j]), color, 0, TacticalScene.FLAG_CULL_BACK, orientX = x, orientY = y, orientZ = z)
            addFace(intArrayOf(bot, ring[j], ring[i]), color, 0, TacticalScene.FLAG_CULL_BACK, orientX = x, orientY = y, orientZ = z)
        }
    }

    /** 진행 방향 (tx, tz) 을 따라 놓인 양면 반투명 벽 (뒷면 제거 없음). */
    fun addWall(x: Float, z: Float, tx: Float, tz: Float, len: Float, h: Float, color: Int, stroke: Int) {
        val hl = len / 2f
        val a0 = addVertex(x - tx * hl, 0f, z - tz * hl)
        val a1 = addVertex(x + tx * hl, 0f, z + tz * hl)
        val b1 = addVertex(x + tx * hl, h, z + tz * hl)
        val b0 = addVertex(x - tx * hl, h, z - tz * hl)
        addFace(intArrayOf(a0, a1, b1, b0), color, stroke, 0, shade = false)
    }

    fun finish(
        mainPath: SampledPath?, evPath: SampledPath?, grid: Pair<FloatArray, IntArray>,
        labels: Array<String>, labelAnchor: FloatArray, labelKind: IntArray, vehicleTemplate: FloatArray,
        rampObjs: IntArray = IntArray(0), rampTargets: IntArray = IntArray(0), rampOverObjs: IntArray = IntArray(0),
    ): TacticalScene {
        // 차량: 템플릿 정점 8개 영역 + 윗면/옆면 5개 (윤곽 4점 프리즘). 정점은 매 프레임 렌더러가 채운다.
        val vehicleStart = vertexCount
        for (i in 0 until 8) addVertex(vehicleTemplate[i * 3], vehicleTemplate[i * 3 + 1], vehicleTemplate[i * 3 + 2])
        beginObject(TacticalScene.KIND_VEHICLE)
        addVehicleFace(intArrayOf(vehicleStart + 4, vehicleStart + 5, vehicleStart + 6, vehicleStart + 7), pal.VEHICLE_TOP)
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            addVehicleFace(intArrayOf(vehicleStart + i, vehicleStart + j, vehicleStart + 4 + j, vehicleStart + 4 + i), pal.VEHICLE_SIDE)
        }
        endObject()

        val nf = fSize.size
        val faceStart = fStart.toIntArray()
        val faceSize = fSize.toIntArray()
        val faceIndex = fIdx.toIntArray()
        val verts = v.toFloatArray()
        val faceCenter = FloatArray(nf * 3)
        val faceRadius = FloatArray(nf)
        for (f in 0 until nf) {
            var cx = 0f; var cy = 0f; var cz = 0f
            val n = faceSize[f]
            for (j in 0 until n) {
                val vi = faceIndex[faceStart[f] + j] * 3
                cx += verts[vi]; cy += verts[vi + 1]; cz += verts[vi + 2]
            }
            cx /= n; cy /= n; cz /= n
            var r2 = 0f
            for (j in 0 until n) {
                val vi = faceIndex[faceStart[f] + j] * 3
                val dx = verts[vi] - cx; val dy = verts[vi + 1] - cy; val dz = verts[vi + 2] - cz
                r2 = max(r2, dx * dx + dy * dy + dz * dz)
            }
            faceCenter[f * 3] = cx; faceCenter[f * 3 + 1] = cy; faceCenter[f * 3 + 2] = cz
            faceRadius[f] = sqrt(r2)
        }
        val no = oKind.size
        val objKind = oKind.toIntArray()
        val objCenter = FloatArray(no * 3)
        val objRadius = FloatArray(no)
        for (o in 0 until no) {
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
            for (f in oStart[o] until oStart[o] + oCount[o]) {
                for (j in 0 until faceSize[f]) {
                    val vi = faceIndex[faceStart[f] + j] * 3
                    minX = minOf(minX, verts[vi]); maxX = maxOf(maxX, verts[vi])
                    minY = minOf(minY, verts[vi + 1]); maxY = maxOf(maxY, verts[vi + 1])
                    minZ = minOf(minZ, verts[vi + 2]); maxZ = maxOf(maxZ, verts[vi + 2])
                }
            }
            objCenter[o * 3] = (minX + maxX) / 2f; objCenter[o * 3 + 1] = (minY + maxY) / 2f; objCenter[o * 3 + 2] = (minZ + maxZ) / 2f
            val dx = maxX - minX; val dy = maxY - minY; val dz = maxZ - minZ
            objRadius[o] = sqrt(dx * dx + dy * dy + dz * dz) / 2f
        }
        return TacticalScene(
            verts, verts.size / 3, vehicleStart, faceStart, faceSize, faceIndex,
            fColor.toIntArray(), fStroke.toIntArray(), fFlags.toIntArray(), fNormal.toFloatArray(),
            faceCenter, faceRadius, fArc.toFloatArray(),
            oStart.toIntArray(), oCount.toIntArray(), objKind, objCenter, objRadius,
            grid.first, grid.second, mainPath, evPath, labels, labelAnchor, labelKind, vehicleTemplate,
            rampObjs, rampTargets, rampOverObjs, pal,
        )
    }

    private fun addVehicleFace(idx: IntArray, color: Int) {
        // 법선은 매 프레임 회전하므로 뒷면 제거 없이 면 정렬만 한다.
        fStart.add(fIdx.size); fSize.add(idx.size)
        for (i in idx) fIdx.add(i)
        fColor.add(color); fStroke.add(pal.VEHICLE_STROKE); fFlags.add(0)
        fNormal.add(0f); fNormal.add(1f); fNormal.add(0f)
        fArc.add(0f)
    }
}
