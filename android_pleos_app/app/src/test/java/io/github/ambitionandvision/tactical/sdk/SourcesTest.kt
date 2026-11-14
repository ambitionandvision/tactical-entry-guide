package io.github.ambitionandvision.tactical.sdk

import io.github.ambitionandvision.tactical.engine.TestZones
import io.github.ambitionandvision.tactical.model.FixSource
import io.github.ambitionandvision.tactical.model.FuelKind
import io.github.ambitionandvision.tactical.model.LocalPoint
import io.github.ambitionandvision.tactical.model.VehicleFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class SourcesTest {

    private val proj = TestZones.projection
    private val path = listOf(0f to 600f, 0f to 0f)
    private val track = GpxTrack.parse(GpxTestData.build(proj, path, { 10.0 }, stopSec = 3).byteInputStream())

    @Test
    fun fuelKindRules() {
        assertEquals(FuelKind.EV, FuelKindRules.decide(evSocSupported = true, fuelSupported = false))
        assertEquals(FuelKind.PHEV, FuelKindRules.decide(evSocSupported = true, fuelSupported = true))
        assertEquals(FuelKind.ICE, FuelKindRules.decide(evSocSupported = false, fuelSupported = true))
        assertEquals(FuelKind.UNKNOWN, FuelKindRules.decide(evSocSupported = false, fuelSupported = false))
    }

    @Test
    fun speedEstimatorConvergesAndRejectsJumps() {
        val est = SpeedEstimator()
        var v = 0f
        for (i in 0..30) {
            val ll = proj.toLatLon(0.0, i * 10.0)
            v = est.update(ll.lat, ll.lon, i * 1000L)
        }
        assertEquals(36f, v, 1f)
        val jump = proj.toLatLon(0.0, 310.0 + 500.0) // 1초에 500m: 이상치
        assertEquals(v, est.update(jump.lat, jump.lon, 31_000L), 1e-3f)
        val ok = proj.toLatLon(0.0, 320.0) // 기준점(300m)에서 2초에 20m
        assertEquals(36f, est.update(ok.lat, ok.lon, 32_000L), 1.5f)
    }

    @Test
    fun speedEstimatorLimitsAcceleration() {
        val est = SpeedEstimator(alpha = 1f, maxAccelMps2 = 6f)
        val a = proj.toLatLon(0.0, 0.0); val b = proj.toLatLon(0.0, 30.0)
        est.update(a.lat, a.lon, 0)
        // 정지 상태에서 1초에 30m(108km/h) 튐 → 6m/s 로 제한
        assertEquals(6f * 3.6f, est.update(b.lat, b.lon, 1000), 0.1f)
    }

    @Test
    fun replayEmitsAllPointsWithRecordedIntervalsAnd2x() {
        val sch = FakeScheduler()
        var finished = false
        val r = GpxReplaySource(track, sch) { finished = true }
        val fixes = ArrayList<VehicleFix>()
        r.start { fixes.add(it) }
        assertEquals(1, fixes.size)
        sch.advance(10_000)
        assertEquals(11, fixes.size)
        r.pause()
        sch.advance(10_000)
        assertEquals(11, fixes.size)
        r.set2x(true)
        r.play()
        sch.advance(5_000)
        assertEquals(22, fixes.size)
        assertTrue(sch.delays.takeLast(5).all { it == 500L })
        sch.advance(1_000_000)
        assertTrue(finished)
        assertFalse(r.playing)
        assertEquals(track.points.size, fixes.size)
        assertEquals(FixSource.GPX_REPLAY, fixes[0].source)
        assertEquals(36f, fixes[5].speedKmh, 0.1f) // <speed> 확장값 사용
        assertEquals(180f, fixes[5].headingDeg!!, 0.5f) // 남쪽 진행
        // 타임스탬프는 트랙 시간 (2배속이어도 1초 간격)
        assertEquals(1000L, fixes[15].timestampMs - fixes[14].timestampMs)
    }

    @Test
    fun replayStopResumesWhereItLeft() {
        val sch = FakeScheduler()
        val r = GpxReplaySource(track, sch)
        val fixes = ArrayList<VehicleFix>()
        r.start { fixes.add(it) }
        sch.advance(4_000)
        r.stop()
        sch.advance(4_000)
        assertEquals(5, fixes.size)
        r.start { fixes.add(it) }
        assertEquals(6, fixes.size)
        assertEquals(fixes[4].timestampMs + 1000L, fixes[5].timestampMs)
    }

    @Test
    fun sliderMapsDistanceOntoTrack() {
        val sch = FakeScheduler()
        val entry = LocalPoint(0f, 0f)
        val s = ManualDistanceSource(track, proj, entry, sch)
        for (d in listOf(500f, 350f, 299f, 100f, 30f, 0f)) {
            val f = s.fixFor(d)
            val p = proj.toLocal(f.lat, f.lon)
            assertEquals("slider $d", d, hypot(p.x - entry.x, p.z - entry.z), 0.6f)
            assertEquals(FixSource.MANUAL_SLIDER, f.source)
        }
    }

    @Test
    fun sliderRepeatsFixEverySecond() {
        val sch = FakeScheduler()
        val s = ManualDistanceSource(track, proj, LocalPoint(0f, 0f), sch)
        val fixes = ArrayList<VehicleFix>()
        s.start { fixes.add(it) }
        s.setDistance(20f)
        sch.advance(3_000)
        assertEquals(5, fixes.size)
        assertEquals(3_000L, fixes.last().timestampMs)
        s.stop()
        sch.advance(3_000)
        assertEquals(5, fixes.size)
    }

    @Test
    fun laneDepartureOffsets25mToTheRightFor5s() {
        val inj = LaneDepartureInjector(proj)
        val base = TestZones.fix(0f, 200f, 30f, 0L, heading = 180f) // 남쪽 진행 → 우측 = 서쪽
        assertEquals(base, inj.apply(base, 0L))
        inj.trigger(1_000L)
        val shifted = proj.toLocal(inj.apply(base, 2_000L).lat, inj.apply(base, 2_000L).lon)
        assertEquals(-25f, shifted.x, 0.05f)
        assertEquals(200f, shifted.z, 0.05f)
        val north = TestZones.fix(0f, 200f, 30f, 0L, heading = 0f) // 북쪽 진행 → 우측 = 동쪽
        assertEquals(25f, proj.toLocal(inj.apply(north, 5_999L).lat, inj.apply(north, 5_999L).lon).x, 0.05f)
        assertEquals(base, inj.apply(base, 6_000L))
    }
}
