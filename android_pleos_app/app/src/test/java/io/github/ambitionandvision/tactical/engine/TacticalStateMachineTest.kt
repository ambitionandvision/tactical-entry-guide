package io.github.ambitionandvision.tactical.engine

import io.github.ambitionandvision.tactical.engine.TestZones.fix
import io.github.ambitionandvision.tactical.model.CameraMode
import io.github.ambitionandvision.tactical.model.FuelKind
import io.github.ambitionandvision.tactical.model.TacticalPhase.PHASE_1_CRUISING
import io.github.ambitionandvision.tactical.model.TacticalPhase.PHASE_2_TACTICAL
import io.github.ambitionandvision.tactical.model.TacticalPhase.PHASE_3_ARRIVED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TacticalStateMachineTest {

    private val sm = TacticalStateMachine(TestZones.zone())
    private var t = 0L

    /** 유도선(x=0) 위, 입구에서 북쪽으로 d 미터. */
    private fun at(d: Float, speed: Float, x: Float = 0f) = sm.update(fix(x, d, speed, t.also { t += 1000 }))

    @Test
    fun p1ToP2ByDistance() {
        assertEquals(PHASE_1_CRUISING, at(301f, 50f).phase)
        assertNull(at(301f, 50f).snap)
        assertEquals(PHASE_2_TACTICAL, at(299f, 50f).phase)
    }

    @Test
    fun p1ToP2ByLowSpeedWithin350() {
        assertEquals(PHASE_1_CRUISING, at(349f, 31f).phase)
        assertEquals(PHASE_1_CRUISING, at(351f, 10f).phase)
        assertEquals(PHASE_2_TACTICAL, at(349f, 30f).phase)
    }

    @Test
    fun p2ToP1Beyond400() {
        at(299f, 50f)
        assertEquals(PHASE_2_TACTICAL, at(399f, 50f).phase)
        assertEquals(PHASE_2_TACTICAL, at(400f, 50f).phase)
        assertEquals(PHASE_1_CRUISING, at(401f, 50f).phase)
    }

    @Test
    fun p2ToP3WithinArrivedDist() {
        at(299f, 30f)
        assertEquals(PHASE_2_TACTICAL, at(15.5f, 20f).phase)
        assertEquals(PHASE_3_ARRIVED, at(14.9f, 20f).phase)
    }

    @Test
    fun p2ToP3ByStopHoldWithin30m() {
        at(299f, 30f)
        assertEquals(PHASE_2_TACTICAL, at(25f, 2f).phase) // t0
        assertEquals(PHASE_2_TACTICAL, at(25f, 1f).phase) // +1s
        assertEquals(PHASE_2_TACTICAL, at(25f, 0f).phase) // +2s
        assertEquals(PHASE_3_ARRIVED, at(25f, 0f).phase) // +3s
    }

    @Test
    fun stopHoldResetsWhenMoving() {
        at(299f, 30f)
        at(28f, 0f); at(28f, 0f)
        at(27f, 5f) // 다시 움직임 → 타이머 초기화
        assertEquals(PHASE_2_TACTICAL, at(26f, 0f).phase)
        assertEquals(PHASE_2_TACTICAL, at(26f, 0f).phase)
        assertEquals(PHASE_2_TACTICAL, at(26f, 0f).phase)
        assertEquals(PHASE_3_ARRIVED, at(26f, 0f).phase)
    }

    @Test
    fun noFalseArrivalAtTrafficLight() {
        at(299f, 30f)
        // 입구 31m·60m·120m 앞 신호 대기 30초씩
        for (d in listOf(120f, 60f, 31f)) repeat(30) { assertEquals(PHASE_2_TACTICAL, at(d, 0f).phase) }
    }

    @Test
    fun noReentryWhileParkedAfterArrival() {
        at(299f, 30f)
        assertEquals(PHASE_3_ARRIVED, at(5f, 5f).phase)
        sm.acknowledgeArrival()
        assertEquals(PHASE_1_CRUISING, sm.phase)
        // 주차장 안에서 정차·서행이 길게 이어져도 P2/P3 가 반복되지 않는다.
        repeat(120) { assertEquals(PHASE_1_CRUISING, at(5f + (it % 20), (it % 4).toFloat()).phase) }
        assertEquals(PHASE_1_CRUISING, at(250f, 20f).phase)
        assertEquals(PHASE_1_CRUISING, at(399f, 20f).phase)
        // 400m 밖으로 나갔다 오면 다시 진입한다.
        assertEquals(PHASE_1_CRUISING, at(401f, 40f).phase)
        assertEquals(PHASE_2_TACTICAL, at(299f, 40f).phase)
    }

    @Test
    fun arrivedStaysUntilAcknowledgedOrLeaving() {
        at(299f, 30f)
        assertEquals(PHASE_3_ARRIVED, at(10f, 3f).phase)
        repeat(10) { assertEquals(PHASE_3_ARRIVED, at(10f, 0f).phase) }
        assertEquals(PHASE_3_ARRIVED, at(300f, 30f).phase)
        assertEquals(PHASE_1_CRUISING, at(401f, 30f).phase)
        assertEquals(PHASE_2_TACTICAL, at(299f, 30f).phase)
    }

    @Test
    fun cameraModeHysteresis() {
        at(299f, 30f)
        assertEquals(CameraMode.OVERVIEW, at(120f, 25f).cameraMode)
        assertEquals(CameraMode.OVERVIEW, at(101f, 25f).cameraMode)
        assertEquals(CameraMode.APPROACH, at(100f, 25f).cameraMode)
        assertEquals(CameraMode.APPROACH, at(110f, 25f).cameraMode)
        assertEquals(CameraMode.APPROACH, at(115f, 25f).cameraMode)
        assertEquals(CameraMode.OVERVIEW, at(116f, 25f).cameraMode)
    }

    @Test
    fun activeEntryFollowsFuelKind() {
        assertEquals("MAIN_PARKING", sm.activeEntry.id)
        sm.setFuelKind(FuelKind.EV); assertEquals("EV_CHARGING_ENTRY", sm.activeEntry.id)
        sm.setFuelKind(FuelKind.PHEV); assertEquals("EV_CHARGING_ENTRY", sm.activeEntry.id)
        sm.setFuelKind(FuelKind.ICE); assertEquals("MAIN_PARKING", sm.activeEntry.id)
        sm.setFuelKind(FuelKind.UNKNOWN); assertEquals("MAIN_PARKING", sm.activeEntry.id)
    }

    @Test
    fun evDistanceAndGuideUseEvEntry() {
        sm.setFuelKind(FuelKind.EV)
        val f = sm.update(fix(-118f, -86f, 20f, 0))
        assertEquals(200f, f.distanceToEntryM, 0.5f)
        assertEquals(PHASE_2_TACTICAL, f.phase)
        assertEquals("EV_CHARGING_ENTRY", f.activeEntry.id)
        assertTrue(f.snap!!.attached)
        assertEquals(FuelKind.EV, f.fuelKind)
        assertNull("MAIN 경로의 핀은 EV 경로에 섞이지 않는다", f.nextHazard)
    }

    @Test
    fun nextHazardIsFirstPinAheadByProgress() {
        at(299f, 30f)
        val a = at(250f, 25f)
        assertEquals("Pin_WaitLane_01", a.nextHazard!!.id)
        assertEquals(50f, a.nextHazardDistM!!, 0.5f)
        val b = at(150f, 25f)
        assertEquals("Pin_Barrier_01", b.nextHazard!!.id)
        assertEquals(100f, b.nextHazardDistM!!, 0.5f)
        val c = at(40f, 15f)
        assertNull(c.nextHazard); assertNull(c.nextHazardDistM)
    }

    @Test
    fun attachedFrameUsesSnappedPositionAndOffRouteUsesRaw() {
        at(299f, 30f)
        val a = at(200f, 25f, x = 6f)
        assertTrue(a.snap!!.attached)
        assertEquals(0f, a.vehicleX, 0.01f); assertEquals(6f, a.rawX, 0.05f)
        assertEquals(180f, a.headingDeg, 0.5f)
        val b = at(190f, 25f, x = 25f)
        assertFalse(b.snap!!.attached); assertTrue(b.snap!!.offRouteWarning)
        assertEquals(25f, b.vehicleX, 0.05f)
    }

    @Test
    fun motionRestrictedIsCarriedInFrame() {
        sm.setMotionRestricted(true)
        assertTrue(at(299f, 30f).motionRestricted)
        sm.setMotionRestricted(false)
        assertFalse(at(290f, 30f).motionRestricted)
    }

    @Test
    fun resetReturnsToInitialState() {
        at(299f, 30f); at(5f, 0f)
        sm.reset()
        assertEquals(PHASE_1_CRUISING, sm.phase)
        val f = at(299f, 30f)
        assertEquals(PHASE_2_TACTICAL, f.phase)
        assertNotNull(f.snap)
        assertEquals(CameraMode.OVERVIEW, f.cameraMode)
    }
}
