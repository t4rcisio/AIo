package com.example.ai_assistant.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai_assistant.api.ApiConfigManager
import com.example.ai_assistant.api.ApiSettings
import com.example.ai_assistant.api.LlmApiClient
import com.example.ai_assistant.api.TtsApiClient
import com.example.ai_assistant.llm.ChatMessage
import com.example.ai_assistant.tts.CallAudioPlayer
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ApiConfigCard(
    apiConfigManager: ApiConfigManager,
    audioPlayer: CallAudioPlayer,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isDark = isSystemInDarkTheme()
    val settings by apiConfigManager.settings.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    var showAdvanced by remember { mutableStateOf(false) }
    var showApiKey by remember { mutableStateOf(false) }
    var testStatusText by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }

    // Campos de estado local
    var geminiKey by remember(settings.geminiApiKey) { mutableStateOf(settings.geminiApiKey) }
    var deepseekKey by remember(settings.deepseekApiKey) { mutableStateOf(settings.deepseekApiKey) }
    var showKeys by remember { mutableStateOf(false) }
    var llmModel by remember(settings.llmModel) { mutableStateOf(settings.llmModel) }
    var llmBaseUrl by remember(settings.llmBaseUrl) { mutableStateOf(settings.llmBaseUrl) }
    var sttModel by remember(settings.sttModel) { mutableStateOf(settings.sttModel) }
    var ttsVoice by remember(settings.ttsVoice) { mutableStateOf(settings.ttsVoice) }
    var autoAnswer by remember(settings.autoAnswer) { mutableStateOf(settings.autoAnswer) }
    var autoSpeaker by remember(settings.autoSpeakerphone) { mutableStateOf(settings.autoSpeakerphone) }
    var deepseekThinking by remember(settings.deepseekThinking) { mutableStateOf(settings.deepseekThinking) }

    val primaryGreen = if (isDark) Color(0xFF81C784) else Color(0xFF2E7D32)
    val cardBg = if (isDark) Color(0xFF131D17) else Color(0xFFF3F9F4)
    val borderColor = if (isDark) Color(0xFF27432E) else Color(0xFFC4E2CC)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = BorderStroke(1.dp, borderColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Cabeçalho
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.CloudQueue,
                            contentDescription = null,
                            tint = if (isDark) Color(0xFFA5D6A7) else Color(0xFF1B5E20),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Configuração das APIs em Nuvem",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isDark) Color(0xFFA5D6A7) else Color(0xFF1B5E20)
                        )
                    }
                    Text(
                        text = "Gemini STT/TTS + DeepSeek LLM (Thinking)",
                        fontSize = 11.sp,
                        color = if (isDark) Color(0xFF81C784) else Color(0xFF388E3C)
                    )
                }
                TextButton(onClick = { showAdvanced = !showAdvanced }) {
                    Text(if (showAdvanced) "Menos" else "Avançado", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Campo Chave DeepSeek
            OutlinedTextField(
                value = deepseekKey,
                onValueChange = {
                    deepseekKey = it
                    apiConfigManager.updateSettings(settings.copy(deepseekApiKey = it))
                },
                label = { Text("Chave DeepSeek API (LLM)") },
                placeholder = { Text("sk-...") },
                supportingText = { Text("Chave de API da plataforma DeepSeek", fontSize = 10.sp, color = Color.Gray) },
                singleLine = true,
                visualTransformation = if (showKeys) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showKeys = !showKeys }) {
                        Icon(
                            imageVector = if (showKeys) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (showKeys) "Ocultar chave" else "Exibir chave",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Campo Chave Gemini
            OutlinedTextField(
                value = geminiKey,
                onValueChange = {
                    geminiKey = it
                    apiConfigManager.updateSettings(settings.copy(geminiApiKey = it))
                },
                label = { Text("Chave Gemini API (STT & TTS)") },
                placeholder = { Text("AIzaSy... ou AQ...") },
                supportingText = { Text("Chave de API do Google AI Studio (Gemini)", fontSize = 10.sp, color = Color.Gray) },
                singleLine = true,
                visualTransformation = if (showKeys) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showKeys = !showKeys }) {
                        Icon(
                            imageVector = if (showKeys) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (showKeys) "Ocultar chave" else "Exibir chave",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Modelo LLM e Voz TTS em destaque
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = llmModel,
                    onValueChange = {
                        llmModel = it
                        apiConfigManager.updateSettings(settings.copy(llmModel = it))
                    },
                    label = { Text("Modelo LLM") },
                    placeholder = { Text("deepseek-flash") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )

                OutlinedTextField(
                    value = ttsVoice,
                    onValueChange = {
                        ttsVoice = it
                        apiConfigManager.updateSettings(settings.copy(ttsVoice = it))
                    },
                    label = { Text("Voz Gemini") },
                    placeholder = { Text("Puck") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Chips rápidos de voz Gemini
            Text("Vozes Disponíveis (Gemini):", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf("Puck", "Aoede", "Charon", "Fenrir", "Kore").forEach { v ->
                    val isSel = ttsVoice.equals(v, ignoreCase = true)
                    FilterChip(
                        selected = isSel,
                        onClick = {
                            ttsVoice = v
                            apiConfigManager.updateSettings(settings.copy(ttsVoice = v))
                        },
                        label = { Text(v, fontSize = 11.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Opções de Chamada Telefônica
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Atender chamada automaticamente",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Switch(
                    checked = autoAnswer,
                    onCheckedChange = {
                        autoAnswer = it
                        apiConfigManager.updateSettings(settings.copy(autoAnswer = it))
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Forçar viva-voz para injeção de áudio",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Switch(
                    checked = autoSpeaker,
                    onCheckedChange = {
                        autoSpeaker = it
                        apiConfigManager.updateSettings(settings.copy(autoSpeakerphone = it))
                    }
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Modo Raciocínio (DeepSeek Thinking)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (deepseekThinking) "Ativado (mais lento, gera reflexão)" else "Desativado (resposta imediata para ligação)",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }
                Switch(
                    checked = deepseekThinking,
                    onCheckedChange = {
                        deepseekThinking = it
                        apiConfigManager.updateSettings(settings.copy(deepseekThinking = it))
                    }
                )
            }

            // Seção Avançada (URLs e modelos específicos)
            AnimatedVisibility(visible = showAdvanced) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Text("Endpoints Customizados:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = llmBaseUrl,
                        onValueChange = {
                            llmBaseUrl = it
                            apiConfigManager.updateSettings(settings.copy(llmBaseUrl = it))
                        },
                        label = { Text("Base URL do LLM (OpenAI-compatible)") },
                        placeholder = { Text("https://api.openai.com/v1") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = sttModel,
                        onValueChange = {
                            sttModel = it
                            apiConfigManager.updateSettings(settings.copy(sttModel = it))
                        },
                        label = { Text("Modelo de Transcrição (STT)") },
                        placeholder = { Text("whisper-1") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Botão de Testar Conexão das APIs
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        isTesting = true
                        testStatusText = "Testando LLM e TTS..."
                        coroutineScope.launch {
                            try {
                                val current = apiConfigManager.settings.value
                                val llmClient = LlmApiClient()
                                val ttsClient = TtsApiClient(context = context)

                                val testMsg = listOf(
                                    ChatMessage(role = "system", content = "Você é um assistente rápido."),
                                    ChatMessage(role = "user", content = "Diga apenas: 'Conexão OK, assistente pronto.'")
                                )

                                val llmRes = llmClient.chat(testMsg, current)
                                val text = llmRes.getOrThrow()

                                testStatusText = "LLM OK: \"$text\". Sintetizando voz..."
                                val ttsRes = ttsClient.synthesizeToPcm(text, current)
                                val audio = ttsRes.getOrThrow()

                                audioPlayer.enqueueAudio(audio.samples, audio.sampleRate)
                                testStatusText = "[OK] Teste concluído com sucesso! Áudio reproduzido."
                            } catch (e: Exception) {
                                testStatusText = "[ERRO] Erro no teste: ${e.message}"
                            } finally {
                                isTesting = false
                            }
                        }
                    },
                    enabled = !isTesting && settings.effectiveLlmKey.isNotBlank() && settings.effectiveTtsKey.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = primaryGreen),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text("Testar Conexão das APIs (LLM + TTS)")
                }
            }

            testStatusText?.let { text ->
                val isSuccess = text.startsWith("[OK]")
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSuccess) Color(0x1F4CAF50) else Color(0x1FE91E63)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(10.dp)
                    ) {
                        Icon(
                            imageVector = if (isSuccess) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = if (isSuccess) primaryGreen else Color(0xFFD32F2F),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = text.removePrefix("[OK] ").removePrefix("[ERRO] "),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (isSuccess) primaryGreen else Color(0xFFD32F2F)
                        )
                    }
                }
            }
        }
    }
}
