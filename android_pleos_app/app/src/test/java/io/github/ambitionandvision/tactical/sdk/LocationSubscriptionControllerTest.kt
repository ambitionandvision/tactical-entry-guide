package io.github.ambitionandvision.tactical.sdk

import io.github.ambitionandvision.tactical.model.LatLon
import io.github.ambitionandvision.tactical.sdk.LocationSubscriptionController.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationSubscriptionControllerTest {

    private class Recorder : LocationSubscriptionController.Actions {
        val calls = ArrayList<String>()
        val registered = ArrayList<Pair<LatLon, Int>>()
        var continuous = false
        override fun registerGeofence(center: LatLon, radiusM: Float, generation: Int) {
            calls.add("register:$generation"); registered.add(center to generation)
        }
        override fun unregisterGeofence() { calls.add("unregister") }
        override fun requestLastKnown() { calls.add("lastKnown") }
        override fun startContinuous() { calls.add("start"); continuous = true }
        override fun stopContinuous() { calls.add("stop"); continuous = false }
        fun count(c: String) = calls.count { it == c }
    }

    private val main = LatLon(36.469512, 127.270845)
    private val ev = LatLon(36.4669, 127.2695)
    private val sch = FakeScheduler()
    private val rec = Recorder()
    private var phase1 = true
    private val ctl = LocationSubscriptionController(rec, sch, enterDistM = 400f).also { it.isPhase1 = { phase1 } }

    @Test
    fun outsideZoneOnlyGeofenceAndLastKnown() {
        ctl.start(main)
        ctl.onGeofenceRegistered(1)
        assertEquals(listOf("register:1", "lastKnown"), rec.calls)
        ctl.onDistance(1_500f)
        sch.advance(10_000)
        assertFalse(rec.continuous)
        assertEquals(Mode.WATCHING, ctl.mode)
        assertEquals(main, rec.registered.single().first)
    }

    @Test
    fun enterStartsAndExitInPhase1Stops() {
        ctl.start(main); ctl.onGeofenceRegistered(1)
        ctl.onEnter()
        assertTrue(rec.continuous); assertEquals(Mode.CONTINUOUS_BY_ZONE, ctl.mode)
        ctl.onEnter()
        assertEquals(1, rec.count("start"))
        ctl.onExit()
        assertFalse(rec.continuous); assertEquals(Mode.WATCHING, ctl.mode)
    }

    @Test
    fun exitOutsidePhase1KeepsContinuous() {
        ctl.start(main); ctl.onGeofenceRegistered(1)
        ctl.onEnter()
        phase1 = false
        ctl.onExit()
        assertTrue(rec.continuous)
        assertEquals(0, rec.count("stop"))
    }

    @Test
    fun alreadyInsideAtStartStartsContinuous() {
        ctl.start(main); ctl.onGeofenceRegistered(1)
        ctl.onDistance(380f) // getLastKnownLocation 결과
        assertTrue(rec.continuous)
    }

    @Test
    fun registrationFailureFallsBackImmediatelyAndStays() {
        ctl.start(main)
        ctl.onGeofenceFailed(1, "unsupported")
        assertTrue(rec.continuous); assertEquals(Mode.CONTINUOUS_FALLBACK, ctl.mode)
        ctl.onExit()
        assertTrue("폴백은 EXIT 로 끄지 않는다", rec.continuous)
    }

    @Test
    fun noRegistrationWithin3sFallsBack() {
        ctl.start(main)
        sch.advance(2_999)
        assertFalse(rec.continuous)
        sch.advance(1)
        assertTrue(rec.continuous); assertEquals(Mode.CONTINUOUS_FALLBACK, ctl.mode)
    }

    @Test
    fun registeredInTimeDoesNotFallBack() {
        ctl.start(main)
        sch.advance(1_000)
        ctl.onGeofenceRegistered(1)
        sch.advance(10_000)
        assertFalse(rec.continuous)
    }

    @Test
    fun pollsLastKnownEvery30sWhileOutside() {
        ctl.start(main); ctl.onGeofenceRegistered(1)
        sch.advance(95_000)
        assertEquals(1 + 3, rec.count("lastKnown"))
        ctl.onEnter()
        sch.advance(60_000)
        assertEquals("연속 측위 중에는 폴링 안 함", 4, rec.count("lastKnown"))
        ctl.onExit()
        sch.advance(30_000)
        assertEquals(5, rec.count("lastKnown"))
        sch.advance(30_000)
        assertEquals("폴링 루프는 하나뿐", 6, rec.count("lastKnown"))
    }

    @Test
    fun entryChangeReregistersAtNewCenter() {
        ctl.start(main); ctl.onGeofenceRegistered(1)
        ctl.setEntryCenter(ev)
        assertEquals(listOf("register:1", "lastKnown", "unregister", "register:2"), rec.calls)
        assertEquals(ev, rec.registered.last().first)
        ctl.setEntryCenter(ev)
        assertEquals(2, rec.registered.size)
        // 이전 세대 등록 응답은 무시 → 새 세대가 3초 안에 등록되지 않으면 폴백
        ctl.onGeofenceRegistered(1)
        sch.advance(3_000)
        assertTrue(rec.continuous)
    }

    @Test
    fun staleFailureIsIgnored() {
        ctl.start(main)
        ctl.setEntryCenter(ev)
        ctl.onGeofenceRegistered(2)
        ctl.onGeofenceFailed(1, "old")
        assertFalse(rec.continuous)
    }

    @Test
    fun stopReleasesEverything() {
        ctl.start(main); ctl.onGeofenceRegistered(1); ctl.onEnter()
        ctl.stop()
        assertFalse(rec.continuous)
        assertEquals(Mode.IDLE, ctl.mode)
        assertTrue(rec.calls.contains("unregister"))
        val n = rec.calls.size
        sch.advance(120_000)
        ctl.onEnter()
        assertEquals(n, rec.calls.size)
    }
}
