package io.github.ambitionandvision.tactical.sdk

import io.github.ambitionandvision.tactical.engine.TacticalStateMachine
import io.github.ambitionandvision.tactical.engine.TestZones
import io.github.ambitionandvision.tactical.model.FuelKind
import io.github.ambitionandvision.tactical.model.TacticalPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NaviZoneGateTest {

    private val zone = TestZones.zone()
    private val gate = NaviZoneGate(zone)
    private val proj = TestZones.projection

    private fun classifyLocal(x: Double, z: Double, remain: Int = 1234) =
        proj.toLatLon(x, z).let { gate.classify("dest", it.lat, it.lon, remain) }

    @Test
    fun emulatorNoRouteIsZeroZero() {
        // 에뮬레이터 실측: 경로 없음 → (0,0), remainDistance 0 → d=1.2085955E7m
        val s = gate.classify("", 0.0, 0.0, 0)
        assertFalse(s.hasActiveRoute)
        assertFalse(s.nearZone)
        assertNull(s.distanceToZoneM)
        assertTrue(NaviZoneGate.armed(s, naviAvailable = true))
    }

    @Test
    fun passingVehicleWithFarDestinationIsDisarmed() {
        val s = classifyLocal(3_000.0, 4_000.0) // 목적지 5km 밖
        assertTrue(s.hasActiveRoute)
        assertFalse(s.nearZone)
        assertEquals(5_000f, s.distanceToZoneM!!, 5f)
        assertFalse(NaviZoneGate.armed(s, naviAvailable = true))
    }

    @Test
    fun destinationNearOriginOrAnyEntryIsArmed() {
        assertTrue(NaviZoneGate.armed(classifyLocal(100.0, 100.0), true))
        // EV 입구(-118,-286) 남쪽 300m: 원점에서는 ~600m 지만 Zone 전체 기준으로 근처
        val nearEv = classifyLocal(-118.0, -586.0)
        assertTrue(nearEv.nearZone)
        assertEquals(300f, nearEv.distanceToZoneM!!, 1f)
        assertTrue(NaviZoneGate.armed(nearEv, true))
        assertFalse(classifyLocal(0.0, 501.0).nearZone)
        assertEquals(1234, classifyLocal(0.0, 0.0).remainDistanceM)
    }

    @Test
    fun naviFailureOrNoResponseIsArmed() {
        assertTrue(NaviZoneGate.armed(null, naviAvailable = true))
        assertTrue(NaviZoneGate.armed(null, naviAvailable = false))
        assertTrue(NaviZoneGate.armed(classifyLocal(3_000.0, 4_000.0), naviAvailable = false))
    }

    @Test
    fun passingVehicleNeverEntersTacticalPhase() {
        val sm = TacticalStateMachine(zone)
        sm.setArmed(NaviZoneGate.armed(classifyLocal(0.0, -5_000.0), true))
        var t = 0L
        // 입구 옆을 지나 남쪽으로 계속 주행 (최근접 0m)
        for (z in 600 downTo -600 step 10) {
            val f = sm.update(TestZones.fix(0f, z.toFloat(), 30f, t)); t += 1000
            assertEquals("z=$z", TacticalPhase.PHASE_1_CRUISING, f.phase)
        }
    }

    @Test
    fun activeEntryChangeDoesNotChangeArming() {
        val sm = TacticalStateMachine(zone)
        val s = classifyLocal(-118.0, -586.0)
        sm.setFuelKind(FuelKind.ICE); sm.setArmed(NaviZoneGate.armed(s, true)); assertTrue(sm.armed)
        sm.setFuelKind(FuelKind.EV); sm.setArmed(NaviZoneGate.armed(s, true)); assertTrue(sm.armed)
    }
}
