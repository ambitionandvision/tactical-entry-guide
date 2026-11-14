package io.github.ambitionandvision.tactical.render

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderMathTest {

    private val near = 0.5f

    @Test
    fun clipFullyInFrontKeepsPolygon() {
        val src = floatArrayOf(0f, 0f, 2f, 1f, 0f, 3f, 0f, 1f, 4f)
        val dst = FloatArray(6 * 3)
        assertEquals(3, RenderMath.clipPolygonNear(src, 3, near, dst))
        // 시작점 순환만 다를 수 있으므로 정점 집합이 같음을 본다
        assertArrayEquals(src, dst.copyOf(9), 0f)
    }

    @Test
    fun clipFullyBehindRemovesPolygon() {
        val src = floatArrayOf(0f, 0f, -2f, 1f, 0f, 0.2f, 0f, 1f, 0.49f)
        assertEquals(0, RenderMath.clipPolygonNear(src, 3, near, FloatArray(18)))
    }

    @Test
    fun clipTriangleWithOneVertexBehindMakesQuadOnNearPlane() {
        // 정점 A 만 근평면 뒤
        val src = floatArrayOf(0f, 0f, -1f, 2f, 0f, 2f, -2f, 0f, 2f)
        val dst = FloatArray(18)
        val n = RenderMath.clipPolygonNear(src, 3, near, dst)
        assertEquals(4, n)
        var onPlane = 0
        for (i in 0 until n) {
            assertTrue(dst[i * 3 + 2] >= near - 1e-6f)
            if (kotlin.math.abs(dst[i * 3 + 2] - near) < 1e-6f) onPlane++
        }
        assertEquals(2, onPlane)
        // 교점 A→B: t = (0.5 - -1)/(2 - -1) = 0.5 → x = 1
        val xs = (0 until n).filter { kotlin.math.abs(dst[it * 3 + 2] - near) < 1e-6f }.map { dst[it * 3] }.sorted()
        assertEquals(-1f, xs[0], 1e-5f)
        assertEquals(1f, xs[1], 1e-5f)
    }

    @Test
    fun clipConcavePolygonCrossingPlaneSeveralTimes() {
        // 톱니 모양: 앞(z=3)·뒤(z=-1)가 번갈아 6개 → 교점 6개 + 앞 정점 3개 = 9 (1.5n)
        val src = floatArrayOf(
            0f, 0f, 3f, 1f, 0f, -1f, 2f, 0f, 3f, 3f, 0f, -1f, 4f, 0f, 3f, 5f, 0f, -1f,
        )
        val dst = FloatArray(6 * 2 * 3)
        val n = RenderMath.clipPolygonNear(src, 6, near, dst)
        assertEquals(9, n)
        for (i in 0 until n) assertTrue(dst[i * 3 + 2] >= near - 1e-6f)
    }

    @Test
    fun clipSegment() {
        val s = floatArrayOf(0f, 0f, -1f, 0f, 3f, 3f)
        assertTrue(RenderMath.clipSegmentNear(s, near))
        assertEquals(near, s[2], 1e-6f)
        assertEquals(1.5f * 3f / 4f, s[1], 1e-5f) // t = 1.5/4
        val behind = floatArrayOf(0f, 0f, -1f, 0f, 0f, 0.1f)
        assertFalse(RenderMath.clipSegmentNear(behind, near))
    }

    @Test
    fun projectCenterAndOffsets() {
        val out = FloatArray(2)
        RenderMath.project(0f, 0f, 10f, 500f, 400f, 300f, out, 0)
        assertArrayEquals(floatArrayOf(400f, 300f), out, 1e-4f)
        RenderMath.project(1f, 1f, 10f, 500f, 400f, 300f, out, 0)
        assertArrayEquals(floatArrayOf(450f, 250f), out, 1e-4f) // 위(+y) → 화면 위(작은 y)
    }

    @Test
    fun cameraBasisLookingNorthPutsEastOnTheRight() {
        val b = CameraBasis()
        b.lookAt(0f, 0f, 0f, 0f, 0f, 10f)
        val c = FloatArray(3)
        b.toCamera(5f, 0f, 10f, c, 0)
        assertTrue("동쪽 점은 카메라 x>0", c[0] > 0f)
        assertEquals(10f, c[2], 1e-5f)
        b.toCamera(0f, 3f, 10f, c, 0)
        assertTrue("위쪽 점은 카메라 y>0", c[1] > 0f)
        // 거의 수직으로 내려다봐도 퇴화하지 않는다
        b.lookAt(0f, 100f, 0f, 0f, 0f, 0f)
        b.toCamera(10f, 0f, 0f, c, 0)
        assertTrue(c[0].isFinite() && c[1].isFinite() && c[2] > 0f)
    }

    @Test
    fun backFaceAndFrustum() {
        // 북쪽을 향한 벽(법선 +z). 북쪽에 있는 눈은 앞면, 남쪽 눈은 뒷면.
        assertFalse(RenderMath.isBackFacing(0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 10f))
        assertTrue(RenderMath.isBackFacing(0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, -10f))

        val tan = 0.577f
        assertTrue(RenderMath.sphereInFrustum(0f, 0f, 50f, 1f, near, 1000f, tan, tan))
        assertFalse(RenderMath.sphereInFrustum(0f, 0f, -5f, 1f, near, 1000f, tan, tan))
        assertFalse(RenderMath.sphereInFrustum(0f, 0f, 2000f, 10f, near, 1000f, tan, tan))
        assertFalse(RenderMath.sphereInFrustum(100f, 0f, 50f, 5f, near, 1000f, tan, tan))
        assertTrue("가장자리에 걸친 구", RenderMath.sphereInFrustum(30f, 0f, 50f, 5f, near, 1000f, tan, tan))
        assertTrue("카메라를 감싸는 구", RenderMath.sphereInFrustum(0f, 0f, 0f, 20f, near, 1000f, tan, tan))
    }

    @Test
    fun sortDescendingByKey() {
        val keys = floatArrayOf(5f, 1f, 9f, 3f, 7f, 7f, -2f)
        val idx = intArrayOf(0, 1, 2, 3, 4, 5, 6)
        RenderMath.sortIndicesByKeyDesc(idx, 0, idx.size, keys)
        for (i in 1 until idx.size) assertTrue(keys[idx[i - 1]] >= keys[idx[i]])
        assertEquals(2, idx[0])
        assertEquals(6, idx[6])
        // 부분 범위만 정렬
        val idx2 = intArrayOf(6, 1, 0, 2)
        RenderMath.sortIndicesByKeyDesc(idx2, 1, 4, keys)
        assertArrayEquals(intArrayOf(6, 2, 0, 1), idx2)
    }

    @Test
    fun signedAreaAndReverse() {
        val sq = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)
        val a = RenderMath.signedArea2(sq, 0, 4)
        assertEquals(200f, kotlin.math.abs(a), 1e-4f)
        RenderMath.reverse2(sq, 0, 4)
        assertEquals(-a, RenderMath.signedArea2(sq, 0, 4), 1e-4f)
    }

    @Test
    fun easingAndAngles() {
        assertEquals(0f, RenderMath.easeInOutCubic(0f), 0f)
        assertEquals(1f, RenderMath.easeInOutCubic(1f), 0f)
        assertEquals(0.5f, RenderMath.easeInOutCubic(0.5f), 1e-6f)
        assertEquals(1f, RenderMath.easeInOutCubic(3f), 0f)
        assertEquals(0f, RenderMath.lerpAngleDeg(350f, 10f, 0.5f), 1e-4f)
        assertEquals(90f, RenderMath.headingOf(1f, 0f), 1e-4f)
        assertEquals(180f, RenderMath.headingOf(0f, -1f), 1e-4f)
    }
}
