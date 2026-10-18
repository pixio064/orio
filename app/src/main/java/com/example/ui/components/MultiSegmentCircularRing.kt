package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.usage.AppUsageInfo

data class AppProgressSegment(
    val id: String,
    val appName: String,
    val packageName: String,
    val durationMs: Long,
    val percentOfTotal: Int,
    val color: Color,
    val launchCount: Int = 0
)

object SegmentPalette {
    val EmeraldGreen = Color(0xFF10B981) // WhatsApp
    val RoyalBlue = Color(0xFF3B82F6)    // eFootball / Games
    val CrimsonRed = Color(0xFFEF4444)   // YouTube
    val AmberOrange = Color(0xFFF59E0B)  // Chrome / Web
    val CyanTeal = Color(0xFF06B6D4)     // Social / Messaging
    val PurpleOther = Color(0xFF8B5CF6)  // Other Apps
    val PinkAccent = Color(0xFFEC4899)

    val DefaultColors = listOf(
        EmeraldGreen,
        RoyalBlue,
        CrimsonRed,
        AmberOrange,
        CyanTeal,
        PurpleOther
    )

    fun buildSegments(
        appUsages: List<AppUsageInfo>,
        totalScreenOnMs: Long
    ): List<AppProgressSegment> {
        val nonZeroApps = appUsages.filter { it.totalTimeInForegroundMs > 30 * 1000L }

        if (nonZeroApps.isEmpty() || totalScreenOnMs <= 0) {
            // Realistic default breakdown for interactive exploration (WhatsApp, eFootball™, YouTube, Chrome, Other)
            return listOf(
                AppProgressSegment("com.whatsapp", "WhatsApp", "com.whatsapp", 42 * 60 * 1000L, 35, EmeraldGreen, 18),
                AppProgressSegment("jp.konami.pesam", "eFootball™", "jp.konami.pesam", 30 * 60 * 1000L, 25, RoyalBlue, 6),
                AppProgressSegment("com.google.android.youtube", "YouTube", "com.google.android.youtube", 24 * 60 * 1000L, 20, CrimsonRed, 8),
                AppProgressSegment("com.android.chrome", "Chrome", "com.android.chrome", 14 * 60 * 1000L, 12, AmberOrange, 10),
                AppProgressSegment("other", "Other Apps", "other", 10 * 60 * 1000L, 8, PurpleOther, 12)
            )
        }

        val topApps = nonZeroApps.take(4)
        val segments = mutableListOf<AppProgressSegment>()
        var sumTopMs = 0L

        topApps.forEachIndexed { index, app ->
            val color = when {
                app.packageName.contains("whatsapp", ignoreCase = true) -> EmeraldGreen
                app.packageName.contains("pesam", ignoreCase = true) || app.packageName.contains("efootball", ignoreCase = true) -> RoyalBlue
                app.packageName.contains("youtube", ignoreCase = true) -> CrimsonRed
                app.packageName.contains("chrome", ignoreCase = true) -> AmberOrange
                else -> DefaultColors[index % (DefaultColors.size - 1)]
            }
            val percent = ((app.totalTimeInForegroundMs.toFloat() / totalScreenOnMs) * 100).toInt().coerceAtLeast(1)
            segments.add(
                AppProgressSegment(
                    id = app.packageName,
                    appName = app.appName,
                    packageName = app.packageName,
                    durationMs = app.totalTimeInForegroundMs,
                    percentOfTotal = percent,
                    color = color,
                    launchCount = app.launchCount
                )
            )
            sumTopMs += app.totalTimeInForegroundMs
        }

        // Remaining apps grouped into "Other Apps"
        val remainingMs = totalScreenOnMs - sumTopMs
        if (remainingMs > 60 * 1000L) {
            val remainingPercent = ((remainingMs.toFloat() / totalScreenOnMs) * 100).toInt().coerceAtLeast(1)
            segments.add(
                AppProgressSegment(
                    id = "other",
                    appName = "Other Apps",
                    packageName = "other",
                    durationMs = remainingMs,
                    percentOfTotal = remainingPercent,
                    color = PurpleOther,
                    launchCount = nonZeroApps.drop(4).sumOf { it.launchCount }
                )
            )
        }

        return segments
    }
}

@Composable
fun MultiSegmentCircularProgressRing(
    segments: List<AppProgressSegment>,
    totalScreenOnMinutes: Long,
    dailyGoalMinutes: Int,
    selectedSegment: AppProgressSegment?,
    onSegmentSelected: (AppProgressSegment?) -> Unit,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 16.dp,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
) {
    val goalMins = dailyGoalMinutes.coerceAtLeast(1)
    val progress = (totalScreenOnMinutes.toFloat() / goalMins).coerceIn(0.05f, 1f)

    val animatedOverallProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(800),
        label = "overallProgress"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth()
    ) {
        // The Multi-Segment Canvas Ring with Dynamic Center
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(230.dp)
                .clickable {
                    // Tap on ring cycles through apps or resets
                    if (selectedSegment == null && segments.isNotEmpty()) {
                        onSegmentSelected(segments.first())
                    } else {
                        val nextIdx = segments.indexOf(selectedSegment) + 1
                        if (nextIdx < segments.size) {
                            onSegmentSelected(segments[nextIdx])
                        } else {
                            onSegmentSelected(null)
                        }
                    }
                }
                .testTag("multi_segment_progress_ring")
        ) {
            Canvas(modifier = Modifier.fillMaxSize().padding(strokeWidth / 2 + 4.dp)) {
                val strokePx = strokeWidth.toPx()
                val activeStrokePx = strokePx + 5.dp.toPx()

                // Background Full Track Arc
                drawArc(
                    color = trackColor,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round)
                )

                // Draw each app segment
                if (segments.isNotEmpty()) {
                    val totalDegrees = 360f * animatedOverallProgress
                    val gapDegrees = if (segments.size > 1) 3.5f else 0f
                    val totalGaps = segments.size * gapDegrees
                    val availableAngle = (totalDegrees - totalGaps).coerceAtLeast(0f)

                    var currentAngle = -90f

                    for (segment in segments) {
                        val sweep = ((segment.percentOfTotal / 100f) * availableAngle).coerceAtLeast(4f)
                        val isSelected = selectedSegment?.id == segment.id

                        val arcColor = if (selectedSegment != null && !isSelected) {
                            segment.color.copy(alpha = 0.35f)
                        } else {
                            segment.color
                        }

                        val arcStroke = if (isSelected) activeStrokePx else strokePx

                        drawArc(
                            color = arcColor,
                            startAngle = currentAngle,
                            sweepAngle = sweep,
                            useCenter = false,
                            style = Stroke(width = arcStroke, cap = StrokeCap.Round)
                        )
                        currentAngle += sweep + gapDegrees
                    }
                }
            }

            // Interactive Center Content
            AnimatedContent(
                targetState = selectedSegment,
                transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(200)) },
                label = "centerContent"
            ) { segment ->
                if (segment != null) {
                    // App-specific inspect view
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = segment.color.copy(alpha = 0.2f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(segment.color)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = segment.appName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val mins = (segment.durationMs / (60 * 1000)).toInt()
                        val hrs = mins / 60
                        val remMins = mins % 60
                        Text(
                            text = if (hrs > 0) "${hrs}h ${remMins}m" else "${remMins}m",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = segment.color
                        )
                        Text(
                            text = "${segment.percentOfTotal}% of screen time",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (segment.launchCount > 0) {
                            Text(
                                text = "${segment.launchCount} launches today",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Tap to reset",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                } else {
                    // Overall Total Screen Time View
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(20.dp)
                    ) {
                        val hrs = totalScreenOnMinutes / 60
                        val mins = totalScreenOnMinutes % 60
                        Text(
                            text = if (hrs > 0) "${hrs}h ${mins}m" else "${mins}m",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Goal: ${goalMins / 60}h ${goalMins % 60}m",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val remaining = goalMins - totalScreenOnMinutes
                        Text(
                            text = if (remaining > 0) "$remaining min left" else "Limit reached",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (remaining > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Tap bar to inspect apps",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Interactive App Legend Chips (Most Used 4-5 Apps + Other)
        Text(
            text = "Most Used Apps Breakdown",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val displaySegments = segments.take(5)
            displaySegments.forEach { seg ->
                val isSelected = selectedSegment?.id == seg.id
                val chipBorderColor by animateColorAsState(
                    targetValue = if (isSelected) seg.color else Color.Transparent,
                    label = "chipBorder"
                )
                val chipBgColor = if (isSelected) seg.color.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = chipBgColor,
                    border = BorderStroke(1.5.dp, if (isSelected) seg.color else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            if (isSelected) onSegmentSelected(null) else onSegmentSelected(seg)
                        }
                        .testTag("app_chip_${seg.appName.lowercase().replace(" ", "_")}")
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(seg.color)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = seg.appName,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                        val appMins = seg.durationMs / (60 * 1000)
                        Text(
                            text = "${appMins}m",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
