package com.example.ai_assistant.ui.screens

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai_assistant.callconversation.CallConversationState
import com.example.ai_assistant.llm.ChatMessage
import com.example.ai_assistant.ui.components.RealtimeAudioWaveform
import com.example.ai_assistant.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun ActiveCallScreen(
    callerName: String,
    phoneNumber: String,
    callState: CallConversationState,
    conversationHistory: List<ChatMessage>,
    partialTranscript: String,
    currentStreamResponse: String,
    liveRms: Float,
    isSpeechDetected: Boolean,
    isAutoModeEnabled: Boolean,
    onToggleAutoMode: () -> Unit,
    onEndCall: () -> Unit,
    onToggleSpeaker: () -> Unit,
    isSpeakerActive: Boolean = false,
    isDaemonOffline: Boolean = false,
    isWhatsApp: Boolean = false,
    modifier: Modifier = Modifier
) {
    var durationSeconds by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        val startTime = SystemClock.elapsedRealtime()
        while (true) {
            durationSeconds = (SystemClock.elapsedRealtime() - startTime) / 1000
            delay(1000)
        }
    }

    val durationText = remember(durationSeconds) {
        val mins = durationSeconds / 60
        val secs = durationSeconds % 60
        "%02d:%02d".format(mins, secs)
    }

    // Identificação do estado atual: Ouvindo, Processando, Respondendo, WhatsApp ou Modo Manual
    val (statusLabel, statusBg, statusColor) = if (isWhatsApp) {
        Triple("WhatsApp • Fale Direto", Color(0xFFE8F5E9), Color(0xFF2E7D32))
    } else if (!isAutoModeEnabled) {
        Triple("Você no Comando (IA em Pausa)", AioSurfaceVariant, AioTextSecondary)
    } else {
        when (callState) {
            CallConversationState.LISTENING_REMOTE -> Triple("Ouvindo", AioSelection, AioPineGreen)
            CallConversationState.TRANSCRIBING, CallConversationState.GENERATING -> Triple("Processando", AioSelection, AioGraphite)
            CallConversationState.SPEAKING -> Triple("Respondendo", AioPineGreen, Color.White)
            CallConversationState.CALL_ACTIVE -> Triple("Em linha", AioSelection, AioPineGreen)
            CallConversationState.ERROR -> Triple("Atenção", Color(0xFFFFEBEE), AioError)
            else -> Triple("Conectado", AioSelection, AioPineGreen)
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(conversationHistory.size, partialTranscript, currentStreamResponse) {
        val count = conversationHistory.size + (if (partialTranscript.isNotEmpty() || currentStreamResponse.isNotEmpty()) 1 else 0)
        if (count > 0) {
            listState.animateScrollToItem(count - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AioBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 1. Cabeçalho da Ligação: Interlocutor e Duração
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (callerName.isNotBlank() && callerName != "Sem Nome") callerName else phoneNumber.ifBlank { "Ligação em Andamento" },
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = AioGraphite,
                textAlign = TextAlign.Center
            )

            if (phoneNumber.isNotBlank() && callerName.isNotBlank() && callerName != phoneNumber) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = phoneNumber,
                    fontSize = 15.sp,
                    color = AioTextSecondary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = durationText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = AioTextSecondary
            )

            if (isWhatsApp) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFE8F5E9),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF81C784))
                ) {
                    Text(
                        text = "WhatsApp Chamada",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2E7D32),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Badge de Estado: "Ouvindo", "Processando", "Respondendo"
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = statusBg,
                border = androidx.compose.foundation.BorderStroke(1.dp, AioOutline)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(statusColor, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = statusLabel,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor
                    )
                }
            }
        }

        // Aviso em destaque se o módulo de áudio estiver offline e ocorrer erro durante a ligação
        if (isDaemonOffline && callState == CallConversationState.ERROR) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFFFFEBEE),
                border = androidx.compose.foundation.BorderStroke(1.dp, AioError.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MicOff,
                        contentDescription = null,
                        tint = AioError,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Módulo de Áudio offline. O assistente não conseguirá ouvir a chamada. Fale normalmente.",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = AioError,
                        lineHeight = 16.sp
                    )
                }
            }
        }

        // 2. Forma de Onda em Tempo Real (Áudio real)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            RealtimeAudioWaveform(
                liveRms = liveRms,
                isSpeechDetected = isSpeechDetected,
                isSpeaking = callState == CallConversationState.SPEAKING,
                barColor = AioPineGreen
            )
        }

        // 3. Transcrição ao Vivo (Distinguindo Interlocutor e AIô)
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = AioSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, AioOutline),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 8.dp)
        ) {
            if (conversationHistory.isEmpty() && partialTranscript.isEmpty() && currentStreamResponse.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val emptyNotice = if (isWhatsApp) {
                        "Chamada do WhatsApp (VoIP) em andamento.\nFale diretamente usando o microfone do seu aparelho."
                    } else if (!isAutoModeEnabled) {
                        "Assistente em pausa.\nVocê está no comando: fale diretamente usando o seu microfone."
                    } else {
                        "Aguardando fala do interlocutor..."
                    }
                    Text(
                        text = emptyNotice,
                        fontSize = 14.sp,
                        color = AioTextSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(conversationHistory) { msg ->
                        val isAI = msg.role == "assistant"
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = if (isAI) Alignment.End else Alignment.Start
                        ) {
                            Text(
                                text = if (isAI) "AIô" else "Interlocutor",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isAI) AioPineGreen else AioTextSecondary,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (isAI) AioSelection else AioSurfaceVariant,
                                modifier = Modifier.fillMaxWidth(0.85f)
                            ) {
                                Text(
                                    text = msg.content,
                                    fontSize = 14.sp,
                                    color = if (isAI) AioPineGreen else AioGraphite,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    }

                    // Transcrição em andamento do interlocutor
                    if (partialTranscript.isNotEmpty()) {
                        item {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.Start
                            ) {
                                Text(
                                    text = "Interlocutor (falando...)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AioTextSecondary,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = AioSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.fillMaxWidth(0.85f)
                                ) {
                                    Text(
                                        text = "$partialTranscript...",
                                        fontSize = 14.sp,
                                        color = AioGraphite,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Resposta em geração contínua do AIô
                    if (currentStreamResponse.isNotEmpty()) {
                        item {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.End
                            ) {
                                Text(
                                    text = "AIô (respondendo...)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AioPineGreen,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = AioSelection,
                                    modifier = Modifier.fillMaxWidth(0.85f)
                                ) {
                                    Text(
                                        text = currentStreamResponse,
                                        fontSize = 14.sp,
                                        color = AioPineGreen,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 4. Controles da Ligação: Pausar AIô, Viva-voz e Encerrar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Botão Pausar / Retomar Assistente
            IconButton(
                onClick = onToggleAutoMode,
                modifier = Modifier
                    .size(56.dp)
                    .background(if (isAutoModeEnabled) AioSelection else AioSurfaceVariant, CircleShape)
            ) {
                Icon(
                    imageVector = if (isAutoModeEnabled) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = if (isAutoModeEnabled) "Pausar assistente" else "Ativar assistente",
                    tint = if (isAutoModeEnabled) AioPineGreen else AioTextSecondary,
                    modifier = Modifier.size(26.dp)
                )
            }

            // Botão Viva-voz
            IconButton(
                onClick = onToggleSpeaker,
                modifier = Modifier
                    .size(56.dp)
                    .background(if (isSpeakerActive) AioSelection else AioSurfaceVariant, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Outlined.VolumeUp,
                    contentDescription = "Viva-voz",
                    tint = if (isSpeakerActive) AioPineGreen else AioTextSecondary,
                    modifier = Modifier.size(26.dp)
                )
            }

            // Botão Encerrar Chamada (Vermelho reservado para encerramento)
            IconButton(
                onClick = onEndCall,
                modifier = Modifier
                    .size(64.dp)
                    .background(AioError, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Outlined.CallEnd,
                    contentDescription = "Encerrar chamada",
                    tint = Color.White,
                    modifier = Modifier.size(30.dp)
                )
            }
        }
    }
}
