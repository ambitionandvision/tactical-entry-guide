package io.github.ambitionandvision.tactical.render

import io.github.ambitionandvision.tactical.bridge.UnityBridgeManager
import io.github.ambitionandvision.tactical.bridge.UnityBridgeProtocol
import io.github.ambitionandvision.tactical.model.CameraMode
import io.github.ambitionandvision.tactical.model.ZoneJsonParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class UnityBridgeProtocolTest {

    @Test
    fun vehicleStateHasExactlyTheContractKeys() {
        val json = UnityBridgeProtocol.vehicleStateJson(RenderFixtures.frame(12.5f, -3.25f, 271f, CameraMode.APPROACH))
        val o = JSONObject(json)
        assertEquals(
            setOf("x", "z", "headingDeg", "speedKmh", "phase", "cameraMode", "attached", "offRouteWarning",
                "distanceToEntryM", "entryId", "motionRestricted"),
            o.keySet(),
        )
        assertEquals(12.5, o.getDouble("x"), 1e-6)
        assertEquals("PHASE_2_TACTICAL", o.getString("phase"))
        assertEquals("APPROACH", o.getString("cameraMode"))
        assertEquals(true, o.getBoolean("attached"))
        assertEquals("MAIN_PARKING", o.getString("entryId"))
        assertFalse(json.contains("E-") || json.contains("E+") || json.contains("NaN"))
    }

    @Test
    fun zoneJsonRoundTripsThroughContractParser() {
        val zone = RenderFixtures.zone()
        val json = UnityBridgeProtocol.zoneJson(zone)
        assertEquals(zone, ZoneJsonParser.parse(json))
        // JsonUtility 호환: 중첩 배열 없음
        assertFalse(Regex("\\[\\s*\\[").containsMatchIn(json))
    }

    @Test
    fun unityAbsentOnJvm() {
        assertNull(UnityBridgeManager.findSendMessage())
    }
}
