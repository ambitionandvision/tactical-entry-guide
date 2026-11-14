package io.github.ambitionandvision.tactical.bridge

import io.github.ambitionandvision.tactical.model.LocalPoint
import io.github.ambitionandvision.tactical.model.TacticalFrame
import io.github.ambitionandvision.tactical.model.ZoneData

/**
 * Kotlin → Unity 메시지 본문 (TEAM_WORKSPACE 5.7). Android 의존 없는 순수 직렬화라 JVM 테스트 가능.
 * Unity `JsonUtility` 호환: 중첩 배열 없음, 점은 항상 {"x","z"} 객체, enum 은 이름 문자열.
 */
object UnityBridgeProtocol {
    const val GAME_OBJECT = "PleosBridge"
    const val METHOD_ZONE = "OnZone"
    const val METHOD_VEHICLE_STATE = "OnVehicleState"
    const val METHOD_ACTIVE = "OnActive"

    /** 5.2 스키마 그대로의 zone JSON. */
    fun zoneJson(z: ZoneData): String = buildString {
        append('{')
        field("schemaVersion", z.schemaVersion); comma()
        field("zoneId", z.zoneId); comma()
        field("displayName", z.displayName); comma()
        key("origin"); append("{"); field("lat", z.origin.lat); comma(); field("lon", z.origin.lon); append('}'); comma()
        field("radiusM", z.radiusM); comma()
        array("entries", z.entries) { e ->
            append('{')
            field("id", e.id); comma()
            field("label", e.label); comma()
            key("fuelKinds"); append('[')
            e.fuelKinds.sortedBy { it.ordinal }.forEachIndexed { i, k -> if (i > 0) comma(); string(k.name) }
            append(']'); comma()
            key("position"); point(e.position); comma()
            field("recommendedLane", e.recommendedLane)
            append('}')
        }; comma()
        array("buildings", z.buildings) { b ->
            append('{')
            field("id", b.id); comma(); field("role", b.role); comma(); field("heightM", b.heightM); comma()
            array("footprint", b.footprint) { point(it) }
            append('}')
        }; comma()
        array("roads", z.roads) { r ->
            append('{')
            field("id", r.id); comma(); field("widthM", r.widthM); comma(); field("lanes", r.lanes); comma()
            array("centerline", r.centerline) { point(it) }
            append('}')
        }; comma()
        array("guidePathPoints", z.guidePathPoints) { p ->
            append('{'); field("id", p.id); comma(); field("x", p.x); comma(); field("z", p.z); append('}')
        }; comma()
        array("evGuidePathPoints", z.evGuidePathPoints) { p ->
            append('{'); field("id", p.id); comma(); field("x", p.x); comma(); field("z", p.z); append('}')
        }; comma()
        array("hazardPins", z.hazardPins) { p ->
            append('{')
            field("id", p.id); comma(); field("type", p.type.name); comma()
            field("x", p.x); comma(); field("z", p.z); comma()
            field("label", p.label); comma(); field("fieldVerified", p.fieldVerified)
            append('}')
        }; comma()
        key("staticNotices"); append('{')
        field("heightLimitM", z.staticNotices.heightLimitM); comma(); field("barrierNote", z.staticNotices.barrierNote)
        append('}'); comma()
        val t = z.triggers
        key("triggers"); append('{')
        field("tacticalDistM", t.tacticalDistM); comma()
        field("tacticalSlowDistM", t.tacticalSlowDistM); comma()
        field("tacticalSpeedKmh", t.tacticalSpeedKmh); comma()
        field("tacticalExitDistM", t.tacticalExitDistM); comma()
        field("followViewDistM", t.followViewDistM); comma()
        field("followViewExitDistM", t.followViewExitDistM); comma()
        field("arrivedDistM", t.arrivedDistM); comma()
        field("arrivedStopDistM", t.arrivedStopDistM); comma()
        field("arrivedStopSpeedKmh", t.arrivedStopSpeedKmh); comma()
        field("arrivedStopHoldSec", t.arrivedStopHoldSec); comma()
        field("snapDistM", t.snapDistM); comma()
        field("offRouteWarnDistM", t.offRouteWarnDistM)
        append('}')
        append('}')
    }

    /** OnVehicleState 본문. 키는 5.7 의 11개. 위치는 화면 표시 좌표(attached 면 스냅 좌표). */
    fun vehicleStateJson(f: TacticalFrame): String = buildString {
        append('{')
        field("x", f.vehicleX); comma()
        field("z", f.vehicleZ); comma()
        field("headingDeg", f.headingDeg); comma()
        field("speedKmh", f.speedKmh); comma()
        field("phase", f.phase.name); comma()
        field("cameraMode", f.cameraMode.name); comma()
        field("attached", f.snap?.attached ?: false); comma()
        field("offRouteWarning", f.snap?.offRouteWarning ?: false); comma()
        field("distanceToEntryM", f.distanceToEntryM); comma()
        field("entryId", f.activeEntry.id); comma()
        field("motionRestricted", f.motionRestricted)
        append('}')
    }

    fun activeArg(active: Boolean): String = if (active) "true" else "false"

    // ---- 작은 JSON 작성기 ----

    private fun StringBuilder.comma() { append(',') }
    private fun StringBuilder.key(k: String) { string(k); append(':') }
    private fun StringBuilder.field(k: String, v: String) { key(k); string(v) }
    private fun StringBuilder.field(k: String, v: Int) { key(k); append(v) }
    private fun StringBuilder.field(k: String, v: Boolean) { key(k); append(if (v) "true" else "false") }
    private fun StringBuilder.field(k: String, v: Float) { key(k); number(v.isNaN() || v.isInfinite(), v.toString()) }
    private fun StringBuilder.field(k: String, v: Double) { key(k); number(v.isNaN() || v.isInfinite(), v.toString()) }

    private fun StringBuilder.point(p: LocalPoint) {
        append('{'); field("x", p.x); comma(); field("z", p.z); append('}')
    }

    private inline fun <T> StringBuilder.array(k: String, items: List<T>, item: StringBuilder.(T) -> Unit) {
        key(k); append('[')
        items.forEachIndexed { i, v -> if (i > 0) comma(); item(v) }
        append(']')
    }

    /**
     * [shortest] = Kotlin 의 최단 왕복 표기(Float/Double.toString, 로캘 무관). 지수 표기를 풀어 쓴다.
     * NaN/무한대는 JSON 에 없으므로 0.
     */
    private fun StringBuilder.number(invalid: Boolean, shortest: String) {
        if (invalid) { append('0'); return }
        append(java.math.BigDecimal(shortest).toPlainString())
    }

    internal fun StringBuilder.string(s: String) {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
            }
        }
        append('"')
    }
}
