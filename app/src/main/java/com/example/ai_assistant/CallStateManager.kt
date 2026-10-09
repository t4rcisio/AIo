package com.example.ai_assistant

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton responsável por centralizar o estado de chamada no aplicativo,
 * permitindo observar se existe uma chamada ativa e o nome exato do estado
 * (NONE, RINGING, ACTIVE, DISCONNECTED).
 */
object CallStateManager {
    private val _isCallActive = MutableStateFlow(false)
    val isCallActive: StateFlow<Boolean> = _isCallActive.asStateFlow()

    private val _callStateName = MutableStateFlow("NONE")
    val callStateName: StateFlow<String> = _callStateName.asStateFlow()

    fun setCallState(active: Boolean, stateName: String) {
        _isCallActive.value = active
        _callStateName.value = stateName
    }

    fun setCallActive(active: Boolean) {
        _isCallActive.value = active
        _callStateName.value = if (active) "ACTIVE" else "NONE"
    }
}
