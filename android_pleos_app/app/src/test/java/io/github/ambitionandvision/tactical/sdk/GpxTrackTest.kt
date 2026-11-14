package io.github.ambitionandvision.tactical.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxTrackTest {

    private fun parse(s: String) = GpxTrack.parse(s.byteInputStream())

    @Test
    fun parsesSimulatedTrackWithExtensions() {
        val t = parse(
            """<?xml version="1.0"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <metadata><keywords>simulated</keywords><desc>합성 궤적</desc></metadata>
              <trk><trkseg>
                <trkpt lat="36.47" lon="127.27"><ele>51.5</ele><time>2026-11-05T01:00:00Z</time>
                  <extensions><speed>13.89</speed><course>135.0</course></extensions></trkpt>
                <trkpt lat="36.4701" lon="127.2701"><time>2026-11-05T01:00:01Z</time></trkpt>
              </trkseg></trk>
            </gpx>""",
        )
        assertTrue(t.simulated)
        assertEquals("GPX 주행 재생 (시뮬레이션)", t.replayLabel)
        assertEquals("합성 궤적", t.description)
        assertEquals(2, t.points.size)
        val p = t.points[0]
        assertEquals(36.47, p.lat, 1e-9); assertEquals(127.27, p.lon, 1e-9)
        assertEquals(51.5, p.ele!!, 1e-9)
        assertEquals(13.89f, p.speedMps!!, 1e-4f); assertEquals(135f, p.courseDeg!!, 1e-4f)
        assertEquals(1000L, t.points[1].timeMs!! - p.timeMs!!)
        assertNull(t.points[1].speedMps)
    }

    @Test
    fun realLogLabelWhenNotSimulated() {
        val t = parse(
            """<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><metadata><keywords>drive, cn8</keywords></metadata>
               <trk><trkseg><trkpt lat="1" lon="2"/></trkseg></trk></gpx>""",
        )
        assertFalse(t.simulated)
        assertEquals("실차 GPX 주행 재생", t.replayLabel)
    }

    @Test
    fun readsGarminStyleNamespacedExtensions() {
        val t = parse(
            """<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"
                    xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v2">
               <trk><trkseg><trkpt lat="1" lon="2"><time>2026-11-05T10:00:00+09:00</time>
               <extensions><gpxtpx:TrackPointExtension><gpxtpx:speed>4.5</gpxtpx:speed><gpxtpx:course>90</gpxtpx:course>
               </gpxtpx:TrackPointExtension></extensions></trkpt></trkseg></trk></gpx>""",
        )
        assertEquals(4.5f, t.points[0].speedMps!!, 1e-4f)
        assertEquals(90f, t.points[0].courseDeg!!, 1e-4f)
        assertEquals(java.time.Instant.parse("2026-11-05T01:00:00Z").toEpochMilli(), t.points[0].timeMs)
    }
}
