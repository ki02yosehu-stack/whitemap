package com.whitemap.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.whitemap.app.util.WorldPoint
import kotlin.math.exp
import kotlin.math.ln

/**
 * 地図の視点。x, y は画面中央のローカル座標（メートル）、scale は 1メートルあたりのピクセル数。
 */
@Stable
class MapCamera(x: Double, y: Double, scale: Float) {
    var x by mutableDoubleStateOf(x)
    var y by mutableDoubleStateOf(y)
    var scale by mutableFloatStateOf(scale.coerceIn(MIN_SCALE, MAX_SCALE))

    fun screenToWorld(p: Offset, size: Size): WorldPoint = WorldPoint(
        x + (p.x - size.width / 2) / scale,
        y + (p.y - size.height / 2) / scale,
    )

    fun worldToScreen(wx: Double, wy: Double, size: Size): Offset = Offset(
        ((wx - x) * scale + size.width / 2).toFloat(),
        ((wy - y) * scale + size.height / 2).toFloat(),
    )

    fun pan(dx: Float, dy: Float) {
        x -= dx / scale
        y -= dy / scale
    }

    /** focus（画面座標）の下にある地点を動かさずに拡大・縮小する。 */
    fun zoomAround(focus: Offset, factor: Float, size: Size) {
        val before = screenToWorld(focus, size)
        scale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        x = before.x - (focus.x - size.width / 2) / scale
        y = before.y - (focus.y - size.height / 2) / scale
    }

    suspend fun animateTo(tx: Double, ty: Double, targetScale: Float = scale) {
        val sx = x
        val sy = y
        val logStart = ln(scale)
        val logEnd = ln(targetScale.coerceIn(MIN_SCALE, MAX_SCALE))
        animate(0f, 1f, animationSpec = tween(450, easing = FastOutSlowInEasing)) { t, _ ->
            x = sx + (tx - sx) * t
            y = sy + (ty - sy) * t
            scale = exp(logStart + (logEnd - logStart) * t)
        }
    }

    companion object {
        /** 約 1px = 1.25km（日本列島が画面に収まる程度）。 */
        const val MIN_SCALE = 0.0008f
        /** 約 1m = 30px（路地の細部まで）。 */
        const val MAX_SCALE = 30f
    }
}
