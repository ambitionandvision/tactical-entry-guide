package io.github.ambitionandvision.tactical.engine

import io.github.ambitionandvision.tactical.engine.TestZones.fix
import io.github.ambitionandvision.tactical.model.TacticalPhase.PHASE_1_CRUISING
import io.github.ambitionandvision.tactical.model.TacticalPhase.PHASE_2_TACTICAL
import io.github.ambitionandvision.tactical.model.TacticalPhase.PHASE_3_ARRIVED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArmedGateTest {

    private val sm = TacticalStateMachine(TestZones.zone())
    private var t = 0L
    private fun at(d: Float, speed: Float) = sm.update(fix(0f, d, speed, t.also { t += 1000 }))

    @Test
    fun armedByDefault() {
        assertTrue(sm.armed)
        assertEquals(PHASE_2_TACTICAL, at(299f, 30f).phase)
    }

    @Test
    fun disarmedBlocksP1ToP2() {
        sm.setArmed(false)
        assertEquals(PHASE_1_CRUISING, at(299f, 30f).phase)
        assertEquals(PHASE_1_CRUISING, at(100f, 10f).phase)
        assertEquals(PHASE_1_CRUISING, at(10f, 0f).phase)
        sm.setArmed(true)
        assertEquals(PHASE_2_TACTICAL, at(200f, 20f).phase)
    }

    @Test
    fun disarmingDuringP2KeepsP2AndArrives() {
        assertEquals(PHASE_2_TACTICAL, at(299f, 30f).phase)
        sm.setArmed(false)
        assertEquals(PHASE_2_TACTICAL, at(150f, 25f).phase)
        assertEquals(PHASE_3_ARRIVED, at(10f, 5f).phase)
    }
}
