package com.example.ai_assistant.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai_assistant.api.ApiConfigManager
import com.example.ai_assistant.api.LlmApiClient
import com.example.ai_assistant.api.TtsApiClient
import com.example.ai_assistant.llm.ChatMessage
import com.example.ai_assistant.persona.Persona
import com.example.ai_assistant.tts.CallAudioPlayer
import com.example.ai_assistant.ui.theme.*
import kotlinx.coroutines.launch

import com.example.ai_assistant.ui.AudioPreviewPlayer

@Composable
fun TestAssistantDialog(
    activePersona: Persona,
    apiConfigManager: ApiConfigManager,
    audioPlayer: CallAudioPlayer,
    onDismiss: () -> Unit
) {
    val settings by apiConfigManager.settings.collectAsState()
    val scope = rememberCoroutineScope()

    var testInput by remember { mutableStateOf("Olá, quem está falando?") }
    var assistantReply by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    val isPlayingAudio by AudioPreviewPlayer.isPlaying.collectAsState()

    DisposableEffect(Unit) {
        onDispose {
            AudioPreviewPlayer.stop()
        }
    }

    AlertDialog(
        onDismissRequest = {
            AudioPreviewPlayer.stop()
            onDismiss()
        },
        confirmButton = {
            TextButton(onClick = {
                AudioPreviewPlayer.stop()
                onDismiss()
            }) {
                Text("Fechar", color = AioPineGreen, fontWeight = FontWeight.SemiBold)
            }
        },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Testar AIô",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = AioGraphite
                )
                IconButton(
                    onClick = {
                        AudioPreviewPlayer.stop()
                        onDismiss()
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(imageVector = Icons.Outlined.Close, contentDescription = "Fechar", tint = AioTextSecondary)
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Perfil: ${activePersona.name} • Voz: ${settings.ttsVoice}",
                    fontSize = 12.sp,
                    color = AioTextSecondary
                )

                OutlinedTextField(
                    value = testInput,
                    onValueChange = { testInput = it },
                    label = { Text("Mensagem do interlocutor") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = AioGraphite,
                        unfocusedTextColor = AioGraphite,
                        focusedLabelColor = AioPineGreen,
                        unfocusedLabelColor = AioTextSecondary,
                        focusedBorderColor = AioPineGreen,
                        unfocusedBorderColor = AioOutline,
                        cursorColor = AioPineGreen,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    ),
                    singleLine = false,
                    maxLines = 3
                )

                Button(
                    onClick = {
                        scope.launch {
                            isLoading = true
                            assistantReply = null
                            try {
                                val llm = LlmApiClient()
                                val tts = TtsApiClient(audioPlayer.context)

                                val messages = listOf(
                                    ChatMessage("system", activePersona.systemPrompt),
                                    ChatMessage("user", testInput.trim())
                                )
                                val replyRes = llm.generateReply(messages, settings)
                                replyRes.onSuccess { reply ->
                                    assistantReply = reply
                                    val ttsRes = tts.synthesizeToPcm(reply, settings)
                                    ttsRes.onSuccess { audio ->
                                        AudioPreviewPlayer.play(audio.samples, audio.sampleRate)
                                    }.onFailure { err ->
                                        assistantReply = "$reply\n\n(Erro no TTS: ${err.message})"
                                    }
                                }.onFailure { e ->
                                    assistantReply = "Erro no LLM: ${e.message}"
                                }
                            } catch (e: Exception) {
                                assistantReply = "Erro: ${e.message}"
                            } finally {
                                isLoading = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isPlayingAudio) AioSelection else AioPineGreen,
                        contentColor = if (isPlayingAudio) AioPineGreen else Color.White
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = testInput.isNotBlank() && !isLoading,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = if (isPlayingAudio) Icons.Outlined.VolumeUp else Icons.Outlined.Send,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        when {
                            isLoading -> "Processando fala..."
                            isPlayingAudio -> "Reproduzindo áudio..."
                            else -> "Enviar e Ouvir Resposta"
                        }
                    )
                }

                assistantReply?.let { reply ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = AioSelection,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "AIô respondeu:",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = AioPineGreen
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = reply,
                                fontSize = 14.sp,
                                color = AioGraphite
                            )
                        }
                    }
                }
            }
        },
        containerColor = AioSurface,
        shape = RoundedCornerShape(20.dp)
    )
}
