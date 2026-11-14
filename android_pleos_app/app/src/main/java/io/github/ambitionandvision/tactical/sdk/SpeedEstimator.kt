package io.github.ambitionandvision.tactical.sdk

import io.github.ambitionandvision.tactical.model.LocalProjection
import io.github.ambitionandvision.tactical.model.LatLon
import kotlin.math.hypot

/**
 * Pleos LocationData 에는 속도 필드가 없으므로 연속 측위에서 속도를 추정한다.
 *
 * - 순간 속도 = 이동거리 / 시간차, 지수이동평균(EMA)으로 평활
 * - 이상치 제한: 시간차가 0 이하이거나 [maxGapMs] 초과면 기준점만 갱신, [maxSpeedKmh] 초과는 버림,
 *   직전 추정 대비 [maxAccelMps2] 이상의 변화는 그 한도로 자른다(GNSS 튐 방지).
 */
class SpeedEstimator(
    private val alpha: Float = 0.4f,
    private val maxSpeedKmh: Float = 200f,
    private val maxAccelMps2: Float = 6f,
    private val maxGapMs: Long = 10_000L,
) {
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var lastMs = 0L
    private var hasLast = false
    private var emaMps = 0f

    val speedKmh: Float get() = emaMps * 3.6f

    fun reset() {
        hasLast = false
        emaMps = 0f
    }

    fun update(lat: Double, lon: Double, timestampMs: Long): Float {
        if (!hasLast) {
            lastLat = lat; lastLon = lon; lastMs = timestampMs; hasLast = true
            return speedKmh
        }
        val dtMs = timestampMs - lastMs
        if (dtMs <= 0L) return speedKmh
        if (dtMs > maxGapMs) {
            lastLat = lat; lastLon = lon; lastMs = timestampMs
            return speedKmh
        }
        val local = LocalProjection(LatLon(lastLat, lastLon)).toLocal(lat, lon)
        val dt = dtMs / 1000f
        val raw = hypot(local.x, local.z) / dt
        if (raw * 3.6f > maxSpeedKmh) return speedKmh // 튐: 기준점을 옮기지 않는다
        val maxStep = maxAccelMps2 * dt
        val limited = raw.coerceIn(emaMps - maxStep, emaMps + maxStep).coerceAtLeast(0f)
        emaMps += alpha * (limited - emaMps)
        lastLat = lat; lastLon = lon; lastMs = timestampMs
        return speedKmh
    }
}
