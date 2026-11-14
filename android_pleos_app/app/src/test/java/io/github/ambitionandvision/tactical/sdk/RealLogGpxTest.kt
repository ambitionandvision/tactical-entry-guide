package io.github.ambitionandvision.tactical.sdk

import io.github.ambitionandvision.tactical.engine.LocalSplineSnapper
import io.github.ambitionandvision.tactical.engine.TacticalStateMachine
import io.github.ambitionandvision.tactical.engine.TestZones
import io.github.ambitionandvision.tactical.model.GuidePoint
import io.github.ambitionandvision.tactical.model.TacticalPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Locale
import java.util.Random
import kotlin.math.hypot

/**
 * 스마트폰 GPS 로거 형태의 실주행 로그 호환 (TEAM_WORKSPACE round2 #6):
 * metadata·extensions·고도 없음, 비정수 간격(0.5~3초), 초 미만 시각, 여러 trkseg, 중복 시각, 시작·끝 정지.
 */
class RealLogGpxTest {

    private val guide = listOf(
        GuidePoint("Path_01", -300f, 400f), GuidePoint("Path_02", -150f, 200f), GuidePoint("Path_03", 40f, 140f),
        GuidePoint("Path_04", 84f, 120f), GuidePoint("Path_05", 54f, -9f), GuidePoint("Path_06", 51f, -21f),
        GuidePoint("Path_07", 20f, -14f), GuidePoint("Path_08", 0f, 0f),
    )
    private val zone = TestZones.zone(guide = guide)

    /** 경로를 실제 시간 간격으로 주행한 폰 로그. */
    private fun phoneLog(): String {
        val path = listOf(-420f to 560f) + LocalSplineSnapper(guide, zone.triggers).samples.map { it.x to it.z }
        val cum = DoubleArray(path.size)
        for (i in 1 until path.size) cum[i] = cum[i - 1] + hypot((path[i].first - path[i - 1].first).toDouble(), (path[i].second - path[i - 1].second).toDouble())
        val total = cum.last()
        fun at(s: Double): Pair<Double, Double> {
            var j = 0
            while (j < path.size - 2 && cum[j + 1] < s) j++
            val u = ((s - cum[j]) / (cum[j + 1] - cum[j])).coerceIn(0.0, 1.0)
            return Pair(path[j].first + (path[j + 1].first - path[j].first) * u, path[j].second + (path[j + 1].second - path[j].second) * u)
        }
        val rnd = Random(11)
        val t0 = Instant.parse("2026-11-20T05:12:03.250Z").toEpochMilli()
        val sb = StringBuilder("""<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="GPSLogger" xmlns="http://www.topografix.com/GPX/1/1">
<trk><name>drive</name><trkseg>
""")
        var tMs = 0L
        var s = 0.0
        var nx = 0.0; var nz = 0.0
        var n = 0
        var stoppedAtEndMs = 0L
        var lastEmitMs = 0L
        fun emit() {
            // 시간 상관 AR(1): 1초당 상관 0.9, 정상 σ 2m (간격이 달라도 같은 물리적 노이즈)
            val phi = Math.pow(0.9, (tMs - lastEmitMs) / 1000.0).coerceAtMost(0.999)
            val k = 2.0 * Math.sqrt(1 - phi * phi)
            lastEmitMs = tMs
            nx = phi * nx + k * rnd.nextGaussian(); nz = phi * nz + k * rnd.nextGaussian()
            val (x, z) = at(s)
            val ll = TestZones.projection.toLatLon(x + nx, z + nz)
            sb.append(String.format(Locale.US, "<trkpt lat=\"%.7f\" lon=\"%.7f\"><time>%s</time></trkpt>\n", ll.lat, ll.lon, Instant.ofEpochMilli(t0 + tMs)))
            if (++n % 40 == 0) sb.append(String.format(Locale.US, "<trkpt lat=\"%.7f\" lon=\"%.7f\"><time>%s</time></trkpt>\n", ll.lat, ll.lon, Instant.ofEpochMilli(t0 + tMs))) // 중복 시각
            if (n == 70) sb.append("</trkseg><trkseg>\n") // 세그먼트 분할
        }
        // 시작 정지 15초
        while (tMs < 15_000) { emit(); tMs += 1000 }
        while (true) {
            emit()
            val dtMs = listOf(500L, 1000L, 1000L, 1500L, 3000L)[rnd.nextInt(5)]
            if (s >= total) {
                stoppedAtEndMs += dtMs
                if (stoppedAtEndMs > 20_000) break
            }
            val rem = total - s
            val v = when { rem > 380 -> 13.9; rem > 40 -> 7.0; else -> maxOf(1.5, rem / 6) }
            s = (s + v * dtMs / 1000.0).coerceAtMost(total)
            tMs += dtMs
        }
        sb.append("</trkseg></trk></gpx>\n")
        return sb.toString()
    }

    @Test
    fun parsesPhoneLogShape() {
        val t = GpxTrack.parse(phoneLog().byteInputStream())
        assertFalse(t.simulated)
        assertEquals("실차 GPX 주행 재생", t.replayLabel)
        assertTrue(t.points.all { it.ele == null && it.speedMps == null })
        for (i in 1 until t.points.size) assertTrue("시각 단조 증가 $i", t.points[i].timeMs!! > t.points[i - 1].timeMs!!)
        assertEquals(250L, t.points[0].timeMs!! % 1000L) // 초 미만 시각 유지
        // 정차 구간(시작 15초, 끝 마지막 15초) 의 추정 속도 중앙값은 GNSS 흔들림에도 3km/h 미만
        val t0 = t.points.first().timeMs!!; val tEnd = t.points.last().timeMs!!
        fun median(idx: List<Int>) = idx.map { t.speedsMps[it] * 3.6f }.sorted().let { it[it.size / 2] }
        val startStop = median(t.points.indices.filter { t.points[it].timeMs!! - t0 < 12_000 })
        val endStop = median(t.points.indices.filter { tEnd - t.points[it].timeMs!! < 15_000 })
        println("[phone-log] 정차 속도 중앙값 start=$startStop end=$endStop km/h")
        assertTrue("시작 정지 $startStop", startStop < 3f)
        assertTrue("끝 정지 $endStop", endStop < 3f)
    }

    @Test
    fun phoneLogReplaysThroughAllPhases() {
        val track = GpxTrack.parse(phoneLog().byteInputStream())
        val sm = TacticalStateMachine(zone)
        val phases = ArrayList<TacticalPhase>()
        var p2 = 0; var attached = 0
        val src = GpxReplaySource(track, FakeScheduler())
        src.start { fix ->
            val f = sm.update(fix)
            if (phases.lastOrNull() != f.phase) phases.add(f.phase)
            if (f.phase == TacticalPhase.PHASE_2_TACTICAL) { p2++; if (f.snap!!.attached) attached++ }
        }
        while (src.step()) Unit
        println("[phone-log] phases=$phases P2=$p2 snap=${attached.toFloat() / p2}")
        assertEquals(listOf(TacticalPhase.PHASE_1_CRUISING, TacticalPhase.PHASE_2_TACTICAL, TacticalPhase.PHASE_3_ARRIVED), phases)
        assertTrue(attached.toFloat() / p2 >= 0.98f)
    }

    @Test
    fun irregularIntervalsAreReplayedAsRecorded() {
        val track = GpxTrack.parse(phoneLog().byteInputStream())
        val sch = FakeScheduler()
        val r = GpxReplaySource(track, sch)
        r.start { }
        sch.advance(200_000)
        val expected = (1 until track.points.size).map { track.points[it].timeMs!! - track.points[it - 1].timeMs!! }
        assertEquals(expected, sch.delays.take(expected.size))
        assertTrue(expected.any { it == 500L } && expected.any { it == 3000L })
    }

    @Test
    fun gpx10DirectSpeedAndNoTimeStillWork() {
        val t = GpxTrack.parse(
            """<gpx version="1.0" xmlns="http://www.topografix.com/GPX/1/0"><trk><trkseg>
               <trkpt lat="36.47" lon="127.27"><speed>5.5</speed><course>10</course></trkpt>
               <trkpt lat="36.4701" lon="127.27"/></trkseg></trk></gpx>""".byteInputStream(),
        )
        assertEquals(5.5f, t.points[0].speedMps!!, 1e-4f)
        assertEquals(10f, t.points[0].courseDeg!!, 1e-4f)
        assertEquals(2, t.speedsMps.size)
        assertEquals("실차 GPX 주행 재생", t.replayLabel)
    }
}
