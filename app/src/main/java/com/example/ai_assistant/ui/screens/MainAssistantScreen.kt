package com.example.ai_assistant.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai_assistant.adb.AdbInfo
import com.example.ai_assistant.adb.AdbState
import com.example.ai_assistant.api.ApiConfigManager
import com.example.ai_assistant.persona.Persona
import com.example.ai_assistant.shell.ShellDaemonInfo
import com.example.ai_assistant.shell.ShellDaemonState
import com.example.ai_assistant.tts.CallAudioPlayer
import com.example.ai_assistant.ui.components.AudioMirroredCurves
import com.example.ai_assistant.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun MainAssistantScreen(
    isDefaultDialer: Boolean,
    hasAudioPermission: Boolean,
    isAutoModeEnabled: Boolean,
    onToggleAutoMode: () -> Unit,
    activePersona: Persona,
    personas: List<Persona>,
    onSelectPersona: (String) -> Unit,
    apiConfigManager: ApiConfigManager,
    audioPlayer: CallAudioPlayer,
    onRequestDefaultDialer: () -> Unit,
    onRequestAudioPermission: () -> Unit,
    onTestAssistantClick: () -> Unit,
    adbInfo: AdbInfo? = null,
    daemonInfo: ShellDaemonInfo? = null,
    onOpenDeveloperSettings: (() -> Unit)? = null,
    onRefreshAdb: (() -> Unit)? = null,
    liveRms: Float = 0f,
    modifier: Modifier = Modifier
) {
    val settings by apiConfigManager.settings.collectAsState()
    val scope = rememberCoroutineScope()

    var showPersonaSheet by remember { mutableStateOf(false) }
    var showVoiceSheet by remember { mutableStateOf(false) }

    // Requisitos reais para funcionamento
    val hasValidKey = settings.effectiveLlmKey.isNotBlank() && settings.effectiveTtsKey.isNotBlank()
    val isRequirementsSatisfied = isDefaultDialer && hasAudioPermission && hasValidKey

    // Estado real: "Pronto", "Pausado" ou "Configurar"
    val (statusLabel, statusBg, statusColor) = when {
        !isRequirementsSatisfied -> Triple("Configurar", Color(0xFFFBE9E7), AioError)
        isAutoModeEnabled -> Triple("Pronto", AioSelection, AioPineGreen)
        else -> Triple("Pausado", AioSurfaceVariant, AioTextSecondary)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AioBackground)
            .padding(horizontal = 24.dp)
            .padding(bottom = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 1. Topo: Marca AIô e Botão Microfone de Teste
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Marca oficial AIô
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "AI",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = AioPineGreen,
                    letterSpacing = (-0.5).sp
                )
                Text(
                    text = "ô",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = AioGraphite,
                    letterSpacing = (-0.5).sp
                )
            }

            // Ação de microfone no topo para testar
            IconButton(
                onClick = onTestAssistantClick,
                modifier = Modifier
                    .size(48.dp)
                    .background(AioSelection, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Mic,
                    contentDescription = "Testar assistente",
                    tint = AioPineGreen,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Alerta de Módulo Privilegiado (apenas quando o daemon realmente não estiver rodando no sistema)
        val isDaemonOffline = daemonInfo?.state != ShellDaemonState.RUNNING
        if (isDaemonOffline) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFFFFF8E1),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFB300)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.WarningAmber,
                            contentDescription = "Aviso Módulo Offline",
                            tint = Color(0xFFE65100),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Módulo de Áudio Não Iniciado",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFE65100)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "O módulo em segundo plano precisa ser iniciado uma vez após ligar o aparelho. Ative a depuração ou inicie na aba Diagnóstico.",
                        fontSize = 12.sp,
                        color = Color(0xFF4E342E),
                        lineHeight = 16.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (onOpenDeveloperSettings != null) {
                            FilledTonalButton(
                                onClick = onOpenDeveloperSettings,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("Ativar Depuração Wi-Fi", fontSize = 11.sp)
                            }
                        }
                        if (onRefreshAdb != null) {
                            OutlinedButton(
                                onClick = onRefreshAdb,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("Verificar Módulo", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // 2. Centro: Símbolo discreto de duas curvas de áudio espelhadas e Estado
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            AudioMirroredCurves(
                liveRms = liveRms,
                isActive = isAutoModeEnabled && isRequirementsSatisfied
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Badge de Estado
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = statusBg,
                border = androidx.compose.foundation.BorderStroke(1.dp, if (!isRequirementsSatisfied) AioError.copy(alpha = 0.3f) else AioOutline)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(statusColor, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = statusLabel,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor
                    )
                }
            }

            // Aviso de Pendência Clicável se estiver em "Configurar"
            if (!isRequirementsSatisfied) {
                Spacer(modifier = Modifier.height(14.dp))
                val pendingText = when {
                    !hasAudioPermission -> "Permissão de microfone necessária"
                    !isDefaultDialer -> "Definir AIô como discador padrão"
                    else -> "Configurar chaves de conexão"
                }
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFFFFF3E0),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFB74D)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            when {
                                !hasAudioPermission -> onRequestAudioPermission()
                                !isDefaultDialer -> onRequestDefaultDialer()
                            }
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.WarningAmber,
                            contentDescription = null,
                            tint = Color(0xFFE65100),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = pendingText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFE65100),
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.Outlined.ChevronRight,
                            contentDescription = "Resolver",
                            tint = Color(0xFFE65100),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Transição suave de cores do botão principal
        val buttonBgColor by animateColorAsState(
            targetValue = if (isAutoModeEnabled) AioPineGreen else AioSelection,
            animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
            label = "AutoModeBtnBg"
        )
        val buttonTextColor by animateColorAsState(
            targetValue = if (isAutoModeEnabled) Color.White else AioPineGreen,
            animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
            label = "AutoModeBtnText"
        )

        // 3. Controle Principal Grande: Ativar ou Pausar com Animação Fluida
        Button(
            onClick = onToggleAutoMode,
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = buttonBgColor,
                contentColor = buttonTextColor
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
        ) {
            AnimatedContent(
                targetState = isAutoModeEnabled,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(220)) + scaleIn(initialScale = 0.88f))
                        .togetherWith(fadeOut(animationSpec = tween(160)) + scaleOut(targetScale = 0.88f))
                },
                label = "AutoModeBtnContent"
            ) { enabled ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = if (enabled) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (enabled) "Pausar assistente" else "Ativar assistente",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 4. Lista Compacta com "Perfil" e "Voz"
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = AioSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, AioOutline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                // Item 1: Perfil
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showPersonaSheet = true }
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Perfil",
                            fontSize = 13.sp,
                            color = AioTextSecondary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = activePersona.name,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AioGraphite
                        )
                    }
                    Icon(
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = "Alterar perfil",
                        tint = AioTextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                HorizontalDivider(color = AioOutline, thickness = 0.8.dp)

                // Item 2: Voz
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showVoiceSheet = true }
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Voz",
                            fontSize = 13.sp,
                            color = AioTextSecondary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = settings.ttsVoice,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AioGraphite
                        )
                    }
                    Icon(
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = "Alterar voz",
                        tint = AioTextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    // Modal de Troca Rápida de Perfil
    if (showPersonaSheet) {
        AlertDialog(
            onDismissRequest = { showPersonaSheet = false },
            confirmButton = {
                TextButton(onClick = { showPersonaSheet = false }) {
                    Text("Concluir", color = AioPineGreen, fontWeight = FontWeight.SemiBold)
                }
            },
            title = { Text("Selecionar Perfil", fontWeight = FontWeight.Bold, color = AioGraphite) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    personas.forEach { persona ->
                        val isSelected = persona.id == activePersona.id
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) AioSelection else AioSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectPersona(persona.id)
                                    showPersonaSheet = false
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        onSelectPersona(persona.id)
                                        showPersonaSheet = false
                                    },
                                    colors = RadioButtonDefaults.colors(selectedColor = AioPineGreen)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = persona.name,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isSelected) AioPineGreen else AioGraphite
                                    )
                                    Text(
                                        text = persona.description,
                                        fontSize = 12.sp,
                                        color = AioTextSecondary,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            },
            containerColor = AioSurface,
            shape = RoundedCornerShape(20.dp)
        )
    }

    // Modal de Troca Rápida de Voz
    if (showVoiceSheet) {
        val availableVoices = listOf("Puck", "Charon", "Aoede", "Fenrir", "Kore", "Alloy", "Echo")
        AlertDialog(
            onDismissRequest = { showVoiceSheet = false },
            confirmButton = {
                TextButton(onClick = { showVoiceSheet = false }) {
                    Text("Concluir", color = AioPineGreen, fontWeight = FontWeight.SemiBold)
                }
            },
            title = { Text("Selecionar Voz", fontWeight = FontWeight.Bold, color = AioGraphite) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    availableVoices.forEach { voice ->
                        val isSelected = settings.ttsVoice.equals(voice, ignoreCase = true)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) AioSelection else AioSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    apiConfigManager.updateSetting { copy(ttsVoice = voice) }
                                    scope.launch {
                                        val client = com.example.ai_assistant.api.TtsApiClient(audioPlayer.context)
                                        val testSettings = settings.copy(ttsVoice = voice)
                                        val res = client.synthesizeToPcm("Olá, esta é a voz $voice.", testSettings)
                                        res.onSuccess { audioPlayer.playAudio(it.samples, it.sampleRate) }
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = voice,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) AioPineGreen else AioGraphite
                                )
                                if (isSelected) {
                                    Text("Ativa", color = AioPineGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            },
            containerColor = AioSurface,
            shape = RoundedCornerShape(20.dp)
        )
    }
}
