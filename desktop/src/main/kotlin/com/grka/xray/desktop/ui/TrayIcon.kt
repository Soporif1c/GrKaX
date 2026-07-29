package com.grka.xray.desktop.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter

/**
 * Menu-bar icon: the app's own mark, the shield with the bolt cut out of it,
 * drawn rather than loaded so it stays sharp at whatever size the bar asks for.
 *
 * The dark plate the app icon sits on is deliberately left out. The menu bar
 * supplies its own background and follows the system theme, so a filled square
 * would read as a blob on it; the shield alone carries the identity. Connected
 * wears the icon's violet-to-cyan gradient, idle goes flat grey.
 *
 * Geometry is lifted from docs/icon.svg — same 108×108 artboard, so the two
 * stay recognisably one mark. Keep them in step if the icon is redrawn.
 */
class TrayIconPainter(private val connected: Boolean) : Painter() {

    override val intrinsicSize: Size = Size(64f, 64f)

    override fun DrawScope.onDraw() {
        // The shield spans 34..74 across and 26..82 down, so its middle is at
        // (54, 54) — the artboard centre, which makes the placement symmetric.
        val k = size.minDimension * 0.94f / SHIELD_HEIGHT
        translate(
            left = size.width / 2f - SHIELD_CENTRE * k,
            top = size.height / 2f - SHIELD_CENTRE * k,
        ) {
            scale(k, k, pivot = Offset.Zero) {
                drawPath(shieldWithBolt, fill)
                drawPath(shieldOutline, SolidColor(edge), style = Stroke(width = 3f))
            }
        }
    }

    private val fill: Brush
        get() = if (connected) {
            Brush.linearGradient(
                colors = listOf(Color(0xFF8B7CFF), Color(0xFF22D3EE)),
                start = Offset(34f, 26f),
                end = Offset(74f, 82f),
            )
        } else {
            SolidColor(Color(0xFF9AA0B8))
        }

    private val edge: Color
        get() = if (connected) Color(0xFF22D3EE) else Color(0xFF9AA0B8)

    private companion object {
        const val SHIELD_HEIGHT = 56f
        const val SHIELD_CENTRE = 54f

        /** The shield outline alone, for the stroke the app icon carries. */
        val shieldOutline = Path().apply {
            moveTo(54f, 26f)
            lineTo(74f, 34f)
            lineTo(74f, 54f)
            cubicTo(74f, 68f, 65f, 78f, 54f, 82f)
            cubicTo(43f, 78f, 34f, 68f, 34f, 54f)
            lineTo(34f, 34f)
            close()
        }

        /**
         * Shield and bolt in one path with an even-odd fill, so the bolt comes
         * out as a hole. Painting it in the background colour instead would
         * only work while we know what that colour is — in the menu bar we do
         * not.
         */
        val shieldWithBolt = Path().apply {
            addPath(shieldOutline)
            moveTo(56f, 38f)
            lineTo(46f, 56f)
            lineTo(53f, 56f)
            lineTo(51f, 70f)
            lineTo(62f, 50f)
            lineTo(55f, 50f)
            close()
            fillType = PathFillType.EvenOdd
        }
    }
}
