package com.tactical.app.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Geometry scale for compact phone layouts.
 *
 * The reference design is 400 dp wide. Narrower app windows shrink large
 * controls and gutters proportionally so the same composition fits on phones
 * with a smaller available width. Wider windows do not make controls
 * disproportionately large; they simply get more breathing room.
 */
@Immutable
data class ResponsiveUi(
    val scale: Float,
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    val sectionSpacing: Dp,
    val smallSpacing: Dp,
    val controlHeight: Dp,
    val pttOuter: Dp,
    val pttRingOuter: Dp,
    val pttRingInner: Dp,
    val pttCore: Dp,
    val emergencyOuter: Dp,
    val emergencyRingOuter: Dp,
    val emergencyRingInner: Dp,
    val emergencyCore: Dp,
    val cardPadding: Dp,
    val compactIcon: Dp
) {
    fun dp(value: Dp): Dp = value * scale

    fun sp(value: Float): TextUnit = (value * scale).sp
}

@Composable
fun ResponsiveScreen(
    modifier: Modifier = Modifier,
    content: @Composable (ResponsiveUi) -> Unit
) {
    BoxWithConstraints(modifier = modifier) {
        val dimensions = remember(maxWidth) {
            responsiveUiForWidth(maxWidth)
        }
        content(dimensions)
    }
}

private fun responsiveUiForWidth(width: Dp): ResponsiveUi {
    val scale = (width.value / 400f).coerceIn(0.78f, 1f)

    fun d(value: Float): Dp = (value * scale).dp

    return ResponsiveUi(
        scale = scale,
        horizontalPadding = d(20f),
        verticalPadding = d(10f),
        sectionSpacing = d(12f),
        smallSpacing = d(8f),
        controlHeight = d(50f),
        pttOuter = minOf(d(222f), width * 0.62f),
        pttRingOuter = minOf(d(202f), width * 0.56f),
        pttRingInner = minOf(d(216f), width * 0.60f),
        pttCore = minOf(d(186f), width * 0.52f),
        emergencyOuter = minOf(d(176f), width * 0.50f),
        emergencyRingOuter = minOf(d(164f), width * 0.46f),
        emergencyRingInner = minOf(d(148f), width * 0.42f),
        emergencyCore = minOf(d(124f), width * 0.35f),
        cardPadding = d(14f),
        compactIcon = d(21f)
    )
}
