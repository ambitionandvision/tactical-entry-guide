package io.github.ambitionandvision.tactical.render

import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/** 매 프레임 렌더러 입력. 뷰가 필드를 덮어써서 재사용한다. */
class RenderState {
    var hasVehicle = false
    var vehicleX = 0f
    var vehicleZ = 0f
    var vehicleHeadingDeg = 0f
    /** 활성 경로상 진행 거리(m). 모르면 NaN. */
    var progressM = Float.NaN
    var evActive = false
    /** 주행 제한: 화살표 흐름을 멈추고 정적으로 그린다. */
    var motionRestricted = false
    var nowSec = 0f
}

/**
 * 한 프레임의 2D 그리기 목록. 화면 좌표(px). 모든 배열은 [TacticalRenderer] 생성 시 한 번 할당된다.
 * 다각형은 그리는 순서대로이며 화면상 감기 방향이 통일돼 있다(같은 색 연속 면을 한 Path 로 합쳐도 안전).
 */
class RenderOutput(polyCap: Int, polyVertexCap: Int, gridCap: Int, ringCap: Int, labelCap: Int) {
    val polyXY = FloatArray(polyVertexCap * 2)
    val polyStart = IntArray(polyCap)
    val polySize = IntArray(polyCap)
    val polyColor = IntArray(polyCap)
    val polyStroke = IntArray(polyCap)
    var polyCount = 0
    var vertexCount = 0

    /** Canvas.drawLines 형식(x0,y0,x1,y1 반복). */
    val gridLines = FloatArray(gridCap * 4)
    var gridFloats = 0
    val ringLines = FloatArray(ringCap * 4)
    var ringFloats = 0

    val labelIndex = IntArray(labelCap)
    val labelX = FloatArray(labelCap)
    val labelY = FloatArray(labelCap)
    var labelCount = 0

    // 통계
    var culledFaces = 0
    var backFaces = 0
    var clippedFaces = 0

    fun clear() {
        polyCount = 0; vertexCount = 0; gridFloats = 0; ringFloats = 0; labelCount = 0
        culledFaces = 0; backFaces = 0; clippedFaces = 0
    }
}

/**
 * 순수 Kotlin 3D → 2D 파이프라인: 카메라 변환 → 절두체·뒷면 제거 → 근평면 클리핑 → 투영 → 정렬.
 * [render] 는 할당하지 않는다. 장면이 바뀌면 렌더러를 새로 만든다(메인 스레드, 프레임 밖).
 */
class TacticalRenderer(val scene: TacticalScene) {

    var near = 0.5f
    var far = 2500f
    var fovMinDeg = 60f

    private val maxArrows: Int = (max(scene.mainPath?.lengthM ?: 0f, scene.evPath?.lengthM ?: 0f) / ARROW_SPACING_M).toInt() + 2

    val output = RenderOutput(
        polyCap = scene.faceCount + maxArrows,
        polyVertexCap = scene.polygonVertexBudget + maxArrows * ARROW_SHAPE.size,
        gridCap = scene.gridClass.count { it == 0 },
        ringCap = scene.gridClass.count { it == 1 },
        labelCap = scene.labels.size,
    )

    private val cam = FloatArray(scene.vertexCount * 3)
    private val clipIn = FloatArray((max(scene.maxFaceSize, ARROW_SHAPE.size / 2) + 1) * 3)
    // 오목 다각형은 단일 평면 클리핑으로 정점이 최대 1.5배까지 늘 수 있다.
    private val clipOut = FloatArray((max(scene.maxFaceSize, ARROW_SHAPE.size / 2) * 2 + 2) * 3)
    private val seg = FloatArray(6)
    private val tmp4 = FloatArray(4)
    private val tmp3 = FloatArray(3)
    private val objOrder = IntArray(scene.objectCount)
    private val objKey = FloatArray(scene.objectCount)
    private val faceOrder = IntArray(max(1, scene.maxObjectFaces))
    private val faceKey = FloatArray(scene.faceCount)
    private val objIsRamp = BooleanArray(scene.objectCount).also { a -> for (o in scene.rampObjs) a[o] = true }
    private val vehicleObj: Int = scene.objKind.indexOf(TacticalScene.KIND_VEHICLE)

    /** 직전 [render] 에서 쓴 차량 마커 배율 (로그·테스트용). */
    var lastVehicleScale = 1f
        private set

    private var focal = 1f
    private var cx = 0f
    private var cy = 0f
    private var tanX = 1f
    private var tanY = 1f

    /** [basis] 카메라로 [width]×[height] 화면에 [state] 를 그릴 목록을 [output] 에 만든다. */
    fun render(basis: CameraBasis, width: Int, height: Int, state: RenderState): RenderOutput {
        val out = output
        out.clear()
        if (width <= 0 || height <= 0) return out
        val sc = scene
        focal = (min(width, height) / 2f) / tan(Math.toRadians(fovMinDeg / 2.0)).toFloat()
        cx = width / 2f
        cy = height / 2f
        tanX = cx / focal
        tanY = cy / focal

        // 1) 정점 → 카메라 공간 (차량 영역은 현재 자세로 변환)
        val vs = sc.vertices
        for (i in 0 until sc.vehicleVertexStart) basis.toCamera(vs[i * 3], vs[i * 3 + 1], vs[i * 3 + 2], cam, i * 3)
        val hx = RenderMath.sinDeg(state.vehicleHeadingDeg)
        val hz = RenderMath.cosDeg(state.vehicleHeadingDeg)
        val t = sc.vehicleTemplate
        // 멀리서(OVERVIEW) 도 차량이 보이도록 눈까지 거리에 따라 마커를 키운다. 가까우면 배율 1.
        val vdx = state.vehicleX - basis.ex
        val vdy = VEHICLE_CENTER_Y - basis.ey
        val vdz = state.vehicleZ - basis.ez
        val vScale = vehicleMarkerScale(kotlin.math.sqrt(vdx * vdx + vdy * vdy + vdz * vdz), focal, min(width, height).toFloat())
        lastVehicleScale = vScale
        for (k in 0 until 8) {
            val along = t[k * 3] * vScale
            val across = t[k * 3 + 2] * vScale
            val wx = state.vehicleX + hx * along + hz * across
            val wz = state.vehicleZ + hz * along - hx * across
            basis.toCamera(wx, t[k * 3 + 1] * vScale, wz, cam, (sc.vehicleVertexStart + k) * 3)
        }

        // 2) 레이더 그리드 (선)
        val gs = sc.gridSegments
        for (i in sc.gridClass.indices) {
            basis.toCamera(gs[i * 4], 0f, gs[i * 4 + 1], seg, 0)
            basis.toCamera(gs[i * 4 + 2], 0f, gs[i * 4 + 3], seg, 3)
            if (seg[2] > far && seg[5] > far) continue
            if (!RenderMath.clipSegmentNear(seg, near)) continue
            if (sc.gridClass[i] == 0) {
                RenderMath.project(seg[0], seg[1], seg[2], focal, cx, cy, out.gridLines, out.gridFloats)
                RenderMath.project(seg[3], seg[4], seg[5], focal, cx, cy, out.gridLines, out.gridFloats + 2)
                out.gridFloats += 4
            } else {
                RenderMath.project(seg[0], seg[1], seg[2], focal, cx, cy, out.ringLines, out.ringFloats)
                RenderMath.project(seg[3], seg[4], seg[5], focal, cx, cy, out.ringLines, out.ringFloats + 2)
                out.ringFloats += 4
            }
        }

        // 3) 지면 레이어 (고정 순서: 도로 → 활성 리본 → 활성 입구 패드)
        val ribbonKind = if (state.evActive) TacticalScene.KIND_RIBBON_EV else TacticalScene.KIND_RIBBON_MAIN
        val padKind = if (state.evActive) TacticalScene.KIND_PAD_EV else TacticalScene.KIND_PAD_MAIN
        emitKind(TacticalScene.KIND_GROUND, basis, state)
        emitKind(ribbonKind, basis, state)
        // 주 입구 패드는 램프가 화면에 있으면 램프 뒤로 미룬다. 램프가 없으면 기존 순서 그대로다.
        var padDeferred = false
        if (!state.evActive) for (ramp in sc.rampObjs) if (solidInFrustum(ramp, basis)) { padDeferred = true; break }
        if (!padDeferred) emitKind(padKind, basis, state)

        // 4) 흐르는 화살표
        val path = if (state.evActive) sc.evPath else sc.mainPath
        if (path != null && path.count >= 2) emitArrows(path, basis, state)

        // 5) 입체: 객체(건물) 단위 원거리→근거리, 객체 안에서 면 원거리→근거리
        var nObj = 0
        for (o in 0 until sc.objectCount) {
            val kind = sc.objKind[o]
            if (kind != TacticalScene.KIND_SOLID && kind != TacticalScene.KIND_VEHICLE) continue
            val ox: Float; val oy: Float; val oz: Float; val r: Float
            if (kind == TacticalScene.KIND_VEHICLE) {
                if (!state.hasVehicle) continue
                ox = state.vehicleX; oy = VEHICLE_CENTER_Y * vScale; oz = state.vehicleZ; r = VEHICLE_RADIUS_M * vScale
            } else {
                ox = sc.objCenter[o * 3]; oy = sc.objCenter[o * 3 + 1]; oz = sc.objCenter[o * 3 + 2]; r = sc.objRadius[o]
            }
            basis.toCamera(ox, oy, oz, tmp3, 0)
            if (!RenderMath.sphereInFrustum(tmp3[0], tmp3[1], tmp3[2], r, near, far, tanX, tanY)) {
                out.culledFaces += sc.objFaceCount[o]
                continue
            }
            objKey[o] = tmp3[2]
            objOrder[nObj++] = o
        }
        RenderMath.sortIndicesByKeyDesc(objOrder, 0, nObj, objKey)
        // 램프는 본관 부피 안쪽에 놓이는 투시 표현이다: 항상 본관 뒤에 칠하고, 입구 마커·차량은 램프 뒤에 칠한다.
        for (ramp in sc.rampObjs) {
            for (t in sc.rampTargets) RampGeometry.enforceBefore(objOrder, nObj, t, ramp, ramp)
        }
        for (ramp in sc.rampObjs) {
            for (x in sc.rampOverObjs) RampGeometry.enforceBefore(objOrder, nObj, ramp, x, x)
            if (vehicleObj >= 0) RampGeometry.enforceBefore(objOrder, nObj, ramp, vehicleObj, vehicleObj)
        }
        for (i in 0 until nObj) {
            val o = objOrder[i]
            emitSolid(o, basis)
            // 미뤄 둔 입구 패드는 램프 바로 뒤에 칠한다 (램프 낮은 끝이 입구를 덮지 않게).
            if (padDeferred && objIsRamp[o]) { padDeferred = false; emitKind(padKind, basis, state) }
        }

        // 6) 라벨 기준점
        val la = sc.labelAnchor
        for (i in sc.labels.indices) {
            val k = sc.labelKind[i]
            if (k == TacticalScene.LABEL_ENTRY_MAIN && state.evActive) continue
            if (k == TacticalScene.LABEL_ENTRY_EV && !state.evActive) continue
            basis.toCamera(la[i * 3], la[i * 3 + 1], la[i * 3 + 2], tmp3, 0)
            if (tmp3[2] < near * 4f || tmp3[2] > LABEL_MAX_DEPTH_M) continue
            RenderMath.project(tmp3[0], tmp3[1], tmp3[2], focal, cx, cy, tmp4, 0)
            if (tmp4[0] < 0f || tmp4[0] > width || tmp4[1] < 0f || tmp4[1] > height) continue
            out.labelIndex[out.labelCount] = i
            out.labelX[out.labelCount] = tmp4[0]
            out.labelY[out.labelCount] = tmp4[1]
            out.labelCount++
        }
        return out
    }

    /** 정적 입체 객체 [o] 의 경계 구가 절두체에 걸리는지 (5단계의 판정과 같다). */
    private fun solidInFrustum(o: Int, basis: CameraBasis): Boolean {
        val c = scene.objCenter
        basis.toCamera(c[o * 3], c[o * 3 + 1], c[o * 3 + 2], tmp3, 0)
        return RenderMath.sphereInFrustum(tmp3[0], tmp3[1], tmp3[2], scene.objRadius[o], near, far, tanX, tanY)
    }

    private fun emitKind(kind: Int, basis: CameraBasis, state: RenderState) {
        val sc = scene
        for (o in 0 until sc.objectCount) {
            if (sc.objKind[o] != kind) continue
            val start = sc.objFaceStart[o]
            for (f in start until start + sc.objFaceCount[o]) {
                if (!faceInFrustum(f, basis)) { output.culledFaces++; continue }
                var color = sc.faceColor[f]
                if (sc.faceFlags[f] and TacticalScene.FLAG_RIBBON_CORE != 0 && !state.progressM.isNaN() && sc.faceArc[f] < state.progressM) {
                    color = sc.palette.RIBBON_PASSED
                }
                emitFace(f, color, sc.faceStroke[f])
            }
        }
    }

    private fun emitSolid(o: Int, basis: CameraBasis) {
        val sc = scene
        val start = sc.objFaceStart[o]
        val isVehicle = sc.objKind[o] == TacticalScene.KIND_VEHICLE
        var n = 0
        for (f in start until start + sc.objFaceCount[o]) {
            if (isVehicle) {
                faceKey[f] = faceDepthFromCam(f)
            } else {
                if (sc.faceFlags[f] and TacticalScene.FLAG_CULL_BACK != 0 &&
                    RenderMath.isBackFacing(
                        sc.faceNormal[f * 3], sc.faceNormal[f * 3 + 1], sc.faceNormal[f * 3 + 2],
                        sc.faceCenter[f * 3], sc.faceCenter[f * 3 + 1], sc.faceCenter[f * 3 + 2],
                        basis.ex, basis.ey, basis.ez,
                    )
                ) { output.backFaces++; continue }
                if (!faceInFrustum(f, basis)) { output.culledFaces++; continue }
                // 얹힌 표시(쉐브론)는 같은 객체의 다른 면보다 항상 나중에 칠한다.
                faceKey[f] = if (sc.faceFlags[f] and TacticalScene.FLAG_DECAL != 0) tmp3[2] - DECAL_KEY_BIAS else tmp3[2]
            }
            faceOrder[n++] = f
        }
        RenderMath.sortIndicesByKeyDesc(faceOrder, 0, n, faceKey)
        for (i in 0 until n) {
            val f = faceOrder[i]
            emitFace(f, sc.faceColor[f], sc.faceStroke[f])
        }
    }

    /** 면 중심을 카메라 공간으로 [tmp3] 에 두고 절두체 판정. */
    private fun faceInFrustum(f: Int, basis: CameraBasis): Boolean {
        val c = scene.faceCenter
        basis.toCamera(c[f * 3], c[f * 3 + 1], c[f * 3 + 2], tmp3, 0)
        return RenderMath.sphereInFrustum(tmp3[0], tmp3[1], tmp3[2], scene.faceRadius[f], near, far, tanX, tanY)
    }

    private fun faceDepthFromCam(f: Int): Float {
        val sc = scene
        var z = 0f
        val s = sc.faceStart[f]
        val n = sc.faceSize[f]
        for (j in 0 until n) z += cam[sc.faceIndex[s + j] * 3 + 2]
        return z / n
    }

    private fun emitFace(f: Int, color: Int, stroke: Int) {
        val sc = scene
        val s = sc.faceStart[f]
        val n = sc.faceSize[f]
        for (j in 0 until n) {
            val vi = sc.faceIndex[s + j] * 3
            clipIn[j * 3] = cam[vi]; clipIn[j * 3 + 1] = cam[vi + 1]; clipIn[j * 3 + 2] = cam[vi + 2]
        }
        emitPolygon(n, color, stroke)
    }

    /** [clipIn] 의 카메라 공간 다각형 n 개 정점을 클리핑·투영해 출력에 추가. */
    private fun emitPolygon(n: Int, color: Int, stroke: Int) {
        val out = output
        var behind = false
        for (j in 0 until n) if (clipIn[j * 3 + 2] < near) { behind = true; break }
        val src: FloatArray
        val m: Int
        if (behind) {
            m = RenderMath.clipPolygonNear(clipIn, n, near, clipOut)
            out.clippedFaces++
            src = clipOut
        } else {
            m = n
            src = clipIn
        }
        if (m < 3) return
        val off = out.vertexCount * 2
        for (j in 0 until m) RenderMath.project(src[j * 3], src[j * 3 + 1], src[j * 3 + 2], focal, cx, cy, out.polyXY, off + j * 2)
        val area = RenderMath.signedArea2(out.polyXY, off, m)
        if (area > -MIN_AREA2_PX && area < MIN_AREA2_PX) return
        if (area < 0f) RenderMath.reverse2(out.polyXY, off, m)
        val p = out.polyCount
        out.polyStart[p] = out.vertexCount
        out.polySize[p] = m
        out.polyColor[p] = color
        out.polyStroke[p] = stroke
        out.polyCount = p + 1
        out.vertexCount += m
    }

    private fun emitArrows(path: SampledPath, basis: CameraBasis, state: RenderState) {
        val flow = if (state.motionRestricted) 0f else (state.nowSec * ARROW_FLOW_MPS) % ARROW_SPACING_M
        val startArc = if (state.progressM.isNaN()) 0f else state.progressM + 4f
        val endArc = path.lengthM - 3f
        var k = ((startArc - flow) / ARROW_SPACING_M).toInt()
        if (k < 0) k = 0
        var emitted = 0
        while (emitted < maxArrows) {
            val arc = k * ARROW_SPACING_M + flow
            k++
            if (arc > endArc) break
            if (arc < startArc) continue
            path.pointAt(arc, tmp4)
            val px = tmp4[0]; val pz = tmp4[1]; val tx = tmp4[2]; val tz = tmp4[3]
            val n = ARROW_SHAPE.size / 2
            for (j in 0 until n) {
                val a = ARROW_SHAPE[j * 2]
                val c = ARROW_SHAPE[j * 2 + 1]
                // 오른쪽 = (tz, -tx)
                basis.toCamera(px + tx * a + tz * c, ARROW_Y, pz + tz * a - tx * c, clipIn, j * 3)
            }
            // 화살표 하나의 경계 구 판정
            basis.toCamera(px, ARROW_Y, pz, tmp3, 0)
            if (RenderMath.sphereInFrustum(tmp3[0], tmp3[1], tmp3[2], 2.5f, near, far, tanX, tanY)) emitPolygon(n, scene.palette.ARROW, 0)
            emitted++
        }
    }

    companion object {
        const val ARROW_SPACING_M = 10f
        const val ARROW_FLOW_MPS = 6f
        const val ARROW_Y = TacticalScene.RIBBON_Y + 0.08f
        const val LABEL_MAX_DEPTH_M = 900f
        /** 화면 넓이(×2) 가 이보다 작은 다각형은 버린다. */
        const val MIN_AREA2_PX = 0.5f

        const val VEHICLE_CENTER_Y = 0.7f
        const val VEHICLE_RADIUS_M = 3.5f
        /** 차량 마커 템플릿의 길이(m): along -1.9 .. 2.8. */
        const val VEHICLE_LENGTH_M = 4.7f
        /** 마커의 화면상 길이 하한: 화면 짧은 변의 이 비율. */
        const val VEHICLE_MIN_SCREEN_FRACTION = 0.04f
        /** 내려다보는 각도로 길이가 줄어드는 몫(45도에서 약 0.71)을 메우는 여유. */
        const val VEHICLE_FORESHORTEN_MARGIN = 1.45f
        /** 배율 상한. 아주 먼 카메라에서 마커가 지도를 덮지 않게 한다. */
        const val VEHICLE_SCALE_MAX = 12f

        /**
         * 차량 마커 배율. 눈에서 차량까지 [distM] 에서 배율 1 의 투영 길이(= 길이 × 초점 / 거리)가
         * 화면 짧은 변 [minSidePx] 의 [VEHICLE_MIN_SCREEN_FRACTION] 보다 작으면 그만큼 키운다.
         *   배율 = clamp(거리 / 기준거리, 1, [VEHICLE_SCALE_MAX]),
         *   기준거리 = 길이 × 초점 / (하한 비율 × 짧은 변 × 여유)
         * 거리에 대해 연속이고 줄어들지 않는다. 기준거리보다 가까우면(APPROACH) 정확히 1 이다.
         */
        fun vehicleMarkerScale(distM: Float, focalPx: Float, minSidePx: Float): Float {
            if (!(distM > 0f) || !(focalPx > 0f) || !(minSidePx > 0f)) return 1f
            val refDist = VEHICLE_LENGTH_M * focalPx / (VEHICLE_MIN_SCREEN_FRACTION * minSidePx * VEHICLE_FORESHORTEN_MARGIN)
            return (distM / refDist).coerceIn(1f, VEHICLE_SCALE_MAX)
        }
        /** FLAG_DECAL 면의 정렬 키에서 빼는 값(m). far 보다 커야 한다. */
        const val DECAL_KEY_BIAS = 100000f
        /** 쉐브론(›) 윤곽 (along, across). */
        val ARROW_SHAPE = floatArrayOf(-0.8f, -1.5f, 0.9f, 0f, -0.8f, 1.5f, -1.7f, 1.5f, -0.1f, 0f, -1.7f, -1.5f)
    }
}
