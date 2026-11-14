package io.github.ambitionandvision.tactical.sdk

import io.github.ambitionandvision.tactical.engine.LocalSplineSnapper
import io.github.ambitionandvision.tactical.model.FixSource
import io.github.ambitionandvision.tactical.model.LocalPoint
import io.github.ambitionandvision.tactical.model.LocalProjection
import io.github.ambitionandvision.tactical.model.LocationSource
import io.github.ambitionandvision.tactical.model.VehicleFix
import kotlin.math.hypot

/**
 * 데모 거리 슬라이더(500~0m). 슬라이더 값 = 활성 입구까지의 직선 거리이며, 트랙을 진행 순서로 따라가
 * 처음으로 그 거리 안에 들어오는 지점(두 점 사이 선형 보간)을 측위로 낸다.
 * 정차 유지 판정이 흐르도록 시작 후에는 1초마다 같은 지점을 다시 낸다.
 */
class ManualDistanceSource(
    track: GpxTrack,
    private val projection: LocalProjection,
    entry: LocalPoint,
    private val scheduler: FixScheduler,
) : LocationSource {

    private class Node(val x: Float, val z: Float, val dist: Float, val speedKmh: Float)

    private val nodes: List<Node> = track.points.mapIndexed { i, p ->
        val l = projection.toLocal(p.lat, p.lon)
        Node(l.x, l.z, hypot(l.x - entry.x, l.z - entry.z), track.speedsMps[i] * 3.6f)
    }

    private var listener: ((VehicleFix) -> Unit)? = null
    var distanceM: Float = 500f
        private set

    override fun start(listener: (VehicleFix) -> Unit) {
        this.listener = listener
        scheduler.cancelAll()
        emitAndReschedule()
    }

    override fun stop() {
        scheduler.cancelAll()
        listener = null
    }

    fun setDistance(distanceM: Float) {
        this.distanceM = distanceM.coerceIn(0f, 500f)
        listener?.invoke(fixFor(this.distanceM))
    }

    private fun emitAndReschedule() {
        val l = listener ?: return
        l(fixFor(distanceM))
        scheduler.postDelayed(1000L) { emitAndReschedule() }
    }

    fun fixFor(distanceM: Float): VehicleFix {
        require(nodes.isNotEmpty()) { "트랙이 비어 있다" }
        val i = nodes.indexOfFirst { it.dist <= distanceM }
        val (x, z, speed, heading) = when {
            i < 0 -> nodes.last().let { Quad(it.x, it.z, it.speedKmh, headingBetween(nodes.size - 2, nodes.size - 1)) }
            i == 0 -> nodes[0].let { Quad(it.x, it.z, it.speedKmh, headingBetween(0, 1)) }
            else -> {
                val a = nodes[i - 1]; val b = nodes[i]
                val span = a.dist - b.dist
                val u = if (span > 0f) ((a.dist - distanceM) / span).coerceIn(0f, 1f) else 1f
                Quad(a.x + (b.x - a.x) * u, a.z + (b.z - a.z) * u, a.speedKmh + (b.speedKmh - a.speedKmh) * u, headingBetween(i - 1, i))
            }
        }
        val ll = projection.toLatLon(x.toDouble(), z.toDouble())
        return VehicleFix(ll.lat, ll.lon, heading, speed, scheduler.nowMs(), FixSource.MANUAL_SLIDER)
    }

    private data class Quad(val x: Float, val z: Float, val speed: Float, val heading: Float?)

    private fun headingBetween(a: Int, b: Int): Float? {
        if (a < 0 || b >= nodes.size || a == b) return null
        val dx = nodes[b].x - nodes[a].x
        val dz = nodes[b].z - nodes[a].z
        return if (dx == 0f && dz == 0f) null else LocalSplineSnapper.headingOf(dx, dz)
    }
}
