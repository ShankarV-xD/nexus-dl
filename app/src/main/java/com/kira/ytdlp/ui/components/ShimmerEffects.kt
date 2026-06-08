package com.kira.ytdlp.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Creates a shimmer brush for loading placeholders.
 * 
 * @param colors List of colors for the shimmer effect (default: dark theme optimized)
 * @return Brush that can be applied as background
 */
@Composable
fun shimmerBrush(
    colors: List<Color> = listOf(
        Color(0xFF1A2433),
        Color(0xFF2A3A4D),
        Color(0xFF1A2433)
    )
): Brush {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnimation = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_translate"
    )

    return Brush.linearGradient(
        colors = colors,
        start = Offset(x = translateAnimation.value - 200f, y = 0f),
        end = Offset(x = translateAnimation.value + 200f, y = 0f)
    )
}

/**
 * Shimmer placeholder for a video thumbnail.
 */
@Composable
fun ShimmerThumbnail(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(shimmerBrush())
    )
}

/**
 * Shimmer placeholder for text lines.
 * 
 * @param lines Number of lines to show
 * @param lineHeight Height of each line
 * @param lastLineWidth Percentage width of the last line (0-1)
 */
@Composable
fun ShimmerText(
    lines: Int = 3,
    lineHeight: androidx.compose.ui.unit.Dp = 16.dp,
    lastLineWidth: Float = 0.6f,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        repeat(lines) { index ->
            val isLast = index == lines - 1
            val widthFraction = if (isLast) lastLineWidth else 1f
            Box(
                modifier = Modifier
                    .fillMaxWidth(widthFraction)
                    .height(lineHeight)
                    .clip(RoundedCornerShape(4.dp))
                    .background(shimmerBrush())
            )
        }
    }
}

/**
 * Shimmer placeholder for a format card.
 */
@Composable
fun ShimmerFormatCard(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(shimmerBrush())
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF2A3A4D))
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF2A3A4D))
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF2A3A4D))
            )
        }
    }
}

/**
 * Full shimmer loading screen for video info.
 */
@Composable
fun VideoInfoShimmerLoading(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Thumbnail shimmer
        ShimmerThumbnail(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
        )

        // Title shimmer
        ShimmerText(lines = 2, lineHeight = 20.dp)

        // Info shimmer
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                modifier = Modifier
                    .width(100.dp)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(shimmerBrush())
            )
            Box(
                modifier = Modifier
                    .width(80.dp)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(shimmerBrush())
            )
        }

        // Format cards shimmer
        repeat(5) {
            ShimmerFormatCard()
        }
    }
}
