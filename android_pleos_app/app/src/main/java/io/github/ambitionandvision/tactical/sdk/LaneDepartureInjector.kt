package io.github.ambitionandvision.tactical.sdk

import io.github.ambitionandvision.tactical.model.LocalProjection
import io.github.ambitionandvision.tactical.model.VehicleFix
import kotlin.math.cos
import kotlin.math.sin

/** 데모 "차선 이탈 경고 테스트": 트리거 후 [durationMs] 동안 측위를 진행 방향 우측으로 [offsetM] 민다. */
class LaneDepartureInjector(
    private val projection: LocalProjection,
    private val offsetM: Float = 25f,
    private val durationMs: Long = 5_000L,
) {
    private var startedAtMs: Long? = null

    fun trigger(nowMs: Long) {
        startedAtMs = nowMs
    }

    fun isActive(nowMs: Long): Boolean = startedAtMs?.let { nowMs - it < durationMs } ?: false

    fun apply(fix: VehicleFix, nowMs: Long): VehicleFix {
        if (!isActive(nowMs)) {
            startedAtMs = null
            return fix
        }
        val h = Math.toRadians((fix.headingDeg ?: 0f).toDouble())
        // 나침반 heading h 의 우측 단위벡터 (x=동, z=북) = (cos h, −sin h)
        val p = projection.toLocal(fix.lat, fix.lon)
        val ll = projection.toLatLon(p.x + offsetM * cos(h), p.z - offsetM * sin(h))
        return fix.copy(lat = ll.lat, lon = ll.lon)
    }
}
