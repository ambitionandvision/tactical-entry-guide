package io.github.ambitionandvision.tactical.model

import org.json.JSONArray
import org.json.JSONObject

/** `sejong_costco_zone.json` (TEAM_WORKSPACE 5.2) → [ZoneData]. 키 이름은 TacticalModels.kt 와 같다. */
object ZoneJsonParser {

    fun parse(json: String): ZoneData {
        val root = JSONObject(json)
        val origin = root.getJSONObject("origin")
        return ZoneData(
            schemaVersion = root.getInt("schemaVersion"),
            zoneId = root.getString("zoneId"),
            displayName = root.getString("displayName"),
            origin = LatLon(origin.getDouble("lat"), origin.getDouble("lon")),
            radiusM = root.getDouble("radiusM").toFloat(),
            entries = root.getJSONArray("entries").objects().map(::entry),
            buildings = root.optJSONArray("buildings").objects().map(::building),
            roads = root.optJSONArray("roads").objects().map(::road),
            guidePathPoints = root.getJSONArray("guidePathPoints").objects().map(::guidePoint),
            evGuidePathPoints = root.optJSONArray("evGuidePathPoints").objects().map(::guidePoint),
            hazardPins = root.optJSONArray("hazardPins").objects().map(::hazardPin),
            staticNotices = staticNotices(root.optJSONObject("staticNotices")),
            triggers = triggers(root.optJSONObject("triggers")),
        )
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

    private fun point(o: JSONObject) = LocalPoint(o.getDouble("x").toFloat(), o.getDouble("z").toFloat())

    private fun entry(o: JSONObject): EntryPoint {
        val kinds = o.optJSONArray("fuelKinds")
        return EntryPoint(
            id = o.getString("id"),
            label = o.getString("label"),
            fuelKinds = if (kinds == null) emptySet() else (0 until kinds.length()).map { FuelKind.valueOf(kinds.getString(it)) }.toSet(),
            position = point(o.getJSONObject("position")),
            recommendedLane = o.optString("recommendedLane", ""),
        )
    }

    private fun building(o: JSONObject) = BuildingFootprint(
        id = o.getString("id"),
        role = o.optString("role", "SURROUND"),
        heightM = o.optDouble("heightM", 0.0).toFloat(),
        footprint = o.optJSONArray("footprint").objects().map(::point),
    )

    private fun road(o: JSONObject) = RoadStrip(
        id = o.getString("id"),
        widthM = o.optDouble("widthM", 0.0).toFloat(),
        lanes = o.optInt("lanes", 0),
        centerline = o.optJSONArray("centerline").objects().map(::point),
    )

    private fun guidePoint(o: JSONObject) =
        GuidePoint(o.getString("id"), o.getDouble("x").toFloat(), o.getDouble("z").toFloat())

    private fun hazardPin(o: JSONObject) = HazardPin(
        id = o.getString("id"),
        type = HazardType.valueOf(o.getString("type")),
        x = o.getDouble("x").toFloat(),
        z = o.getDouble("z").toFloat(),
        label = o.optString("label", ""),
        fieldVerified = o.optBoolean("fieldVerified", false),
    )

    private fun staticNotices(o: JSONObject?) = StaticNotices(
        heightLimitM = o?.optDouble("heightLimitM", 0.0)?.toFloat() ?: 0f,
        barrierNote = o?.optString("barrierNote", "") ?: "",
    )

    private fun triggers(o: JSONObject?): TriggerConfig {
        val d = TriggerConfig()
        if (o == null) return d
        fun f(key: String, def: Float) = o.optDouble(key, def.toDouble()).toFloat()
        return TriggerConfig(
            tacticalDistM = f("tacticalDistM", d.tacticalDistM),
            tacticalSlowDistM = f("tacticalSlowDistM", d.tacticalSlowDistM),
            tacticalSpeedKmh = f("tacticalSpeedKmh", d.tacticalSpeedKmh),
            tacticalExitDistM = f("tacticalExitDistM", d.tacticalExitDistM),
            followViewDistM = f("followViewDistM", d.followViewDistM),
            followViewExitDistM = f("followViewExitDistM", d.followViewExitDistM),
            arrivedDistM = f("arrivedDistM", d.arrivedDistM),
            arrivedStopDistM = f("arrivedStopDistM", d.arrivedStopDistM),
            arrivedStopSpeedKmh = f("arrivedStopSpeedKmh", d.arrivedStopSpeedKmh),
            arrivedStopHoldSec = f("arrivedStopHoldSec", d.arrivedStopHoldSec),
            snapDistM = f("snapDistM", d.snapDistM),
            offRouteWarnDistM = f("offRouteWarnDistM", d.offRouteWarnDistM),
        )
    }
}
