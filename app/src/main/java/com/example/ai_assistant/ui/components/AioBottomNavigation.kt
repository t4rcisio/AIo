package com.example.ai_assistant.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai_assistant.ui.theme.*

enum class NavigationTab(val label: String, val icon: ImageVector) {
    ASSISTENTE("Assistente", Icons.Outlined.Phone),
    HISTORICO("Histórico", Icons.Outlined.Schedule),
    AJUSTES("Ajustes", Icons.Outlined.Settings)
}

/**
 * Barra de navegação inferior com transições e animações fluidas:
 * - Fundo translúcido (superfície branca 92% alpha com borda sutil em #D9E2DC).
 * - Pílula animada com transição fluida de cor, expansão de texto e feedback tátil elástico (mola).
 * - Feedback ao clique responsivo e suave, eliminando sensação rígida/seca.
 */
@Composable
fun AioBottomNavigation(
    currentTab: NavigationTab,
    onTabSelected: (NavigationTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = AioSurface.copy(alpha = 0.92f),
            shadowElevation = 8.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, AioOutline.copy(alpha = 0.8f)),
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavigationTab.values().forEach { tab ->
                    val isSelected = currentTab == tab
                    val interactionSource = remember { MutableInteractionSource() }
                    val isPressed by interactionSource.collectIsPressedAsState()

                    // Feedback tátil: leve compressão ao pressionar e retorno com mola elástica
                    val pressScale by animateFloatAsState(
                        targetValue = if (isPressed) 0.92f else 1.0f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium
                        ),
                        label = "TabPressScale_${tab.name}"
                    )

                    // Distribuição suave de espaço: aba selecionada ganha peso extra de forma elástica
                    val animatedWeight by animateFloatAsState(
                        targetValue = if (isSelected) 1.25f else 0.88f,
                        animationSpec = spring(
                            stiffness = Spring.StiffnessMediumLow,
                            dampingRatio = Spring.DampingRatioNoBouncy
                        ),
                        label = "TabWeight_${tab.name}"
                    )

                    // Transição contínua da cor de fundo da pílula
                    val targetBgColor = when {
                        isSelected -> AioSelection
                        isPressed -> AioSelection.copy(alpha = 0.45f)
                        else -> Color.Transparent
                    }
                    val animatedBgColor by animateColorAsState(
                        targetValue = targetBgColor,
                        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                        label = "TabBgColor_${tab.name}"
                    )

                    // Transição suave de cor do ícone e do texto
                    val animatedContentColor by animateColorAsState(
                        targetValue = if (isSelected) AioPineGreen else AioTextSecondary,
                        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
                        label = "TabContentColor_${tab.name}"
                    )

                    // Micro-animação de escala no ícone
                    val iconScale by animateFloatAsState(
                        targetValue = if (isSelected) 1.08f else 1.0f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        ),
                        label = "TabIconScale_${tab.name}"
                    )

                    Box(
                        modifier = Modifier
                            .weight(animatedWeight)
                            .height(48.dp)
                            .graphicsLayer {
                                scaleX = pressScale
                                scaleY = pressScale
                            }
                            .background(
                                color = animatedBgColor,
                                shape = RoundedCornerShape(24.dp)
                            )
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null
                            ) {
                                onTabSelected(tab)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(horizontal = 6.dp)
                        ) {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.label,
                                tint = animatedContentColor,
                                modifier = Modifier
                                    .size(20.dp)
                                    .graphicsLayer {
                                        scaleX = iconScale
                                        scaleY = iconScale
                                    }
                            )
                            AnimatedVisibility(
                                visible = isSelected,
                                enter = fadeIn(
                                    animationSpec = tween(durationMillis = 220, delayMillis = 40, easing = LinearOutSlowInEasing)
                                ) + expandHorizontally(
                                    animationSpec = spring(
                                        stiffness = Spring.StiffnessMediumLow,
                                        dampingRatio = Spring.DampingRatioNoBouncy
                                    ),
                                    expandFrom = Alignment.Start
                                ),
                                exit = fadeOut(
                                    animationSpec = tween(durationMillis = 150, easing = FastOutLinearInEasing)
                                ) + shrinkHorizontally(
                                    animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                                    shrinkTowards = Alignment.Start
                                )
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = tab.label,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = animatedContentColor,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
