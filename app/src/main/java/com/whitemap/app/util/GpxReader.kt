package com.whitemap.app.util

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.time.OffsetDateTime
import javax.xml.parsers.SAXParserFactory

/** GPX ファイルから読み取った内容。 */
data class GpxData(val tracks: List<List<GpxPoint>>, val waypoints: List<GpxWaypoint>)

data class GpxPoint(val lat: Double, val lon: Double, val time: Long?)

data class GpxWaypoint(val lat: Double, val lon: Double, val name: String, val time: Long?)

/**
 * GPX 1.1 を読む。このアプリが書き出したファイルのほか、他のアプリの GPX（trk / wpt）も読める。
 * trk 1 つを「1 回の旅」として扱う。
 */
object GpxReader {
    fun read(input: InputStream): GpxData {
        val factory = SAXParserFactory.newInstance()
        // 外部ファイルを読みに行くような XML は受け付けない（対応していないパーサーでは無視）
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        val handler = Handler()
        factory.newSAXParser().parse(input, handler)
        return GpxData(handler.tracks, handler.waypoints)
    }

    fun parseTime(text: String): Long? =
        runCatching { OffsetDateTime.parse(text.trim()).toInstant().toEpochMilli() }.getOrNull()

    private class Handler : DefaultHandler() {
        val tracks = ArrayList<List<GpxPoint>>()
        val waypoints = ArrayList<GpxWaypoint>()

        private var track: ArrayList<GpxPoint>? = null
        private var inPoint = false
        private var inWaypoint = false
        private var lat = 0.0
        private var lon = 0.0
        private var valid = false
        private var time: Long? = null
        private var name: String? = null
        private val text = StringBuilder()

        private fun tag(localName: String?, qName: String?): String =
            (if (localName.isNullOrEmpty()) qName.orEmpty() else localName).substringAfter(':')

        override fun startElement(uri: String?, localName: String?, qName: String?, attrs: Attributes) {
            text.setLength(0)
            when (tag(localName, qName)) {
                "trk" -> track = ArrayList()
                "trkpt" -> if (track != null) {
                    inPoint = true
                    readPosition(attrs)
                }
                "wpt" -> {
                    inWaypoint = true
                    readPosition(attrs)
                }
            }
        }

        private fun readPosition(attrs: Attributes) {
            val la = attrs.getValue("lat")?.toDoubleOrNull()
            val lo = attrs.getValue("lon")?.toDoubleOrNull()
            valid = la != null && lo != null && la in -90.0..90.0 && lo in -180.0..180.0
            lat = la ?: 0.0
            lon = lo ?: 0.0
            time = null
            name = null
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            when (tag(localName, qName)) {
                "time" -> if (inPoint || inWaypoint) time = parseTime(text.toString())
                "name" -> if (inWaypoint) name = text.toString().trim()
                "trkpt" -> {
                    if (inPoint && valid) track?.add(GpxPoint(lat, lon, time))
                    inPoint = false
                }
                "wpt" -> {
                    if (valid) waypoints.add(GpxWaypoint(lat, lon, name.orEmpty(), time))
                    inWaypoint = false
                }
                "trk" -> {
                    track?.takeIf { it.isNotEmpty() }?.let { tracks.add(it) }
                    track = null
                }
            }
            text.setLength(0)
        }
    }
}
