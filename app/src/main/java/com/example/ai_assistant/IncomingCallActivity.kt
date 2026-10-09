package com.example.ai_assistant

import android.app.KeyguardManager
import android.os.Bundle
import android.os.SystemClock
import android.telecom.Call
import android.telecom.VideoProfile
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai_assistant.ui.theme.AI_assistantTheme
import kotlinx.coroutines.delay

/**
 * Activity dedicada para a interface de Chamada Recebida e Chamada em Andamento.
 */
class IncomingCallActivity : ComponentActivity() {

    companion object {
        private const val TAG = "IncomingCallActivity"
        const val EXTRA_CALL_ID = "extra_call_id"
    }

    private var currentCallId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Configurações para exibir sobre a tela de bloqueio e ligar a tela
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val keyguardManager = getSystemService(KeyguardManager::class.java)
        keyguardManager?.requestDismissKeyguard(this, null)

        Log.i(TAG, "[UI CALL] IncomingCallActivity opened")

        currentCallId = intent.getStringExtra(EXTRA_CALL_ID)

        val apiConfigManager = com.example.ai_assistant.api.ApiConfigManager(applicationContext)
        val callConvManager = com.example.ai_assistant.callconversation.CallConversationManager.getInstance(
            context = applicationContext,
            apiConfigManager = apiConfigManager
        )
        val adbManager = com.example.ai_assistant.adb.AdbManager.getInstance(applicationContext)
        val shellDaemonManager = com.example.ai_assistant.shell.ShellDaemonManager.getInstance(applicationContext, adbManager)

        setContent {
            AI_assistantTheme {
                val callStateInfo by CallRepository.activeCallState.collectAsState()
                val callStateName by CallStateManager.callStateName.collectAsState()
                val callConvState by callConvManager.state.collectAsState()
                val callConvHistory by callConvManager.conversationHistory.collectAsState()
                val callConvPartial by callConvManager.partialTranscript.collectAsState()
                val callConvStream by callConvManager.currentStreamResponse.collectAsState()
                val callConvAutoMode by callConvManager.isAutoModeEnabled.collectAsState()
                val callConvRms by callConvManager.liveRms.collectAsState()
                val callConvSpeech by callConvManager.isSpeechDetected.collectAsState()
                val adbInfo by adbManager.adbInfo.collectAsState()
                val daemonInfo by shellDaemonManager.daemonInfo.collectAsState()
                val diagnostics by callConvManager.diagnostics.collectAsState()
                val isDaemonOffline = daemonInfo.state != com.example.ai_assistant.shell.ShellDaemonState.RUNNING && !diagnostics.isDownlinkActive

                var hasEverBeenActiveOrRinging by remember { mutableStateOf(false) }

                val isCurrentlyActiveOrRinging = callStateInfo != null && (
                    callStateInfo?.state == Call.STATE_RINGING ||
                    callStateInfo?.state == Call.STATE_ACTIVE ||
                    callStateInfo?.state == Call.STATE_DIALING ||
                    callStateInfo?.state == Call.STATE_HOLDING ||
                    callStateInfo?.state == Call.STATE_CONNECTING
                )

                if (isCurrentlyActiveOrRinging) {
                    hasEverBeenActiveOrRinging = true
                }

                // Se a chamada foi encerrada, desconectada ou removida, fecha imediatamente e volta para a tela inicial
                LaunchedEffect(callStateInfo, callStateName) {
                    val isDisconnected = callStateName == "DISCONNECTED" ||
                            callStateName == "NONE" ||
                            callStateInfo == null ||
                            callStateInfo?.state == Call.STATE_DISCONNECTED

                    if (isDisconnected) {
                        if (hasEverBeenActiveOrRinging) {
                            Log.i(TAG, "[UI CALL] Chamada finalizada ($callStateName). Retornando automaticamente para a tela inicial...")
                            delay(400)
                            finishAndRemoveTask()
                        } else {
                            // Se a tela abriu mas a chamada já não existe mais, fecha imediatamente
                            delay(600)
                            if (callStateInfo == null || callStateInfo?.state == Call.STATE_DISCONNECTED) {
                                finishAndRemoveTask()
                            }
                        }
                    }
                }

                val state = callStateInfo?.state ?: Call.STATE_DISCONNECTED
                val isActive = state == Call.STATE_ACTIVE || state == Call.STATE_DIALING || state == Call.STATE_HOLDING || state == Call.STATE_CONNECTING

                if (isActive) {
                    com.example.ai_assistant.ui.screens.ActiveCallScreen(
                        callerName = callStateInfo?.callerName ?: "Desconhecido",
                        phoneNumber = callStateInfo?.phoneNumber ?: "",
                        callState = callConvState,
                        conversationHistory = callConvHistory,
                        partialTranscript = callConvPartial,
                        currentStreamResponse = callConvStream,
                        liveRms = callConvRms,
                        isSpeechDetected = callConvSpeech,
                        isAutoModeEnabled = callConvAutoMode,
                        onToggleAutoMode = {
                            val newMode = !callConvAutoMode
                            callConvManager.setAutoModeEnabled(newMode)
                            CallRepository.setBotActiveForCall(newMode)
                            if (newMode) {
                                try {
                                    CallRepository.setCallMicrophoneMute(true)
                                    AICallInCallService.sendDaemonMute(true)
                                } catch (ignored: Exception) {}
                                callConvManager.startCallConversation()
                            } else {
                                try {
                                    CallRepository.setCallMicrophoneMute(false)
                                    AICallInCallService.sendDaemonMute(false)
                                } catch (ignored: Exception) {}
                                callConvManager.stopCallConversation(clearHistory = false)
                            }
                        },
                        onEndCall = { disconnectCall() },
                        onToggleSpeaker = { CallRepository.toggleSpeaker() },
                        isSpeakerActive = callStateInfo?.audioRoute == android.telecom.CallAudioState.ROUTE_SPEAKER,
                        isDaemonOffline = isDaemonOffline,
                        isWhatsApp = callStateInfo?.isWhatsApp == true
                    )
                } else {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = com.example.ai_assistant.ui.theme.AioBackground
                    ) { innerPadding ->
                        CallScreenContent(
                            callStateInfo = callStateInfo,
                            onAnswerPressed = { answerCall() },
                            onRejectPressed = { rejectCall() },
                            onDisconnectPressed = { disconnectCall() },
                            onToggleMutePressed = { CallRepository.toggleMute() },
                            onToggleSpeakerPressed = { CallRepository.toggleSpeaker() },
                            modifier = Modifier.padding(innerPadding)
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (CallStateManager.callStateName.value == "NONE" || CallStateManager.callStateName.value == "DISCONNECTED") {
            window.decorView.postDelayed({
                finishAndRemoveTask()
            }, 400)
        }
    }

    private fun answerCall() {
        val callId = currentCallId ?: return
        val call = CallRepository.getCall(callId)
        if (call != null) {
            Log.i(TAG, "[UI CALL] Answer pressed manualmente pelo usuário")
            CallRepository.setBotActiveForCall(false)
            val convManager = com.example.ai_assistant.callconversation.CallConversationManager.getInstance(
                applicationContext,
                com.example.ai_assistant.api.ApiConfigManager(applicationContext)
            )
            convManager.setAutoModeEnabled(false)
            call.answer(VideoProfile.STATE_AUDIO_ONLY)
            CallNotificationManager.cancelNotification(this)
            // Força a rota padrão para Auricular (Earpiece), garantindo que não inicie em viva-voz
            CallRepository.setAudioRoute(android.telecom.CallAudioState.ROUTE_EARPIECE)
        } else {
            Log.w(TAG, "[UI CALL] Falha ao atender: Call não encontrada no repositório.")
        }
    }

    private fun rejectCall() {
        val callId = currentCallId ?: return
        val call = CallRepository.getCall(callId)
        if (call != null) {
            Log.i(TAG, "[UI CALL] Reject pressed")
            call.reject(false, null)
            CallNotificationManager.cancelNotification(this)
        } else {
            Log.w(TAG, "[UI CALL] Falha ao recusar: Call não encontrada no repositório.")
        }
        finishAndRemoveTask()
    }

    private fun disconnectCall() {
        try {
            val callId = currentCallId
            val call = callId?.let { CallRepository.getCall(it) } ?: CallRepository.currentCall
            if (call != null) {
                Log.i(TAG, "[UI CALL] Disconnect pressed")
                call.disconnect()
                CallNotificationManager.cancelNotification(this)
            } else {
                Log.w(TAG, "[UI CALL] Falha ao desconectar: Call não encontrada no repositório.")
            }
        } catch (_: Exception) {}
        window.decorView.postDelayed({
            finishAndRemoveTask()
        }, 400)
    }
}

@Composable
fun CallScreenContent(
    callStateInfo: CallRepository.CallStateInfo?,
    onAnswerPressed: () -> Unit,
    onRejectPressed: () -> Unit,
    onDisconnectPressed: () -> Unit,
    onToggleMutePressed: () -> Unit,
    onToggleSpeakerPressed: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state = callStateInfo?.state ?: Call.STATE_DISCONNECTED
    val isRinging = state == Call.STATE_RINGING || state == Call.STATE_SIMULATED_RINGING
    val isActive = state == Call.STATE_ACTIVE || state == Call.STATE_DIALING || state == Call.STATE_HOLDING
    val isDisconnected = state == Call.STATE_DISCONNECTED

    var callDurationSeconds by remember { mutableLongStateOf(0L) }

    // Cronômetro para chamada ativa
    LaunchedEffect(state) {
        if (state == Call.STATE_ACTIVE) {
            val startTime = SystemClock.elapsedRealtime()
            while (true) {
                callDurationSeconds = (SystemClock.elapsedRealtime() - startTime) / 1000
                delay(1000)
            }
        } else {
            callDurationSeconds = 0L
        }
    }

    val durationText = remember(callDurationSeconds) {
        val mins = callDurationSeconds / 60
        val secs = callDurationSeconds % 60
        "%02d:%02d".format(mins, secs)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Cabeçalho da Chamada
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 48.dp)
        ) {
            // Avatar simples
            Card(
                shape = CircleShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.size(100.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = callStateInfo?.callerName?.take(1)?.uppercase() ?: "?",
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = callStateInfo?.callerName ?: "Chamada Desconhecida",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = callStateInfo?.phoneNumber ?: "",
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = if (state == Call.STATE_ACTIVE) durationText else (callStateInfo?.stateString ?: "Desconectado"),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (state == Call.STATE_ACTIVE) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary
            )
        }

        // Área de Ações
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(bottom = 32.dp)
        ) {
            if (isRinging) {
                // BOTOES ATENDER / RECUSAR
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Botão Recusar
                    Button(
                        onClick = onRejectPressed,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                        modifier = Modifier.size(72.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CallEnd,
                            contentDescription = "Recusar",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    // Botão Atender
                    Button(
                        onClick = onAnswerPressed,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                        modifier = Modifier.size(72.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Call,
                            contentDescription = "Atender",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Text("Recusar", fontSize = 14.sp, color = Color(0xFFC62828), fontWeight = FontWeight.Bold)
                    Text("Atender", fontSize = 14.sp, color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                }

            } else if (isActive) {
                // BOTOES MUTE / VIVA-VOZ / ENCERRAR
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Botão Mute
                    Button(
                        onClick = onToggleMutePressed,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (callStateInfo?.isMuted == true) Color(0xFFFF9800) else MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        Text(
                            text = if (callStateInfo?.isMuted == true) "Mute: ON" else "Mute: OFF",
                            color = if (callStateInfo?.isMuted == true) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Botão Viva-Voz / Auricular
                    val isSpeaker = callStateInfo?.audioRoute == android.telecom.CallAudioState.ROUTE_SPEAKER
                    Button(
                        onClick = onToggleSpeakerPressed,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSpeaker) Color(0xFF0288D1) else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(
                            text = if (isSpeaker) "Viva-Voz" else "Auricular",
                            color = if (isSpeaker) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Botão Encerrar
                Button(
                    onClick = onDisconnectPressed,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text("Encerrar Chamada", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }

            } else if (isDisconnected) {
                Text(
                    text = "Chamada Encerrada",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Red
                )
            }
        }
    }
}
