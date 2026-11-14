package io.github.ambitionandvision.tactical.engine

import io.github.ambitionandvision.tactical.engine.TestZones.fix
import io.github.ambitionandvision.tactical.model.FuelKind
import io.github.ambitionandvision.tactical.model.GuidePoint
import io.github.ambitionandvision.tactical.model.TacticalFrame
import io.github.ambitionandvision.tactical.model.TacticalPhase
import io.github.ambitionandvision.tactical.model.ZoneJsonParser
import io.github.ambitionandvision.tactical.sdk.FakeScheduler
import io.github.ambitionandvision.tactical.sdk.GpxReplaySource
import io.github.ambitionandvision.tactical.sdk.GpxTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * TacticalFrame.pathRemainingM: 유도선을 따라 입구까지 남은 거리. HUD "입구까지" 표시 전용.
 * 유도선 투영이 있으면(Phase 2·3, 흡착 여부 무관) 값이 있고, 다음 위험요소 거리와 같은 진행거리 기준이다.
 */
class PathRemainingTest {

    @Test
    fun straightGuideMatchesStraightDistanceWhenAttached() {
        val sm = TacticalStateMachine(TestZones.zone())
        val f = sm.update(fix(5f, 200f, 20f, 0L))
        assertEquals(TacticalPhase.PHASE_2_TACTICAL, f.phase)
        assertTrue(f.snap!!.attached)
        assertEquals(200f, f.pathRemainingM!!, 0.5f)
        assertEquals(f.snap!!.remainingM, f.pathRemainingM!!, 0f)
        // 직선거리는 횡오프셋만큼만 더 길 수 있다.
        assertTrue(f.pathRemainingM!! >= f.distanceToEntryM - f.snap!!.lateralOffsetM)
    }

    @Test
    fun detourGuideIsLongerThanStraightDistance() {
        // ㄷ자 유도선: 입구 북쪽 100m 에서 동쪽으로 200m 돌아 들어온다.
        val zone = TestZones.zone(
            guide = listOf(
                GuidePoint("Path_01", 0f, 400f),
                GuidePoint("Path_02", 0f, 100f),
                GuidePoint("Path_03", 200f, 100f),
                GuidePoint("Path_04", 200f, 0f),
                GuidePoint("Path_05", 0f, 0f),
            ),
            pins = emptyList(),
        )
        val sm = TacticalStateMachine(zone)
        val f = sm.update(fix(0f, 250f, 20f, 0L))
        assertTrue(f.snap!!.attached)
        assertEquals(250f, f.distanceToEntryM, 1f)
        assertTrue("경로상 ${f.pathRemainingM}m 는 직선 250m 보다 훨씬 길다", f.pathRemainingM!! > 550f)
        // 직선거리 기준 동작은 그대로다: 입구 100m 밖이므로 OVERVIEW.
        assertEquals(io.github.ambitionandvision.tactical.model.CameraMode.OVERVIEW, f.cameraMode)
    }

    @Test
    fun nullInPhase1ButValueWhenDetachedInPhase2() {
        val sm = TacticalStateMachine(TestZones.zone())
        val p1 = sm.update(fix(0f, 450f, 50f, 0L))
        assertEquals(TacticalPhase.PHASE_1_CRUISING, p1.phase)
        assertNull(p1.pathRemainingM)

        val attached = sm.update(fix(14.9f, 200f, 20f, 1000L))
        assertNotNull(attached.pathRemainingM)

        // Phase 2 미흡착·이탈 경고 중에도 투영 진행거리 기준 값이 있다.
        val detached = sm.update(fix(15.5f, 190f, 20f, 2000L))
        assertEquals(TacticalPhase.PHASE_2_TACTICAL, detached.phase)
        assertTrue(!detached.snap!!.attached)
        assertEquals(190f, detached.pathRemainingM!!, 0.5f)

        val far = sm.update(fix(25f, 180f, 20f, 3000L))
        assertTrue(far.snap!!.offRouteWarning)
        assertEquals(180f, far.pathRemainingM!!, 0.5f)
        assertEquals(far.snap!!.remainingM, far.pathRemainingM!!, 0f)
    }

    @Test
    fun hazardDistanceNeverExceedsPathRemainingWhen25mOffRoute() {
        val sm = TacticalStateMachine(TestZones.zone())
        var t = 0L
        var withHazard = 0
        for (d in 299 downTo 40) {
            val f = sm.update(fix(25f, d.toFloat(), 20f, t)); t += 1000
            assertEquals(TacticalPhase.PHASE_2_TACTICAL, f.phase)
            assertTrue(f.snap!!.offRouteWarning && !f.snap!!.attached)
            val hz = f.nextHazardDistM ?: continue
            withHazard++
            assertTrue("d=$d hazard=$hz remaining=${f.pathRemainingM}", hz <= f.pathRemainingM!!)
        }
        assertTrue(withHazard > 100)
    }

    @Test
    fun hazardDistanceNeverExceedsPathRemaining() {
        val sm = TacticalStateMachine(TestZones.zone())
        var t = 0L
        var withHazard = 0
        for (d in 299 downTo 16) {
            val f = sm.update(fix(0f, d.toFloat(), 20f, t)); t += 1000
            val hz = f.nextHazardDistM ?: continue
            withHazard++
            assertTrue("d=$d hazard=$hz remaining=${f.pathRemainingM}", hz <= f.pathRemainingM!!)
        }
        assertTrue(withHazard > 100)
    }

    // ---- 실제 산출물 --------------------------------------------------------------------------

    private val assets = File("src/main/assets")

    private fun replayReal(gpx: String, fuel: FuelKind): List<TacticalFrame> {
        val zf = File(assets, "sejong_costco_zone.json")
        val gf = File(assets, gpx)
        assumeTrue("자산 없음", zf.isFile && gf.isFile)
        val zone = ZoneJsonParser.parse(zf.readText(Charsets.UTF_8))
        val sm = TacticalStateMachine(zone)
        sm.setFuelKind(fuel)
        val frames = ArrayList<TacticalFrame>()
        val src = GpxReplaySource(gf.inputStream().use { GpxTrack.parse(it) }, FakeScheduler())
        src.start { frames.add(sm.update(it)) }
        while (src.step()) Unit
        return frames
    }

    private fun assertRealReplay(name: String, frames: List<TacticalFrame>) {
        val p2 = frames.filter { it.phase == TacticalPhase.PHASE_2_TACTICAL }
        assertTrue("$name Phase 2 프레임", p2.isNotEmpty())
        var hazardFrames = 0
        var maxExcess = 0f
        for (f in p2) {
            val snap = f.snap!!
            if (!snap.attached) {
                f.nextHazardDistM?.let { assertTrue(it <= f.pathRemainingM!!) }
                continue
            }
            val rem = f.pathRemainingM!!
            // 유도선은 입구에서 끝나므로 경로상 거리는 직선거리보다 (횡오프셋 이상) 짧을 수 없다.
            assertTrue(
                "$name 경로상 ${rem}m < 직선 ${f.distanceToEntryM}m − 횡 ${snap.lateralOffsetM}m",
                rem >= f.distanceToEntryM - snap.lateralOffsetM - 0.5f,
            )
            maxExcess = maxOf(maxExcess, rem - f.distanceToEntryM)
            val hz = f.nextHazardDistM
            if (f.nextHazard != null && hz != null) {
                hazardFrames++
                assertTrue("$name ${f.nextHazard!!.id} ${hz}m > 입구까지 ${rem}m", hz <= rem)
            }
        }
        println("[$name] P2 frames=${p2.size} hazardFrames=$hazardFrames maxExcess=${"%.1f".format(maxExcess)}m")
        assertTrue("$name 위험요소가 있는 Phase 2 프레임", hazardFrames > 0)
    }

    @Test
    fun realMainDriveHazardIsNotFartherThanEntry() {
        assertRealReplay("cn8_drive", replayReal("sejong_costco_cn8_drive.gpx", FuelKind.ICE))
    }

    @Test
    fun realEvDriveHazardIsNotFartherThanEntry() {
        val frames = replayReal("sejong_costco_ev_drive.gpx", FuelKind.EV)
        val p2 = frames.filter { it.phase == TacticalPhase.PHASE_2_TACTICAL && it.snap!!.attached }
        assertTrue(p2.isNotEmpty())
        for (f in p2) {
            assertTrue(f.pathRemainingM!! >= f.distanceToEntryM - f.snap!!.lateralOffsetM - 0.5f)
            f.nextHazardDistM?.let { assertTrue(it <= f.pathRemainingM!!) }
        }
    }
}
