package io.github.ambitionandvision.tactical.render

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 호 길이 기준 등간격 재샘플된 경로 (TEAM_WORKSPACE 5.4).
 * [xz] = x,z 반복, [s] = 각 샘플의 누적 호 길이(m). 마지막 샘플은 항상 마지막 제어점이다.
 */
class SampledPath(val xz: FloatArray, val s: FloatArray) {
    val count: Int get() = s.size
    val lengthM: Float get() = if (s.isEmpty()) 0f else s[s.size - 1]

    /** 호 길이 [arc] 위치와 진행 방향 단위벡터를 [out] = (x, z, tx, tz) 로 쓴다. 할당 없음. */
    fun pointAt(arc: Float, out: FloatArray) {
        val n = count
        if (n == 0) { out[0] = 0f; out[1] = 0f; out[2] = 0f; out[3] = 1f; return }
        if (n == 1) { out[0] = xz[0]; out[1] = xz[1]; out[2] = 0f; out[3] = 1f; return }
        val a = arc.coerceIn(0f, lengthM)
        // 등간격이므로 이분 탐색 대신 근사 인덱스에서 보정
        var i = 0
        var lo = 0
        var hi = n - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (s[mid] <= a) { i = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (i >= n - 1) i = n - 2
        val seg = s[i + 1] - s[i]
        val t = if (seg > 1e-6f) (a - s[i]) / seg else 0f
        val x0 = xz[i * 2]; val z0 = xz[i * 2 + 1]
        val x1 = xz[i * 2 + 2]; val z1 = xz[i * 2 + 3]
        out[0] = x0 + (x1 - x0) * t
        out[1] = z0 + (z1 - z0) * t
        val dx = x1 - x0
        val dz = z1 - z0
        val l = sqrt(dx * dx + dz * dz)
        if (l > 1e-6f) { out[2] = dx / l; out[3] = dz / l } else { out[2] = 0f; out[3] = 1f }
    }

    /** 점 (x,z) 에서 가장 가까운 경로상 호 길이. 초기화·저작 시점용(전 구간 탐색). */
    fun nearestArc(x: Float, z: Float): Float {
        var best = Float.MAX_VALUE
        var bestArc = 0f
        for (i in 0 until count - 1) {
            val ax = xz[i * 2]; val az = xz[i * 2 + 1]
            val bx = xz[i * 2 + 2]; val bz = xz[i * 2 + 3]
            val dx = bx - ax; val dz = bz - az
            val l2 = dx * dx + dz * dz
            val t = if (l2 > 1e-9f) (((x - ax) * dx + (z - az) * dz) / l2).coerceIn(0f, 1f) else 0f
            val px = ax + dx * t - x
            val pz = az + dz * t - z
            val d = px * px + pz * pz
            if (d < best) { best = d; bestArc = s[i] + (s[i + 1] - s[i]) * t }
        }
        return bestArc
    }
}

/**
 * 제어점 전체를 지나는 centripetal Catmull-Rom (alpha 0.5).
 * 양 끝은 끝점 반사(P₋₁ = 2P₀ − P₁, Pₙ = 2Pₙ₋₁ − Pₙ₋₂). Python·C# 구현과 같은 식이다.
 */
object CentripetalSpline {
    const val ALPHA = 0.5f
    private const val DENSE_PER_SEGMENT = 32

    /** 세그먼트 i (P[i]→P[i+1]) 위 u∈[0,1] 지점을 [out] = (x, z) 로 쓴다. */
    fun evalSegment(px: FloatArray, pz: FloatArray, i: Int, u: Float, out: FloatArray) {
        val n = px.size
        val x1 = px[i]; val z1 = pz[i]
        val x2 = px[i + 1]; val z2 = pz[i + 1]
        val x0: Float; val z0: Float
        if (i > 0) { x0 = px[i - 1]; z0 = pz[i - 1] } else { x0 = 2f * x1 - x2; z0 = 2f * z1 - z2 }
        val x3: Float; val z3: Float
        if (i + 2 < n) { x3 = px[i + 2]; z3 = pz[i + 2] } else { x3 = 2f * x2 - x1; z3 = 2f * z2 - z1 }

        val t0 = 0f
        val t1 = t0 + knot(x0, z0, x1, z1)
        val t2 = t1 + knot(x1, z1, x2, z2)
        val t3 = t2 + knot(x2, z2, x3, z3)
        val t = t1 + (t2 - t1) * u

        // Barry–Goldman 피라미드
        val ax1 = lerpK(x0, x1, t0, t1, t); val az1 = lerpK(z0, z1, t0, t1, t)
        val ax2 = lerpK(x1, x2, t1, t2, t); val az2 = lerpK(z1, z2, t1, t2, t)
        val ax3 = lerpK(x2, x3, t2, t3, t); val az3 = lerpK(z2, z3, t2, t3, t)
        val b1x = lerpK(ax1, ax2, t0, t2, t); val b1z = lerpK(az1, az2, t0, t2, t)
        val b2x = lerpK(ax2, ax3, t1, t3, t); val b2z = lerpK(az2, az3, t1, t3, t)
        out[0] = lerpK(b1x, b2x, t1, t2, t)
        out[1] = lerpK(b1z, b2z, t1, t2, t)
    }

    /** 제어점 → 호 길이 [spacingM] 간격 재샘플. 제어점이 1개면 그 점 하나. */
    fun sample(px: FloatArray, pz: FloatArray, spacingM: Float = 2f): SampledPath {
        require(px.size == pz.size)
        val n = px.size
        if (n == 0) return SampledPath(FloatArray(0), FloatArray(0))
        if (n == 1) return SampledPath(floatArrayOf(px[0], pz[0]), floatArrayOf(0f))

        // 1) 조밀 샘플
        val denseN = (n - 1) * DENSE_PER_SEGMENT + 1
        val dx = FloatArray(denseN)
        val dz = FloatArray(denseN)
        val ds = FloatArray(denseN)
        val tmp = FloatArray(2)
        var k = 0
        for (i in 0 until n - 1) {
            for (j in 0 until DENSE_PER_SEGMENT) {
                evalSegment(px, pz, i, j.toFloat() / DENSE_PER_SEGMENT, tmp)
                dx[k] = tmp[0]; dz[k] = tmp[1]; k++
            }
        }
        dx[k] = px[n - 1]; dz[k] = pz[n - 1]
        for (i in 1 until denseN) {
            val ex = dx[i] - dx[i - 1]
            val ez = dz[i] - dz[i - 1]
            ds[i] = ds[i - 1] + sqrt(ex * ex + ez * ez)
        }
        val total = ds[denseN - 1]

        // 2) 호 길이 등간격 재샘플 (마지막 점은 끝점)
        val step = max(spacingM, 0.01f)
        val count = (total / step).toInt() + 1 + (if (total % step > 1e-3f) 1 else 0)
        val outXz = FloatArray(count * 2)
        val outS = FloatArray(count)
        var seg = 0
        for (m in 0 until count) {
            val a = min(m * step, total)
            while (seg < denseN - 2 && ds[seg + 1] < a) seg++
            val l = ds[seg + 1] - ds[seg]
            val t = if (l > 1e-6f) ((a - ds[seg]) / l).coerceIn(0f, 1f) else 0f
            outXz[m * 2] = dx[seg] + (dx[seg + 1] - dx[seg]) * t
            outXz[m * 2 + 1] = dz[seg] + (dz[seg + 1] - dz[seg]) * t
            outS[m] = a
        }
        outXz[(count - 1) * 2] = px[n - 1]
        outXz[(count - 1) * 2 + 1] = pz[n - 1]
        outS[count - 1] = total
        return SampledPath(outXz, outS)
    }

    private fun knot(ax: Float, az: Float, bx: Float, bz: Float): Float {
        val dx = bx - ax
        val dz = bz - az
        // |d|^alpha, alpha = 0.5 → sqrt(|d|) = (d²)^0.25. 겹친 점은 0 나눗셈 방지용 최소값.
        val d2 = dx * dx + dz * dz
        return max(sqrt(sqrt(d2)), 1e-4f)
    }

    private fun lerpK(a: Float, b: Float, ta: Float, tb: Float, t: Float): Float {
        val w = tb - ta
        return if (w < 1e-9f) a else a * ((tb - t) / w) + b * ((t - ta) / w)
    }
}
