package io.github.ambitionandvision.tactical.render

import io.github.ambitionandvision.tactical.engine.ObjGroup
import io.github.ambitionandvision.tactical.engine.ObjMeshLoader
import io.github.ambitionandvision.tactical.model.ZoneJsonParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.sqrt

/** 램프(`RAMP_*` 그룹) 표현 검증. 픽스처는 합성 쐐기이며 실데이터가 아니다. */
class RampRenderTest {

    /**
     * 그리기 순서 검사는 출력 다각형을 색으로 구별한다. 역할마다 색이 서로 다른 야간 팔레트로 장면을 만든다
     * (주간은 화살표·쉐브론·차량 외곽선이 같은 흰색이다). 순서 로직은 팔레트와 무관하다.
     */
    private val P = Palette.NIGHT

    /**
     * 경사 쐐기를 OBJ 문자열로 추가한다 (바닥면 없음, 파일 Z = -북).
     * 낮은 끝 중심 (x0,z0) 높이 0 → 높은 끝 중심 (x1,z1) 높이 [h]. 윗면 [segs] 칸, 양 옆벽, 높은 끝 벽.
     * [triangulateTop] 이면 윗면 사각형을 삼각형 둘로 쪼갠다.
     */
    private fun wedgeObj(
        sb: StringBuilder, base: Int, name: String, x0: Float, z0: Float, x1: Float, z1: Float,
        width: Float, h: Float, segs: Int, triangulateTop: Boolean = false,
    ): Int {
        sb.append("o $name\n")
        val dx = x1 - x0; val dz = z1 - z0
        val len = sqrt(dx * dx + dz * dz)
        val rx = dz / len * width / 2f; val rz = -dx / len * width / 2f // 진행 방향 오른쪽
        // 정점: i 번째 단면마다 [왼쪽 위, 오른쪽 위, 왼쪽 바닥, 오른쪽 바닥]
        for (i in 0..segs) {
            val t = i.toFloat() / segs
            val cx = x0 + dx * t; val cz = z0 + dz * t; val y = h * t
            sb.append("v ${cx - rx} $y ${-(cz - rz)}\n")
            sb.append("v ${cx + rx} $y ${-(cz + rz)}\n")
            sb.append("v ${cx - rx} 0 ${-(cz - rz)}\n")
            sb.append("v ${cx + rx} 0 ${-(cz + rz)}\n")
        }
        fun id(i: Int, k: Int) = base + i * 4 + k + 1
        for (i in 0 until segs) {
            if (triangulateTop) {
                sb.append("f ${id(i, 0)} ${id(i, 1)} ${id(i + 1, 1)}\n")
                sb.append("f ${id(i, 0)} ${id(i + 1, 1)} ${id(i + 1, 0)}\n")
            } else {
                sb.append("f ${id(i, 0)} ${id(i, 1)} ${id(i + 1, 1)} ${id(i + 1, 0)}\n")
            }
            if (i == 0) {
                // 낮은 끝 칸의 옆벽은 삼각형 (높이 0 에서 시작)
                sb.append("f ${id(0, 2)} ${id(1, 2)} ${id(1, 0)}\n")
                sb.append("f ${id(0, 3)} ${id(1, 1)} ${id(1, 3)}\n")
            } else {
                sb.append("f ${id(i, 2)} ${id(i + 1, 2)} ${id(i + 1, 0)} ${id(i, 0)}\n")
                sb.append("f ${id(i, 3)} ${id(i, 1)} ${id(i + 1, 1)} ${id(i + 1, 3)}\n")
            }
        }
        sb.append("f ${id(segs, 2)} ${id(segs, 3)} ${id(segs, 1)} ${id(segs, 0)}\n")
        return base + (segs + 1) * 4
    }

    /** 본관 상자 x[-100,0] z[-110,40] 높이 14 + 그 동쪽 0.5m 밖에 남쪽으로 오르는 폭 6m 램프. */
    private fun buildingWithRamp(withRamp: Boolean = true, triangulateTop: Boolean = false): List<ObjGroup> {
        val sb = StringBuilder("# synthetic\no TARGET_COSTCO_BUILDING\n")
        var v = RenderFixtures.boxObj(sb, 0, -100f, -110f, 0f, 40f, 14f)
        if (withRamp) v = wedgeObj(sb, v, "RAMP_MAIN", 3.5f, 0f, 3.5f, -95f, 6f, 14f, 6, triangulateTop)
        sb.append("o SURROUND_BUILDINGS\n")
        RenderFixtures.boxObj(sb, v, 40f, -60f, 60f, -40f, 7f)
        return ObjMeshLoader.load(sb.toString().byteInputStream())
    }

    private fun rampObject(scene: TacticalScene): Int = scene.rampObjs.first()

    private fun render(scene: TacticalScene, ex: Float, ey: Float, ez: Float, tx: Float, ty: Float, tz: Float,
                       w: Int = 1080, h: Int = 1700): RenderOutput {
        val r = TacticalRenderer(scene)
        val b = CameraBasis().apply { lookAt(ex, ey, ez, tx, ty, tz) }
        val out = r.render(b, w, h, RenderState())
        for (i in 0 until out.vertexCount * 2) assertTrue("좌표가 유한해야 한다", out.polyXY[i].isFinite())
        return out
    }

    private fun isRampPoly(out: RenderOutput, i: Int) =
        out.polyColor[i] == P.RAMP_TOP || out.polyColor[i] == P.RAMP_CHEVRON || out.polyStroke[i] == P.RAMP_STROKE

    private fun isTargetPoly(out: RenderOutput, i: Int) = out.polyStroke[i] == P.TARGET_STROKE

    @Test
    fun rampIsOneObjectWithBrightTopAndDimmerWalls() {
        val with = TacticalScene.build(buildingWithRamp(), null, P)
        val without = TacticalScene.build(buildingWithRamp(withRamp = false), null, P)
        assertEquals("램프는 객체 하나", without.objectCount + 1, with.objectCount)
        assertEquals(1, with.rampObjs.size)
        val o = rampObject(with)
        assertEquals(TacticalScene.KIND_SOLID, with.objKind[o])
        var tops = 0; var walls = 0
        for (f in with.objFaceStart[o] until with.objFaceStart[o] + with.objFaceCount[o]) {
            if (with.faceFlags[f] and TacticalScene.FLAG_DECAL != 0) continue
            val ny = with.faceNormal[f * 3 + 1]
            if (ny > RampGeometry.TOP_NY) {
                tops++
                assertEquals(P.RAMP_TOP, with.faceColor[f])
                assertEquals("윗면 분할선은 그리지 않는다", 0, with.faceStroke[f])
            } else {
                walls++
                assertEquals(P.RAMP_STROKE, with.faceStroke[f])
                assertTrue("벽은 윗면보다 어둡다", luminance(with.faceColor[f]) < luminance(P.RAMP_TOP))
                assertTrue("벽 알파 < 윗면 알파", (with.faceColor[f] ushr 24) < (P.RAMP_TOP ushr 24))
            }
            assertTrue("뒷면 제거", with.faceFlags[f] and TacticalScene.FLAG_CULL_BACK != 0)
        }
        assertEquals(6, tops)
        assertEquals(13, walls)
    }

    @Test
    fun rampColorRelationsHoldInBothPalettes() {
        // 색 값이 아니라 관계를 검사한다: 윗면이 벽보다 밝고 더 불투명하다, 쉐브론은 윗면과 구별된다.
        for (pal in listOf(Palette.DAY, Palette.NIGHT)) {
            val scene = TacticalScene.build(buildingWithRamp(), null, pal)
            val o = scene.rampObjs.first()
            var walls = 0
            for (f in scene.objFaceStart[o] until scene.objFaceStart[o] + scene.objFaceCount[o]) {
                if (scene.faceFlags[f] and TacticalScene.FLAG_DECAL != 0) {
                    assertTrue("쉐브론은 윗면보다 밝다", luminance(scene.faceColor[f]) > luminance(pal.RAMP_TOP))
                    continue
                }
                if (scene.faceNormal[f * 3 + 1] > RampGeometry.TOP_NY) {
                    assertEquals(pal.RAMP_TOP, scene.faceColor[f])
                } else {
                    walls++
                    assertTrue("벽은 윗면보다 어둡다", luminance(scene.faceColor[f]) < luminance(pal.RAMP_TOP))
                    assertTrue("벽 알파 < 윗면 알파", (scene.faceColor[f] ushr 24) < (pal.RAMP_TOP ushr 24))
                    assertTrue("벽 외곽선이 있다", scene.faceStroke[f] ushr 24 != 0)
                }
            }
            assertTrue(walls > 0)
        }
    }

    @Test
    fun dayPaletteKeepsKeyElementsDistinguishable() {
        val d = Palette.DAY
        fun over(fg: Int, bg: Int) = RenderFixtures.composite(fg, bg)
        assertTrue("라벨 글자/바탕 4.5 이상", contrast(d.LABEL, over(d.LABEL_BG, d.BACKGROUND)) >= 4.5f)
        // 리본은 도로면보다 어둡고 채도가 높은 파랑이다. 기준 문서 색 그대로면 명암비는 약 2.2 (색상 차로 구분).
        assertTrue("리본/도로면 2 이상", contrast(over(d.RIBBON_CORE, d.ROAD), d.ROAD) >= 2f)
        assertTrue("리본/바탕 3 이상", contrast(over(d.RIBBON_CORE, d.BACKGROUND), d.BACKGROUND) >= 3f)
        assertTrue("차량/리본 3 이상", contrast(d.VEHICLE_TOP, over(d.RIBBON_CORE, d.ROAD)) >= 3f)
        assertTrue("차량 외곽선이 있다", d.VEHICLE_STROKE ushr 24 != 0)
        assertTrue("화살표/리본 3 이상", contrast(d.ARROW, over(d.RIBBON_CORE, d.ROAD)) >= 3f)
        assertTrue("바탕은 밝다", luminance(d.BACKGROUND) > 200f)
        assertTrue("야간 바탕은 어둡다", luminance(Palette.NIGHT.BACKGROUND) < 40f)
        // 윗면이 옆면보다 밝다 (두 팔레트 모두)
        for (pal in listOf(Palette.DAY, Palette.NIGHT)) {
            val top = pal.shade(pal.SURROUND, 0f, 1f, 0f)
            for (n in listOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 0f, -1f))) {
                assertTrue(luminance(pal.shade(pal.SURROUND, n[0], n[1], n[2])) < luminance(top))
            }
            assertEquals("음영은 알파를 유지", pal.SURROUND ushr 24, top ushr 24)
        }
        // 주간 그늘 면은 원색의 약 80% 밑으로 내려가지 않는다 (탁해지지 않게)
        assertTrue(luminance(d.shade(d.TARGET, 1f, 0f, 0f)) >= luminance(d.TARGET) * 0.79f)
    }

    private fun contrast(a: Int, b: Int): Float = RenderFixtures.contrastRatio(a, b)

    private fun luminance(argb: Int): Float =
        0.299f * ((argb shr 16) and 0xFF) + 0.587f * ((argb shr 8) and 0xFF) + 0.114f * (argb and 0xFF)

    @Test
    fun rampFacesPointOutward() {
        val scene = TacticalScene.build(buildingWithRamp(), null, P)
        val o = rampObject(scene)
        // 쐐기 내부의 한 점 (중간 길이, 높이 절반 아래)
        val ix = 3.5f; val iy = 2f; val iz = -47.5f
        for (f in scene.objFaceStart[o] until scene.objFaceStart[o] + scene.objFaceCount[o]) {
            val d = scene.faceNormal[f * 3] * (scene.faceCenter[f * 3] - ix) +
                scene.faceNormal[f * 3 + 1] * (scene.faceCenter[f * 3 + 1] - iy) +
                scene.faceNormal[f * 3 + 2] * (scene.faceCenter[f * 3 + 2] - iz)
            assertTrue("면 $f 법선이 바깥", d > 0f)
        }
    }

    @Test
    fun chevronsAreStaticDecalsPointingUphillOnTheSlope() {
        for (tri in listOf(false, true)) {
            val scene = TacticalScene.build(buildingWithRamp(triangulateTop = tri), null, P)
            val o = rampObject(scene)
            var n = 0
            for (f in scene.objFaceStart[o] until scene.objFaceStart[o] + scene.objFaceCount[o]) {
                if (scene.faceFlags[f] and TacticalScene.FLAG_DECAL == 0) continue
                n++
                assertEquals(P.RAMP_CHEVRON, scene.faceColor[f])
                assertTrue("쉐브론 법선은 위", scene.faceNormal[f * 3 + 1] > 0.9f)
                val s = scene.faceStart[f]
                assertEquals(TacticalRenderer.ARROW_SHAPE.size / 2, scene.faceSize[f])
                var tipY = -Float.MAX_VALUE; var tipZ = 0f; var tailY = Float.MAX_VALUE; var tailZ = 0f
                for (j in 0 until scene.faceSize[f]) {
                    val vi = scene.faceIndex[s + j] * 3
                    val x = scene.vertices[vi]; val y = scene.vertices[vi + 1]; val z = scene.vertices[vi + 2]
                    // 경사면: y = 14 * (-z / 95). 띄운 높이만큼만 위에 있다.
                    assertEquals("쉐브론 정점은 경사면 위", 14f * (-z / 95f), y, 0.1f)
                    assertTrue("램프 폭 안", x in 0.5f..6.5f)
                    if (y > tipY) { tipY = y; tipZ = z }
                    if (y < tailY) { tailY = y; tailZ = z }
                }
                assertTrue("뾰족한 끝이 오르막(남쪽) 방향", tipZ < tailZ)
            }
            // 길이 95m: 7m 부터 12m 간격, 끝 5m 여유 → 7,19,...,79 = 7개
            assertEquals("triangulate=$tri", 7, n)
        }
    }

    @Test
    fun chevronGeometryIsIndependentOfTime() {
        // 쉐브론은 장면 정점이다. nowSec·motionRestricted 가 달라도 같은 화면 좌표여야 한다(새 애니메이션 없음).
        val scene = TacticalScene.build(buildingWithRamp(), null, P)
        val r = TacticalRenderer(scene)
        val b = CameraBasis().apply { lookAt(60f, 50f, 40f, 3.5f, 5f, -40f) }
        fun chevronXY(now: Float, restricted: Boolean): FloatArray {
            val out = r.render(b, 1080, 1700, RenderState().apply { nowSec = now; motionRestricted = restricted })
            val xs = ArrayList<Float>()
            for (i in 0 until out.polyCount) if (out.polyColor[i] == P.RAMP_CHEVRON) {
                for (k in 0 until out.polySize[i] * 2) xs.add(out.polyXY[out.polyStart[i] * 2 + k])
            }
            return xs.toFloatArray()
        }
        val a = chevronXY(0f, false)
        assertTrue(a.isNotEmpty())
        assertArrayEquals(a, chevronXY(3.7f, false), 0f)
        assertArrayEquals(a, chevronXY(9.1f, true), 0f)
    }

    @Test
    fun flatTopHasNoChevrons() {
        val flat = listOf(floatArrayOf(0f, 3f, 0f, 6f, 3f, 0f, 6f, 3f, -40f, 0f, 3f, -40f))
        assertNull(RampGeometry.uphillDir(flat))
        assertTrue(RampGeometry.chevrons(flat, TacticalRenderer.ARROW_SHAPE).isEmpty())
    }

    @Test
    fun uphillDirIgnoresWinding() {
        val a = floatArrayOf(0f, 0f, 0f, 6f, 0f, 0f, 6f, 10f, -50f, 0f, 10f, -50f)
        val rev = floatArrayOf(0f, 10f, -50f, 6f, 10f, -50f, 6f, 0f, 0f, 0f, 0f, 0f)
        for (p in listOf(a, rev)) {
            val d = RampGeometry.uphillDir(listOf(p))!!
            assertEquals(0f, d[0], 1e-4f)
            assertEquals(-1f, d[1], 1e-4f)
        }
    }

    /** 본관 상자 x[-100,0] z[-110,40] 의 동쪽 가장자리 안쪽(x 중심 -4)으로 들어가 있는 램프. 대부분이 본관 부피 안이다. */
    private fun buildingWithRampInside(): List<ObjGroup> {
        val sb = StringBuilder("# synthetic\no TARGET_COSTCO_BUILDING\n")
        var v = RenderFixtures.boxObj(sb, 0, -100f, -110f, 0f, 40f, 14f)
        // 낮은 끝 (2, 46): 본관 북동 모서리 바로 바깥 → 높은 끝 (-6, -49): 본관 안, 높이 14 = 본관 높이
        v = wedgeObj(sb, v, "RAMP_MAIN", 2f, 46f, -6f, -49f, 6f, 14f, 6)
        sb.append("o ORIGIN_RAMP_MARKER\n")
        RenderFixtures.boxObj(sb, v, 1f, 45f, 3f, 47f, 1f)
        return ObjMeshLoader.load(sb.toString().byteInputStream())
    }

    private val eyes = listOf(
        floatArrayOf(120f, 90f, -40f, 0f, 0f, -40f),      // 동쪽 상공 (램프 쪽)
        floatArrayOf(5f, 9f, 75f, 0f, 2f, 20f),           // APPROACH 유사: 입구 북쪽에서 남쪽으로
        floatArrayOf(-220f, 120f, -20f, -20f, 0f, -20f),  // 서쪽 상공 (본관 반대편)
        floatArrayOf(-40f, 160f, -200f, -20f, 0f, -20f),  // 남쪽 상공
        floatArrayOf(-150f, 180f, 180f, 0f, 0f, 0f),      // OVERVIEW 유사 쿼터뷰
    )

    @Test
    fun rampInsideBuildingVolumeIsDrawnAfterBuildingFromEverySide() {
        for (groups in listOf(buildingWithRampInside(), buildingWithRamp())) {
            val scene = TacticalScene.build(groups, null, P)
            assertEquals(1, scene.rampObjs.size)
            assertEquals(1, scene.rampTargets.size)
            for (eye in eyes) {
                val out = render(scene, eye[0], eye[1], eye[2], eye[3], eye[4], eye[5])
                var lastTarget = -1; var firstRamp = Int.MAX_VALUE; var ramps = 0
                val roof = P.shade(P.TARGET, 0f, 1f, 0f)
                for (i in 0 until out.polyCount) {
                    if (isTargetPoly(out, i) || out.polyColor[i] == roof) lastTarget = i
                    if (isRampPoly(out, i)) { ramps++; if (i < firstRamp) firstRamp = i }
                }
                assertTrue("눈 ${eye.toList()}: 램프가 보인다", ramps > 0)
                assertTrue("눈 ${eye.toList()}: 본관이 보인다", lastTarget >= 0)
                assertTrue("눈 ${eye.toList()}: 본관을 먼저, 램프를 나중에 칠한다", lastTarget < firstRamp)
            }
        }
    }

    @Test
    fun vehicleEntryMarkerAndEntryPadAreDrawnAfterRamp() {
        val scene = TacticalScene.build(buildingWithRampInside(), RenderFixtures.zone(), P)
        assertEquals(1, scene.rampOverObjs.size)
        val r = TacticalRenderer(scene)
        // 차량이 램프 낮은 끝(입구)에 서 있거나, 카메라에서 램프보다 먼 쪽에 있어도 램프에 덮이지 않는다.
        for (veh in listOf(floatArrayOf(2f, 46f), floatArrayOf(0f, 0f), floatArrayOf(-3f, 60f))) {
            for (eye in eyes) {
                val b = CameraBasis().apply { lookAt(eye[0], eye[1], eye[2], eye[3], eye[4], eye[5]) }
                val s = RenderState().apply { hasVehicle = true; vehicleX = veh[0]; vehicleZ = veh[1]; vehicleHeadingDeg = 185f }
                val out = r.render(b, 1080, 1700, s)
                var lastRamp = -1; var firstVehicle = Int.MAX_VALUE; var firstMarker = Int.MAX_VALUE; var firstPad = Int.MAX_VALUE; var pads = 0
                val markerTop = P.shade(P.ENTRY_MARKER, 0f, 1f, 0f)
                for (i in 0 until out.polyCount) {
                    val c = out.polyColor[i]
                    if (isRampPoly(out, i)) lastRamp = i
                    if ((c == P.VEHICLE_TOP || c == P.VEHICLE_SIDE) && i < firstVehicle) firstVehicle = i
                    if (c == markerTop && i < firstMarker) firstMarker = i
                    if (c == P.ENTRY_PAD) { pads++; if (i < firstPad) firstPad = i }
                }
                if (lastRamp < 0) continue
                if (firstVehicle != Int.MAX_VALUE) assertTrue("차량은 램프 뒤에 칠한다 (눈 ${eye.toList()})", lastRamp < firstVehicle)
                if (firstMarker != Int.MAX_VALUE) assertTrue("입구 마커는 램프 뒤에 칠한다", lastRamp < firstMarker)
                if (pads > 0) {
                    assertEquals("입구 패드는 한 번만 칠한다", 1, pads)
                    assertTrue("입구 패드는 램프 뒤에 칠한다", lastRamp < firstPad)
                }
            }
        }
    }

    @Test
    fun entryPadKeepsGroundOrderWhenRampIsOffScreenOrEvActive() {
        val scene = TacticalScene.build(buildingWithRampInside(), RenderFixtures.zone(), P)
        val r = TacticalRenderer(scene)
        // (1) 주 입구 패드(0,0) 를 내려다보되 램프(z -49..46, x -9..5)는 화면 밖: 서쪽 멀리서 서쪽을 봄 → 패드 없음이므로
        //     대신 EV 활성 장면으로 확인한다: EV 패드는 램프가 화면에 있어도 지면 순서 그대로다.
        val b = CameraBasis().apply { lookAt(60f, 260f, 120f, -60f, 0f, -150f) }
        val out = r.render(b, 1080, 1700, RenderState().apply { evActive = true })
        var pad = -1; var firstRamp = Int.MAX_VALUE; var firstTarget = Int.MAX_VALUE
        for (i in 0 until out.polyCount) {
            if (out.polyColor[i] == P.ENTRY_PAD) pad = i
            if (isRampPoly(out, i) && i < firstRamp) firstRamp = i
            if (isTargetPoly(out, i) && i < firstTarget) firstTarget = i
        }
        assertTrue("EV 패드가 그려진다", pad >= 0)
        assertTrue("램프가 그려진다", firstRamp != Int.MAX_VALUE)
        assertTrue("EV 패드는 지면 순서(입체보다 먼저)", pad < firstTarget && pad < firstRamp)
    }

    @Test
    fun chevronsAreDrawnAfterRampFaces() {
        val scene = TacticalScene.build(buildingWithRamp(), null, P)
        for (eye in listOf(floatArrayOf(120f, 90f, -40f, 0f, 0f, -40f), floatArrayOf(5f, 9f, 35f, 3.5f, 2f, -20f), floatArrayOf(3.5f, 3f, 12f, 3.5f, 4f, -30f))) {
            val out = render(scene, eye[0], eye[1], eye[2], eye[3], eye[4], eye[5])
            var lastBody = -1; var firstChevron = Int.MAX_VALUE
            for (i in 0 until out.polyCount) {
                if (out.polyColor[i] == P.RAMP_CHEVRON) { if (i < firstChevron) firstChevron = i }
                else if (isRampPoly(out, i)) lastBody = i
            }
            assertTrue("쉐브론이 보인다", firstChevron != Int.MAX_VALUE)
            assertTrue(lastBody < firstChevron)
        }
    }

    @Test
    fun cameraAtRampMouthAndOnRampDoesNotBreak() {
        val scene = TacticalScene.build(buildingWithRamp(), RenderFixtures.zone(), P)
        val r = TacticalRenderer(scene)
        val b = CameraBasis()
        val s = RenderState().apply { hasVehicle = true; vehicleX = 3.5f; vehicleZ = 2f; vehicleHeadingDeg = 180f }
        var clipped = 0
        for ((w, h) in listOf(1080 to 1700, 1920 to 720)) {
            // 입구 30m 앞에서 램프 위 60m 지점까지 0.5m 씩, 눈높이 3 종류
            var z = 30f
            while (z > -60f) {
                for (ey in floatArrayOf(0.8f, 3f, 9f)) {
                    val rampY = if (z < 0f) 14f * (-z / 95f) else 0f
                    b.lookAt(3.5f, rampY + ey, z, 3.2f, rampY + ey - 0.5f, z - 20f)
                    val out = r.render(b, w, h, s)
                    for (i in 0 until out.vertexCount * 2) assertTrue(out.polyXY[i].isFinite())
                    for (i in 0 until out.polyCount) assertTrue("근평면 클리핑 후 3각형 이상", out.polySize[i] >= 3)
                    clipped += out.clippedFaces
                }
                z -= 0.5f
            }
        }
        assertTrue("램프 근처에서 근평면 클리핑이 실제로 일어났다", clipped > 0)
    }

    @Test
    fun sceneWithoutRampHasNoRampArtifacts() {
        val scene = TacticalScene.build(RenderFixtures.denseObj(1200), RenderFixtures.zone(), P)
        assertEquals(0, scene.rampObjs.size)
        assertEquals(0, scene.rampTargets.size)
        assertEquals(0, scene.rampOverObjs.size)
        for (f in 0 until scene.faceCount) {
            assertEquals(0, scene.faceFlags[f] and TacticalScene.FLAG_DECAL)
            assertFalse(scene.faceColor[f] == P.RAMP_TOP || scene.faceColor[f] == P.RAMP_CHEVRON || scene.faceStroke[f] == P.RAMP_STROKE)
        }
        // zone 만 있는 장면(OBJ 없음)도 마찬가지
        assertEquals(0, TacticalScene.build(null, RenderFixtures.zone(), P).rampObjs.size)
    }

    @Test
    fun addingRampDoesNotChangeOtherPolygons() {
        // 램프가 있어도 램프 외 다각형(색·정점)은 램프 없는 장면과 같은 순서·같은 좌표로 나온다.
        // (입구 패드·차량은 램프 뒤로 옮겨지므로 여기서는 zone·차량 없이 비교한다.)
        val with = TacticalScene.build(buildingWithRamp(), null, P)
        val without = TacticalScene.build(buildingWithRamp(withRamp = false), null, P)
        for (eye in listOf(floatArrayOf(120f, 90f, -40f, 0f, 0f, -40f), floatArrayOf(5f, 9f, 35f, 3.5f, 2f, -20f), floatArrayOf(-200f, 150f, 150f, 0f, 0f, 0f))) {
            val a = render(with, eye[0], eye[1], eye[2], eye[3], eye[4], eye[5])
            val b = render(without, eye[0], eye[1], eye[2], eye[3], eye[4], eye[5])
            var j = 0
            for (i in 0 until a.polyCount) {
                if (isRampPoly(a, i) || a.polyStroke[i] == P.RAMP_STROKE) continue
                assertTrue(j < b.polyCount)
                assertEquals(b.polyColor[j], a.polyColor[i])
                assertEquals(b.polySize[j], a.polySize[i])
                for (k in 0 until a.polySize[i] * 2) assertEquals(b.polyXY[b.polyStart[j] * 2 + k], a.polyXY[a.polyStart[i] * 2 + k], 0f)
                j++
            }
            assertEquals(b.polyCount, j)
        }
    }

    @Test
    fun enforceBeforeMovesOnlyTheMover() {
        val a = intArrayOf(5, 2, 7, 9, 4, 1)
        assertFalse("이미 순서가 맞음", RampGeometry.enforceBefore(a, 6, 2, 9, 9))
        assertArrayEquals(intArrayOf(5, 2, 7, 9, 4, 1), a)
        // 9 가 2 보다 먼저 와야 한다, 9 를 옮긴다 → 2 바로 앞
        assertTrue(RampGeometry.enforceBefore(a, 6, 9, 2, 9))
        assertArrayEquals(intArrayOf(5, 9, 2, 7, 4, 1), a)
        // 4 가 9 보다 먼저, 9 를 옮긴다 → 4 바로 뒤
        assertTrue(RampGeometry.enforceBefore(a, 6, 4, 9, 9))
        assertArrayEquals(intArrayOf(5, 2, 7, 4, 9, 1), a)
        // 없는 원소, 범위 밖 원소는 무시
        assertFalse(RampGeometry.enforceBefore(a, 6, 42, 9, 9))
        assertFalse(RampGeometry.enforceBefore(a, 4, 1, 5, 5))
        assertArrayEquals(intArrayOf(5, 2, 7, 4, 9, 1), a)
    }

    /** 실제 자산에 RAMP_MAIN 이 있을 때만: 본관과 연결되고 쉐브론이 생긴다. */
    @Test
    fun realAssetRampIsLinkedToBuilding() {
        val dir = listOf("src/main/assets", "app/src/main/assets", "android_pleos_app/app/src/main/assets").map(::File).firstOrNull { it.isDirectory }
        val obj = dir?.resolve("sejong_costco_custom.obj")
        val json = dir?.resolve("sejong_costco_zone.json")
        assumeTrue("assets 미생성", obj?.isFile == true && json?.isFile == true)
        val groups = obj!!.inputStream().use { ObjMeshLoader.load(it) }
        assumeTrue("자산에 RAMP_ 그룹 없음", groups.any { it.name.startsWith(TacticalScene.RAMP_PREFIX) })
        val scene = TacticalScene.build(groups, ZoneJsonParser.parse(json!!.readText(Charsets.UTF_8)), P)
        assertTrue("램프 객체", scene.rampObjs.isNotEmpty())
        assertTrue("본관과 연결", scene.rampTargets.isNotEmpty())
        val o = scene.rampObjs[0]
        var decals = 0
        for (f in scene.objFaceStart[o] until scene.objFaceStart[o] + scene.objFaceCount[o]) {
            if (scene.faceFlags[f] and TacticalScene.FLAG_DECAL != 0) decals++
        }
        assertTrue("오르막 쉐브론", decals > 0)
    }
}
