package io.github.ambitionandvision.tactical.render

import kotlin.math.min
import kotlin.math.sqrt

/**
 * 램프(경사 쐐기) 표현용 순수 수학. Android 의존 없음. 빌드 시점(메인 스레드)에만 할당한다.
 * [enforceBefore] 만 매 프레임 호출되며 할당하지 않는다.
 */
object RampGeometry {

    /** 이 값보다 법선 y 가 크면 경사 윗면으로 본다 (경사 60도 미만). */
    const val TOP_NY = 0.5f
    /** 수평 성분이 이보다 작으면 평지로 보고 오르막 표시를 만들지 않는다 (경사 약 2% 미만). */
    const val MIN_SLOPE_H = 0.02f
    const val CHEVRON_SPACING_M = 12f
    const val CHEVRON_FIRST_M = 7f
    const val CHEVRON_END_MARGIN_M = 5f
    /** 윗면에서 법선 방향으로 띄우는 높이(m). */
    const val CHEVRON_LIFT_M = 0.06f

    /**
     * 윗면 다각형들([tops]: 각 원소는 x,y,z 반복, 월드 좌표)에서 오르막 방향(수평 단위 벡터)을 구한다.
     * 넓이 가중 평균 법선의 수평 성분 반대 방향이다. 평지면 null.
     */
    fun uphillDir(tops: List<FloatArray>): FloatArray? {
        var nx = 0f; var ny = 0f; var nz = 0f
        val n3 = FloatArray(3)
        for (p in tops) {
            newell(p, n3)
            // 위를 향하도록 뒤집어 더한다 (감기 방향과 무관)
            val s = if (n3[1] < 0f) -1f else 1f
            nx += n3[0] * s; ny += n3[1] * s; nz += n3[2] * s
        }
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        if (len < 1e-9f) return null
        nx /= len; nz /= len
        val h = sqrt(nx * nx + nz * nz)
        if (h < MIN_SLOPE_H) return null
        return floatArrayOf(-nx / h, -nz / h)
    }

    /**
     * 윗면 위에 놓을 정적 오르막 쉐브론 다각형들을 만든다. 각 원소는 [shape] 정점 수 × (x,y,z).
     * 쉐브론은 램프 폭 중앙에 [CHEVRON_SPACING_M] 간격으로 놓이고, 정점마다 그 아래 윗면의 평면 높이를 따른다.
     * 정점이 윗면 밖으로 나가는 쉐브론은 만들지 않는다.
     *
     * @param shape (along, across) 반복. along 이 오르막 방향.
     */
    fun chevrons(tops: List<FloatArray>, shape: FloatArray): List<FloatArray> {
        val dir = uphillDir(tops) ?: return emptyList()
        val ux = dir[0]; val uz = dir[1]
        // 가로 방향(오르막 기준 오른쪽) = (uz, -ux)
        val ax = uz; val az = -ux
        var s0 = Float.MAX_VALUE; var s1 = -Float.MAX_VALUE
        var t0 = Float.MAX_VALUE; var t1 = -Float.MAX_VALUE
        for (p in tops) {
            for (i in 0 until p.size / 3) {
                val s = p[i * 3] * ux + p[i * 3 + 2] * uz
                val t = p[i * 3] * ax + p[i * 3 + 2] * az
                if (s < s0) s0 = s; if (s > s1) s1 = s
                if (t < t0) t0 = t; if (t > t1) t1 = t
            }
        }
        val width = t1 - t0
        if (width < 1f || s1 - s0 < CHEVRON_FIRST_M + CHEVRON_END_MARGIN_M) return emptyList()
        var shapeHalf = 0f
        for (j in 0 until shape.size / 2) shapeHalf = maxOf(shapeHalf, kotlin.math.abs(shape[j * 2 + 1]))
        // 쉐브론 폭 = 램프 폭의 60%, 원래 크기의 1.4배를 넘지 않는다.
        val k = min(1.4f, width * 0.3f / shapeHalf)
        val tm = (t0 + t1) / 2f
        val planes = tops.map { planeOf(it) }
        val out = ArrayList<FloatArray>()
        val nv = shape.size / 2
        var s = s0 + CHEVRON_FIRST_M
        while (s <= s1 - CHEVRON_END_MARGIN_M + 1e-3f) {
            val poly = FloatArray(nv * 3)
            var ok = true
            for (j in 0 until nv) {
                val a = shape[j * 2] * k
                val c = shape[j * 2 + 1] * k
                val x = ux * (s + a) + ax * (tm + c)
                val z = uz * (s + a) + az * (tm + c)
                val f = faceAt(tops, x, z)
                if (f < 0) { ok = false; break }
                val pl = planes[f]
                // 평면: n·(p - c) = 0 → y
                val y = pl[4] - (pl[0] * (x - pl[3]) + pl[2] * (z - pl[5])) / pl[1]
                poly[j * 3] = x + pl[0] * CHEVRON_LIFT_M
                poly[j * 3 + 1] = y + pl[1] * CHEVRON_LIFT_M
                poly[j * 3 + 2] = z + pl[2] * CHEVRON_LIFT_M
            }
            if (ok) out.add(poly)
            s += CHEVRON_SPACING_M
        }
        return out
    }

    /** (x, z) 를 xz 투영으로 포함하는 윗면의 인덱스. 없으면 -1. */
    fun faceAt(tops: List<FloatArray>, x: Float, z: Float): Int {
        for (f in tops.indices) if (containsXZ(tops[f], x, z)) return f
        return -1
    }

    /** xz 평면에서 점이 다각형 안(경계 포함에 가까운 허용 오차)인지. 짝홀 규칙. */
    fun containsXZ(poly: FloatArray, x: Float, z: Float): Boolean {
        val n = poly.size / 3
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = poly[i * 3]; val zi = poly[i * 3 + 2]
            val xj = poly[j * 3]; val zj = poly[j * 3 + 2]
            if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** 위를 향하는 단위 법선 nx,ny,nz 와 정점 평균 cx,cy,cz. */
    private fun planeOf(p: FloatArray): FloatArray {
        val n3 = FloatArray(3)
        newell(p, n3)
        val len = sqrt(n3[0] * n3[0] + n3[1] * n3[1] + n3[2] * n3[2])
        val s = (if (n3[1] < 0f) -1f else 1f) / (if (len < 1e-9f) 1f else len)
        val n = p.size / 3
        var cx = 0f; var cy = 0f; var cz = 0f
        for (i in 0 until n) { cx += p[i * 3]; cy += p[i * 3 + 1]; cz += p[i * 3 + 2] }
        return floatArrayOf(n3[0] * s, n3[1] * s, n3[2] * s, cx / n, cy / n, cz / n)
    }

    /** Newell 법선(정규화 전, 길이 = 넓이×2). */
    private fun newell(p: FloatArray, out: FloatArray) {
        val n = p.size / 3
        var nx = 0f; var ny = 0f; var nz = 0f
        for (i in 0 until n) {
            val a = i * 3
            val b = ((i + 1) % n) * 3
            nx += (p[a + 1] - p[b + 1]) * (p[a + 2] + p[b + 2])
            ny += (p[a + 2] - p[b + 2]) * (p[a] + p[b])
            nz += (p[a] - p[b]) * (p[a + 1] + p[b + 1])
        }
        out[0] = nx; out[1] = ny; out[2] = nz
    }

    /**
     * 그리기 순서 [order] 의 앞 [n] 개에서 [first] 가 [second] 보다 먼저 오게 한다 (먼저 = 더 멀리 = 먼저 칠함).
     * 이미 그렇거나 둘 중 하나가 없으면 그대로 둔다. 어긋나 있으면 [mover] (둘 중 하나)만 옮겨 상대 바로 옆에 둔다.
     * 나머지 원소의 상대 순서는 바뀌지 않는다. 할당하지 않는다.
     * @return 순서를 바꿨으면 true
     */
    fun enforceBefore(order: IntArray, n: Int, first: Int, second: Int, mover: Int): Boolean {
        var iFirst = -1
        var iSecond = -1
        for (i in 0 until n) {
            if (order[i] == first) iFirst = i else if (order[i] == second) iSecond = i
        }
        if (iFirst < 0 || iSecond < 0 || iFirst < iSecond) return false
        // 현재 second(iSecond) ... first(iFirst)
        if (mover == first) {
            for (i in iFirst downTo iSecond + 1) order[i] = order[i - 1]
            order[iSecond] = first
        } else {
            for (i in iSecond until iFirst) order[i] = order[i + 1]
            order[iFirst] = second
        }
        return true
    }
}
