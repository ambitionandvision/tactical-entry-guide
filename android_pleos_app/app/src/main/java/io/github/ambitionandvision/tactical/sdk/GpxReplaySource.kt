package io.github.ambitionandvision.tactical.sdk

import io.github.ambitionandvision.tactical.engine.LocalSplineSnapper
import io.github.ambitionandvision.tactical.model.FixSource
import io.github.ambitionandvision.tactical.model.LatLon
import io.github.ambitionandvision.tactical.model.LocalProjection
import io.github.ambitionandvision.tactical.model.LocationSource
import io.github.ambitionandvision.tactical.model.VehicleFix

/**
 * GPX 트랙을 기록된 시간 간격대로 재생한다. 재생/일시정지/2배속. stop() 후 start() 는 이어서 재생한다.
 *
 * 측위 타임스탬프는 트랙 시간이다(2배속에서도 상태머신의 정차 유지 시간이 트랙 기준으로 유지된다).
 * 속도는 [GpxTrack.speedsMps] (`<speed>` 확장, 없으면 위치·시간 창 추정) 이다.
 */
class GpxReplaySource(
    val track: GpxTrack,
    private val scheduler: FixScheduler,
    private val onFinished: () -> Unit = {},
) : LocationSource {

    private var listener: ((VehicleFix) -> Unit)? = null
    private var index = 0
    private var lastFixMs = 0L

    var playing = false
        private set
    var speed2x = false
        private set

    val positionIndex: Int get() = index
    val isFinished: Boolean get() = index >= track.points.size

    override fun start(listener: (VehicleFix) -> Unit) {
        this.listener = listener
        play()
    }

    override fun stop() {
        pause()
        listener = null
    }

    fun play() {
        if (playing || listener == null || track.points.isEmpty()) return
        if (isFinished) rewind()
        playing = true
        scheduler.cancelAll()
        tick()
    }

    fun pause() {
        playing = false
        scheduler.cancelAll()
    }

    fun set2x(enabled: Boolean) {
        speed2x = enabled
    }

    fun rewind() {
        index = 0
    }

    /** 다음 점 하나를 즉시 내보낸다. 테스트와 디버그용. 끝났으면 false. */
    fun step(): Boolean {
        if (isFinished) return false
        listener?.invoke(fixAt(index))
        index++
        return true
    }

    private fun tick() {
        if (!playing) return
        if (!step()) {
            playing = false
            onFinished()
            return
        }
        if (isFinished) {
            playing = false
            onFinished()
            return
        }
        val gap = intervalMs(index - 1, index)
        scheduler.postDelayed(if (speed2x) gap / 2 else gap) { tick() }
    }

    private fun intervalMs(a: Int, b: Int): Long {
        val ta = track.points[a].timeMs
        val tb = track.points[b].timeMs
        return if (ta != null && tb != null && tb > ta) (tb - ta).coerceAtMost(10_000L) else 1000L
    }

    fun fixAt(i: Int): VehicleFix {
        val p = track.points[i]
        val ts = p.timeMs ?: (lastFixMs + 1000L)
        lastFixMs = ts
        return VehicleFix(
            lat = p.lat,
            lon = p.lon,
            headingDeg = p.courseDeg ?: headingAt(i),
            speedKmh = track.speedsMps[i] * 3.6f,
            timestampMs = ts,
            source = FixSource.GPX_REPLAY,
        )
    }

    private fun headingAt(i: Int): Float? {
        val pts = track.points
        if (pts.size < 2) return null
        val a = pts[if (i == 0) 0 else i - 1]
        val b = pts[if (i == 0) 1 else i]
        val d = LocalProjection(LatLon(a.lat, a.lon)).toLocal(b.lat, b.lon)
        if (d.x == 0f && d.z == 0f) return null
        return LocalSplineSnapper.headingOf(d.x, d.z)
    }
}
