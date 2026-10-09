package com.example.ai_assistant.llm

/**
 * Representa uma mensagem dentro do histórico conversacional com o LLM.
 *
 * @param role Papel do emissor: "system", "user" ou "assistant"
 * @param content Conteúdo textual da mensagem
 */
data class ChatMessage(
    val role: String,
    val content: String
)
