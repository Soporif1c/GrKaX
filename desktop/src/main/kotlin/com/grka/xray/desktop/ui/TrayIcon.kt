package com.grka.xray.desktop.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter

/**
 * Menu-bar icon, drawn rather than loaded: a vector Painter scales to whatever
 * the bar asks for, and it avoids shipping a bitmap the build would have to
 * generate. The power glyph mirrors the button on the home screen, filled in
 * when connected so the state is readable at a glance.
 */
class TrayIconPainter(private val connected: Boolean) : Painter() {

    override val intrinsicSize: Size = Size(64f, 64f)

    override fun DrawScope.onDraw() {
        val tint = if (connected) Color(0xFF8B7CFF) else Color(0xFF9AA0B8)
        val stroke = size.minDimension * 0.13f
        val radius = size.minDimension * 0.36f
        val center = Offset(size.width / 2f, size.height / 2f)

        // Ring, left open at the top for the power symbol's gap.
        drawArc(
            color = tint,
            startAngle = -60f,
            sweepAngle = 300f,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2, radius * 2),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )

        drawLine(
            color = tint,
            start = Offset(center.x, center.y - radius * 1.05f),
            end = Offset(center.x, center.y - radius * 0.15f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}
