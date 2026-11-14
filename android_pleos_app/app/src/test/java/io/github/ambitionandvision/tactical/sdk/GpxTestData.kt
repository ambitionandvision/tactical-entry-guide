package io.github.ambitionandvision.tactical.sdk

import io.github.ambitionandvision.tactical.model.LocalProjection
import java.time.Instant
import java.util.Random
import kotlin.math.hypot
import kotlin.math.sqrt

/** 테스트용 합성 GPX 생성기: 로컬 폴리라인을 속도 프로파일로 1Hz 주행, 시간 상관(AR(1)) 노이즈. */
object GpxTestData {

    fun build(
        projection: LocalProjection,
        path: List<Pair<Float, Float>>,
        speedAt: (remainingM: Double) -> Double,
        stopSec: Int = 6,
        noiseSigmaM: Double = 0.0,
        seed: Long = 7L,
        simulated: Boolean = true,
    ): String {
        val cum = DoubleArray(path.size)
        for (i in 1 until path.size) {
            cum[i] = cum[i - 1] + hypot((path[i].first - path[i - 1].first).toDouble(), (path[i].second - path[i - 1].second).toDouble())
        }
        val total = cum.last()
        fun pointAt(s: Double): Pair<Double, Double> {
            var j = 0
            while (j < path.size - 2 && cum[j + 1] < s) j++
            val seg = cum[j + 1] - cum[j]
            val u = if (seg > 0) ((s - cum[j]) / seg).coerceIn(0.0, 1.0) else 0.0
            return Pair(
                path[j].first + (path[j + 1].first - path[j].first) * u,
                path[j].second + (path[j + 1].second - path[j].second) * u,
            )
        }
        val rnd = Random(seed)
        val phi = 0.9
        var nx = 0.0; var nz = 0.0
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
<metadata>${if (simulated) "<keywords>simulated</keywords>" else ""}<desc>unit test</desc></metadata>
<trk><trkseg>
""")
        val t0 = Instant.parse("2026-11-05T01:00:00Z").toEpochMilli()
        var s = 0.0
        var t = 0
        var stopped = 0
        while (true) {
            val v = if (s >= total) 0.0 else speedAt(total - s)
            val (x, z) = pointAt(s)
            nx = phi * nx + sqrt(1 - phi * phi) * noiseSigmaM * rnd.nextGaussian()
            nz = phi * nz + sqrt(1 - phi * phi) * noiseSigmaM * rnd.nextGaussian()
            val ll = projection.toLatLon(x + nx, z + nz)
            sb.append("<trkpt lat=\"%.8f\" lon=\"%.8f\"><ele>50</ele><time>%s</time><extensions><speed>%.2f</speed></extensions></trkpt>\n"
                .format(ll.lat, ll.lon, Instant.ofEpochMilli(t0 + t * 1000L), v))
            if (s >= total) { if (++stopped >= stopSec) break }
            s = (s + v).coerceAtMost(total)
            t++
        }
        sb.append("</trkseg></trk></gpx>\n")
        return sb.toString()
    }
}
