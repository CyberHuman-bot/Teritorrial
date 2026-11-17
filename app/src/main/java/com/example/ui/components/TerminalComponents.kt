package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurface

@Composable
fun SparklineChart(
    values: List<Float>,
    lineColor: Color,
    maxValue: Float? = null,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val points = if (values.size >= 2) values else listOf(0f, values.firstOrNull() ?: 0f)
        val topValue = (maxValue ?: (points.maxOrNull()?.coerceAtLeast(1f) ?: 100f)).coerceAtLeast(1f)
        val stepX = size.width / (points.size - 1).coerceAtLeast(1)

        val linePath = Path()
        val fillPath = Path()

        points.forEachIndexed { index, raw ->
            val x = index * stepX
            val normalized = (raw / topValue).coerceIn(0f, 1f)
            val y = size.height - (normalized * (size.height - 6f)) - 3f
            if (index == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, size.height)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
            if (index == points.lastIndex) {
                fillPath.lineTo(x, size.height)
                fillPath.close()
            }
        }

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(
                    lineColor.copy(alpha = 0.28f),
                    lineColor.copy(alpha = 0.02f),
                )
            )
        )
        drawPath(
            path = linePath,
            color = lineColor,
            style = Stroke(width = 3.5f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        val lastVal = points.last()
        val lastNorm = (lastVal / topValue).coerceIn(0f, 1f)
        val lastY = size.height - (lastNorm * (size.height - 6f)) - 3f
        drawCircle(
            color = lineColor,
            radius = 5.5f,
            center = Offset(size.width, lastY),
        )
    }
}

@Composable
fun MetricGaugeCard(
    title: String,
    primaryValue: String,
    subtitle: String,
    badgeText: String,
    accentColor: Color,
    icon: ImageVector,
    history: List<Float>,
    maxValue: Float? = null,
    isWarning: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (isWarning) accentColor.copy(alpha = 0.85f) else ObsidianOutline
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(if (isWarning) 1.5.dp else 1.dp, borderColor),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    color = accentColor.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = accentColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }

            Text(
                text = primaryValue,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(2.dp))

            SparklineChart(
                values = history,
                lineColor = accentColor,
                maxValue = maxValue,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            )
        }
    }
}
