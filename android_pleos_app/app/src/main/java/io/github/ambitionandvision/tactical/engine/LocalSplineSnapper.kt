package io.github.ambitionandvision.tactical.engine

import io.github.ambitionandvision.tactical.model.GuidePoint
import io.github.ambitionandvision.tactical.model.LocalPoint
import io.github.ambitionandvision.tactical.model.SnapResult
import io.github.ambitionandvision.tactical.model.TriggerConfig
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * TEAM_WORKSPACE 5.4: 제어점 전체를 지나는 centripetal Catmull-Rom (alpha 0.5),
 * 양 끝은 끝점 반사(P[-1] = 2·P0 − P1), 호 길이 기준 [spacingM] 간격 재샘플.
 * Python(extract_shp_to_tactical.py)·C#(Unity) 구현과 같은 식이다.
 */
object CentripetalCatmullRom {
    const val ALPHA = 0.5
    const val DEFAULT_SPACING_M = 2.0

    /** 세그먼트당 조밀 샘플 수. 호 길이 적분용이며 결과는 [spacingM] 간격으로 다시 고른다. */
    private const val DENSE_PER_SEGMENT = 64

    fun resample(control: List<LocalPoint>, spacingM: Double = DEFAULT_SPACING_M): List<LocalPoint> {
        val pts = control.map { doubleArrayOf(it.x.toDouble(), it.z.toDouble()) }
            .fold(mutableListOf<DoubleArray>()) { acc, p ->
                // 같은 점이 연속하면 매개변수 간격이 0 이 되므로 제거한다.
                if (acc.isEmpty() || hypot(acc.last()[0] - p[0], acc.last()[1] - p[1]) > 1e-6) acc.add(p)
                acc
            }
        if (pts.isEmpty()) return emptyList()
        if (pts.size == 1) return listOf(LocalPoint(pts[0][0].toFloat(), pts[0][1].toFloat()))

        val n = pts.size
        val ext = ArrayList<DoubleArray>(n + 2)
        ext.add(doubleArrayOf(2 * pts[0][0] - pts[1][0], 2 * pts[0][1] - pts[1][1]))
        ext.addAll(pts)
        ext.add(doubleArrayOf(2 * pts[n - 1][0] - pts[n - 2][0], 2 * pts[n - 1][1] - pts[n - 2][1]))

        val dense = ArrayList<DoubleArray>()
        dense.add(pts[0])
        for (i in 0 until n - 1) {
            val p0 = ext[i]; val p1 = ext[i + 1]; val p2 = ext[i + 2]; val p3 = ext[i + 3]
            val t0 = 0.0
            val t1 = t0 + knot(p0, p1)
            val t2 = t1 + knot(p1, p2)
            val t3 = t2 + knot(p2, p3)
            for (k in 1..DENSE_PER_SEGMENT) {
                val t = t1 + (t2 - t1) * k / DENSE_PER_SEGMENT
                dense.add(barryGoldman(p0, p1, p2, p3, t0, t1, t2, t3, t))
            }
        }

        val cum = DoubleArray(dense.size)
        for (i in 1 until dense.size) {
            cum[i] = cum[i - 1] + hypot(dense[i][0] - dense[i - 1][0], dense[i][1] - dense[i - 1][1])
        }
        val total = cum.last()
        val out = ArrayList<LocalPoint>()
        var j = 0
        var s = 0.0
        while (s < total) {
            while (j < dense.size - 2 && cum[j + 1] < s) j++
            val segLen = cum[j + 1] - cum[j]
            val u = if (segLen > 0) (s - cum[j]) / segLen else 0.0
            out.add(
                LocalPoint(
                    (dense[j][0] + (dense[j + 1][0] - dense[j][0]) * u).toFloat(),
                    (dense[j][1] + (dense[j + 1][1] - dense[j][1]) * u).toFloat(),
                ),
            )
            s += spacingM
        }
        val last = dense.last()
        val lastOut = out.last()
        if (hypot(lastOut.x - last[0], lastOut.z - last[1]) > 1e-3) out.add(LocalPoint(last[0].toFloat(), last[1].toFloat()))
        return out
    }

    private fun knot(a: DoubleArray, b: DoubleArray): Double =
        max(hypot(b[0] - a[0], b[1] - a[1]).pow(ALPHA), 1e-6)

    private fun barryGoldman(
        p0: DoubleArray, p1: DoubleArray, p2: DoubleArray, p3: DoubleArray,
        t0: Double, t1: Double, t2: Double, t3: Double, t: Double,
    ): DoubleArray {
        fun lerp(a: DoubleArray, b: DoubleArray, ta: Double, tb: Double): DoubleArray {
            val wa = (tb - t) / (tb - ta)
            val wb = (t - ta) / (tb - ta)
            return doubleArrayOf(wa * a[0] + wb * b[0], wa * a[1] + wb * b[1])
        }
        val a1 = lerp(p0, p1, t0, t1)
        val a2 = lerp(p1, p2, t1, t2)
        val a3 = lerp(p2, p3, t2, t3)
        val b1 = lerp(a1, a2, t0, t2)
        val b2 = lerp(a2, a3, t1, t3)
        return lerp(b1, b2, t1, t2)
    }
}

/**
 * 유도선 스플라인에 차량 위치를 투영한다.
 *
 * - 흡착: 횡거리 ≤ snapDistM(15m)
 * - 경고: 횡거리 ≥ offRouteWarnDistM(20m). 15~20m 사이는 직전 경고 상태를 유지한다(히스테리시스).
 * - 역주행 점프 방지: 직전 진행거리 근처 창을 먼저 찾고, 거기서 흡착이 안 될 때만 전 구간을 찾는다.
 *
 * headingDeg 는 나침반 기준(0 = 북, 90 = 동)이다. 단일 스레드에서 호출한다.
 */
class LocalSplineSnapper(controlPoints: List<GuidePoint>, private val cfg: TriggerConfig) {

    val samples: List<LocalPoint> = CentripetalCatmullRom.resample(controlPoints.map { LocalPoint(it.x, it.z) })

    private val cumulative: FloatArray = FloatArray(samples.size).also { c ->
        for (i in 1 until samples.size) {
            c[i] = c[i - 1] + hypot(samples[i].x - samples[i - 1].x, samples[i].z - samples[i - 1].z)
        }
    }

    val totalLengthM: Float = if (cumulative.isEmpty()) 0f else cumulative.last()

    init {
        require(samples.isNotEmpty()) { "유도선 제어점이 비어 있다" }
    }

    private var lastProgressM: Float? = null
    private var warning = false

    fun reset() {
        lastProgressM = null
        warning = false
    }

    fun snap(x: Float, z: Float): SnapResult {
        val local = lastProgressM?.let { p -> nearest(x, z, p - WINDOW_BACK_M, p + WINDOW_AHEAD_M) }
        val hit = if (local != null && local.dist <= cfg.snapDistM) local else {
            val global = nearest(x, z, 0f, totalLengthM)
            if (local != null && local.dist <= global.dist + 1e-3f) local else global
        }

        val d = hit.dist
        warning = when {
            d >= cfg.offRouteWarnDistM -> true
            d <= cfg.snapDistM -> false
            else -> warning
        }
        lastProgressM = hit.progress

        return SnapResult(
            snappedX = hit.x,
            snappedZ = hit.z,
            headingDeg = hit.headingDeg,
            lateralOffsetM = d,
            progressM = hit.progress,
            remainingM = max(0f, totalLengthM - hit.progress),
            attached = d <= cfg.snapDistM,
            offRouteWarning = warning,
        )
    }

    /** 상태를 바꾸지 않는 순수 투영. 핀의 진행거리 계산 등에 쓴다. */
    fun project(x: Float, z: Float): SnapResult {
        val hit = nearest(x, z, 0f, totalLengthM)
        return SnapResult(
            hit.x, hit.z, hit.headingDeg, hit.dist, hit.progress,
            max(0f, totalLengthM - hit.progress), hit.dist <= cfg.snapDistM, hit.dist >= cfg.offRouteWarnDistM,
        )
    }

    private class Hit(val x: Float, val z: Float, val dist: Float, val progress: Float, val headingDeg: Float)

    private fun nearest(px: Float, pz: Float, fromM: Float, toM: Float): Hit {
        if (samples.size == 1) {
            val p = samples[0]
            return Hit(p.x, p.z, hypot(px - p.x, pz - p.z), 0f, 0f)
        }
        var best: Hit? = null
        var bestD2 = Float.MAX_VALUE
        for (i in 0 until samples.size - 1) {
            if (cumulative[i + 1] < fromM || cumulative[i] > toM) continue
            val a = samples[i]; val b = samples[i + 1]
            val dx = b.x - a.x; val dz = b.z - a.z
            val len2 = dx * dx + dz * dz
            val u = if (len2 > 0f) (((px - a.x) * dx + (pz - a.z) * dz) / len2).coerceIn(0f, 1f) else 0f
            val qx = a.x + dx * u; val qz = a.z + dz * u
            val d2 = (px - qx) * (px - qx) + (pz - qz) * (pz - qz)
            if (d2 < bestD2) {
                bestD2 = d2
                best = Hit(qx, qz, 0f, cumulative[i] + sqrt(len2) * u, headingOf(dx, dz))
            }
        }
        val h = best ?: return nearest(px, pz, 0f, totalLengthM)
        return Hit(h.x, h.z, sqrt(bestD2), h.progress, h.headingDeg)
    }

    companion object {
        /** 직전 진행거리 기준 우선 탐색 창. 1Hz 측위에서 50km/h(≈14m/s)·GNSS 튐을 넉넉히 덮는다. */
        const val WINDOW_BACK_M = 30f
        const val WINDOW_AHEAD_M = 80f

        fun headingOf(dx: Float, dz: Float): Float {
            val deg = Math.toDegrees(atan2(dx.toDouble(), dz.toDouble())).toFloat()
            return if (deg < 0f) deg + 360f else deg
        }
    }
}
