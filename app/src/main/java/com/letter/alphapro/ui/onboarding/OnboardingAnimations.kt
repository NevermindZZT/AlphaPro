package com.letter.alphapro.ui.onboarding

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.letter.alphapro.R
import com.letter.alphapro.ui.theme.AlphaOrangePrimary
import kotlin.math.cos
import kotlin.math.sin

/**
 * Animated visual illustrations for AlphaPro core feature onboarding.
 * Pure Compose animations using Canvas, vector geometry, and infinite transitions.
 */

@Composable
fun OnboardingGpsAnimation(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "gps_anim")
    val pulseProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "radar_pulse"
    )
    val satelliteAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "satellite_orbit"
    )
    val heartbeatScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "heartbeat"
    )

    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.tertiary
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val onSurfaceVar = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.22f),
                        surfaceColor.copy(alpha = 0.65f)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerX = size.width / 2f
            val centerY = size.height * 0.44f

            // Concentric radar wave rings
            for (i in 0..2) {
                val waveOffset = (pulseProgress + i * 0.33f) % 1f
                val radius = 24.dp.toPx() + waveOffset * 76.dp.toPx()
                val alpha = ((1f - waveOffset) * 0.6f).coerceIn(0f, 1f)
                drawCircle(
                    color = primaryColor.copy(alpha = alpha),
                    radius = radius,
                    center = Offset(centerX, centerY),
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }

            // Dashed orbital ring
            val orbitRadius = 78.dp.toPx()
            drawCircle(
                color = onSurfaceVar.copy(alpha = 0.25f),
                radius = orbitRadius,
                center = Offset(centerX, centerY),
                style = Stroke(
                    width = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f)
                )
            )

            // Orbiting satellite dot
            val rad = Math.toRadians(satelliteAngle.toDouble())
            val satX = centerX + (orbitRadius * cos(rad)).toFloat()
            val satY = centerY + (orbitRadius * sin(rad)).toFloat()
            drawCircle(
                color = secondaryColor,
                radius = 5.dp.toPx(),
                center = Offset(satX, satY)
            )
            drawCircle(
                color = secondaryColor.copy(alpha = 0.35f),
                radius = 9.dp.toPx(),
                center = Offset(satX, satY)
            )

            // Data transfer beam between Satellite and Camera
            val beamStartY = centerY + 18.dp.toPx()
            val beamEndY = size.height * 0.86f
            drawLine(
                color = primaryColor.copy(alpha = 0.35f),
                start = Offset(centerX, beamStartY),
                end = Offset(centerX, beamEndY),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), pulseProgress * 30f)
            )

            // Pulse coordinate packets along the beam
            val packetY = beamStartY + (beamEndY - beamStartY) * ((pulseProgress * 2f) % 1f)
            drawCircle(
                color = primaryColor,
                radius = 3.dp.toPx(),
                center = Offset(centerX, packetY)
            )
        }

        // Center GPS beacon node
        Surface(
            modifier = Modifier
                .offset(y = (-12).dp)
                .size(46.dp * heartbeatScale),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            shadowElevation = 6.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Target Camera node at bottom
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp)
                .height(30.dp),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Camera,
                    contentDescription = null,
                    tint = primaryColor,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = stringResource(R.string.onboarding_anim_camera_label),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Status badge: 5s Heartbeat
        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 14.dp, top = 14.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF00E676))
                )
                Text(
                    text = stringResource(R.string.onboarding_anim_heartbeat),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Status badge: Microsecond Sync
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 14.dp, top = 14.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Schedule,
                    contentDescription = null,
                    tint = primaryColor,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = stringResource(R.string.onboarding_anim_time_sync),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
fun OnboardingGalleryAnimation(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "gallery_anim")
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = -180f,
        targetValue = 480f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer"
    )
    val loupeOffsetX by infiniteTransition.animateFloat(
        initialValue = -30f,
        targetValue = 30f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "loupe_x"
    )
    val loupeOffsetY by infiniteTransition.animateFloat(
        initialValue = -12f,
        targetValue = 12f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "loupe_y"
    )

    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainerHighest
    val primaryColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        AlphaOrangePrimary.copy(alpha = 0.20f),
                        surfaceContainer.copy(alpha = 0.65f)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        // Stack of 2 photo cards (representing RAW + HIF clustering)
        // Background RAW card tilted -7 degrees
        Box(
            modifier = Modifier
                .width(170.dp)
                .height(115.dp)
                .offset(x = (-8).dp, y = (-4).dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
        )

        // Foreground 10-bit HIF Photo Card
        Box(
            modifier = Modifier
                .width(180.dp)
                .height(120.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(1.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
        ) {
            // Scenic Photo landscape background
            Canvas(modifier = Modifier.fillMaxSize()) {
                // Sky gradient: indigo to warm golden sunset
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFF1E293B), Color(0xFFC2410C), Color(0xFFF59E0B))
                    )
                )

                // Sun
                drawCircle(
                    color = Color(0xFFFEF08A),
                    radius = 16.dp.toPx(),
                    center = Offset(size.width * 0.72f, size.height * 0.38f)
                )

                // Mountain silhouettes
                val mountainPath1 = Path().apply {
                    moveTo(0f, size.height)
                    lineTo(size.width * 0.35f, size.height * 0.45f)
                    lineTo(size.width * 0.65f, size.height * 0.75f)
                    lineTo(size.width, size.height * 0.35f)
                    lineTo(size.width, size.height)
                    close()
                }
                drawPath(mountainPath1, color = Color(0xFF475569).copy(alpha = 0.85f))

                val mountainPath2 = Path().apply {
                    moveTo(0f, size.height)
                    lineTo(size.width * 0.2f, size.height * 0.65f)
                    lineTo(size.width * 0.5f, size.height * 0.40f)
                    lineTo(size.width * 0.85f, size.height * 0.70f)
                    lineTo(size.width, size.height * 0.55f)
                    lineTo(size.width, size.height)
                    close()
                }
                drawPath(mountainPath2, color = Color(0xFF0F172A))

                // Light shimmer sweep across the photo
                val shimmerBrush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0f),
                        Color.White.copy(alpha = 0.30f),
                        Color.White.copy(alpha = 0f)
                    ),
                    start = Offset(shimmerOffset, 0f),
                    end = Offset(shimmerOffset + 100f, size.height)
                )
                drawRect(shimmerBrush)
            }

            // Focus inspection loupe moving across the focal point
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .align(Alignment.Center)
                    .offset(x = loupeOffsetX.dp, y = loupeOffsetY.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.40f))
                    .border(1.5.dp, Color(0xFF00E676), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                // Focus brackets [ + ]
                Canvas(modifier = Modifier.size(24.dp)) {
                    val w = size.width
                    val h = size.height
                    val green = Color(0xFF00E676)
                    val stroke = 1.5.dp.toPx()

                    // Crosshair
                    drawLine(green, Offset(w * 0.35f, h / 2), Offset(w * 0.65f, h / 2), stroke)
                    drawLine(green, Offset(w / 2, h * 0.35f), Offset(w / 2, h * 0.65f), stroke)

                    // Corner brackets
                    // Top-left
                    drawLine(green, Offset(2f, 8f), Offset(2f, 2f), stroke)
                    drawLine(green, Offset(2f, 2f), Offset(8f, 2f), stroke)
                    // Bottom-right
                    drawLine(green, Offset(w - 2f, h - 8f), Offset(w - 2f, h - 2f), stroke)
                    drawLine(green, Offset(w - 2f, h - 2f), Offset(w - 8f, h - 2f), stroke)
                }
            }

            // Top-left badge: RAW + 10b HIF
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
                shape = RoundedCornerShape(6.dp),
                color = AlphaOrangePrimary
            ) {
                Text(
                    text = stringResource(R.string.onboarding_anim_raw_badge),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    ),
                    color = Color.White
                )
            }
        }

        // Bottom chip: 10-bit HEIF -> JPEG (95%)
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Transform,
                    contentDescription = null,
                    tint = primaryColor,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = stringResource(R.string.onboarding_anim_heif_to_jpeg),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
fun OnboardingRemoteAnimation(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "remote_anim")
    val cycleProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shutter_cycle"
    )
    val apertureRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "aperture_rot"
    )

    // Three states in a 3s cycle:
    // 0.0 - 0.45: Half-press AF lock (focus green ring lights up)
    // 0.45 - 0.65: Full-press Shutter Trigger (exposure ripple flashes)
    // 0.65 - 1.00: Release & ready
    val isHalfPressed = cycleProgress in 0.05f..0.65f
    val isFullTriggered = cycleProgress in 0.45f..0.60f
    val triggerScale = if (isFullTriggered) 0.92f else 1.0f

    val primaryColor = MaterialTheme.colorScheme.primary
    val focusGreen = Color(0xFF00E676)
    val ringColor = if (isHalfPressed) focusGreen else primaryColor.copy(alpha = 0.4f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.22f),
                        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.65f)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(136.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val outerRadius = size.width / 2f - 4.dp.toPx()

            // Outer metallic lens rim with subtle tick marks
            drawCircle(
                color = Color.Black.copy(alpha = 0.35f),
                radius = outerRadius,
                center = center
            )
            drawCircle(
                color = ringColor,
                radius = outerRadius,
                center = center,
                style = Stroke(width = 2.5.dp.toPx())
            )

            // Tick marks
            rotate(apertureRotation, center) {
                for (angle in 0 until 360 step 30) {
                    val tickRad = Math.toRadians(angle.toDouble())
                    val startDist = outerRadius - 6.dp.toPx()
                    val endDist = outerRadius - 1.dp.toPx()
                    drawLine(
                        color = ringColor.copy(alpha = 0.5f),
                        start = Offset(center.x + (startDist * cos(tickRad)).toFloat(), center.y + (startDist * sin(tickRad)).toFloat()),
                        end = Offset(center.x + (endDist * cos(tickRad)).toFloat(), center.y + (endDist * sin(tickRad)).toFloat()),
                        strokeWidth = 1.dp.toPx()
                    )
                }
            }

            // 6 Aperture blades breathing in and out
            val bladeCount = 6
            val apertureRadius = outerRadius * 0.72f
            for (i in 0 until bladeCount) {
                val bladeAngle = (i * (360f / bladeCount) + apertureRotation * 0.5f) % 360f
                val bRad = Math.toRadians(bladeAngle.toDouble())
                val p1 = Offset(center.x + (apertureRadius * cos(bRad)).toFloat(), center.y + (apertureRadius * sin(bRad)).toFloat())
                val p2 = Offset(center.x + (apertureRadius * cos(bRad + 0.8)).toFloat(), center.y + (apertureRadius * sin(bRad + 0.8)).toFloat())
                drawLine(
                    color = Color.Gray.copy(alpha = 0.45f),
                    start = p1,
                    end = p2,
                    strokeWidth = 1.5.dp.toPx()
                )
            }

            // Exposure flash ripple on trigger
            if (isFullTriggered) {
                val flashFrac = ((cycleProgress - 0.45f) / 0.15f).coerceIn(0f, 1f)
                val flashRadius = outerRadius * (0.8f + flashFrac * 0.4f)
                drawCircle(
                    color = Color.White.copy(alpha = (1f - flashFrac) * 0.8f),
                    radius = flashRadius,
                    center = center,
                    style = Stroke(width = 3.dp.toPx())
                )
            }

            // AF focus brackets around the shutter button [  ]
            if (isHalfPressed) {
                val bSize = 36.dp.toPx()
                val bracketStroke = 2.dp.toPx()
                val offset = 12.dp.toPx()

                // Top-Left
                drawLine(focusGreen, Offset(center.x - bSize, center.y - bSize + offset), Offset(center.x - bSize, center.y - bSize), bracketStroke)
                drawLine(focusGreen, Offset(center.x - bSize, center.y - bSize), Offset(center.x - bSize + offset, center.y - bSize), bracketStroke)
                // Top-Right
                drawLine(focusGreen, Offset(center.x + bSize, center.y - bSize + offset), Offset(center.x + bSize, center.y - bSize), bracketStroke)
                drawLine(focusGreen, Offset(center.x + bSize, center.y - bSize), Offset(center.x + bSize - offset, center.y - bSize), bracketStroke)
                // Bottom-Left
                drawLine(focusGreen, Offset(center.x - bSize, center.y + bSize - offset), Offset(center.x - bSize, center.y + bSize), bracketStroke)
                drawLine(focusGreen, Offset(center.x - bSize, center.y + bSize), Offset(center.x - bSize + offset, center.y + bSize), bracketStroke)
                // Bottom-Right
                drawLine(focusGreen, Offset(center.x + bSize, center.y + bSize - offset), Offset(center.x + bSize, center.y + bSize), bracketStroke)
                drawLine(focusGreen, Offset(center.x + bSize, center.y + bSize), Offset(center.x + bSize - offset, center.y + bSize), bracketStroke)
            }
        }

        // Center Shutter Button
        Surface(
            modifier = Modifier.size(76.dp * triggerScale),
            shape = CircleShape,
            color = if (isHalfPressed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            border = androidx.compose.foundation.BorderStroke(3.dp, ringColor),
            shadowElevation = 8.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.Camera,
                    contentDescription = null,
                    tint = if (isHalfPressed) MaterialTheme.colorScheme.onPrimaryContainer else primaryColor,
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        // Top-left pill: Burst Mode indicator
        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 14.dp, top = 14.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Bolt,
                    contentDescription = null,
                    tint = AlphaOrangePrimary,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = stringResource(R.string.onboarding_anim_burst_mode),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Top-right pill: AF Status indicator
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 14.dp, top = 14.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isHalfPressed) focusGreen else Color.Gray)
                )
                Text(
                    text = stringResource(R.string.onboarding_anim_af_lock),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Bottom pill: BULB Timer Mode
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Timelapse,
                    contentDescription = null,
                    tint = primaryColor,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = stringResource(R.string.onboarding_anim_bulb_timer),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
fun OnboardingTetherAnimation(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "tether_anim")
    val packetProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "tether_packet"
    )
    val wifiPulse by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "wifi_pulse"
    )

    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.tertiary
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val hasLanded = packetProgress > 0.85f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        secondaryColor.copy(alpha = 0.20f),
                        surfaceColor.copy(alpha = 0.65f)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val startX = size.width * 0.22f
            val startY = size.height * 0.5f
            val endX = size.width * 0.78f
            val endY = size.height * 0.5f

            // Curved transmission trajectory
            val path = Path().apply {
                moveTo(startX, startY)
                quadraticBezierTo(size.width * 0.5f, size.height * 0.25f, endX, endY)
            }

            drawPath(
                path = path,
                color = primaryColor.copy(alpha = 0.35f),
                style = Stroke(
                    width = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), packetProgress * 40f)
                )
            )

            // Wi-Fi wave arcs radiating from camera
            for (i in 1..3) {
                val arcRadius = i * 14.dp.toPx()
                val alpha = (wifiPulse / i).coerceIn(0.1f, 1f)
                drawArc(
                    color = primaryColor.copy(alpha = alpha),
                    startAngle = -45f,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(startX - arcRadius, startY - arcRadius),
                    size = Size(arcRadius * 2, arcRadius * 2),
                    style = Stroke(width = 2.dp.toPx())
                )
            }

            // Flying photo thumbnail packet
            val t = packetProgress
            // Quadratic Bezier interpolation: B(t) = (1-t)^2 * P0 + 2(1-t)t * P1 + t^2 * P2
            val ctrlX = size.width * 0.5f
            val ctrlY = size.height * 0.25f
            val curX = (1 - t) * (1 - t) * startX + 2 * (1 - t) * t * ctrlX + t * t * endX
            val curY = (1 - t) * (1 - t) * startY + 2 * (1 - t) * t * ctrlY + t * t * endY

            // Mini photo card shadow & body
            drawRoundRect(
                color = AlphaOrangePrimary,
                topLeft = Offset(curX - 11.dp.toPx(), curY - 9.dp.toPx()),
                size = Size(22.dp.toPx(), 18.dp.toPx()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
            )
            drawRoundRect(
                color = Color.White.copy(alpha = 0.85f),
                topLeft = Offset(curX - 9.dp.toPx(), curY - 7.dp.toPx()),
                size = Size(18.dp.toPx(), 14.dp.toPx()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
            )
        }

        // Left Node: Camera
        Surface(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 20.dp)
                .size(54.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 4.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.Camera,
                    contentDescription = null,
                    tint = AlphaOrangePrimary,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        // Right Node: Smartphone / Tablet Receiver
        Surface(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 20.dp)
                .size(54.dp),
            shape = RoundedCornerShape(16.dp),
            color = if (hasLanded) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            border = androidx.compose.foundation.BorderStroke(
                1.5.dp,
                if (hasLanded) Color(0xFF00E676) else MaterialTheme.colorScheme.outlineVariant
            ),
            shadowElevation = 4.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (hasLanded) Icons.Rounded.CheckCircle else Icons.Rounded.Smartphone,
                    contentDescription = null,
                    tint = if (hasLanded) Color(0xFF00E676) else primaryColor,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        // Top-left status pill: 5GHz Wi-Fi
        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 14.dp, top = 14.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Wifi,
                    contentDescription = null,
                    tint = primaryColor,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = stringResource(R.string.onboarding_anim_wifi_speed),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Bottom status pill: FTP Live Server
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.CloudSync,
                    contentDescription = null,
                    tint = primaryColor,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = stringResource(R.string.onboarding_anim_ftp_live),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
