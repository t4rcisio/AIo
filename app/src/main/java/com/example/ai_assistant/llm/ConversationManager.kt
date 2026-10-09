package com.example.ai_assistant.llm

import android.util.Log
import com.example.ai_assistant.api.ApiSettings
import com.example.ai_assistant.api.LlmApiClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/**
 * Gerencia o histórico conversacional geral e o envio de prompts ao LLM via API OpenAI-like.
 */
class ConversationManager(
    private val llmApiClient: LlmApiClient = LlmApiClient()
) {
    companion object {
        private const val TAG = "ConversationManager"

        const val DEFAULT_SYSTEM_PROMPT =
            "Você é o assistente do AI Call.\n" +
            "Responda em português do Brasil.\n" +
            "Seja natural, objetivo e curto.\n" +
            "Suas respostas serão faladas em uma ligação telefônica.\n" +
            "Não use markdown.\n" +
            "Responda diretamente sem pensamentos ou tags <think>."
    }

    private val _systemPrompt = MutableStateFlow(DEFAULT_SYSTEM_PROMPT)
    val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()

    private val _history = MutableStateFlow<List<ChatMessage>>(emptyList())
    val history: StateFlow<List<ChatMessage>> = _history.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _currentStreamResponse = MutableStateFlow("")
    val currentStreamResponse: StateFlow<String> = _currentStreamResponse.asStateFlow()

    fun setSystemPrompt(newPrompt: String) {
        if (newPrompt.isNotBlank()) {
            _systemPrompt.value = newPrompt.trim()
        }
    }

    fun addUserMessage(content: String) {
        if (content.isBlank()) return
        _history.value = _history.value + ChatMessage(role = "user", content = content.trim())
    }

    fun addAssistantMessage(content: String) {
        if (content.isBlank()) return
        _history.value = _history.value + ChatMessage(role = "assistant", content = content.trim())
    }

    fun clearHistory() {
        _history.value = emptyList()
        _currentStreamResponse.value = ""
    }

    /**
     * Envia mensagem do usuário e gera resposta via streaming da API LLM.
     */
    fun sendUserMessageAndGenerate(userText: String, settings: ApiSettings): Flow<String> = flow {
        if (userText.isBlank()) return@flow

        addUserMessage(userText)

        val fullMessages = mutableListOf<ChatMessage>()
        fullMessages.add(ChatMessage(role = "system", content = _systemPrompt.value))
        fullMessages.addAll(_history.value)

        _isGenerating.value = true
        _currentStreamResponse.value = ""
        val assistantResponseBuilder = StringBuilder()
        val filter = ReasoningStreamFilter()

        try {
            llmApiClient.streamChat(fullMessages, settings).collect { token ->
                val pieces = filter.processToken(token)
                for (p in pieces) {
                    assistantResponseBuilder.append(p)
                    _currentStreamResponse.value = assistantResponseBuilder.toString()
                    emit(p)
                }
            }

            for (p in filter.finish()) {
                assistantResponseBuilder.append(p)
                _currentStreamResponse.value = assistantResponseBuilder.toString()
                emit(p)
            }

            val finalReply = assistantResponseBuilder.toString().trim()
            if (finalReply.isNotEmpty()) {
                addAssistantMessage(finalReply)
            }
        } finally {
            _isGenerating.value = false
            _currentStreamResponse.value = ""
        }
    }
}
