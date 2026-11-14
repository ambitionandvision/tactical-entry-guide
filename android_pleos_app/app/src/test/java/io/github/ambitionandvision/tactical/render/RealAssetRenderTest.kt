package io.github.ambitionandvision.tactical.render

import io.github.ambitionandvision.tactical.engine.ObjGroup
import io.github.ambitionandvision.tactical.engine.ObjMeshLoader
import io.github.ambitionandvision.tactical.model.ZoneData
import io.github.ambitionandvision.tactical.model.ZoneJsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * GIS 파이프라인 산출물(assets 의 OBJ·zone JSON)이 있을 때만 도는 실데이터 검증.
 * OBJ 의 Path_xx, Pin_xx 상자 중심(= 위치, 5.3)이 zone JSON 좌표와 같아야 로더 축 변환(5.1)이 맞은 것이다.
 */
class RealAssetRenderTest {

    private fun assetFile(name: String): File? =
        listOf("src/main/assets/$name", "app/src/main/assets/$name", "android_pleos_app/app/src/main/assets/$name")
            .map(::File).firstOrNull { it.isFile }

    private fun loadAssets(): Pair<List<ObjGroup>, ZoneData> {
        val obj = assetFile("sejong_costco_custom.obj")
        val json = assetFile("sejong_costco_zone.json")
        assumeTrue("assets 미생성", obj != null && json != null)
        return obj!!.inputStream().use { ObjMeshLoader.load(it) } to ZoneJsonParser.parse(json!!.readText(Charsets.UTF_8))
    }

    private fun bboxCenter(g: ObjGroup): Pair<Float, Float> {
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (i in 0 until g.vertexCount) {
            minX = minOf(minX, g.vertices[i * 3]); maxX = maxOf(maxX, g.vertices[i * 3])
            minZ = minOf(minZ, g.vertices[i * 3 + 2]); maxZ = maxOf(maxZ, g.vertices[i * 3 + 2])
        }
        return (minX + maxX) / 2f to (minZ + maxZ) / 2f
    }

    @Test
    fun objMarkersMatchZoneJsonInWorldFrame() {
        val (groups, zone) = loadAssets()
        val byName = groups.associateBy { it.name.substringBefore('.') }
        for (name in listOf("ORIGIN_RAMP_MARKER", "TARGET_COSTCO_BUILDING", "SURROUND_BUILDINGS", "ROAD_SURFACE_300M",
            "Pin_Barrier_01", "Pin_NoTurn_01", "Pin_WaitLane_01")) {
            assertTrue("OBJ 에 $name 없음", byName.containsKey(name))
        }
        val (ox, oz) = bboxCenter(byName.getValue("ORIGIN_RAMP_MARKER"))
        assertEquals(0f, ox, 0.05f)
        assertEquals(0f, oz, 0.05f)
        for (p in zone.guidePathPoints + zone.evGuidePathPoints) {
            val g = byName[p.id] ?: throw AssertionError("OBJ 에 ${p.id} 없음")
            val (cx, cz) = bboxCenter(g)
            assertEquals("${p.id}.x", p.x, cx, 0.05f)
            assertEquals("${p.id}.z (북)", p.z, cz, 0.05f)
        }
        for (p in zone.hazardPins) {
            val (cx, cz) = bboxCenter(byName.getValue(p.id))
            assertEquals("${p.id}.x", p.x, cx, 0.05f)
            assertEquals("${p.id}.z (북)", p.z, cz, 0.05f)
        }
        assertTrue("면 수 예산 6,000", groups.sumOf { it.faceCount } <= 6000)
    }

    @Test
    fun realSceneRendersInBothOrientationsAndModes() {
        val (groups, zone) = loadAssets()
        val scene = TacticalScene.build(groups, zone)
        val r = TacticalRenderer(scene)
        for (path in listOf(scene.mainPath!!, scene.evPath!!)) {
            val rig = CameraRig().apply { setOverviewFromPath(path) }
            val b = CameraBasis()
            val s = RenderState().apply { hasVehicle = true; evActive = path === scene.evPath }
            for ((w, h) in listOf(1080 to 1700, 1920 to 720)) {
                // 경로를 따라가며 OVERVIEW → APPROACH 전환
                var t = 0f
                var arc = 0f
                while (arc <= path.lengthM) {
                    val o = FloatArray(4)
                    path.pointAt(arc, o)
                    s.vehicleX = o[0]; s.vehicleZ = o[1]; s.vehicleHeadingDeg = RenderMath.headingOf(o[2], o[3]); s.progressM = arc
                    rig.update(t, path.lengthM - arc < 100f, s.vehicleX, s.vehicleZ, s.vehicleHeadingDeg, true)
                    b.lookAt(rig.eye[0], rig.eye[1], rig.eye[2], rig.target[0], rig.target[1], rig.target[2])
                    s.nowSec = t
                    val out = r.render(b, w, h, s)
                    assertTrue(out.polyCount > 0)
                    for (i in 0 until out.vertexCount * 2) assertTrue(out.polyXY[i].isFinite())
                    arc += 5f; t += 0.5f
                }
            }
        }
    }
}
