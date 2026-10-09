package com.example.ai_assistant

import android.content.Intent
import android.telecom.Call
import android.telecom.CallEndpoint
import android.telecom.InCallService
import android.util.Log

/**
 * AICallInCallService é o serviço central que recebe os eventos do Telecom Framework do Android.
 *
 * Na ETAPA 4:
 * Integramos com o CallRepository, CallNotificationManager e IncomingCallActivity para fornecer
 * a UI de chamada recebida e em andamento exigida pelo SO para o ROLE_DIALER.
 */
class AICallInCallService : InCallService() {

    companion object {
        private const val TAG = "AICallInCallService"
    }

    // Armazena o endpoint ativo mais recente recebido via callback
    private var activeCallEndpoint: CallEndpoint? = null

    // Armazena em memória os endpoints disponíveis mais recentes fornecidos pelo sistema
    private var availableEndpointsList: List<CallEndpoint> = emptyList()

    // Armazena o estado atual do mudo (mute)
    private var isCallMuted: Boolean = false

    override fun onCreate() {
        super.onCreate()
        CallRepository.setInCallService(this)
        com.example.ai_assistant.callconversation.CallConversationManager.getInstance(
            applicationContext,
            com.example.ai_assistant.api.ApiConfigManager(applicationContext)
        )
    }

    private fun launchCallScreen(callId: String) {
        try {
            val activityIntent = Intent(this, IncomingCallActivity::class.java).apply {
                putExtra(IncomingCallActivity.EXTRA_CALL_ID, callId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(activityIntent)
            Log.i(TAG, "[UI CALL] IncomingCallActivity lançada para callId: $callId")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao abrir tela de chamada: ${e.message}", e)
        }
    }

    private fun sendDaemonMute(mute: Boolean) {
        Thread {
            try {
                java.net.Socket().use { s ->
                    s.connect(java.net.InetSocketAddress("127.0.0.1", 28472), 500)
                    val writer = java.io.PrintWriter(s.getOutputStream(), true)
                    writer.println(if (mute) "MUTE_LOCAL_AUDIO" else "UNMUTE_LOCAL_AUDIO")
                }
            } catch (ignored: Exception) {}
        }.start()
    }

    private fun getCallState(call: Call): Int {
        return call.details?.state ?: @Suppress("DEPRECATION") call.state
    }

    /**
     * Chamado pelo Android Telecom Framework quando uma nova chamada entra ou é iniciada.
     */
    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        CallRepository.setInCallService(this)

        val callId = CallRepository.registerCall(call)
        val currentState = getCallState(call)

        Log.i(TAG, "==================================================")
        Log.i(TAG, "[UI CALL] Call added: $callId | State: ${stateToString(currentState)}")
        logCallDetails(call)
        Log.i(TAG, "==================================================")

        // Callback para acompanhar as mudanças de estado da chamada
        call.registerCallback(object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                super.onStateChanged(call, state)
                CallRepository.updateStateInfo(callId, call, isCallMuted)
                Log.i(TAG, "[UI CALL] Call state changed to: ${stateToString(state)}")

                logAudioPolicy("Mudança de Estado para ${stateToString(state)}")

                when (state) {
                    Call.STATE_RINGING, Call.STATE_SIMULATED_RINGING -> CallStateManager.setCallState(false, "RINGING")
                    Call.STATE_ACTIVE -> {
                        Log.i(TAG, "[UI CALL] Call became ACTIVE")
                        CallNotificationManager.cancelNotification(applicationContext)
                        launchCallScreen(callId)
                        val convManager = com.example.ai_assistant.callconversation.CallConversationManager.getInstance(
                            applicationContext,
                            com.example.ai_assistant.api.ApiConfigManager(applicationContext)
                        )
                        if (convManager.isAutoModeEnabled.value) {
                            try {
                                setMuted(true)
                                CallRepository.setCallMicrophoneMute(true)
                            } catch (e: Exception) {
                                Log.w(TAG, "Erro ao mutar microfone: ${e.message}")
                            }
                        }
                        CallStateManager.setCallState(true, "ACTIVE")
                        logAudioEndpoints("Chamada Passou para STATE_ACTIVE")
                        try {
                            val am = getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
                            am?.isSpeakerphoneOn = false
                            setAudioRoute(android.telecom.CallAudioState.ROUTE_EARPIECE)
                            CallRepository.setAudioRoute(android.telecom.CallAudioState.ROUTE_EARPIECE)
                            // Envia ordem ao Daemon Shell para mutar STREAM_VOICE_CALL via cmd audio (UID 2000)
                            sendDaemonMute(true)
                            Log.i(TAG, "[SILÊNCIO LOCAL TOTAL] Alto-falante desativado e mute total aplicado no sistema.")
                        } catch (e: Exception) {
                            Log.e(TAG, "Falha ao silenciar áudio local: ${e.message}")
                        }
                    }
                    Call.STATE_DISCONNECTED -> {
                        Log.i(TAG, "[UI CALL] Call DISCONNECTED")
                        CallNotificationManager.cancelNotification(applicationContext)
                        CallStateManager.setCallState(false, "DISCONNECTED")
                        try {
                            setMuted(false)
                            CallRepository.setCallMicrophoneMute(false)
                        } catch (ignored: Exception) {}
                        sendDaemonMute(false)
                    }
                }
            }

            override fun onDetailsChanged(call: Call, details: Call.Details) {
                super.onDetailsChanged(call, details)
                CallRepository.updateStateInfo(callId, call, isCallMuted)
            }
        })

        // Diagnóstico de áudio da Etapa 2
        logAudioEndpoints("Chamada Entrou (onCallAdded)")

        // Se a chamada está tocando (STATE_RINGING), exibe a notificação e abre a IncomingCallActivity
        if (currentState == Call.STATE_RINGING || currentState == Call.STATE_SIMULATED_RINGING) {
            CallStateManager.setCallState(false, "RINGING")

            val phoneNumber = call.details?.handle?.schemeSpecificPart ?: "Desconhecido"
            val callerName = call.details?.callerDisplayName?.takeIf { it.isNotBlank() } ?: "Sem Nome"

            // 1. Notificação com FullScreenIntent para tela bloqueada/background
            CallNotificationManager.showIncomingCallNotification(
                context = applicationContext,
                callId = callId,
                phoneNumber = phoneNumber,
                callerName = callerName
            )

            // 2. Abrir diretamente a IncomingCallActivity
            launchCallScreen(callId)

            val isWhatsAppCall = call.details?.hasProperty(Call.Details.PROPERTY_SELF_MANAGED) == true ||
                    call.details?.accountHandle?.componentName?.packageName?.contains("whatsapp", ignoreCase = true) == true

            val apiSettings = com.example.ai_assistant.api.ApiConfigManager(applicationContext).settings.value
            val shouldAutoAnswer = if (isWhatsAppCall) {
                apiSettings.autoAnswerWhatsApp
            } else {
                apiSettings.autoAnswer
            }

            if (shouldAutoAnswer) {
                Log.i(TAG, "[AUTO ANSWER] Atendendo chamada automaticamente (${if (isWhatsAppCall) "WhatsApp VoIP" else "Operadora Celular"})...")
                call.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY)
            }
        } else {
            val isActive = currentState == Call.STATE_ACTIVE
            launchCallScreen(callId)
            CallStateManager.setCallState(isActive, stateToString(currentState))
        }
    }

    /**
     * Chamado pelo Android Telecom Framework quando uma chamada é encerrada ou removida.
     */
    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        val currentState = getCallState(call)

        Log.i(TAG, "==================================================")
        Log.i(TAG, "[UI CALL] Call removed. Estado no encerramento: ${stateToString(currentState)}")
        Log.i(TAG, "==================================================")

        CallRepository.unregisterCall(call)
        CallNotificationManager.cancelNotification(applicationContext)
        CallStateManager.setCallState(false, "NONE")
        sendDaemonMute(false)
    }

    // =========================================================================
    // CALLBACKS DE ÁUDIO E MUTE
    // =========================================================================

    override fun onCallAudioStateChanged(audioState: android.telecom.CallAudioState) {
        super.onCallAudioStateChanged(audioState)
        Log.i(TAG, "[EVENTO] onCallAudioStateChanged: route=${audioState.route}, isMuted=${audioState.isMuted}")
        CallRepository.updateAudioRoute(audioState.route)
        CallRepository.setMuteState(audioState.isMuted)
    }

    override fun onCallEndpointChanged(callEndpoint: CallEndpoint) {
        super.onCallEndpointChanged(callEndpoint)
        activeCallEndpoint = callEndpoint
        val route = when (callEndpoint.endpointType) {
            CallEndpoint.TYPE_SPEAKER -> android.telecom.CallAudioState.ROUTE_SPEAKER
            CallEndpoint.TYPE_BLUETOOTH -> android.telecom.CallAudioState.ROUTE_BLUETOOTH
            CallEndpoint.TYPE_WIRED_HEADSET -> android.telecom.CallAudioState.ROUTE_WIRED_HEADSET
            else -> android.telecom.CallAudioState.ROUTE_EARPIECE
        }
        CallRepository.updateAudioRoute(route)
        Log.d(TAG, "[EVENTO] onCallEndpointChanged: ${formatEndpoint(callEndpoint)}")
        logAudioEndpoints("onCallEndpointChanged (Endpoint ativo alterado)")
    }

    override fun onAvailableCallEndpointsChanged(availableEndpoints: List<CallEndpoint>) {
        super.onAvailableCallEndpointsChanged(availableEndpoints)
        availableEndpointsList = availableEndpoints
        Log.d(TAG, "[EVENTO] onAvailableCallEndpointsChanged (Total: ${availableEndpoints.size})")
        logAudioEndpoints("onAvailableCallEndpointsChanged (Endpoints disponíveis alterados)")
    }

    override fun onMuteStateChanged(isMuted: Boolean) {
        super.onMuteStateChanged(isMuted)
        isCallMuted = isMuted
        CallRepository.setMuteState(isMuted)
        Log.d(TAG, "[EVENTO] onMuteStateChanged: isMuted=$isMuted")
        logAudioEndpoints("onMuteStateChanged (Estado do mute alterado)")
    }

    private fun safeGetCurrentCallEndpoint(): CallEndpoint? {
        return try {
            currentCallEndpoint
        } catch (_: Throwable) {
            null
        } ?: activeCallEndpoint
    }

    private fun logAudioPolicy(reason: String) {
        val audioManager = getSystemService(android.media.AudioManager::class.java) ?: return
        val configs = audioManager.activeRecordingConfigurations

        Log.i(TAG, "--------------------------------------------------")
        Log.i(TAG, "[AUDIO POLICY] Evento: $reason | Configs Ativas: ${configs.size}")

        if (configs.isEmpty()) {
            Log.i(TAG, "[AUDIO POLICY] Nenhuma gravação ativa no sistema.")
        } else {
            configs.forEachIndexed { index, config ->
                val isSilenced = config.isClientSilenced
                val device = config.audioDevice
                val deviceStr = if (device != null) {
                    "Nome: \"${device.productName}\" | Tipo: ${device.type} | ID: ${device.id}"
                } else {
                    "Dispositivo Padrão / Indisponível"
                }

                val format = config.format
                val formatStr = "SampleRate=${format?.sampleRate}Hz, Channels=${format?.channelCount}, Encoding=${format?.encoding}"

                val clientFormat = config.clientFormat
                val clientFormatStr = "SampleRate=${clientFormat?.sampleRate}Hz, Channels=${clientFormat?.channelCount}, Encoding=${clientFormat?.encoding}"

                Log.i(TAG, "[AUDIO POLICY] Config [$index]:")
                Log.i(TAG, "  clientSilenced = $isSilenced")
                Log.i(TAG, "  device = $deviceStr")
                Log.i(TAG, "  format = $formatStr")
                Log.i(TAG, "  clientFormat = $clientFormatStr")
                Log.i(TAG, "  clientAudioSource = ${config.clientAudioSource}")
                Log.i(TAG, "  clientAudioSessionId = ${config.clientAudioSessionId}")
            }
        }
        Log.i(TAG, "--------------------------------------------------")
    }

    private fun logAudioEndpoints(reason: String = "") {
        val currentEndpoint = safeGetCurrentCallEndpoint()
        val available = availableEndpointsList
        val muted = isCallMuted

        Log.i(TAG, "--------------------------------------------------")
        Log.i(TAG, "[AUDIO DIAGNOSTIC] ${if (reason.isNotBlank()) "Reason: $reason" else ""}")
        Log.i(TAG, "Current endpoint: ${formatEndpoint(currentEndpoint)}")
        Log.i(TAG, "Available endpoints (${available.size}):")
        if (available.isEmpty()) {
            Log.i(TAG, "  - (Nenhum endpoint retornado na lista disponível ainda)")
        } else {
            available.forEachIndexed { index, endpoint ->
                Log.i(TAG, "  - [$index] ${formatEndpoint(endpoint)}")
            }
        }
        Log.i(TAG, "Mute: $muted")
        Log.i(TAG, "--------------------------------------------------")
    }

    private fun formatEndpoint(endpoint: CallEndpoint?): String {
        if (endpoint == null) return "Nenhum / Indisponível"
        val name = try { endpoint.endpointName } catch (_: Throwable) { null } ?: "Sem nome"
        val typeStr = endpointTypeToString(endpoint.endpointType)
        val id = try { endpoint.identifier } catch (_: Throwable) { null } ?: "Sem ID"
        return "Nome: \"$name\" | Tipo: $typeStr | ID: $id"
    }

    private fun endpointTypeToString(type: Int): String {
        return when (type) {
            CallEndpoint.TYPE_EARPIECE -> "EARPIECE (1)"
            CallEndpoint.TYPE_BLUETOOTH -> "BLUETOOTH (2)"
            CallEndpoint.TYPE_WIRED_HEADSET -> "WIRED_HEADSET (3)"
            CallEndpoint.TYPE_SPEAKER -> "SPEAKER (4)"
            CallEndpoint.TYPE_STREAMING -> "STREAMING (5)"
            CallEndpoint.TYPE_UNKNOWN -> "UNKNOWN (-1)"
            else -> "DESCONHECIDO ($type)"
        }
    }

    private fun logCallDetails(call: Call) {
        val details = call.details ?: return
        val handleUri = details.handle
        val phoneNumber = handleUri?.schemeSpecificPart ?: "Desconhecido"
        val callerName = details.callerDisplayName ?: "Sem nome exibido"
        val disconnectCause = details.disconnectCause

        Log.d(TAG, "--- Detalhes da Chamada ---")
        Log.d(TAG, "Número / Handle: $phoneNumber (URI: $handleUri)")
        Log.d(TAG, "Nome do Chamador: $callerName")
        Log.d(TAG, "Horário de Conexão (ms): ${details.connectTimeMillis}")
        Log.d(TAG, "Horário de Criação (ms): ${details.creationTimeMillis}")
        if (disconnectCause != null) {
            Log.d(TAG, "Causa do Encerramento: $disconnectCause")
        }
        Log.d(TAG, "---------------------------")
    }

    private fun stateToString(state: Int): String {
        return when (state) {
            Call.STATE_NEW -> "STATE_NEW"
            Call.STATE_RINGING -> "STATE_RINGING"
            Call.STATE_DIALING -> "STATE_DIALING"
            Call.STATE_ACTIVE -> "STATE_ACTIVE"
            Call.STATE_HOLDING -> "STATE_HOLDING"
            Call.STATE_DISCONNECTED -> "STATE_DISCONNECTED"
            Call.STATE_DISCONNECTING -> "STATE_DISCONNECTING"
            Call.STATE_SELECT_PHONE_ACCOUNT -> "STATE_SELECT_PHONE_ACCOUNT"
            Call.STATE_CONNECTING -> "STATE_CONNECTING"
            else -> "DESCONHECIDO ($state)"
        }
    }
}
