package io.github.ambitionandvision.tactical.engine

import io.github.ambitionandvision.tactical.model.CameraMode
import io.github.ambitionandvision.tactical.model.FuelKind
import io.github.ambitionandvision.tactical.model.TacticalFrame
import io.github.ambitionandvision.tactical.model.TacticalPhase
import io.github.ambitionandvision.tactical.model.ZoneData
import io.github.ambitionandvision.tactical.model.ZoneJsonParser
import io.github.ambitionandvision.tactical.sdk.FakeScheduler
import io.github.ambitionandvision.tactical.sdk.GpxReplaySource
import io.github.ambitionandvision.tactical.sdk.GpxTestData
import io.github.ambitionandvision.tactical.sdk.GpxTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * GPX 전 구간 리플레이 수용 기준 (수용 기준):
 * Phase 순서 1→2→3, Phase 2 스냅 비율 ≥ 98%, 오경고 0, 카메라 전환 1회.
 */
class GpxReplayIntegrationTest {

    data class Report(
        val phases: List<TacticalPhase>,
        val p2Frames: Int,
        val p2Attached: Int,
        val warnings: Int,
        val cameraSwitches: Int,
        val maxLateralM: Float,
    ) {
        val snapRatio: Float get() = if (p2Frames == 0) 0f else p2Attached.toFloat() / p2Frames
        override fun toString() =
            "phases=$phases P2 frames=$p2Frames snap=${"%.4f".format(snapRatio)} warnings=$warnings " +
                "cameraSwitches=$cameraSwitches maxLateral=${"%.2f".format(maxLateralM)}m"
    }

    private fun replay(zone: ZoneData, track: GpxTrack, fuel: FuelKind): Report {
        val sm = TacticalStateMachine(zone)
        sm.setFuelKind(fuel)
        val frames = ArrayList<TacticalFrame>()
        val src = GpxReplaySource(track, FakeScheduler())
        src.start { frames.add(sm.update(it)) }
        while (src.step()) Unit

        val phases = frames.map { it.phase }.fold(mutableListOf<TacticalPhase>()) { acc, p ->
            if (acc.lastOrNull() != p) acc.add(p); acc
        }
        val p2 = frames.filter { it.phase == TacticalPhase.PHASE_2_TACTICAL }
        var switches = 0
        for (i in 1 until frames.size) if (frames[i].cameraMode != frames[i - 1].cameraMode) switches++
        return Report(
            phases = phases,
            p2Frames = p2.size,
            p2Attached = p2.count { it.snap!!.attached },
            warnings = frames.count { it.snap?.offRouteWarning == true },
            cameraSwitches = switches,
            maxLateralM = p2.maxOfOrNull { it.snap!!.lateralOffsetM } ?: 0f,
        )
    }

    private fun assertAcceptance(name: String, r: Report) {
        println("[$name] $r")
        assertEquals("$name Phase 순서", listOf(TacticalPhase.PHASE_1_CRUISING, TacticalPhase.PHASE_2_TACTICAL, TacticalPhase.PHASE_3_ARRIVED), r.phases)
        assertTrue("$name Phase 2 스냅 비율 ${r.snapRatio}", r.snapRatio >= 0.98f)
        assertEquals("$name 오경고", 0, r.warnings)
        assertEquals("$name 카메라 전환", 1, r.cameraSwitches)
    }

    @Test
    fun syntheticNoisyDriveMeetsAcceptance() {
        val zone = TestZones.zone(
            guide = listOf(
                io.github.ambitionandvision.tactical.model.GuidePoint("Path_01", -300f, 400f),
                io.github.ambitionandvision.tactical.model.GuidePoint("Path_02", -150f, 200f),
                io.github.ambitionandvision.tactical.model.GuidePoint("Path_03", 40f, 140f),
                io.github.ambitionandvision.tactical.model.GuidePoint("Path_04", 84f, 120f),
                io.github.ambitionandvision.tactical.model.GuidePoint("Path_05", 54f, -9f),
                io.github.ambitionandvision.tactical.model.GuidePoint("Path_06", 51f, -21f),
                io.github.ambitionandvision.tactical.model.GuidePoint("Path_07", 20f, -14f),
                io.github.ambitionandvision.tactical.model.GuidePoint("Path_08", 0f, 0f),
            ),
        )
        // 주행 궤적 = 같은 유도선을 따라가되 GNSS 노이즈(σ 2m, 시간 상관). 시작은 직선거리 500m 밖.
        val drive = listOf(-420f to 560f) + LocalSplineSnapper(zone.guidePathPoints, zone.triggers).samples.map { it.x to it.z }
        val gpx = GpxTestData.build(
            TestZones.projection, drive,
            speedAt = { rem -> when { rem > 380 -> 13.9; rem > 40 -> 7.0; else -> maxOf(1.5, rem / 6) } },
            stopSec = 6, noiseSigmaM = 2.0,
        )
        assertAcceptance("synthetic", replay(zone, GpxTrack.parse(gpx.byteInputStream()), FuelKind.ICE))
    }

    // ---- 실제 산출물(GIS 파이프라인) --------------------------------------------------------------

    private val assets = File("src/main/assets")

    private fun realZone(): ZoneData {
        val f = File(assets, "sejong_costco_zone.json")
        assumeTrue("zone JSON 없음: ${f.absolutePath}", f.isFile)
        return ZoneJsonParser.parse(f.readText(Charsets.UTF_8))
    }

    private fun realTrack(name: String): GpxTrack {
        val f = File(assets, name)
        assumeTrue("GPX 없음: ${f.absolutePath}", f.isFile)
        return f.inputStream().use { GpxTrack.parse(it) }
    }

    @Test
    fun realMainParkingGpxMeetsAcceptance() {
        val zone = realZone()
        val track = realTrack("sejong_costco_cn8_drive.gpx")
        assertTrue("합성 GPX 는 simulated 표시가 있어야 한다", track.simulated || track.description.isNotEmpty())
        assertAcceptance("cn8_drive(MAIN_PARKING)", replay(zone, track, FuelKind.ICE))
    }

    @Test
    fun realEvGpxMeetsAcceptance() {
        val zone = realZone()
        val track = realTrack("sejong_costco_ev_drive.gpx")
        assertAcceptance("ev_drive(EV_CHARGING_ENTRY)", replay(zone, track, FuelKind.EV))
    }

    @Test
    fun realZoneIsConsistent() {
        val zone = realZone()
        assertEquals((1..8).map { "Path_%02d".format(it) }, zone.guidePathPoints.map { it.id })
        assertTrue(zone.entries.any { it.id == "MAIN_PARKING" })
        assertTrue(zone.entries.any { it.id == "EV_CHARGING_ENTRY" })
        val sm = TacticalStateMachine(zone)
        val first = zone.guidePathPoints.first()
        val d = Math.hypot((first.x - sm.activeEntry.position.x).toDouble(), (first.z - sm.activeEntry.position.z).toDouble())
        assertTrue("Path_01 직선거리 ${d}m ≥ 355m", d >= 355.0)
        assertTrue(zone.hazardPins.none { it.fieldVerified })
    }
}
