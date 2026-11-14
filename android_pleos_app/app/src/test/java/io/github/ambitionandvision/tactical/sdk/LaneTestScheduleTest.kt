package io.github.ambitionandvision.tactical.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaneTestScheduleTest {

    private val scheduler = FakeScheduler()
    private var fired = 0
    private fun schedule(sec: Int) = LaneTestSchedule(scheduler, sec) { fired++ }

    @Test
    fun firesOnceAfterDelay() {
        val s = schedule(38)
        assertTrue(s.arm())
        assertEquals(listOf(38_000L), scheduler.delays)
        scheduler.advance(37_999)
        assertEquals(0, fired)
        assertEquals(LaneTestSchedule.State.ARMED, s.state)
        scheduler.advance(1)
        assertEquals(1, fired)
        assertEquals(LaneTestSchedule.State.FIRED, s.state)
        scheduler.advance(600_000)
        assertEquals(1, fired)
    }

    @Test
    fun armIsOneShot() {
        val s = schedule(5)
        assertTrue(s.arm())
        assertFalse(s.arm())
        scheduler.advance(5_000)
        assertFalse(s.arm())
        scheduler.advance(60_000)
        assertEquals(1, fired)
        assertEquals(1, scheduler.delays.size)
    }

    @Test
    fun cancelBeforeDelayPreventsFiringAndRearming() {
        val s = schedule(10)
        s.arm()
        scheduler.advance(9_000)
        assertTrue(s.cancel())
        assertEquals(LaneTestSchedule.State.CANCELLED, s.state)
        scheduler.advance(60_000)
        assertEquals(0, fired)
        assertFalse(s.arm())
        scheduler.advance(60_000)
        assertEquals(0, fired)
    }

    @Test
    fun cancelIsNoOpWhenNotArmed() {
        val s = schedule(3)
        assertFalse(s.cancel())
        assertEquals(LaneTestSchedule.State.IDLE, s.state)
        assertTrue(s.arm())
        scheduler.advance(3_000)
        assertFalse(s.cancel())
        assertEquals(LaneTestSchedule.State.FIRED, s.state)
        assertEquals(1, fired)
    }

    @Test
    fun zeroDelayFiresOnNextTick() {
        val s = schedule(0)
        s.arm()
        assertEquals(0, fired)
        scheduler.advance(0)
        assertEquals(1, fired)
    }

    @Test
    fun extraParsing() {
        assertNull(LaneTestSchedule.delayFromExtra(-1))
        assertEquals(0, LaneTestSchedule.delayFromExtra(0))
        assertEquals(38, LaneTestSchedule.delayFromExtra(38))
    }
}
