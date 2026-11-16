package com.whitemap.app.util

import com.whitemap.app.data.LandmarkEntity
import com.whitemap.app.data.PointEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter

class GpxReaderTest {
    private fun read(xml: String) = GpxReader.read(xml.byteInputStream())

    @Test
    fun readsBackWhatTheAppExports() {
        val points = listOf(
            PointEntity(trackId = 1, lat = 35.6812, lon = 139.7671, time = 1_700_000_000_000, accuracy = 5f),
            PointEntity(trackId = 1, lat = 35.68130001, lon = 139.76720002, time = 1_700_000_002_400, accuracy = 5f),
            PointEntity(trackId = 2, lat = 35.7, lon = 139.8, time = 1_700_100_000_000, accuracy = 5f),
        )
        val landmarks = listOf(
            LandmarkEntity(lat = 35.69, lon = 139.77, name = "パン屋 <A&B> \"1\"", createdAt = 1_700_000_500_000),
        )
        val out = StringWriter()
        GpxWriter.write(out, points, landmarks)

        val data = read(out.toString())
        assertEquals(2, data.tracks.size)
        assertEquals(2, data.tracks[0].size)
        // 位置は 1 桁も変わらない。時刻は秒単位に丸められる
        assertEquals(35.68130001, data.tracks[0][1].lat, 0.0)
        assertEquals(139.76720002, data.tracks[0][1].lon, 0.0)
        assertEquals(1_700_000_002_000, data.tracks[0][1].time)
        assertEquals(1_700_100_000_000, data.tracks[1][0].time)
        assertEquals(GpxWaypoint(35.69, 139.77, "パン屋 <A&B> \"1\"", 1_700_000_500_000), data.waypoints.single())
    }

    @Test
    fun readsGpxFromOtherApps() {
        val data = read(
            """<?xml version="1.0"?>
            <gpx version="1.1" creator="other" xmlns="http://www.topografix.com/GPX/1/1">
              <metadata><name>ignored</name><time>2020-01-01T00:00:00Z</time></metadata>
              <wpt lat="35.1" lon="139.1"><ele>12</ele></wpt>
              <trk><name>morning</name>
                <trkseg>
                  <trkpt lat="35.0" lon="139.0"><ele>3.5</ele><time>2026-11-08T10:00:00.250+09:00</time></trkpt>
                  <trkpt lat="35.001" lon="139.0"></trkpt>
                </trkseg>
                <trkseg><trkpt lat="35.002" lon="139.0"/></trkseg>
              </trk>
              <trk><trkseg></trkseg></trk>
            </gpx>"""
        )
        assertEquals("中身のない trk は無視、trkseg はまとめて 1 回の旅", 1, data.tracks.size)
        assertEquals(3, data.tracks[0].size)
        assertEquals(GpxReader.parseTime("2026-11-08T01:00:00.250Z"), data.tracks[0][0].time)
        assertNull(data.tracks[0][1].time)
        assertEquals("", data.waypoints.single().name)
    }

    @Test
    fun skipsBrokenPositions() {
        val data = read(
            """<gpx><trk><trkseg>
                <trkpt lat="abc" lon="139.0"/><trkpt lon="139.0"/><trkpt lat="95" lon="139"/>
                <trkpt lat="35.0" lon="139.0"/>
            </trkseg></trk></gpx>"""
        )
        assertEquals(1, data.tracks.single().size)
    }

    @Test
    fun rejectsFilesThatAreNotGpx() {
        assertTrue(runCatching { read("this is not xml") }.isFailure)
        val other = read("<html><body>hello</body></html>")
        assertTrue(other.tracks.isEmpty() && other.waypoints.isEmpty())
    }
}
