package com.whitemap.app.ui

import android.location.Location
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import com.whitemap.app.data.LandmarkEntity
import com.whitemap.app.util.LocalProjection
import com.whitemap.app.util.RoadNetwork
import com.whitemap.app.util.TrackGeometry
import com.whitemap.app.util.WorldPoint
import kotlin.math.floor
import kotlin.math.max

object MapColors {
    val Paper = Color(0xFFFFFFFF)
    val Grid = Color(0xFFF4F4F2)
    val RoadCasing = Color(0xFFA8A8A8)
    val RoadFill = Color(0xFFFFFFFF)
    val Ink = Color(0xFF222222)
    val InkSoft = Color(0xFF555555)
    val Me = Color(0xFF2B6CB0)
    val MeHalo = Color(0x222B6CB0)
}

/** 道路の太さ（実寸、メートル）。縮小しても細くなりすぎないよう最小幅を設ける。 */
private const val ROAD_FILL_M = 5f
private const val ROAD_CASING_M = 8f

@Composable
fun WhiteMapCanvas(
    camera: MapCamera,
    roads: RoadNetwork,
    landmarks: List<LandmarkEntity>,
    projection: LocalProjection?,
    currentLocation: Location?,
    onUserGesture: () -> Unit,
    onLongPress: (WorldPoint) -> Unit,
    onLandmarkTap: (LandmarkEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val roadPath = remember(roads) {
        Path().apply {
            for (seg in roads.segments) {
                moveTo(seg[0], seg[1])
                var i = 2
                while (i < seg.size) {
                    lineTo(seg[i], seg[i + 1])
                    i += 2
                }
            }
        }
    }
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = remember {
        TextStyle(color = MapColors.Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
    val labels = remember(landmarks) {
        landmarks.associate { it.id to textMeasurer.measure(it.name, labelStyle) }
    }

    val currentLandmarks by rememberUpdatedState(landmarks)
    val currentProjection by rememberUpdatedState(projection)
    val gestureCallback by rememberUpdatedState(onUserGesture)
    val longPressCallback by rememberUpdatedState(onLongPress)
    val landmarkTapCallback by rememberUpdatedState(onLandmarkTap)

    Canvas(
        modifier = modifier
            .pointerInput(camera) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    gestureCallback()
                    val s = size.toSize()
                    if (zoom != 1f) camera.zoomAround(centroid, zoom, s)
                    camera.pan(pan.x, pan.y)
                }
            }
            .pointerInput(camera) {
                detectTapGestures(
                    onDoubleTap = { p ->
                        gestureCallback()
                        camera.zoomAround(p, 2f, size.toSize())
                    },
                    onLongPress = { p -> longPressCallback(camera.screenToWorld(p, size.toSize())) },
                    onTap = { p ->
                        val proj = currentProjection ?: return@detectTapGestures
                        val s = size.toSize()
                        val hitRadius = 28.dp.toPx()
                        currentLandmarks
                            .map { it to (camera.worldToScreen(proj.x(it.lon), proj.y(it.lat), s) - p).getDistance() }
                            .filter { it.second < hitRadius }
                            .minByOrNull { it.second }
                            ?.let { landmarkTapCallback(it.first) }
                    },
                )
            },
    ) {
        drawRect(MapColors.Paper)
        drawGrid(camera)

        // --- 道 ---
        val s = camera.scale
        val fillPx = max(ROAD_FILL_M * s, 2.5.dp.toPx())
        val casingPx = max(ROAD_CASING_M * s, fillPx + 2.dp.toPx())
        withTransform({
            translate(size.width / 2, size.height / 2)
            scale(s, s, pivot = Offset.Zero)
            translate(-camera.x.toFloat(), -camera.y.toFloat())
        }) {
            drawPath(
                roadPath, MapColors.RoadCasing,
                style = Stroke(width = casingPx / s, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            drawPath(
                roadPath, MapColors.RoadFill,
                style = Stroke(width = fillPx / s, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }

        if (projection == null) return@Canvas

        // --- 目印 ---
        for (lm in landmarks) {
            val p = camera.worldToScreen(projection.x(lm.lon), projection.y(lm.lat), size)
            if (p.x < -200 || p.y < -200 || p.x > size.width + 200 || p.y > size.height + 200) continue
            drawCircle(Color.White, radius = 7.dp.toPx(), center = p)
            drawCircle(MapColors.Ink, radius = 5.dp.toPx(), center = p)
            val layout = labels[lm.id] ?: continue
            if (layout.size.width == 0) continue
            val topLeft = Offset(p.x - layout.size.width / 2f, p.y + 9.dp.toPx())
            val pad = 3.dp.toPx()
            drawRoundRect(
                Color.White.copy(alpha = 0.85f),
                topLeft = topLeft - Offset(pad, pad / 2),
                size = Size(layout.size.width + pad * 2, layout.size.height + pad),
                cornerRadius = CornerRadius(pad, pad),
            )
            drawText(layout, topLeft = topLeft)
        }

        // --- 現在地 ---
        if (currentLocation != null) {
            val p = camera.worldToScreen(
                projection.x(currentLocation.longitude), projection.y(currentLocation.latitude), size,
            )
            val accPx = currentLocation.accuracy * s
            if (accPx > 10.dp.toPx()) drawCircle(MapColors.MeHalo, radius = accPx, center = p)
            drawCircle(Color.White, radius = 9.dp.toPx(), center = p)
            drawCircle(MapColors.Me, radius = 6.dp.toPx(), center = p)
        }
    }
}

/** ほとんど見えないくらいの方眼。真っ白な紙の上でも、動かしていることが分かるように。 */
private fun DrawScope.drawGrid(camera: MapCamera) {
    val spacingM = TrackGeometry.niceDistance(160.dp.toPx() / camera.scale.toDouble())
    val spacingPx = (spacingM * camera.scale).toFloat()
    if (spacingPx < 8f) return
    val left = camera.x - size.width / 2 / camera.scale
    val top = camera.y - size.height / 2 / camera.scale
    val startX = ((floor(left / spacingM) * spacingM - camera.x) * camera.scale + size.width / 2).toFloat()
    val startY = ((floor(top / spacingM) * spacingM - camera.y) * camera.scale + size.height / 2).toFloat()
    val stroke = 1.dp.toPx()
    var gx = startX
    while (gx < size.width) {
        drawLine(MapColors.Grid, Offset(gx, 0f), Offset(gx, size.height), stroke)
        gx += spacingPx
    }
    var gy = startY
    while (gy < size.height) {
        drawLine(MapColors.Grid, Offset(0f, gy), Offset(size.width, gy), stroke)
        gy += spacingPx
    }
}
