package io.github.ambitionandvision.tactical.sdk

import io.github.ambitionandvision.tactical.model.LatLon
import io.github.ambitionandvision.tactical.model.LocalProjection
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.InputStream
import java.time.Instant
import java.time.OffsetDateTime
import javax.xml.parsers.DocumentBuilderFactory

data class GpxPoint(
    val lat: Double,
    val lon: Double,
    val ele: Double?,
    val timeMs: Long?,
    /** `<extensions><speed>` (m/s). 없으면 null. */
    val speedMps: Float?,
    /** `<extensions><course>` (deg, 0 = 북). 없으면 null. */
    val courseDeg: Float?,
)

/**
 * GPX 1.1 트랙 (TEAM_WORKSPACE 5.5). `<metadata><keywords>` 에 `simulated` 가 있으면 합성 궤적이다.
 * javax.xml.parsers 만 써서 JVM 단위 테스트에서도 그대로 파싱된다.
 *
 * 스마트폰 GPS 로거 실주행 로그 호환: `<metadata>`·`<extensions>`·고도 없음, 비정수 간격(0.5~3초),
 * 초 미만 시각, 여러 `<trkseg>`(이어 붙임), 중복·역행 시각(뒤 점 버림), 시작·끝 정지 구간.
 * GPX 1.0 처럼 `<speed>`·`<course>` 가 trkpt 바로 아래에 있어도 읽는다.
 */
data class GpxTrack(val points: List<GpxPoint>, val simulated: Boolean, val description: String) {

    /**
     * 점별 속도(m/s). `<speed>` 가 있으면 그 값, 없으면 위치·시간으로 추정한다.
     * 추정은 앞뒤 [SPEED_WINDOW_MS] 창의 이동거리/시간이라 정차 중 GNSS 흔들림(수 m)이 속도로 크게 번지지 않는다.
     */
    val speedsMps: FloatArray by lazy { estimateSpeeds() }

    private fun estimateSpeeds(): FloatArray {
        val n = points.size
        val out = FloatArray(n)
        if (n == 0) return out
        val proj = LocalProjection(LatLon(points[0].lat, points[0].lon))
        val xs = FloatArray(n); val zs = FloatArray(n)
        val ts = LongArray(n)
        for (i in 0 until n) {
            val p = proj.toLocal(points[i].lat, points[i].lon)
            xs[i] = p.x; zs[i] = p.z
            ts[i] = points[i].timeMs ?: (i * 1000L)
        }
        for (i in 0 until n) {
            val recorded = points[i].speedMps
            if (recorded != null) {
                out[i] = recorded
                continue
            }
            var a = i; var b = i
            while (a > 0 && ts[i] - ts[a - 1] <= SPEED_WINDOW_MS) a--
            while (b < n - 1 && ts[b + 1] - ts[i] <= SPEED_WINDOW_MS) b++
            if (a == b) { if (i > 0) a = i - 1 else if (n > 1) b = 1 }
            val dt = (ts[b] - ts[a]) / 1000f
            out[i] = if (dt > 0f) kotlin.math.hypot(xs[b] - xs[a], zs[b] - zs[a]) / dt else 0f
        }
        return out
    }

    /** 화면 표기. 합성 궤적을 실주행처럼 보이게 하지 않는다. */
    val replayLabel: String get() = if (simulated) LABEL_SIMULATED else LABEL_REAL

    companion object {
        const val LABEL_SIMULATED = "GPX 주행 재생 (시뮬레이션)"
        const val LABEL_REAL = "실차 GPX 주행 재생"

        /** 속도 추정 창(앞뒤 각각). */
        const val SPEED_WINDOW_MS = 3_000L

        fun parse(input: InputStream): GpxTrack {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            }
            val doc = factory.newDocumentBuilder().parse(input)
            val root = doc.documentElement

            val metadata = root.children("metadata").firstOrNull()
            val keywords = metadata?.children("keywords")?.firstOrNull()?.textContent.orEmpty()
            val simulated = keywords.split(',', ';', ' ').any { it.trim().equals("simulated", ignoreCase = true) }
            val desc = metadata?.children("desc")?.firstOrNull()?.textContent?.trim().orEmpty()

            val points = ArrayList<GpxPoint>()
            var lastTime: Long? = null
            for (trk in root.children("trk")) for (seg in trk.children("trkseg")) for (pt in seg.children("trkpt")) {
                val lat = pt.getAttribute("lat").toDoubleOrNull() ?: continue
                val lon = pt.getAttribute("lon").toDoubleOrNull() ?: continue
                val time = pt.children("time").firstOrNull()?.textContent?.trim()?.let(::parseTime)
                // 중복·역행 시각은 재생 간격·속도 추정을 망가뜨리므로 버린다.
                if (time != null && lastTime != null && time <= lastTime) continue
                if (time != null) lastTime = time
                val ext = pt.children("extensions").firstOrNull()
                points.add(
                    GpxPoint(
                        lat = lat,
                        lon = lon,
                        ele = pt.children("ele").firstOrNull()?.textContent?.trim()?.toDoubleOrNull(),
                        timeMs = time,
                        speedMps = (ext?.descendant("speed") ?: pt.children("speed").firstOrNull())
                            ?.textContent?.trim()?.toFloatOrNull(),
                        courseDeg = (ext?.descendant("course") ?: pt.children("course").firstOrNull())
                            ?.textContent?.trim()?.toFloatOrNull(),
                    ),
                )
            }
            return GpxTrack(points, simulated, desc)
        }

        private fun parseTime(s: String): Long? =
            runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()
                ?: runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()

        private fun Node.name(): String = localName ?: nodeName.substringAfter(':')

        private fun Element.children(name: String): List<Element> {
            val out = ArrayList<Element>()
            val nodes = childNodes
            for (i in 0 until nodes.length) {
                val n = nodes.item(i)
                if (n is Element && n.name() == name) out.add(n)
            }
            return out
        }

        /** 확장 요소는 `gpxtpx:TrackPointExtension` 처럼 한 단계 더 감싸여 있을 수 있다. */
        private fun Element.descendant(name: String): Element? {
            val nodes = getElementsByTagNameNS("*", name)
            if (nodes.length > 0) return nodes.item(0) as Element
            val plain = getElementsByTagName(name)
            return if (plain.length > 0) plain.item(0) as Element else null
        }
    }
}
