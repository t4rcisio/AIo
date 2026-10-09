package com.example.ai_assistant.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.ai_assistant.ui.theme.AioPineGreen
import com.example.ai_assistant.ui.theme.AioOutline
import kotlin.math.sin

/**
 * Forma de onda em tempo real vinculada ao RMS do áudio real da ligação e estado da conversa.
 */
@Composable
fun RealtimeAudioWaveform(
    liveRms: Float,
    isSpeechDetected: Boolean,
    isSpeaking: Boolean,
    modifier: Modifier = Modifier,
    barColor: Color = AioPineGreen
) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveAnim")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.283f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
    ) {
        val barCount = 28
        val spacing = size.width / (barCount * 1.6f)
        val barWidth = spacing * 0.6f
        val centerY = size.height / 2f
        val maxBarHeight = size.height * 0.9f
        val minBarHeight = 6.dp.toPx()

        val rmsNormalized = liveRms.coerceIn(0f, 1f)
        val activityLevel = if (isSpeaking) 0.85f else if (isSpeechDetected) (rmsNormalized * 1.5f).coerceIn(0.2f, 1f) else 0.08f

        for (i in 0 until barCount) {
            val x = i * (barWidth + spacing) + spacing / 2f
            val waveFactor = (sin(phase + i * 0.35f) + 1f) / 2f
            val height = (minBarHeight + (maxBarHeight - minBarHeight) * activityLevel * waveFactor)
                .coerceIn(minBarHeight, maxBarHeight)

            val color = if (activityLevel > 0.15f) barColor else AioOutline

            drawRoundRect(
                color = color,
                topLeft = Offset(x, centerY - height / 2f),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}
