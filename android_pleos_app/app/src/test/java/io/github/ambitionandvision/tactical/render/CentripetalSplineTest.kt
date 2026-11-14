package io.github.ambitionandvision.tactical.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class CentripetalSplineTest {

    private val px = floatArrayOf(-306f, -159f, -133f, -89f, 40f, 84f, 54f, 0f)
    private val pz = floatArrayOf(416f, 220f, 190f, 173f, 140f, 120f, -9f, 0f)

    @Test
    fun passesThroughEveryControlPoint() {
        val out = FloatArray(2)
        for (i in 0 until px.size - 1) {
            CentripetalSpline.evalSegment(px, pz, i, 0f, out)
            assertEquals(px[i], out[0], 1e-3f)
            assertEquals(pz[i], out[1], 1e-3f)
            CentripetalSpline.evalSegment(px, pz, i, 1f, out)
            assertEquals(px[i + 1], out[0], 1e-3f)
            assertEquals(pz[i + 1], out[1], 1e-3f)
        }
    }

    @Test
    fun resampledEveryTwoMetresAlongArc() {
        val p = CentripetalSpline.sample(px, pz, 2f)
        assertEquals(px[0], p.xz[0], 1e-4f)
        assertEquals(pz[0], p.xz[1], 1e-4f)
        assertEquals(px.last(), p.xz[(p.count - 1) * 2], 1e-4f)
        assertEquals(pz.last(), p.xz[(p.count - 1) * 2 + 1], 1e-4f)
        for (i in 1 until p.count) {
            val d = hypot((p.xz[i * 2] - p.xz[i * 2 - 2]).toDouble(), (p.xz[i * 2 + 1] - p.xz[i * 2 - 1]).toDouble())
            assertTrue("샘플 간격 $d", d <= 2.0 + 1e-3)
            if (i < p.count - 1) assertEquals(2f, p.s[i] - p.s[i - 1], 1e-3f)
        }
        // 제어점 직선 연결 길이 이상
        var poly = 0.0
        for (i in 1 until px.size) poly += hypot((px[i] - px[i - 1]).toDouble(), (pz[i] - pz[i - 1]).toDouble())
        assertTrue(p.lengthM >= poly - 1.0)
    }

    @Test
    fun duplicateAndTwoPointInputsAreSafe() {
        val two = CentripetalSpline.sample(floatArrayOf(0f, 0f), floatArrayOf(0f, 10f), 2f)
        assertEquals(6, two.count)
        assertEquals(10f, two.lengthM, 1e-3f)
        val dup = CentripetalSpline.sample(floatArrayOf(0f, 0f, 0f, 5f), floatArrayOf(0f, 0f, 5f, 5f), 2f)
        for (v in dup.xz) assertTrue(v.isFinite())
        assertEquals(1, CentripetalSpline.sample(floatArrayOf(3f), floatArrayOf(4f)).count)
    }

    @Test
    fun pointAtAndNearestArc() {
        val p = CentripetalSpline.sample(floatArrayOf(0f, 0f), floatArrayOf(0f, 100f), 2f)
        val o = FloatArray(4)
        p.pointAt(51f, o)
        assertEquals(0f, o[0], 1e-4f)
        assertEquals(51f, o[1], 1e-3f)
        assertEquals(1f, o[3], 1e-4f) // 북쪽 진행
        assertEquals(30f, p.nearestArc(7f, 30f), 1e-3f)
    }
}
