package io.github.ambitionandvision.tactical.engine

import io.github.ambitionandvision.tactical.model.GuidePoint
import io.github.ambitionandvision.tactical.model.LocalPoint
import io.github.ambitionandvision.tactical.model.TriggerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class LocalSplineSnapperTest {

    private val cfg = TriggerConfig()

    /** 북→남 직선 (0,200)→(0,0). 횡오프셋은 x 로 준다. */
    private val straight = LocalSplineSnapper(
        listOf(GuidePoint("Path_01", 0f, 200f), GuidePoint("Path_02", 0f, 100f), GuidePoint("Path_03", 0f, 0f)),
        cfg,
    )

    @Test
    fun straightLineLengthAndSpacing() {
        assertEquals(200f, straight.totalLengthM, 0.05f)
        val s = straight.samples
        for (i in 1 until s.size - 1) {
            assertEquals(2f, hypot(s[i].x - s[i - 1].x, s[i].z - s[i - 1].z), 0.02f)
        }
        s.forEach { assertEquals(0f, it.x, 1e-3f) }
    }

    @Test
    fun snapBoundaryValuesWithHysteresis() {
        straight.reset()
        val a = straight.snap(14.9f, 100f)
        assertTrue(a.attached); assertFalse(a.offRouteWarning)
        assertEquals(14.9f, a.lateralOffsetM, 1e-3f)
        assertEquals(100f, a.progressM, 0.05f)
        assertEquals(100f, a.remainingM, 0.05f)
        assertEquals(180f, a.headingDeg, 0.1f) // 남쪽으로 진행

        val b = straight.snap(15.1f, 100f)
        assertFalse(b.attached); assertFalse("15~20m 는 이전(경고 없음) 유지", b.offRouteWarning)

        val c = straight.snap(19.9f, 100f)
        assertFalse(c.attached); assertFalse(c.offRouteWarning)

        val d = straight.snap(20.1f, 100f)
        assertFalse(d.attached); assertTrue(d.offRouteWarning)

        val e = straight.snap(19.9f, 100f)
        assertTrue("15~20m 는 이전(경고) 유지", e.offRouteWarning)
        val f = straight.snap(15.1f, 100f)
        assertTrue(f.offRouteWarning); assertFalse(f.attached)

        val g = straight.snap(14.9f, 100f)
        assertTrue(g.attached); assertFalse(g.offRouteWarning)
    }

    @Test
    fun exactBoundaries() {
        straight.reset()
        assertTrue(straight.snap(15f, 50f).attached)
        assertTrue(straight.snap(20f, 50f).offRouteWarning)
    }

    @Test
    fun splinePassesThroughAllControlPoints() {
        val control = listOf(
            LocalPoint(-306f, 416f), LocalPoint(-159f, 220f), LocalPoint(-133f, 190f), LocalPoint(-89f, 173f),
            LocalPoint(40f, 140f), LocalPoint(84f, 120f), LocalPoint(54f, -9f), LocalPoint(51f, -21f), LocalPoint(0f, 0f),
        )
        val dense = CentripetalCatmullRom.resample(control, 0.05)
        for (c in control) {
            val dmin = dense.minOf { hypot(it.x - c.x, it.z - c.z) }
            assertTrue("제어점 $c 에서 ${dmin}m", dmin < 0.05f)
        }
        assertEquals(control.first().x, dense.first().x, 1e-3f)
        assertEquals(control.last().z, dense.last().z, 1e-3f)
    }

    @Test
    fun centripetalHasNoCuspOnUnevenSpacing() {
        // 균일 Catmull-Rom 은 간격이 크게 다르면 루프·첨점이 생긴다. centripetal 은 생기지 않아야 한다.
        val control = listOf(LocalPoint(0f, 0f), LocalPoint(1f, 1f), LocalPoint(100f, 1f), LocalPoint(101f, 0f))
        val s = CentripetalCatmullRom.resample(control, 0.5)
        for (i in 2 until s.size) {
            val ax = s[i - 1].x - s[i - 2].x; val az = s[i - 1].z - s[i - 2].z
            val bx = s[i].x - s[i - 1].x; val bz = s[i].z - s[i - 1].z
            assertTrue("진행 방향 역전 at $i", ax * bx + az * bz > -1e-4f)
        }
    }

    @Test
    fun prefersRecentProgressOverParallelLeg() {
        // 25m 떨어진 왕복(헤어핀) 유도선. 1차 구간 주행 중 GNSS 튐이 2차 구간 쪽으로 가도 앞뒤로 점프하지 않는다.
        val hairpin = LocalSplineSnapper(
            listOf(
                GuidePoint("P1", 0f, 0f), GuidePoint("P2", 0f, 50f), GuidePoint("P3", 0f, 100f),
                GuidePoint("P4", 25f, 100f), GuidePoint("P5", 25f, 50f), GuidePoint("P6", 25f, 0f),
            ),
            cfg,
        )
        val first = hairpin.snap(0f, 28f)
        assertEquals(28f, first.progressM, 0.5f)
        val jumped = hairpin.snap(13f, 30f) // 2차 구간까지 12m, 1차 구간까지 13m
        assertTrue("직전 진행거리 근처 유지: ${jumped.progressM}", jumped.progressM < 40f)
        assertEquals(13f, jumped.lateralOffsetM, 0.2f)

        // 이력이 없으면 가장 가까운 구간(2차)을 고른다.
        hairpin.reset()
        assertTrue(hairpin.snap(13f, 30f).progressM > 150f)
    }

    @Test
    fun reacquiresGloballyWhenLocalWindowLost() {
        straight.reset()
        straight.snap(0f, 190f)
        val far = straight.snap(1f, 20f) // 창 밖(170m 앞)으로 이동해도 전역 탐색으로 다시 흡착
        assertTrue(far.attached)
        assertEquals(180f, far.progressM, 0.1f)
    }
}
