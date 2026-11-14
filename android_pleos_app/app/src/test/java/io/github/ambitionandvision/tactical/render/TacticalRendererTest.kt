package io.github.ambitionandvision.tactical.render

import io.github.ambitionandvision.tactical.engine.ObjMeshLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TacticalRendererTest {

    private fun renderOnce(r: TacticalRenderer, b: CameraBasis, w: Int, h: Int, s: RenderState): RenderOutput {
        val out = r.render(b, w, h, s)
        for (i in 0 until out.vertexCount * 2) assertTrue("좌표가 유한해야 한다", out.polyXY[i].isFinite())
        for (i in 0 until out.gridFloats) assertTrue(out.gridLines[i].isFinite())
        return out
    }

    @Test
    fun surroundGroupIsSplitIntoBuildings() {
        val sb = StringBuilder("o SURROUND_BUILDINGS\n")
        var v = 0
        v = RenderFixtures.boxObj(sb, v, 0f, 0f, 10f, 10f, 5f)
        v = RenderFixtures.boxObj(sb, v, 30f, 0f, 40f, 10f, 5f)
        RenderFixtures.boxObj(sb, v, 60f, 0f, 70f, 10f, 5f)
        val scene = TacticalScene.build(ObjMeshLoader.load(sb.toString().byteInputStream()), null)
        val solids = (0 until scene.objectCount).count { scene.objKind[it] == TacticalScene.KIND_SOLID }
        assertEquals(3, solids)
    }

    @Test
    fun solidFacesAreOrientedOutward() {
        // 감기가 뒤죽박죽인 상자도 바깥 법선으로 맞춘다
        val obj = "o SURROUND_BUILDINGS\n" +
            "v 0 0 0\nv 10 0 0\nv 10 0 -10\nv 0 0 -10\nv 0 5 0\nv 10 5 0\nv 10 5 -10\nv 0 5 -10\n" +
            "f 5 6 7 8\nf 1 2 6 5\nf 3 2 6 7\nf 3 4 8 7\nf 1 5 8 4\n"
        val scene = TacticalScene.build(ObjMeshLoader.load(obj.byteInputStream()), null)
        val o = (0 until scene.objectCount).first { scene.objKind[it] == TacticalScene.KIND_SOLID }
        for (f in scene.objFaceStart[o] until scene.objFaceStart[o] + scene.objFaceCount[o]) {
            val n = floatArrayOf(scene.faceNormal[f * 3], scene.faceNormal[f * 3 + 1], scene.faceNormal[f * 3 + 2])
            val c = floatArrayOf(scene.faceCenter[f * 3] - 5f, scene.faceCenter[f * 3 + 1] - 2.5f, scene.faceCenter[f * 3 + 2] - 5f)
            assertTrue("면 $f 법선이 바깥", n[0] * c[0] + n[1] * c[1] + n[2] * c[2] > 0f)
        }
    }

    @Test
    fun overviewFramesWholeRouteInPortraitAndLandscape() {
        val zone = RenderFixtures.zone()
        val scene = TacticalScene.build(null, zone)
        for (path in listOf(scene.mainPath!!, scene.evPath!!)) {
            for ((w, h) in listOf(1080 to 1700, 1920 to 720, 800 to 800)) {
                val rig = CameraRig()
                rig.setOverviewFromPath(path)
                rig.aspect = w.toFloat() / h
                rig.update(0f, false, 0f, 0f, 0f, false)
                val b = CameraBasis()
                b.lookAt(rig.eye[0], rig.eye[1], rig.eye[2], rig.target[0], rig.target[1], rig.target[2])
                val focal = (minOf(w, h) / 2f) / Math.tan(Math.toRadians(rig.fovMinDeg / 2.0)).toFloat()
                val c = FloatArray(3)
                val p = FloatArray(2)
                var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
                for (i in 0 until path.count) {
                    b.toCamera(path.xz[i * 2], 0f, path.xz[i * 2 + 1], c, 0)
                    assertTrue(c[2] > 0f)
                    RenderMath.project(c[0], c[1], c[2], focal, w / 2f, h / 2f, p, 0)
                    assertTrue("${w}x$h 경로점 $i x=${p[0]}", p[0] in 0f..w.toFloat())
                    assertTrue("${w}x$h 경로점 $i y=${p[1]}", p[1] in 0f..h.toFloat())
                    minX = minOf(minX, p[0]); maxX = maxOf(maxX, p[0]); minY = minOf(minY, p[1]); maxY = maxOf(maxY, p[1])
                }
                // 너무 작게 잡히지 않는다: 경로가 화면 가로 또는 세로의 60% 이상을 차지
                assertTrue("${w}x$h 경로 화면 점유", (maxX - minX) / w > 0.6f || (maxY - minY) / h > 0.6f)
            }
        }
    }

    @Test
    fun cameraInsideBuildingDoesNotBreak() {
        val scene = TacticalScene.build(RenderFixtures.denseObj(1200), RenderFixtures.zone())
        val r = TacticalRenderer(scene)
        val b = CameraBasis()
        val s = RenderState().apply { hasVehicle = true; vehicleX = -150f; vehicleZ = -220f; progressM = 50f }
        // 본관(-219..-75, -299..-140, 높이 20) 내부 1인칭, 여러 방향
        for (yaw in 0 until 360 step 15) {
            val tx = -150f + 50f * RenderMath.sinDeg(yaw.toFloat())
            val tz = -220f + 50f * RenderMath.cosDeg(yaw.toFloat())
            b.lookAt(-150f, 1.5f, -220f, tx, 1.5f, tz)
            val out = renderOnce(r, b, 1280, 720, s)
            assertTrue(out.polyCount > 0)
        }
        // 벽을 가로지르며 근평면에 걸치는 경우
        b.lookAt(-75.2f, 5f, -200f, -40f, 5f, -200f)
        val out = renderOnce(r, b, 720, 1280, s)
        assertTrue("근평면에 걸린 면이 클리핑돼야 한다", out.clippedFaces > 0)
    }

    @Test
    fun buildingsAreDrawnFarToNear() {
        // 시선(북쪽) 위에 가까운 본관과 먼 주변 건물
        val sb = StringBuilder("o TARGET_COSTCO_BUILDING\n")
        var v = RenderFixtures.boxObj(sb, 0, -10f, 20f, 10f, 40f, 10f)
        sb.append("o SURROUND_BUILDINGS\n")
        RenderFixtures.boxObj(sb, v, -10f, 80f, 10f, 100f, 30f)
        val scene = TacticalScene.build(ObjMeshLoader.load(sb.toString().byteInputStream()), null)
        val r = TacticalRenderer(scene)
        val b = CameraBasis().apply { lookAt(0f, 15f, -20f, 0f, 5f, 60f) }
        val out = renderOnce(r, b, 1000, 1000, RenderState())
        val alphaOf = { i: Int -> out.polyColor[i] ushr 24 }
        val firstTarget = (0 until out.polyCount).first { alphaOf(it) == (Palette.DAY.TARGET ushr 24) }
        val lastSurround = (0 until out.polyCount).last { alphaOf(it) == (Palette.DAY.SURROUND ushr 24) }
        assertTrue("먼 주변 건물이 가까운 본관보다 먼저", lastSurround < firstTarget)
        assertTrue("뒷면 제거가 동작", out.backFaces > 0)
    }

    @Test
    fun motionRestrictedFreezesArrowFlow() {
        val scene = TacticalScene.build(null, RenderFixtures.zone())
        val r = TacticalRenderer(scene)
        val rig = CameraRig().apply { setOverviewFromPath(scene.mainPath!!); update(0f, false, 0f, 0f, 0f, false) }
        val b = CameraBasis().apply { lookAt(rig.eye[0], rig.eye[1], rig.eye[2], rig.target[0], rig.target[1], rig.target[2]) }
        fun arrows(t: Float, restricted: Boolean): List<Float> {
            val s = RenderState().apply { nowSec = t; motionRestricted = restricted; progressM = 0f }
            val out = r.render(b, 1200, 800, s)
            return (0 until out.polyCount).filter { out.polyColor[it] == Palette.DAY.ARROW }
                .flatMap { p -> (0 until out.polySize[p] * 2).map { out.polyXY[out.polyStart[p] * 2 + it] } }
        }
        assertTrue(arrows(0f, true).isNotEmpty())
        assertEquals(arrows(0.3f, true), arrows(1.1f, true))
        assertTrue(arrows(0.3f, false) != arrows(1.1f, false))
    }

    @Test
    fun approachTransitionTakesOnePointFiveSeconds() {
        val rig = CameraRig()
        rig.setOverviewTarget(0f, 0f, 200f, 0f)
        rig.update(0f, false, 0f, -50f, 0f, true)
        val ov = rig.eye.copyOf()
        rig.update(1f, true, 0f, -50f, 0f, true)
        assertTrue(rig.isTransitioning)
        assertEquals(ov[1], rig.eye[1], 1e-3f) // 시작 순간은 이전 자세
        rig.update(1.75f, true, 0f, -50f, 0f, true)
        assertTrue(rig.isTransitioning)
        rig.update(2.5f, true, 0f, -50f, 0f, true)
        assertTrue(!rig.isTransitioning)
        assertEquals(CameraRig.FOLLOW_HEIGHT_M, rig.eye[1], 1e-3f)
        assertEquals(-50f - CameraRig.FOLLOW_BACK_M, rig.eye[2], 1e-3f)
    }

    /**
     * 면 6,000개 기준 순수 파이프라인 시간과 할당량(JVM). Canvas 칠하기 시간은 포함하지 않는다
     * (기기 측정은 Tactical3DFallbackView 의 logcat `Tactical3D perf` 로그).
     */
    @Test
    fun pipelineBudgetAndNoAllocationAt6000Faces() {
        val groups = RenderFixtures.denseObj(6000)
        val objFaces = groups.sumOf { it.faceCount }
        assertTrue("OBJ 면 수 $objFaces", objFaces in 5900..6000)
        val scene = TacticalScene.build(groups, RenderFixtures.zone())
        val r = TacticalRenderer(scene)
        val rig = CameraRig().apply { setOverviewFromPath(scene.mainPath!!) }
        val b = CameraBasis()
        val s = RenderState().apply { hasVehicle = true; vehicleX = 54f; vehicleZ = 60f; vehicleHeadingDeg = 190f; progressM = 300f }

        fun frame(i: Int) {
            val approach = i % 2 == 0
            rig.update(i * 0.016f, approach, s.vehicleX, s.vehicleZ, s.vehicleHeadingDeg, true)
            b.lookAt(rig.eye[0], rig.eye[1], rig.eye[2], rig.target[0], rig.target[1], rig.target[2])
            s.nowSec = i * 0.016f
            r.render(b, 1280, 720, s)
        }
        repeat(300) { frame(it) } // JIT 예열

        val probe = AllocationProbe.create()
        val before = probe?.bytes() ?: -1L
        val n = 400
        val t0 = System.nanoTime()
        for (i in 0 until n) frame(i)
        val avgMs = (System.nanoTime() - t0) / 1e6 / n
        val allocated = if (probe != null) probe.bytes() - before else -1L
        println("[perf] sceneFaces=${scene.faceCount} solidFaces=${scene.solidFaceCount} avgPipeline=%.3fms allocated=${allocated}B over $n frames".format(avgMs))
        assertTrue("파이프라인 평균 $avgMs ms", avgMs < 16.0)
        if (probe != null) assertTrue("렌더 루프 할당 $allocated B", allocated < 4096)
    }

    /**
     * HotSpot 의 스레드별 누적 할당 바이트. android.jar 컴파일 클래스패스에는 java.lang.management 가 없어 리플렉션으로 쓴다.
     * 측정 호출 자체의 박싱(수십 B)이 포함되므로 임계값에 여유를 둔다.
     */
    private class AllocationProbe(private val bean: Any, private val method: java.lang.reflect.Method) {
        private val tid = Thread.currentThread().id
        fun bytes(): Long = method.invoke(bean, tid) as Long

        companion object {
            fun create(): AllocationProbe? = try {
                val bean: Any = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)!!
                val m = Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
                AllocationProbe(bean, m).also { it.bytes() }
            } catch (_: Throwable) {
                null
            }
        }
    }
}
