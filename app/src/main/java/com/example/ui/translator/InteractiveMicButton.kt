package com.example.ui.translator

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.LocalStudioColors
import com.example.ui.theme.RecordingCoral
import com.example.ui.theme.RecordingCrimson
import kotlin.math.sin

@Composable
fun InteractiveMicButton(
    isListening: Boolean,
    isTranslating: Boolean,
    rmsLevel: Float,
    elapsedSeconds: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val studioColors = LocalStudioColors.current
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val smoothRmsState = animateFloatAsState(
        targetValue = if (isListening) rmsLevel.coerceIn(0.08f, 1f) else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "smooth_rms"
    )

    val pressScaleState = animateFloatAsState(
        targetValue = when {
            isPressed -> 0.90f
            isListening -> 1.04f + (smoothRmsState.value * 0.06f)
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "button_scale"
    )

    val coreStartColor by animateColorAsState(
        targetValue = if (isListening) RecordingCrimson else MaterialTheme.colorScheme.primary,
        animationSpec = tween(250),
        label = "core_start_color"
    )
    val coreEndColor by animateColorAsState(
        targetValue = if (isListening) RecordingCoral else MaterialTheme.colorScheme.tertiary,
        animationSpec = tween(250),
        label = "core_end_color"
    )

    val cornerSize by animateDpAsState(
        targetValue = if (isListening) 28.dp else 42.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "core_corner"
    )

    // Scoped infinite transitions so draw-phase animations never force Composable recompositions
    val wavePhase1State: State<Float>
    val wavePhase2State: State<Float>
    val wavePhase3State: State<Float>
    val dotAlphaState: State<Float>
    if (isListening) {
        val listeningTransition = rememberInfiniteTransition(label = "mic_listening_pulse")
        wavePhase1State = listeningTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1600, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "wave1"
        )
        wavePhase2State = listeningTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1600, delayMillis = 530, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "wave2"
        )
        wavePhase3State = listeningTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1600, delayMillis = 1060, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "wave3"
        )
        dotAlphaState = listeningTransition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 550, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "rec_dot_alpha"
        )
    } else {
        wavePhase1State = rememberUpdatedState(0f)
        wavePhase2State = rememberUpdatedState(0f)
        wavePhase3State = rememberUpdatedState(0f)
        dotAlphaState = rememberUpdatedState(1f)
    }

    val idleBreathState: State<Float> = if (!isListening) {
        val idleTransition = rememberInfiniteTransition(label = "mic_idle_breath")
        idleTransition.animateFloat(
            initialValue = 0.96f,
            targetValue = 1.05f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "idle_breath"
        )
    } else {
        rememberUpdatedState(1f)
    }

    val sweepAngleState: State<Float> = if (isTranslating && !isListening) {
        val translatingTransition = rememberInfiniteTransition(label = "mic_translating_arc")
        translatingTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1100, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "translating_sweep"
        )
    } else {
        rememberUpdatedState(0f)
    }

    val micCd = stringResource(
        if (isListening) R.string.mic_stop_cd else R.string.mic_start_cd
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Live recording / translating badge above the button
        AnimatedVisibility(
            visible = isListening || isTranslating,
            enter = fadeIn() + scaleIn(initialScale = 0.85f),
            exit = fadeOut() + scaleOut(targetScale = 0.85f)
        ) {
            val badgeBg = if (isListening) {
                studioColors.recordingActive.copy(alpha = 0.14f)
            } else {
                MaterialTheme.colorScheme.primaryContainer
            }
            val badgeTextColor = if (isListening) {
                studioColors.recordingActive
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer
            }
            Surface(
                color = badgeBg,
                shape = RoundedCornerShape(50),
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isListening) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .graphicsLayer { alpha = dotAlphaState.value }
                                .clip(CircleShape)
                                .background(studioColors.recordingActive)
                        )
                        Text(
                            text = "${stringResource(R.string.mic_recording_badge)} • ${formatTimer(elapsedSeconds)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = badgeTextColor,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.mic_translating_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = badgeTextColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Interactive Audio-Reactive Stage + Core Recording Button
        Box(
            modifier = Modifier.size(144.dp),
            contentAlignment = Alignment.Center
        ) {
            // Audio-reactive Canvas rings & translating arc (reads animated State only in DrawScope)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val baseRadius = 42.dp.toPx()

                if (isListening) {
                    val smoothRms = smoothRmsState.value
                    val phases = listOf(wavePhase1State.value, wavePhase2State.value, wavePhase3State.value)
                    val maxExpand = 30.dp.toPx() + (smoothRms * 18.dp.toPx())
                    phases.forEach { phase ->
                        val radius = baseRadius + (phase * maxExpand)
                        val alpha = (1f - phase) * (0.28f + smoothRms * 0.42f)
                        drawCircle(
                            color = studioColors.recordingActive.copy(alpha = alpha.coerceIn(0f, 0.7f)),
                            radius = radius,
                            center = center,
                            style = Stroke(width = (3f + (smoothRms * 3f)).dp.toPx())
                        )
                    }

                    // Dynamic reactive halo directly proportional to voice volume
                    drawCircle(
                        color = studioColors.recordingGlow.copy(alpha = 0.14f + smoothRms * 0.22f),
                        radius = baseRadius + (smoothRms * 22.dp.toPx()),
                        center = center
                    )
                } else {
                    // Calm ambient studio halo in idle state
                    drawCircle(
                        color = coreStartColor.copy(alpha = 0.10f),
                        radius = (baseRadius + 10.dp.toPx()) * idleBreathState.value,
                        center = center
                    )
                    drawCircle(
                        color = coreStartColor.copy(alpha = 0.18f),
                        radius = baseRadius + 5.dp.toPx(),
                        center = center,
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }

                if (isTranslating && !isListening) {
                    drawArc(
                        color = coreEndColor,
                        startAngle = sweepAngleState.value,
                        sweepAngle = 110f,
                        useCenter = false,
                        topLeft = Offset(
                            center.x - (baseRadius + 8.dp.toPx()),
                            center.y - (baseRadius + 8.dp.toPx())
                        ),
                        size = Size((baseRadius + 8.dp.toPx()) * 2f, (baseRadius + 8.dp.toPx()) * 2f),
                        style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }

            // Core Interactive Button
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .graphicsLayer {
                        val s = pressScaleState.value
                        scaleX = s
                        scaleY = s
                    }
                    .minimumInteractiveComponentSize()
                    .shadow(
                        elevation = if (isListening) 16.dp else 10.dp,
                        shape = RoundedCornerShape(cornerSize),
                        ambientColor = coreStartColor,
                        spotColor = coreStartColor
                    )
                    .clip(RoundedCornerShape(cornerSize))
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(coreStartColor, coreEndColor)
                        )
                    )
                    .testTag("record_voice_button")
                    .semantics { contentDescription = micCd }
                    .clickable(
                        interactionSource = interactionSource,
                        indication = ripple(color = Color.White),
                        role = Role.Button,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onClick()
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = isListening,
                    transitionSpec = {
                        (fadeIn(tween(180)) + scaleIn(initialScale = 0.8f)) togetherWith
                            (fadeOut(tween(150)) + scaleOut(targetScale = 0.8f))
                    },
                    label = "mic_icon_morph"
                ) { listening ->
                    if (listening) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            LiveEqualizerBars(
                                rmsLevelProvider = { smoothRmsState.value },
                                phaseProvider = { wavePhase1State.value },
                                barColor = Color.White
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Stop,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "STOP",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Mic,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(38.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = stringResource(
                if (isListening) R.string.mic_listening_hint else R.string.mic_idle_hint
            ),
            style = MaterialTheme.typography.labelMedium,
            color = if (isListening) {
                studioColors.recordingActive
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun LiveEqualizerBars(
    rmsLevelProvider: () -> Float,
    phaseProvider: () -> Float,
    barColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .width(40.dp)
            .height(24.dp)
    ) {
        val barCount = 5
        val spacing = 4.dp.toPx()
        val totalSpacing = spacing * (barCount - 1)
        val barWidth = (size.width - totalSpacing) / barCount
        val maxH = size.height
        val minH = 5.dp.toPx()
        val rmsLevel = rmsLevelProvider()
        val phase = phaseProvider()

        for (i in 0 until barCount) {
            val offsetAngle = (phase * 2f * Math.PI + i * 1.15).toFloat()
            val wave = (sin(offsetAngle) + 1f) / 2f
            val centerBias = if (i == 2) 1f else if (i == 1 || i == 3) 0.85f else 0.65f
            val amplitude = (0.22f + rmsLevel * 0.78f) * (0.45f + wave * 0.55f) * centerBias
            val barHeight = (minH + (maxH - minH) * amplitude).coerceIn(minH, maxH)
            val x = i * (barWidth + spacing)
            val top = (maxH - barHeight) / 2f

            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, top),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

private fun formatTimer(seconds: Int): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return "%02d:%02d".format(mins, secs)
}
