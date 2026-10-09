package com.example.ai_assistant.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.ai_assistant.ui.theme.AioPineGreen
import com.example.ai_assistant.ui.theme.AioOutline

/**
 * Símbolo discreto de duas curvas de áudio espelhadas no centro da tela principal.
 * Representa acusticamente o atendimento de voz com estética minimalista e elegante.
 */
@Composable
fun AudioMirroredCurves(
    liveRms: Float = 0f,
    isActive: Boolean = true,
    modifier: Modifier = Modifier
) {
    // Reage sutilmente ao nível RMS real do áudio quando ativo
    val animatedRms by animateFloatAsState(
        targetValue = if (isActive) liveRms.coerceIn(0f, 1f) else 0f,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "animatedRms"
    )

    Canvas(modifier = modifier.size(width = 120.dp, height = 90.dp)) {
        val w = size.width
        val h = size.height
        val centerX = w / 2f
        val centerY = h / 2f

        val strokeWidth = 3.dp.toPx()
        val baseCurveSpread = 16.dp.toPx()
        val dynamicExpand = animatedRms * 14.dp.toPx()

        val mainColor = if (isActive) AioPineGreen else AioOutline

        // Curva Esquerda (Espelhada)
        val leftPath = Path().apply {
            moveTo(centerX - baseCurveSpread - dynamicExpand, centerY - 28.dp.toPx())
            cubicTo(
                centerX - baseCurveSpread - dynamicExpand - 24.dp.toPx(),
                centerY - 10.dp.toPx(),
                centerX - baseCurveSpread - dynamicExpand - 24.dp.toPx(),
                centerY + 10.dp.toPx(),
                centerX - baseCurveSpread - dynamicExpand,
                centerY + 28.dp.toPx()
            )
        }

        // Curva Direita (Espelhada)
        val rightPath = Path().apply {
            moveTo(centerX + baseCurveSpread + dynamicExpand, centerY - 28.dp.toPx())
            cubicTo(
                centerX + baseCurveSpread + dynamicExpand + 24.dp.toPx(),
                centerY - 10.dp.toPx(),
                centerX + baseCurveSpread + dynamicExpand + 24.dp.toPx(),
                centerY + 10.dp.toPx(),
                centerX + baseCurveSpread + dynamicExpand,
                centerY + 28.dp.toPx()
            )
        }

        // Curvas secundárias interiores menores e mais sutis
        val innerSpread = baseCurveSpread * 0.5f + dynamicExpand * 0.4f
        val leftInner = Path().apply {
            moveTo(centerX - innerSpread, centerY - 16.dp.toPx())
            cubicTo(
                centerX - innerSpread - 10.dp.toPx(),
                centerY - 6.dp.toPx(),
                centerX - innerSpread - 10.dp.toPx(),
                centerY + 6.dp.toPx(),
                centerX - innerSpread,
                centerY + 16.dp.toPx()
            )
        }

        val rightInner = Path().apply {
            moveTo(centerX + innerSpread, centerY - 16.dp.toPx())
            cubicTo(
                centerX + innerSpread + 10.dp.toPx(),
                centerY - 6.dp.toPx(),
                centerX + innerSpread + 10.dp.toPx(),
                centerY + 6.dp.toPx(),
                centerX + innerSpread,
                centerY + 16.dp.toPx()
            )
        }

        // Desenha as curvas principais
        drawPath(
            path = leftPath,
            color = mainColor,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
        drawPath(
            path = rightPath,
            color = mainColor,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )

        // Desenha as curvas internas discretas
        drawPath(
            path = leftInner,
            color = mainColor.copy(alpha = 0.65f),
            style = Stroke(width = strokeWidth * 0.8f, cap = StrokeCap.Round)
        )
        drawPath(
            path = rightInner,
            color = mainColor.copy(alpha = 0.65f),
            style = Stroke(width = strokeWidth * 0.8f, cap = StrokeCap.Round)
        )
    }
}
