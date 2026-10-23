package io.github.unknownprocess.wristle.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

// @keep(pacodelavega): Curved arc page indicator positioned along the bottom bezel of round Wear OS displays

@Composable
fun CurvedBottomPageIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier,
    activeColor: Color = Color(0xFF14B8A6), // Vibrant Samsung Teal
    inactiveColor: Color = Color(0x44FFFFFF)
) {
    if (pageCount <= 1) return

    Canvas(modifier = modifier.fillMaxSize()) {
        val strokeWidth = 3.5.dp.toPx()
        // Radius matches the circular bezel of the 40mm/44mm Galaxy Watch display with comfortable inset
        val diameter = size.minDimension - 14.dp.toPx()
        val arcTopLeft = Offset(
            (size.width - diameter) / 2f,
            (size.height - diameter) / 2f
        )
        val arcSize = Size(diameter, diameter)

        // Arc centered exactly at the bottom of the circular display (90 degrees)
        val totalSpan = 44f  // Total degrees span across the bottom
        val gap = 6f         // Gap between arc segments in degrees
        val segmentSpan = (totalSpan - (gap * (pageCount - 1))) / pageCount
        val startBase = 90f - (totalSpan / 2f)

        for (i in 0 until pageCount) {
            // Reverse index so page 0 is on the left and page (N-1) is on the right
            val segmentIndex = (pageCount - 1) - i
            val startAngle = startBase + segmentIndex * (segmentSpan + gap)
            val isSelected = (i == currentPage)

            drawArc(
                color = if (isSelected) activeColor else inactiveColor,
                startAngle = startAngle,
                sweepAngle = segmentSpan,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(
                    width = if (isSelected) strokeWidth * 1.35f else strokeWidth,
                    cap = StrokeCap.Round
                )
            )
        }
    }
}
