package com.example.ai_assistant.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai_assistant.callconversation.CallAudioSourceDiagnostics
import com.example.ai_assistant.callconversation.CallConversationMetrics
import com.example.ai_assistant.callconversation.CallConversationState
import com.example.ai_assistant.llm.ChatMessage

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CallConversationCard(
    state: CallConversationState,
    diagnostics: CallAudioSourceDiagnostics,
    metrics: CallConversationMetrics,
    conversationHistory: List<ChatMessage>,
    partialTranscript: String,
    currentStreamResponse: String,
    errorMessage: String?,
    isAutoModeEnabled: Boolean,
    liveRms: Float = 0f,
    isSpeechDetected: Boolean = false,
    activePersonaName: String = "Assistente (Irônico)",
    onToggleAutoMode: (Boolean) -> Unit,
    onStartCallConversation: () -> Unit,
    onStopCallConversation: () -> Unit,
    onClearConversation: () -> Unit,
    onSendManualMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()

    val stateColor = when (state) {
        CallConversationState.WAITING_CALL -> Color(0xFF757575)
        CallConversationState.CALL_ACTIVE -> Color(0xFF00897B)
        CallConversationState.LISTENING_REMOTE -> Color(0xFF4CAF50)
        CallConversationState.TRANSCRIBING -> Color(0xFFFFA000)
        CallConversationState.GENERATING -> Color(0xFF2196F3)
        CallConversationState.SPEAKING -> Color(0xFFAB47BC)
        CallConversationState.ERROR -> Color(0xFFE53935)
    }

    var manualInputText by remember { mutableStateOf("") }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(650, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isDark) Color(0xFF131E29) else Color(0xFFF0F5FA)
        ),
        border = BorderStroke(
            1.5.dp,
            if (state == CallConversationState.WAITING_CALL) {
                if (isDark) Color(0xFF1E3547) else Color(0xFFCFDCE6)
            } else {
                stateColor
            }
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Cabeçalho Principal
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.PhoneInTalk,
                            contentDescription = null,
                            tint = if (isDark) Color(0xFFE1F5FE) else Color(0xFF0D47A1),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Assistente de Ligação (APIs)",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isDark) Color(0xFFE1F5FE) else Color(0xFF0D47A1)
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Persona: $activePersonaName",
                        fontSize = 12.sp,
                        color = if (isDark) Color(0xFF81D4FA) else Color(0xFF1976D2)
                    )
                }

                // Badge de Estado com pulso
                val isPulsing = state in listOf(
                    CallConversationState.LISTENING_REMOTE,
                    CallConversationState.TRANSCRIBING,
                    CallConversationState.GENERATING,
                    CallConversationState.SPEAKING
                )

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = stateColor.copy(alpha = if (isDark) 0.25f else 0.15f),
                    border = BorderStroke(1.dp, stateColor)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .alpha(if (isPulsing) pulseAlpha else 1.0f)
                                .background(stateColor, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = state.label,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = stateColor
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Barra de Medidor de Áudio (RMS) do Interlocutor
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isSpeechDetected) "Falando:" else "Silêncio:",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isSpeechDetected) Color(0xFF4CAF50) else Color.Gray,
                    modifier = Modifier.width(64.dp)
                )
                val rmsFraction = (liveRms / 800f).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { rmsFraction },
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp),
                    color = if (isSpeechDetected) Color(0xFF4CAF50) else Color(0xFF64B5F6),
                    trackColor = if (isDark) Color(0xFF263238) else Color(0xFFECEFF1)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${liveRms.toInt()} RMS",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color.Gray
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Caixa de Conversa (Chat)
            val listState = rememberLazyListState()
            LaunchedEffect(conversationHistory.size, currentStreamResponse) {
                if (conversationHistory.isNotEmpty() || currentStreamResponse.isNotEmpty()) {
                    listState.animateScrollToItem(
                        maxOf(0, conversationHistory.size + (if (currentStreamResponse.isNotEmpty()) 1 else 0) - 1)
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 260.dp),
                shape = RoundedCornerShape(8.dp),
                color = if (isDark) Color(0xFF0D141C) else Color(0xFFFFFFFF),
                border = BorderStroke(1.dp, if (isDark) Color(0xFF1E2D3D) else Color(0xFFE0E0E0))
            ) {
                if (conversationHistory.isEmpty() && currentStreamResponse.isEmpty() && partialTranscript.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Quando uma pessoa ligar, a transcrição da voz e a resposta da IA aparecerão aqui em tempo real.",
                            fontSize = 12.sp,
                            color = Color.Gray,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(conversationHistory) { message ->
                            val isUser = message.role == "user"
                            val bubbleBg = if (isUser) {
                                if (isDark) Color(0xFF1E3A5F) else Color(0xFFE3F2FD)
                            } else {
                                if (isDark) Color(0xFF1B4332) else Color(0xFFE8F5E9)
                            }
                            val textColor = if (isUser) {
                                if (isDark) Color(0xFFBBDEFB) else Color(0xFF0D47A1)
                            } else {
                                if (isDark) Color(0xFFA5D6A7) else Color(0xFF1B5E20)
                            }

                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = if (isUser) Alignment.Start else Alignment.End
                            ) {
                                Text(
                                    text = if (isUser) "Interlocutor (Ligação):" else "IA (Resposta Falada):",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isUser) Color(0xFF64B5F6) else Color(0xFF81C784)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = bubbleBg
                                ) {
                                    Text(
                                        text = message.content,
                                        fontSize = 13.sp,
                                        color = textColor,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }

                        // Resposta em streaming ao vivo
                        if (currentStreamResponse.isNotEmpty()) {
                            item {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.End
                                ) {
                                    Text(
                                        text = "IA Falando ao vivo...",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFBA68C8)
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isDark) Color(0xFF382347) else Color(0xFFF3E5F5)
                                    ) {
                                        Text(
                                            text = currentStreamResponse,
                                            fontSize = 13.sp,
                                            fontStyle = FontStyle.Italic,
                                            color = if (isDark) Color(0xFFCE93D8) else Color(0xFF6A1B9A),
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Transcrição parcial
                        if (partialTranscript.isNotEmpty()) {
                            item {
                                Text(
                                    text = partialTranscript,
                                    fontSize = 11.sp,
                                    fontStyle = FontStyle.Italic,
                                    color = Color(0xFFFFA000)
                                )
                            }
                        }
                    }
                }
            }

            // Mensagem de Erro
            errorMessage?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0x22F44336)),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = Color(0xFFD32F2F),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = error,
                            fontSize = 11.sp,
                            color = Color(0xFFD32F2F)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Campo de Teste Manual da IA
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = manualInputText,
                    onValueChange = { manualInputText = it },
                    placeholder = { Text("Simular fala do interlocutor...", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Button(
                    onClick = {
                        if (manualInputText.isNotBlank()) {
                            onSendManualMessage(manualInputText)
                            manualInputText = ""
                        }
                    },
                    enabled = manualInputText.isNotBlank()
                ) {
                    Text("Enviar", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Controles Principais
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = isAutoModeEnabled,
                        onCheckedChange = onToggleAutoMode
                    )
                    Text("Ativação Automática ao Atender", fontSize = 11.sp)
                }

                TextButton(onClick = onClearConversation) {
                    Text("Limpar Chat", fontSize = 12.sp)
                }
            }

            // Botão Iniciar / Parar Manual
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state == CallConversationState.WAITING_CALL) {
                    Button(
                        onClick = onStartCallConversation,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00897B)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("▶ Iniciar Pipeline Manualmente")
                    }
                } else {
                    Button(
                        onClick = onStopCallConversation,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("■ Parar Pipeline da Chamada")
                    }
                }
            }
        }
    }
}
