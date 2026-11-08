package com.whitemap.app.util

import com.whitemap.app.data.LandmarkEntity
import com.whitemap.app.data.PointEntity
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 記録した道と目印を GPX 1.1 形式で書き出す。 */
object GpxWriter {
    fun write(out: Writer, points: List<PointEntity>, landmarks: List<LandmarkEntity>) {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        out.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        out.appendLine(
            """<gpx version="1.1" creator="WhiteMap" xmlns="http://www.topografix.com/GPX/1/1">"""
        )
        for (l in landmarks) {
            out.appendLine("""  <wpt lat="${l.lat}" lon="${l.lon}">""")
            out.appendLine("""    <time>${iso.format(Date(l.createdAt))}</time>""")
            out.appendLine("""    <name>${escape(l.name)}</name>""")
            out.appendLine("""  </wpt>""")
        }
        points.groupBy { it.trackId }.forEach { (trackId, pts) ->
            out.appendLine("""  <trk><name>trip $trackId</name><trkseg>""")
            for (p in pts) {
                out.appendLine(
                    """    <trkpt lat="${p.lat}" lon="${p.lon}"><time>${iso.format(Date(p.time))}</time></trkpt>"""
                )
            }
            out.appendLine("""  </trkseg></trk>""")
        }
        out.appendLine("</gpx>")
        out.flush()
    }

    fun escape(s: String): String = buildString {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }
}
