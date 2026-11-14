package io.github.ambitionandvision.tactical.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ZoneJsonParserTest {

    /** TEAM_WORKSPACE 5.2 의 예시 그대로. */
    private val contractSample = """
    {
      "schemaVersion": 1, "zoneId": "sejong_costco", "displayName": "코스트코 세종점",
      "origin": {"lat": 36.469512, "lon": 127.270845}, "radiusM": 300.0,
      "entries": [
        {"id":"MAIN_PARKING","label":"주차장 통합 입구","fuelKinds":["ICE","UNKNOWN"],
         "position":{"x":0.0,"z":0.0},"recommendedLane":"우측 끝 차로"},
        {"id":"EV_CHARGING_ENTRY","label":"전기차 충전소 인접 입구","fuelKinds":["EV","PHEV"],
         "position":{"x":-118.0,"z":-286.0},"recommendedLane":"우측 끝 차로"}
      ],
      "buildings": [{"id":"TARGET_COSTCO_BUILDING","role":"TARGET","heightM":20.0,"footprint":[{"x":0,"z":0}]}],
      "roads": [{"id":"R0001","widthM":15.0,"lanes":2,"centerline":[{"x":0,"z":0}]}],
      "guidePathPoints": [{"id":"Path_01","x":0,"z":0}],
      "evGuidePathPoints": [{"id":"PathEV_01","x":0,"z":0}],
      "hazardPins": [{"id":"Pin_Barrier_01","type":"BARRIER","x":0,"z":0,"label":"차단기","fieldVerified":false}],
      "staticNotices": {"heightLimitM":2.3,"barrierNote":"우회전 후 30m 앞 차단기"},
      "triggers": {"tacticalDistM":300,"tacticalSlowDistM":350,"tacticalSpeedKmh":30,"tacticalExitDistM":400,
                   "followViewDistM":100,"followViewExitDistM":115,"arrivedDistM":15,"arrivedStopDistM":30,
                   "arrivedStopSpeedKmh":3,"arrivedStopHoldSec":3,"snapDistM":15,"offRouteWarnDistM":20}
    }
    """

    @Test
    fun parsesContractSample() {
        val z = ZoneJsonParser.parse(contractSample)
        assertEquals(1, z.schemaVersion)
        assertEquals("코스트코 세종점", z.displayName)
        assertEquals(36.469512, z.origin.lat, 1e-9)
        assertEquals(2, z.entries.size)
        assertEquals(setOf(FuelKind.EV, FuelKind.PHEV), z.entries[1].fuelKinds)
        assertEquals(LocalPoint(-118f, -286f), z.entries[1].position)
        assertEquals("TARGET", z.buildings[0].role)
        assertEquals(2, z.roads[0].lanes)
        assertEquals("Path_01", z.guidePathPoints[0].id)
        assertEquals("PathEV_01", z.evGuidePathPoints[0].id)
        assertEquals(HazardType.BARRIER, z.hazardPins[0].type)
        assertFalse(z.hazardPins[0].fieldVerified)
        assertEquals(2.3f, z.staticNotices.heightLimitM, 1e-6f)
        assertEquals(TriggerConfig(), z.triggers)
    }

    @Test
    fun projectionMatchesContractFormula() {
        val p = LocalProjection(LatLon(36.469512, 127.270845))
        val l = p.toLocal(36.469512 + 0.001, 127.270845 + 0.001)
        assertEquals(110.574f, l.z, 1e-3f)
        assertEquals((111.320 * Math.cos(Math.toRadians(36.469512))).toFloat(), l.x, 1e-3f)
        val back = p.toLatLon(l.x.toDouble(), l.z.toDouble())
        assertEquals(36.470512, back.lat, 1e-7)
        assertEquals(127.271845, back.lon, 1e-7)
    }
}
