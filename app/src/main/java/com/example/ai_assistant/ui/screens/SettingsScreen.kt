package com.example.ai_assistant.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai_assistant.adb.AdbInfo
import com.example.ai_assistant.api.ApiConfigManager
import com.example.ai_assistant.api.LlmApiClient
import com.example.ai_assistant.api.TtsApiClient
import com.example.ai_assistant.logging.InAppLogger
import com.example.ai_assistant.logging.LogLevel
import com.example.ai_assistant.logging.LogEntry
import com.example.ai_assistant.persona.Persona
import com.example.ai_assistant.shell.ShellDaemonInfo
import com.example.ai_assistant.tts.CallAudioPlayer
import com.example.ai_assistant.ui.theme.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import android.content.Intent
import android.net.Uri
import android.widget.Toast

enum class SettingsSection(val title: String) {
    ATENDIMENTO("Atendimento"),
    PERFIS("Perfis"),
    VOZ("Voz"),
    CONEXOES("Conexões"),
    DIAGNOSTICO("Diagnóstico"),
    LOGS("Logs"),
    SOBRE("Sobre")
}

@Composable
fun SettingsScreen(
    isDefaultDialer: Boolean,
    hasAudioPermission: Boolean,
    onRequestDefaultDialer: () -> Unit,
    onRequestAudioPermission: () -> Unit,
    apiConfigManager: ApiConfigManager,
    audioPlayer: CallAudioPlayer,
    personas: List<Persona>,
    activePersona: Persona,
    onSelectPersona: (String) -> Unit,
    onCreatePersona: (String, String, String) -> Unit,
    onUpdatePersona: (String, String, String, String) -> Unit,
    onDeletePersona: (String) -> Unit,
    onResetPersonaDefaults: () -> Unit,
    adbInfo: AdbInfo,
    daemonInfo: ShellDaemonInfo,
    onPairWithCode: (String, Int?) -> Unit,
    onOpenDeveloperSettings: () -> Unit,
    onRefreshAdb: () -> Unit,
    onDisconnectAdb: () -> Unit,
    onTestAdbConnection: () -> Unit,
    onStartDaemon: () -> Unit,
    onTestDaemon: () -> Unit,
    onTestShellCallAudio: () -> Unit,
    onPlayAudio: () -> Unit,
    onStopAudio: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedSection by remember { mutableStateOf(SettingsSection.ATENDIMENTO) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AioBackground)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Ajustes",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = AioGraphite,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Barra de Abas das Seções
        ScrollableTabRow(
            selectedTabIndex = selectedSection.ordinal,
            containerColor = Color.Transparent,
            contentColor = AioPineGreen,
            edgePadding = 24.dp,
            divider = {},
            indicator = { tabPositions ->
                val currentTab = tabPositions[selectedSection.ordinal]
                Box(
                    modifier = Modifier
                        .tabIndicatorOffset(currentTab)
                        .height(3.dp)
                        .padding(horizontal = 12.dp)
                        .background(AioPineGreen, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                )
            }
        ) {
            SettingsSection.values().forEach { section ->
                val isSelected = selectedSection == section
                Tab(
                    selected = isSelected,
                    onClick = { selectedSection = section },
                    text = {
                        Text(
                            text = section.title,
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) AioPineGreen else AioTextSecondary
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Conteúdo da Seção Selecionada com Transição Fluida
        AnimatedContent(
            targetState = selectedSection,
            transitionSpec = {
                val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                (slideInHorizontally(
                    animationSpec = spring(
                        stiffness = Spring.StiffnessMediumLow,
                        dampingRatio = Spring.DampingRatioNoBouncy
                    ),
                    initialOffsetX = { fullWidth -> (fullWidth * 0.14f * direction).toInt() }
                ) + fadeIn(
                    animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
                )).togetherWith(
                    slideOutHorizontally(
                        animationSpec = spring(
                            stiffness = Spring.StiffnessMediumLow,
                            dampingRatio = Spring.DampingRatioNoBouncy
                        ),
                        targetOffsetX = { fullWidth -> (-fullWidth * 0.14f * direction).toInt() }
                    ) + fadeOut(
                        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)
                    )
                )
            },
            label = "SettingsSectionTransition",
            modifier = Modifier.fillMaxSize()
        ) { section ->
            val isLogsSection = section == SettingsSection.LOGS
            val sectionModifier = if (isLogsSection) {
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 12.dp)
            } else {
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 96.dp)
            }

            Column(
                modifier = sectionModifier,
                verticalArrangement = Arrangement.spacedBy(if (isLogsSection) 12.dp else 16.dp)
            ) {
                when (section) {
                    SettingsSection.ATENDIMENTO -> {
                        AtendimentoSection(
                            isDefaultDialer = isDefaultDialer,
                            hasAudioPermission = hasAudioPermission,
                            onRequestDefaultDialer = onRequestDefaultDialer,
                            onRequestAudioPermission = onRequestAudioPermission,
                            apiConfigManager = apiConfigManager
                        )
                    }
                    SettingsSection.PERFIS -> {
                        PerfisSection(
                            personas = personas,
                            activePersona = activePersona,
                            onSelectPersona = onSelectPersona,
                            onCreatePersona = onCreatePersona,
                            onUpdatePersona = onUpdatePersona,
                            onDeletePersona = onDeletePersona,
                            onResetDefaults = onResetPersonaDefaults
                        )
                    }
                    SettingsSection.VOZ -> {
                        VozSection(
                            apiConfigManager = apiConfigManager,
                            audioPlayer = audioPlayer
                        )
                    }
                    SettingsSection.CONEXOES -> {
                        ConexoesSection(
                            apiConfigManager = apiConfigManager,
                            audioPlayer = audioPlayer
                        )
                    }
                    SettingsSection.LOGS -> {
                        LogsSection(
                            apiConfigManager = apiConfigManager,
                            daemonInfo = daemonInfo,
                            adbInfo = adbInfo
                        )
                    }
                    SettingsSection.DIAGNOSTICO -> {
                        DiagnosticoSection(
                            adbInfo = adbInfo,
                            daemonInfo = daemonInfo,
                            onPairWithCode = onPairWithCode,
                            onOpenDeveloperSettings = onOpenDeveloperSettings,
                            onRefreshAdb = onRefreshAdb,
                            onDisconnectAdb = onDisconnectAdb,
                            onTestAdbConnection = onTestAdbConnection,
                            onStartDaemon = onStartDaemon,
                            onTestDaemon = onTestDaemon,
                            onTestShellCallAudio = onTestShellCallAudio,
                            onPlayAudio = onPlayAudio,
                            onStopAudio = onStopAudio
                        )
                    }
                    SettingsSection.SOBRE -> {
                        SobreSection()
                    }
                }
            }
        }
    }
}

@Composable
private fun AtendimentoSection(
    isDefaultDialer: Boolean,
    hasAudioPermission: Boolean,
    onRequestDefaultDialer: () -> Unit,
    onRequestAudioPermission: () -> Unit,
    apiConfigManager: ApiConfigManager
) {
    val settings by apiConfigManager.settings.collectAsState()

    // 1. Discador Padrão
    SettingsCard(
        title = "Discador Padrão",
        subtitle = if (isDefaultDialer) "Ativo como aplicativo principal de telefone" else "Necessário para atender ligações recebidas",
        trailing = {
            if (!isDefaultDialer) {
                Button(
                    onClick = onRequestDefaultDialer,
                    colors = ButtonDefaults.buttonColors(containerColor = AioPineGreen)
                ) {
                    Text("Definir")
                }
            } else {
                Surface(shape = RoundedCornerShape(8.dp), color = AioSelection) {
                    Text("Ativo", color = AioPineGreen, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
        }
    )

    // 2. Permissão de Microfone
    SettingsCard(
        title = "Permissão de Microfone",
        subtitle = if (hasAudioPermission) "Acesso ao áudio concedido" else "Necessário para a voz do assistente",
        trailing = {
            if (!hasAudioPermission) {
                Button(
                    onClick = onRequestAudioPermission,
                    colors = ButtonDefaults.buttonColors(containerColor = AioPineGreen)
                ) {
                    Text("Solicitar")
                }
            } else {
                Surface(shape = RoundedCornerShape(8.dp), color = AioSelection) {
                    Text("Concedida", color = AioPineGreen, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
        }
    )

    // 3. Atendimento Automático (Celular)
    SettingsCard(
        title = "Atendimento Automático (Operadora)",
        subtitle = "Atender ligações normais via chip SIM imediatamente ao tocar",
        trailing = {
            Switch(
                checked = settings.autoAnswer,
                onCheckedChange = { apiConfigManager.updateSetting { copy(autoAnswer = it) } },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AioPineGreen)
            )
        }
    )

    // 4. Chamadas do WhatsApp (VoIP)
    SettingsCard(
        title = "Atender Chamadas do WhatsApp",
        subtitle = "Permite que o assistente atenda e converse em ligações recebidas no WhatsApp",
        trailing = {
            Switch(
                checked = settings.autoAnswerWhatsApp,
                onCheckedChange = { apiConfigManager.updateSetting { copy(autoAnswerWhatsApp = it) } },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AioPineGreen)
            )
        }
    )
}

@Composable
private fun PerfisSection(
    personas: List<Persona>,
    activePersona: Persona,
    onSelectPersona: (String) -> Unit,
    onCreatePersona: (String, String, String) -> Unit,
    onUpdatePersona: (String, String, String, String) -> Unit,
    onDeletePersona: (String) -> Unit,
    onResetDefaults: () -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    var editingPersona by remember { mutableStateOf<Persona?>(null) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Perfis de Atendimento", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = AioGraphite)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    editingPersona = null
                    showDialog = true
                },
                modifier = Modifier
                    .size(40.dp)
                    .background(AioPineGreen, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = "Novo Perfil",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }

    personas.forEach { persona ->
        val isSelected = persona.id == activePersona.id
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (isSelected) AioSelection else AioSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) AioPineGreen else AioOutline),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelectPersona(persona.id) }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = isSelected,
                    onClick = { onSelectPersona(persona.id) },
                    colors = RadioButtonDefaults.colors(selectedColor = AioPineGreen)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = persona.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isSelected) AioPineGreen else AioGraphite
                    )
                    Text(
                        text = persona.description,
                        fontSize = 13.sp,
                        color = AioTextSecondary,
                        maxLines = 2
                    )
                }

                IconButton(
                    onClick = {
                        editingPersona = persona
                        showDialog = true
                    }
                ) {
                    Icon(imageVector = Icons.Outlined.Edit, contentDescription = "Editar", tint = AioTextSecondary)
                }
            }
        }
    }

    if (showDialog) {
        PersonaEditModal(
            persona = editingPersona,
            onDismiss = { showDialog = false },
            onSave = { name, desc, prompt ->
                if (editingPersona != null) {
                    onUpdatePersona(editingPersona!!.id, name, desc, prompt)
                } else {
                    onCreatePersona(name, desc, prompt)
                }
                showDialog = false
            },
            onDelete = editingPersona?.let { p ->
                if (!p.isDefault) {
                    {
                        onDeletePersona(p.id)
                        showDialog = false
                    }
                } else null
            }
        )
    }
}

@Composable
private fun PersonaEditModal(
    persona: Persona?,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
    onDelete: (() -> Unit)?
) {
    var name by remember { mutableStateOf(persona?.name ?: "") }
    var desc by remember { mutableStateOf(persona?.description ?: "") }
    var prompt by remember { mutableStateOf(persona?.systemPrompt ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = { onSave(name, desc, prompt) },
                colors = ButtonDefaults.buttonColors(containerColor = AioPineGreen),
                enabled = name.isNotBlank() && prompt.isNotBlank()
            ) {
                Text("Salvar")
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("Excluir", color = AioError)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancelar", color = AioTextSecondary)
                }
            }
        },
        title = {
            Text(if (persona != null) "Editar Perfil" else "Novo Perfil", fontWeight = FontWeight.Bold, color = AioGraphite)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val fieldColors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = AioGraphite,
                    unfocusedTextColor = AioGraphite,
                    focusedLabelColor = AioPineGreen,
                    unfocusedLabelColor = AioTextSecondary,
                    focusedBorderColor = AioPineGreen,
                    unfocusedBorderColor = AioOutline,
                    cursorColor = AioPineGreen,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome do perfil") },
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("Descrição breve") },
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Instruções de fala (Prompt)") },
                    minLines = 4,
                    maxLines = 8,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        containerColor = AioSurface,
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
private fun VozSection(
    apiConfigManager: ApiConfigManager,
    audioPlayer: CallAudioPlayer
) {
    val settings by apiConfigManager.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var isTesting by remember { mutableStateOf(false) }

    val voices = listOf("Puck", "Charon", "Aoede", "Fenrir", "Kore", "Alloy", "Echo", "Fable", "Onyx", "Nova", "Shimmer")

    SettingsCard(
        title = "Provedor de Voz",
        subtitle = "Motor neural de síntese de fala",
        trailing = {
            Surface(shape = RoundedCornerShape(8.dp), color = AioSelection) {
                Text(
                    text = if (settings.ttsProvider == "gemini") "Gemini Speech" else "OpenAI",
                    color = AioPineGreen,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    )

    Text("Voz Selecionada:", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AioGraphite)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        voices.chunked(3).forEach { rowVoices ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowVoices.forEach { voice ->
                    val isSelected = settings.ttsVoice.equals(voice, ignoreCase = true)
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) AioSelection else AioSurface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) AioPineGreen else AioOutline),
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                apiConfigManager.updateSetting { copy(ttsVoice = voice) }
                            }
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(vertical = 12.dp)
                        ) {
                            Text(
                                text = voice,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) AioPineGreen else AioGraphite
                            )
                        }
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(14.dp))

    val isPlayingPreview by com.example.ai_assistant.ui.AudioPreviewPlayer.isPlaying.collectAsState()

    Button(
        onClick = {
            scope.launch {
                isTesting = true
                try {
                    val client = TtsApiClient(audioPlayer.context)
                    val res = client.synthesizeToPcm("Olá! Eu sou a voz ${settings.ttsVoice} do AIô.", settings)
                    res.onSuccess {
                        com.example.ai_assistant.ui.AudioPreviewPlayer.play(it.samples, it.sampleRate)
                    }
                } finally {
                    isTesting = false
                }
            }
        },
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isPlayingPreview) AioSelection else AioPineGreen,
            contentColor = if (isPlayingPreview) AioPineGreen else Color.White
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(imageVector = Icons.Outlined.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            when {
                isTesting -> "Carregando voz..."
                isPlayingPreview -> "Reproduzindo prévia..."
                else -> "Testar Voz Atual (${settings.ttsVoice})"
            }
        )
    }
}

@Composable
private fun ConexoesSection(
    apiConfigManager: ApiConfigManager,
    audioPlayer: CallAudioPlayer
) {
    val context = LocalContext.current
    val settings by apiConfigManager.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var testResult by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }

    var isKeysCardExpanded by remember { mutableStateOf(false) }
    var inputDeepseekKey by remember(settings.deepseekApiKey) { mutableStateOf(settings.deepseekApiKey) }
    var inputGeminiKey by remember(settings.geminiApiKey) { mutableStateOf(settings.geminiApiKey) }
    var showDeepseekKey by remember { mutableStateOf(false) }
    var showGeminiKey by remember { mutableStateOf(false) }
    var saveFeedback by remember { mutableStateOf<String?>(null) }

    val hasDeepseekKey = settings.effectiveDeepseekKey.isNotBlank()
    val hasGeminiKey = settings.effectiveGeminiKey.isNotBlank()
    val allKeysConfigured = hasDeepseekKey && hasGeminiKey

    SettingsCard(
        title = "Modelo de IA (LLM)",
        subtitle = "${settings.llmProvider.uppercase()}: ${settings.llmModel}",
        trailing = {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (hasDeepseekKey) AioSelection else Color(0xFFFFEBEE)
            ) {
                Text(
                    text = if (hasDeepseekKey) "Pronto" else "Sem Chave",
                    color = if (hasDeepseekKey) AioPineGreen else AioError,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    )

    SettingsCard(
        title = "Transcrição e Voz (STT/TTS)",
        subtitle = "Gemini / Whisper Speech-to-Text",
        trailing = {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (hasGeminiKey) AioSelection else Color(0xFFFFEBEE)
            ) {
                Text(
                    text = if (hasGeminiKey) "Pronto" else "Sem Chave",
                    color = if (hasGeminiKey) AioPineGreen else AioError,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    )

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = AioSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, AioOutline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isKeysCardExpanded = !isKeysCardExpanded }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Chaves de API",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AioGraphite
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (allKeysConfigured) AioSelection else Color(0xFFFFEBEE)
                        ) {
                            Text(
                                text = if (allKeysConfigured) "Configuradas" else "Pendente",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (allKeysConfigured) AioPineGreen else AioError,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (isKeysCardExpanded) "Edite ou insira suas chaves abaixo" else "Toque para visualizar ou editar as chaves",
                        fontSize = 12.sp,
                        color = AioTextSecondary
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (allKeysConfigured) Icons.Outlined.Lock else Icons.Outlined.Key,
                        contentDescription = null,
                        tint = if (allKeysConfigured) AioPineGreen else AioError,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = if (isKeysCardExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (isKeysCardExpanded) "Recolher" else "Expandir",
                        tint = AioTextSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            AnimatedVisibility(visible = isKeysCardExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 16.dp)
                ) {
                    HorizontalDivider(color = AioOutline)
                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "DeepSeek API Key (LLM)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AioGraphite
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = inputDeepseekKey,
                        onValueChange = {
                            inputDeepseekKey = it
                            saveFeedback = null
                        },
                        placeholder = { Text("sk-...", fontSize = 13.sp) },
                        visualTransformation = if (showDeepseekKey) VisualTransformation.None else PasswordVisualTransformation(),
                        singleLine = true,
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { showDeepseekKey = !showDeepseekKey }) {
                                    Icon(
                                        imageVector = if (showDeepseekKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                        contentDescription = if (showDeepseekKey) "Ocultar" else "Exibir",
                                        tint = AioTextSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                IconButton(onClick = {
                                    val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip
                                    if (clip != null && clip.itemCount > 0) {
                                        inputDeepseekKey = clip.getItemAt(0).text?.toString() ?: ""
                                        saveFeedback = null
                                    }
                                }) {
                                    Icon(
                                        imageVector = Icons.Outlined.ContentPaste,
                                        contentDescription = "Colar",
                                        tint = AioPineGreen,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Text(
                        text = "Responsável pelo raciocínio e respostas em texto da IA.",
                        fontSize = 11.sp,
                        color = AioTextSecondary,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "Google Gemini API Key (STT e TTS)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AioGraphite
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = inputGeminiKey,
                        onValueChange = {
                            inputGeminiKey = it
                            saveFeedback = null
                        },
                        placeholder = { Text("AIzaSy... ou chave do Google AI Studio", fontSize = 13.sp) },
                        visualTransformation = if (showGeminiKey) VisualTransformation.None else PasswordVisualTransformation(),
                        singleLine = true,
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { showGeminiKey = !showGeminiKey }) {
                                    Icon(
                                        imageVector = if (showGeminiKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                        contentDescription = if (showGeminiKey) "Ocultar" else "Exibir",
                                        tint = AioTextSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                IconButton(onClick = {
                                    val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip
                                    if (clip != null && clip.itemCount > 0) {
                                        inputGeminiKey = clip.getItemAt(0).text?.toString() ?: ""
                                        saveFeedback = null
                                    }
                                }) {
                                    Icon(
                                        imageVector = Icons.Outlined.ContentPaste,
                                        contentDescription = "Colar",
                                        tint = AioPineGreen,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Text(
                        text = "Responsável pela transcrição de áudio e síntese de voz.",
                        fontSize = 11.sp,
                        color = AioTextSecondary,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            apiConfigManager.updateSettings(
                                settings.copy(
                                    deepseekApiKey = inputDeepseekKey.trim(),
                                    llmApiKey = inputDeepseekKey.trim(),
                                    geminiApiKey = inputGeminiKey.trim(),
                                    sttApiKey = inputGeminiKey.trim(),
                                    ttsApiKey = inputGeminiKey.trim()
                                )
                            )
                            saveFeedback = "Chaves salvas com sucesso no aplicativo!"
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AioPineGreen),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(imageVector = Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Salvar Chaves no Aplicativo")
                    }

                    saveFeedback?.let { msg ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = AioSelection,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(imageVector = Icons.Outlined.CheckCircle, contentDescription = null, tint = AioPineGreen, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = msg, fontSize = 12.sp, color = AioPineGreen, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }
    }

    Button(
        onClick = {
            scope.launch {
                isTesting = true
                testResult = null
                try {
                    val llm = LlmApiClient()
                    val reply = llm.generateReply(
                        messages = listOf(com.example.ai_assistant.llm.ChatMessage("user", "Responda apenas: OK")),
                        settings = settings
                    )
                    testResult = if (reply.isSuccess) "Conexão estabelecida com sucesso!" else "Falha: ${reply.exceptionOrNull()?.message}"
                } catch (e: Exception) {
                    testResult = "Erro: ${e.message}"
                } finally {
                    isTesting = false
                }
            }
        },
        colors = ButtonDefaults.buttonColors(containerColor = AioPineGreen),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(if (isTesting) "Testando conexões..." else "Testar Conexão das APIs")
    }

    testResult?.let { msg ->
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (msg.contains("sucesso", ignoreCase = true)) AioSelection else Color(0xFFFFEBEE),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = msg,
                fontSize = 13.sp,
                color = if (msg.contains("sucesso", ignoreCase = true)) AioPineGreen else AioError,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

@Composable
private fun DiagnosticoSection(
    adbInfo: AdbInfo,
    daemonInfo: ShellDaemonInfo,
    onPairWithCode: (String, Int?) -> Unit,
    onOpenDeveloperSettings: () -> Unit,
    onRefreshAdb: () -> Unit,
    onDisconnectAdb: () -> Unit,
    onTestAdbConnection: () -> Unit,
    onStartDaemon: () -> Unit,
    onTestDaemon: () -> Unit,
    onTestShellCallAudio: () -> Unit,
    onPlayAudio: () -> Unit,
    onStopAudio: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isAdbConnected = adbInfo.state == com.example.ai_assistant.adb.AdbState.CONNECTED
    val isDaemonRunning = daemonInfo.state == com.example.ai_assistant.shell.ShellDaemonState.RUNNING

    var isReconnectingAdb by remember { mutableStateOf(false) }
    var isStartingDaemon by remember { mutableStateOf(false) }

    SettingsCard(
        title = "Depuração sem Fio (ADB)",
        subtitle = if (isAdbConnected) {
            "Conectado ao dispositivo local (porta ${adbInfo.discoveredConnectPort ?: 5555})"
        } else {
            adbInfo.errorMessage ?: adbInfo.statusMessage
        },
        trailing = {
            Surface(shape = RoundedCornerShape(8.dp), color = if (isAdbConnected) AioSelection else AioSurfaceVariant) {
                Text(
                    text = if (isAdbConnected) "Ativo" else "Inativo",
                    color = if (isAdbConnected) AioPineGreen else AioTextSecondary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    )

    SettingsCard(
        title = "Módulo de Áudio do Sistema",
        subtitle = if (isDaemonRunning) {
            "Captura e injeção celular ativa (PID ${daemonInfo.pid ?: "OK"})"
        } else {
            daemonInfo.errorMessage ?: daemonInfo.statusMessage
        },
        trailing = {
            Surface(shape = RoundedCornerShape(8.dp), color = if (isDaemonRunning) AioSelection else AioSurfaceVariant) {
                Text(
                    text = if (isDaemonRunning) "Pronto" else "Aguardando",
                    color = if (isDaemonRunning) AioPineGreen else AioTextSecondary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = {
                if (!isReconnectingAdb) {
                    isReconnectingAdb = true
                    Toast.makeText(context, "Buscando porta e reconectando ADB...", Toast.LENGTH_SHORT).show()
                    onRefreshAdb()
                    scope.launch {
                        delay(2500)
                        isReconnectingAdb = false
                        if (adbInfo.state == com.example.ai_assistant.adb.AdbState.CONNECTED) {
                            Toast.makeText(context, "ADB Conectado com sucesso!", Toast.LENGTH_SHORT).show()
                        } else {
                            val msg = adbInfo.errorMessage ?: adbInfo.statusMessage
                            Toast.makeText(context, "Falha ao conectar ADB: $msg", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
            enabled = !isReconnectingAdb,
            modifier = Modifier.weight(1f)
        ) {
            if (isReconnectingAdb) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text("Reconectar ADB", color = AioGraphite)
        }
        OutlinedButton(
            onClick = {
                if (!isStartingDaemon) {
                    isStartingDaemon = true
                    Toast.makeText(context, "Inicializando Módulo de Áudio...", Toast.LENGTH_SHORT).show()
                    onStartDaemon()
                    scope.launch {
                        delay(3000)
                        isStartingDaemon = false
                        if (daemonInfo.state == com.example.ai_assistant.shell.ShellDaemonState.RUNNING) {
                            Toast.makeText(context, "Módulo de Áudio ativo com sucesso!", Toast.LENGTH_SHORT).show()
                        } else {
                            val msg = daemonInfo.errorMessage ?: daemonInfo.statusMessage
                            Toast.makeText(context, "Falha ao iniciar módulo: $msg", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
            enabled = !isStartingDaemon,
            modifier = Modifier.weight(1f)
        ) {
            if (isStartingDaemon) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text("Iniciar Módulo", color = AioGraphite)
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = AioSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, AioOutline),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AioGraphite)
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = subtitle, fontSize = 12.sp, color = AioTextSecondary)
            }
            trailing()
        }
    }
}

@Composable
private fun ColumnScope.LogsSection(
    apiConfigManager: ApiConfigManager,
    daemonInfo: ShellDaemonInfo,
    adbInfo: AdbInfo
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logs by InAppLogger.logs.collectAsState()
    val settings by apiConfigManager.settings.collectAsState()

    var isRunningDiagnostics by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf<LogLevel?>(null) }

    LaunchedEffect(Unit) {
        if (logs.isEmpty()) {
            InAppLogger.info("Sistema", "Acompanhamento em tempo real ativo. Status ADB: ${adbInfo.state.name} | Módulo: ${daemonInfo.state.name}")
        }
    }

    // Botão de Diagnóstico Completo de Conexões
    Button(
        onClick = {
            scope.launch {
                isRunningDiagnostics = true
                InAppLogger.info("Diagnóstico", "=== TESTANDO CONEXÕES DE SERVIÇOS ===")

                // Teste 1: LLM DeepSeek
                try {
                    InAppLogger.info("LLM", "Testando ${settings.llmProvider} (${settings.llmModel})...")
                    val llm = LlmApiClient()
                    val t0 = System.currentTimeMillis()
                    val reply = llm.generateReply(
                        messages = listOf(com.example.ai_assistant.llm.ChatMessage("user", "Responda apenas: OK")),
                        settings = settings
                    )
                    val dt = System.currentTimeMillis() - t0
                    if (reply.isSuccess) {
                        InAppLogger.success("LLM", "LLM Conectado (${dt}ms)! Resposta: \"${reply.getOrNull()?.trim()}\"")
                    } else {
                        InAppLogger.error("LLM", "Falha HTTP no LLM: ${reply.exceptionOrNull()?.message}")
                    }
                } catch (e: Exception) {
                    InAppLogger.error("LLM", "Exceção LLM: ${e.message}")
                }

                // Teste 2: STT Gemini
                try {
                    InAppLogger.info("STT", "Verificando configuração de transcrição (${settings.sttProvider})...")
                    val key = settings.effectiveGeminiKey
                    if (key.isNotBlank()) {
                        InAppLogger.success("STT", "Chave Gemini configurada (${key.take(8)}...${key.takeLast(4)}).")
                    } else {
                        InAppLogger.warn("STT", "Nenhuma chave Gemini encontrada.")
                    }
                } catch (e: Exception) {
                    InAppLogger.error("STT", "Erro STT: ${e.message}")
                }

                // Teste 3: Módulo Shell Daemon
                try {
                    InAppLogger.info("Módulo", "Testando socket do daemon em 127.0.0.1:28472...")
                    val isAlive = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            val socket = java.net.Socket()
                            socket.connect(java.net.InetSocketAddress("127.0.0.1", 28472), 1500)
                            socket.close()
                            true
                        } catch (_: Exception) {
                            false
                        }
                    }
                    if (isAlive) {
                        InAppLogger.success("Módulo", "Daemon ativo e respondendo na porta 28472!")
                    } else {
                        InAppLogger.warn("Módulo", "Daemon offline na porta 28472. Inicie na aba Diagnóstico.")
                    }
                } catch (e: Exception) {
                    InAppLogger.error("Módulo", "Erro ao verificar daemon: ${e.message}")
                }

                InAppLogger.info("Diagnóstico", "=== TESTE DE CONEXÕES CONCLUÍDO ===")
                isRunningDiagnostics = false
            }
        },
        colors = ButtonDefaults.buttonColors(containerColor = AioPineGreen),
        modifier = Modifier.fillMaxWidth(),
        enabled = !isRunningDiagnostics
    ) {
        Icon(imageVector = Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(if (isRunningDiagnostics) "Testando Conexões..." else "Testar Conexões das APIs")
    }

    // Barra de Ações dos Logs (Título + Copiar + Limpar com botões folgados sem quebrar texto)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${logs.size} registros",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = AioTextSecondary
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val clip = ClipData.newPlainText("Logs AIô", InAppLogger.getAllAsText())
                    clipboard?.setPrimaryClip(clip)
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = AioSelection,
                    contentColor = AioPineGreen
                )
            ) {
                Icon(imageVector = Icons.Outlined.ContentCopy, contentDescription = "Copiar", modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Copiar",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false
                )
            }

            FilledTonalButton(
                onClick = { InAppLogger.clear() },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = AioError.copy(alpha = 0.12f),
                    contentColor = AioError
                )
            ) {
                Icon(imageVector = Icons.Outlined.DeleteOutline, contentDescription = "Limpar", modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Limpar",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }

    // Filtros de nível de log com rolagem horizontal
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val filters = listOf(
            null to "Todos",
            LogLevel.ERROR to "Erros",
            LogLevel.SUCCESS to "Sucessos",
            LogLevel.INFO to "Infos",
            LogLevel.WARN to "Avisos"
        )
        filters.forEach { (level, label) ->
            val isSelected = selectedFilter == level
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isSelected) AioPineGreen else AioSurfaceVariant,
                modifier = Modifier.clickable { selectedFilter = level }
            ) {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) Color.White else AioGraphite,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
    }

    // Lista de Logs ao Vivo (expande dinamicamente e é a única com scroll vertical)
    val filteredLogs = remember(logs, selectedFilter) {
        if (selectedFilter == null) logs.reversed() else logs.filter { it.level == selectedFilter }.reversed()
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF1E2022),
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
    ) {
        if (filteredLogs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Nenhum log registrado ainda.",
                    color = Color.LightGray.copy(alpha = 0.6f),
                    fontSize = 13.sp
                )
            }
        } else {
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filteredLogs, key = { it.id }) { log ->
                    val color = when (log.level) {
                        LogLevel.SUCCESS -> Color(0xFF4CAF50)
                        LogLevel.ERROR -> Color(0xFFFF5252)
                        LogLevel.WARN -> Color(0xFFFFB74D)
                        LogLevel.INFO -> Color(0xFF81D4FA)
                    }
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = log.formattedTime,
                                color = Color.Gray,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "[${log.level.label}]",
                                color = color,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "[${log.tag}]",
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Text(
                            text = log.message,
                            color = Color(0xFFECEFF1),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SobreSection() {
    val context = LocalContext.current

    // 1. Logo e Marca AIô
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = AioSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, AioOutline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "AI",
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Bold,
                    color = AioPineGreen,
                    letterSpacing = (-0.5).sp
                )
                Text(
                    text = "ô",
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Bold,
                    color = AioGraphite,
                    letterSpacing = (-0.5).sp
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Assistente Inteligente de Chamadas Telefônicas & VoIP",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = AioGraphite
            )
            Spacer(modifier = Modifier.height(4.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = AioSelection,
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text(
                    text = "Versão 1.0.0 (Build 1)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = AioPineGreen,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
    }

    // 2. Cartão do Autor / Desenvolvedor
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = AioSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, AioOutline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Person,
                    contentDescription = null,
                    tint = AioPineGreen,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Tarcísio Prates",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = AioGraphite
                    )
                    Text(
                        text = "Engenheiro de Computação",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = AioTextSecondary
                    )
                    Text(
                        text = "CEFET-MG",
                        fontSize = 12.sp,
                        color = AioPineGreen,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
            HorizontalDivider(color = AioOutline)
            Spacer(modifier = Modifier.height(14.dp))

            // Botões de Links Externos (LinkedIn e GitHub)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/t4rcisio")).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "Não foi possível abrir o navegador: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Code,
                        contentDescription = null,
                        tint = AioGraphite,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("GitHub", color = AioGraphite, fontSize = 13.sp)
                }

                FilledTonalButton(
                    onClick = {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.linkedin.com/in/t4rcisio/")).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "Não foi possível abrir o navegador: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Share,
                        contentDescription = null,
                        tint = AioPineGreen,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("LinkedIn", color = AioPineGreen, fontSize = 13.sp)
                }
            }
        }
    }

    // 3. Arquitetura e Engenharia
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = AioSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, AioOutline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = AioPineGreen,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Destaques da Engenharia",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AioGraphite
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "• Injeção de áudio celular e captura bidirecional via Native Shell Daemon (app_process 28472)\n" +
                       "• Transmissão contínua em streaming de baixa latência com Gemini STT/TTS e DeepSeek Reasoning\n" +
                       "• Interceptação unificada de chamadas celulares (SIM) e VoIP auto-gerenciado (WhatsApp Telecom ConnectionService)\n" +
                       "• Zero latência de bloqueio com Coroutines e canal de comunicação loopback em IPC local",
                fontSize = 12.sp,
                color = AioTextSecondary,
                lineHeight = 18.sp
            )
        }
    }
}

