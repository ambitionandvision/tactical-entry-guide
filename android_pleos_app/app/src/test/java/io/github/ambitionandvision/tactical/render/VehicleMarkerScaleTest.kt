package io.github.ambitionandvision.tactical.render

import io.github.ambitionandvision.tactical.engine.ObjMeshLoader
import io.github.ambitionandvision.tactical.model.ZoneJsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.sqrt
import kotlin.math.tan

/** 차량 마커의 화면 크기 하한(거리에 따른 배율) 검증. */
class VehicleMarkerScaleTest {

    private val sizes = listOf(1082 to 1210, 1920 to 720, 1080 to 1700)

    private fun focalOf(w: Int, h: Int, fovMinDeg: Float = 60f) = (minOf(w, h) / 2f) / tan(Math.toRadians(fovMinDeg / 2.0)).toFloat()

    /** 실제 자산이 있으면 그 장면, 없으면 합성 zone 장면. 차량 다각형을 색으로 구별하려고 야간 팔레트를 쓴다. */
    private fun scene(): TacticalScene {
        val dir = listOf("src/main/assets", "app/src/main/assets", "android_pleos_app/app/src/main/assets").map(::File).firstOrNull { it.isDirectory }
        val obj = dir?.resolve("sejong_costco_custom.obj")
        val json = dir?.resolve("sejong_costco_zone.json")
        return if (obj?.isFile == true && json?.isFile == true) {
            TacticalScene.build(obj.inputStream().use { ObjMeshLoader.load(it) }, ZoneJsonParser.parse(json.readText(Charsets.UTF_8)), Palette.NIGHT)
        } else {
            TacticalScene.build(null, RenderFixtures.zone(), Palette.NIGHT)
        }
    }

    /** 출력에서 차량 다각형 정점들의 화면상 최대 간격(px). 차량이 없으면 0. */
    private fun vehicleScreenLength(out: RenderOutput, pal: Palette): Float {
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var best = 0f
        val pts = ArrayList<Float>()
        for (i in 0 until out.polyCount) {
            if (out.polyColor[i] != pal.VEHICLE_TOP && out.polyColor[i] != pal.VEHICLE_SIDE) continue
            for (k in 0 until out.polySize[i]) { pts.add(out.polyXY[(out.polyStart[i] + k) * 2]); pts.add(out.polyXY[(out.polyStart[i] + k) * 2 + 1]) }
        }
        for (a in 0 until pts.size / 2) for (b in a + 1 until pts.size / 2) {
            val dx = pts[a * 2] - pts[b * 2]; val dy = pts[a * 2 + 1] - pts[b * 2 + 1]
            best = maxOf(best, sqrt(dx * dx + dy * dy))
        }
        return best
    }

    @Test
    fun scaleIsContinuousMonotoneAndBounded() {
        for ((w, h) in sizes) {
            val f = focalOf(w, h)
            val m = minOf(w, h).toFloat()
            var prev = TacticalRenderer.vehicleMarkerScale(0.5f, f, m)
            assertEquals(1f, prev, 0f)
            var d = 0.5f
            while (d < 3000f) {
                d += 0.5f
                val s = TacticalRenderer.vehicleMarkerScale(d, f, m)
                assertTrue("줄어들지 않는다 d=$d", s >= prev)
                assertTrue("튀지 않는다 d=$d", s - prev < 0.01f)
                assertTrue(s in 1f..TacticalRenderer.VEHICLE_SCALE_MAX)
                prev = s
            }
            assertEquals("아주 멀면 상한", TacticalRenderer.VEHICLE_SCALE_MAX, prev, 0f)
        }
        // 잘못된 입력은 배율 1
        assertEquals(1f, TacticalRenderer.vehicleMarkerScale(Float.NaN, 900f, 1000f), 0f)
        assertEquals(1f, TacticalRenderer.vehicleMarkerScale(100f, 0f, 1000f), 0f)
    }

    @Test
    fun scaleDependsOnlyOnDistanceNotOnWindowSize() {
        // 초점이 짧은 변에 비례하므로 같은 거리면 창 크기와 무관하게 같은 배율이다.
        val a = TacticalRenderer.vehicleMarkerScale(400f, focalOf(1082, 1210), 1082f)
        val b = TacticalRenderer.vehicleMarkerScale(400f, focalOf(1920, 720), 720f)
        assertEquals(a, b, 1e-4f)
        assertTrue(a > 1f)
    }

    @Test
    fun approachCameraKeepsOriginalSize() {
        val scene = scene()
        val r = TacticalRenderer(scene)
        val path = scene.mainPath!!
        val o = FloatArray(4)
        for ((w, h) in sizes) {
            val rig = CameraRig().apply { setOverviewFromPath(path); aspect = w.toFloat() / h }
            var arc = path.lengthM - 100f
            while (arc <= path.lengthM) {
                path.pointAt(arc, o)
                val s = RenderState().apply { hasVehicle = true; vehicleX = o[0]; vehicleZ = o[1]; vehicleHeadingDeg = RenderMath.headingOf(o[2], o[3]) }
                rig.reset()
                rig.update(0f, true, s.vehicleX, s.vehicleZ, s.vehicleHeadingDeg, true)
                val b = CameraBasis().apply { lookAt(rig.eye[0], rig.eye[1], rig.eye[2], rig.target[0], rig.target[1], rig.target[2]) }
                r.render(b, w, h, s)
                assertEquals("APPROACH 에서는 배율 1 (${w}x$h, arc=$arc)", 1f, r.lastVehicleScale, 0f)
                arc += 10f
            }
        }
    }

    @Test
    fun overviewCameraKeepsMarkerAboveMinimumScreenLength() {
        val scene = scene()
        val r = TacticalRenderer(scene)
        val path = scene.mainPath!!
        val o = FloatArray(4)
        for ((w, h) in sizes) {
            val rig = CameraRig().apply { setOverviewFromPath(path); aspect = w.toFloat() / h }
            val minLen = TacticalRenderer.VEHICLE_MIN_SCREEN_FRACTION * minOf(w, h)
            var arc = 0f
            while (arc <= path.lengthM - 100f) {
                path.pointAt(arc, o)
                val s = RenderState().apply { hasVehicle = true; vehicleX = o[0]; vehicleZ = o[1]; vehicleHeadingDeg = RenderMath.headingOf(o[2], o[3]) }
                rig.reset()
                rig.update(0f, false, s.vehicleX, s.vehicleZ, s.vehicleHeadingDeg, true)
                val b = CameraBasis().apply { lookAt(rig.eye[0], rig.eye[1], rig.eye[2], rig.target[0], rig.target[1], rig.target[2]) }
                val out = r.render(b, w, h, s)
                val len = vehicleScreenLength(out, scene.palette)
                assertTrue("OVERVIEW ${w}x$h arc=$arc: 마커 길이 $len px < 하한 $minLen px (배율 ${r.lastVehicleScale})", len >= minLen)
                assertTrue("배율이 상한에 걸리지 않는다", r.lastVehicleScale < TacticalRenderer.VEHICLE_SCALE_MAX)
                assertTrue("OVERVIEW 에서는 키운다", r.lastVehicleScale > 1f)
                arc += 20f
            }
        }
    }

    @Test
    fun scaleIsSmoothAcrossCameraTransition() {
        // OVERVIEW → APPROACH 1.5초 전환을 60fps 로 따라가며 배율이 프레임 사이에 튀지 않는지 본다.
        val scene = scene()
        val r = TacticalRenderer(scene)
        val path = scene.mainPath!!
        val o = FloatArray(4)
        path.pointAt(path.lengthM - 100f, o)
        val s = RenderState().apply { hasVehicle = true; vehicleX = o[0]; vehicleZ = o[1]; vehicleHeadingDeg = RenderMath.headingOf(o[2], o[3]) }
        val rig = CameraRig().apply { setOverviewFromPath(path); aspect = 1082f / 1210f }
        val b = CameraBasis()
        rig.update(0f, false, s.vehicleX, s.vehicleZ, s.vehicleHeadingDeg, true)
        b.lookAt(rig.eye[0], rig.eye[1], rig.eye[2], rig.target[0], rig.target[1], rig.target[2])
        r.render(b, 1082, 1210, s)
        var prev = r.lastVehicleScale
        val start = prev
        var maxStep = 0f
        for (i in 1..120) {
            rig.update(i / 60f, true, s.vehicleX, s.vehicleZ, s.vehicleHeadingDeg, true)
            b.lookAt(rig.eye[0], rig.eye[1], rig.eye[2], rig.target[0], rig.target[1], rig.target[2])
            r.render(b, 1082, 1210, s)
            maxStep = maxOf(maxStep, kotlin.math.abs(r.lastVehicleScale - prev))
            prev = r.lastVehicleScale
        }
        assertTrue(start > 1f)
        assertEquals("전환이 끝나면 배율 1", 1f, prev, 0f)
        assertTrue("프레임 사이 배율 변화가 전체 변화의 5% 미만 (실제 $maxStep / ${start - 1f})", maxStep < (start - 1f) * 0.05f)
    }
}
