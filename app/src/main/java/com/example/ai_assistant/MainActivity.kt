package com.example.ai_assistant

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.ai_assistant.adb.AdbManager
import com.example.ai_assistant.api.ApiConfigManager
import com.example.ai_assistant.callconversation.CallConversationManager
import com.example.ai_assistant.history.CallHistoryManager
import com.example.ai_assistant.persona.PersonaManager
import com.example.ai_assistant.shell.ShellDaemonManager
import com.example.ai_assistant.ui.components.AioBottomNavigation
import com.example.ai_assistant.ui.components.NavigationTab
import com.example.ai_assistant.ui.components.TestAssistantDialog
import com.example.ai_assistant.ui.screens.ActiveCallScreen
import com.example.ai_assistant.ui.screens.HistoryScreen
import com.example.ai_assistant.ui.screens.MainAssistantScreen
import com.example.ai_assistant.ui.screens.SettingsScreen
import com.example.ai_assistant.ui.theme.AI_assistantTheme

class MainActivity : ComponentActivity() {

    private lateinit var adbManager: AdbManager
    private lateinit var shellDaemonManager: ShellDaemonManager
    private lateinit var apiConfigManager: ApiConfigManager
    private lateinit var callConversationManager: CallConversationManager
    private lateinit var personaManager: PersonaManager
    private lateinit var historyManager: CallHistoryManager

    private var isDefaultDialerState by mutableStateOf(false)
    private var hasAudioPermissionState by mutableStateOf(false)

    private val requestRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        checkDefaultDialerStatus()
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        checkAudioPermissionStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        adbManager = AdbManager.getInstance(applicationContext)
        shellDaemonManager = ShellDaemonManager.getInstance(applicationContext, adbManager)
        apiConfigManager = ApiConfigManager(applicationContext)
        personaManager = PersonaManager.getInstance(applicationContext)
        historyManager = CallHistoryManager.getInstance(applicationContext)

        callConversationManager = CallConversationManager.getInstance(
            context = applicationContext,
            apiConfigManager = apiConfigManager
        )

        // Sincroniza o prompt da persona ativa
        val initialPrompt = personaManager.activePersona.value.systemPrompt
        callConversationManager.setSystemPrompt(initialPrompt)

        com.example.ai_assistant.logging.InAppLogger.info("Sistema", "AIô iniciado. Monitorando serviços locais e telefonia.")

        setContent {
            AI_assistantTheme {
                val isCallActive by CallStateManager.isCallActive.collectAsState()
                val adbInfo by adbManager.adbInfo.collectAsState()
                val daemonInfo by shellDaemonManager.daemonInfo.collectAsState()

                // Estados da Conversação por Chamada
                val callConvState by callConversationManager.state.collectAsState()
                val callConvHistory by callConversationManager.conversationHistory.collectAsState()
                val callConvPartial by callConversationManager.partialTranscript.collectAsState()
                val callConvStream by callConversationManager.currentStreamResponse.collectAsState()
                val callConvAutoMode by callConversationManager.isAutoModeEnabled.collectAsState()
                val callConvRms by callConversationManager.liveRms.collectAsState()
                val callConvSpeech by callConversationManager.isSpeechDetected.collectAsState()

                // Informações da Chamada Ativa no Repositório
                val activeCallInfo by CallRepository.activeCallState.collectAsState()

                // Personas
                val personas by personaManager.personas.collectAsState()
                val activePersona by personaManager.activePersona.collectAsState()

                var currentTab by remember { mutableStateOf(NavigationTab.ASSISTENTE) }
                var showTestDialog by remember { mutableStateOf(false) }

                LaunchedEffect(activePersona) {
                    callConversationManager.setSystemPrompt(activePersona.systemPrompt)
                }

                val hasOngoingCall = isCallActive || (activeCallInfo != null && activeCallInfo?.state != android.telecom.Call.STATE_DISCONNECTED)
                if (hasOngoingCall) {
                    // Tela Dedicada Durante a Ligação
                    ActiveCallScreen(
                        callerName = activeCallInfo?.callerName ?: "Desconhecido",
                        phoneNumber = activeCallInfo?.phoneNumber ?: "",
                        callState = callConvState,
                        conversationHistory = callConvHistory,
                        partialTranscript = callConvPartial,
                        currentStreamResponse = callConvStream,
                        liveRms = callConvRms,
                        isSpeechDetected = callConvSpeech,
                        isAutoModeEnabled = callConvAutoMode,
                        onToggleAutoMode = { callConversationManager.setAutoModeEnabled(!callConvAutoMode) },
                        onEndCall = {
                            CallRepository.disconnectActiveCall()
                        },
                        onToggleSpeaker = { CallRepository.toggleSpeaker() },
                        isSpeakerActive = activeCallInfo?.audioRoute == android.telecom.CallAudioState.ROUTE_SPEAKER,
                        isDaemonOffline = daemonInfo.state != com.example.ai_assistant.shell.ShellDaemonState.RUNNING,
                        isWhatsApp = activeCallInfo?.isWhatsApp == true
                    )
                } else {
                    Scaffold(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding(),
                        bottomBar = {
                            AioBottomNavigation(
                                currentTab = currentTab,
                                onTabSelected = { currentTab = it }
                            )
                        }
                    ) { innerPadding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        ) {
                            AnimatedContent(
                                targetState = currentTab,
                                transitionSpec = {
                                    val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                                    (slideInHorizontally(
                                        animationSpec = spring(
                                            stiffness = Spring.StiffnessMediumLow,
                                            dampingRatio = Spring.DampingRatioNoBouncy
                                        ),
                                        initialOffsetX = { fullWidth -> (fullWidth * 0.16f * direction).toInt() }
                                    ) + fadeIn(
                                        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
                                    )).togetherWith(
                                        slideOutHorizontally(
                                            animationSpec = spring(
                                                stiffness = Spring.StiffnessMediumLow,
                                                dampingRatio = Spring.DampingRatioNoBouncy
                                            ),
                                            targetOffsetX = { fullWidth -> (-fullWidth * 0.16f * direction).toInt() }
                                        ) + fadeOut(
                                            animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)
                                        )
                                    )
                                },
                                label = "MainTabTransition",
                                modifier = Modifier.fillMaxSize()
                            ) { tab ->
                                when (tab) {
                                    NavigationTab.ASSISTENTE -> {
                                        MainAssistantScreen(
                                            isDefaultDialer = isDefaultDialerState,
                                            hasAudioPermission = hasAudioPermissionState,
                                            isAutoModeEnabled = callConvAutoMode,
                                            onToggleAutoMode = { callConversationManager.setAutoModeEnabled(!callConvAutoMode) },
                                            activePersona = activePersona,
                                            personas = personas,
                                            onSelectPersona = { personaManager.selectPersona(it) },
                                            apiConfigManager = apiConfigManager,
                                            audioPlayer = callConversationManager.audioPlayer,
                                            onRequestDefaultDialer = { requestDefaultDialerRole() },
                                            onRequestAudioPermission = { requestAudioPermission() },
                                            onTestAssistantClick = { showTestDialog = true },
                                            adbInfo = adbInfo,
                                            daemonInfo = daemonInfo,
                                            onOpenDeveloperSettings = { adbManager.openDeveloperSettings() },
                                            onRefreshAdb = { adbManager.startAutoDiscoveryAndConnect() },
                                            liveRms = callConvRms
                                        )
                                    }
                                    NavigationTab.HISTORICO -> {
                                        HistoryScreen(
                                            historyManager = historyManager
                                        )
                                    }
                                    NavigationTab.AJUSTES -> {
                                        SettingsScreen(
                                            isDefaultDialer = isDefaultDialerState,
                                            hasAudioPermission = hasAudioPermissionState,
                                            onRequestDefaultDialer = { requestDefaultDialerRole() },
                                            onRequestAudioPermission = { requestAudioPermission() },
                                            apiConfigManager = apiConfigManager,
                                            audioPlayer = callConversationManager.audioPlayer,
                                            personas = personas,
                                            activePersona = activePersona,
                                            onSelectPersona = { personaManager.selectPersona(it) },
                                            onCreatePersona = { name, desc, prompt -> personaManager.createPersona(name, desc, prompt) },
                                            onUpdatePersona = { id, name, desc, prompt -> personaManager.updatePersona(id, name, desc, prompt) },
                                            onDeletePersona = { personaManager.deletePersona(it) },
                                            onResetPersonaDefaults = { personaManager.resetToDefaults() },
                                            adbInfo = adbInfo,
                                            daemonInfo = daemonInfo,
                                            onPairWithCode = { code, port -> adbManager.pairWithCode(code, port) },
                                            onOpenDeveloperSettings = { adbManager.openDeveloperSettings() },
                                            onRefreshAdb = { adbManager.startAutoDiscoveryAndConnect() },
                                            onDisconnectAdb = { adbManager.disconnect() },
                                            onTestAdbConnection = { adbManager.testConnection() },
                                            onStartDaemon = { shellDaemonManager.startDaemon() },
                                            onTestDaemon = { shellDaemonManager.testDaemon() },
                                            onTestShellCallAudio = { shellDaemonManager.testCallAudio(false) },
                                            onPlayAudio = { shellDaemonManager.playRecordedAudio() },
                                            onStopAudio = { shellDaemonManager.stopAudioPlayback() }
                                        )
                                    }
                                }
                            }

                            if (showTestDialog) {
                                TestAssistantDialog(
                                    activePersona = activePersona,
                                    apiConfigManager = apiConfigManager,
                                    audioPlayer = callConversationManager.audioPlayer,
                                    onDismiss = { showTestDialog = false }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkDefaultDialerStatus()
        checkAudioPermissionStatus()
        adbManager.startAutoDiscoveryAndConnect()
    }

    override fun onDestroy() {
        super.onDestroy()
        callConversationManager.release()
        shellDaemonManager.stopAudioPlayback()
    }

    private fun checkDefaultDialerStatus() {
        val roleManager = getSystemService(RoleManager::class.java)
        val isHeld = if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
            roleManager.isRoleHeld(RoleManager.ROLE_DIALER)
        } else {
            val telecomManager = getSystemService(TelecomManager::class.java)
            telecomManager?.defaultDialerPackage == packageName
        }
        isDefaultDialerState = isHeld
    }

    private fun checkAudioPermissionStatus() {
        hasAudioPermissionState = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestDefaultDialerRole() {
        val roleManager = getSystemService(RoleManager::class.java)
        if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
            val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
            requestRoleLauncher.launch(intent)
        } else {
            @Suppress("DEPRECATION")
            val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
            }
            requestRoleLauncher.launch(intent)
        }
    }

    private fun requestAudioPermission() {
        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
}
