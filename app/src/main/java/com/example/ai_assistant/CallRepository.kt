package com.example.ai_assistant

import android.telecom.Call
import android.telecom.InCallService
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * Repositório thread-safe para armazenar referências das chamadas ativas (Call)
 * e expor o estado atual para a UI da IncomingCallActivity.
 */
object CallRepository {

    private const val TAG = "CallRepository"

    private val callsMap = ConcurrentHashMap<String, Call>()
    private var inCallServiceRef: WeakReference<InCallService>? = null

    private val _activeCallState = MutableStateFlow<CallStateInfo?>(null)
    val activeCallState: StateFlow<CallStateInfo?> = _activeCallState.asStateFlow()

    data class CallStateInfo(
        val callId: String,
        val phoneNumber: String,
        val callerName: String,
        val state: Int,
        val stateString: String,
        val isMuted: Boolean = false,
        val audioRoute: Int = android.telecom.CallAudioState.ROUTE_EARPIECE,
        val isWhatsApp: Boolean = false
    )

    private val _currentAudioRoute = MutableStateFlow(android.telecom.CallAudioState.ROUTE_EARPIECE)
    val currentAudioRoute: StateFlow<Int> = _currentAudioRoute.asStateFlow()

    fun setInCallService(service: InCallService?) {
        inCallServiceRef = service?.let { WeakReference(it) }
    }

    fun registerCall(call: Call): String {
        val callId = getCallId(call)
        callsMap[callId] = call
        Log.d(TAG, "Call registrada: $callId (Total no repositório: ${callsMap.size})")
        updateStateInfo(callId, call)
        return callId
    }

    fun unregisterCall(call: Call) {
        val callId = getCallId(call)
        callsMap.remove(callId)
        Log.d(TAG, "Call removida: $callId (Restantes: ${callsMap.size})")
        if (_activeCallState.value?.callId == callId) {
            _activeCallState.value = null
        }
    }

    fun getCall(callId: String): Call? {
        return callsMap[callId]
    }

    val currentCall: Call?
        get() = callsMap.values.firstOrNull()

    private var currentMuteState: Boolean = false

    fun setMuteState(isMuted: Boolean) {
        currentMuteState = isMuted
        _activeCallState.value?.let { current ->
            _activeCallState.value = current.copy(isMuted = isMuted)
        }
    }

    fun updateAudioRoute(route: Int) {
        _currentAudioRoute.value = route
        _activeCallState.value?.let { current ->
            _activeCallState.value = current.copy(audioRoute = route)
        }
        Log.i(TAG, "AudioRoute atualizada: $route (Earpiece=1, Speaker=8, Bluetooth=2, Headset=4)")
    }

    fun setAudioRoute(route: Int) {
        val service = inCallServiceRef?.get()
        if (service != null) {
            try {
                service.setAudioRoute(route)
                updateAudioRoute(route)
                Log.i(TAG, "Definida AudioRoute para $route via InCallService")
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao definir AudioRoute para $route", e)
            }
        } else {
            Log.w(TAG, "InCallService indisponível para definir AudioRoute para $route")
        }
    }

    fun toggleSpeaker(): Int {
        val target = if (_currentAudioRoute.value == android.telecom.CallAudioState.ROUTE_SPEAKER) {
            android.telecom.CallAudioState.ROUTE_EARPIECE
        } else {
            android.telecom.CallAudioState.ROUTE_SPEAKER
        }
        setAudioRoute(target)
        return target
    }

    fun getCallId(call: Call): String {
        return call.hashCode().toString()
    }

    fun updateStateInfo(callId: String, call: Call, overrideMute: Boolean? = null) {
        val details = call.details
        val phoneNumber = details?.handle?.schemeSpecificPart ?: "Desconhecido"
        val callerName = details?.callerDisplayName?.takeIf { it.isNotBlank() } ?: "Sem Nome"
        val state = details?.state ?: @Suppress("DEPRECATION") call.state
        val isMuted = overrideMute ?: currentMuteState
        val route = _currentAudioRoute.value
        val isWhatsApp = details?.hasProperty(Call.Details.PROPERTY_SELF_MANAGED) == true ||
                details?.accountHandle?.componentName?.packageName?.contains("whatsapp", ignoreCase = true) == true

        _activeCallState.value = CallStateInfo(
            callId = callId,
            phoneNumber = phoneNumber,
            callerName = callerName,
            state = state,
            stateString = stateToString(state),
            isMuted = isMuted,
            audioRoute = route,
            isWhatsApp = isWhatsApp
        )
    }

    fun toggleMute(): Boolean {
        val service = inCallServiceRef?.get()
        if (service != null) {
            val newMute = !currentMuteState
            service.setMuted(newMute)
            setMuteState(newMute)
            return newMute
        }
        return false
    }

    fun setCallMicrophoneMute(mute: Boolean) {
        val service = inCallServiceRef?.get()
        if (service != null) {
            try {
                service.setMuted(mute)
                setMuteState(mute)
                Log.i(TAG, "[MICROPHONE MUTE] Microfone da chamada ${if (mute) "MUTADO (Robô ativo)" else "DESMUTADO (Humano falando)"}")
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao alterar mute do microfone: ${e.message}", e)
            }
        } else {
            Log.w(TAG, "InCallService indisponível para setCallMicrophoneMute($mute)")
        }
    }

    fun disconnectActiveCall() {
        _activeCallState.value?.callId?.let { id ->
            callsMap[id]?.disconnect()
        } ?: run {
            callsMap.values.firstOrNull()?.disconnect()
        }
    }

    private fun stateToString(state: Int): String {
        return when (state) {
            Call.STATE_NEW -> "Nova Chamada"
            Call.STATE_RINGING -> "Chamada Recebida"
            Call.STATE_DIALING -> "Discando..."
            Call.STATE_ACTIVE -> "Chamada Em Andamento"
            Call.STATE_HOLDING -> "Em Espera"
            Call.STATE_DISCONNECTED -> "Chamada Encerrada"
            Call.STATE_DISCONNECTING -> "Desconectando..."
            Call.STATE_SELECT_PHONE_ACCOUNT -> "Selecionando Conta"
            Call.STATE_CONNECTING -> "Conectando..."
            else -> "Estado $state"
        }
    }
}
