package com.example.ai_assistant.api

import android.util.Log
import com.example.ai_assistant.llm.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Cliente HTTP para LLM compatível com a API OpenAI (/v1/chat/completions).
 * Funciona com OpenAI (gpt-4o, gpt-4o-mini), Groq, OpenRouter, DeepSeek, Ollama, etc.
 */
class LlmApiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TAG = "LlmApiClient"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    /**
     * Gera resposta via Server-Sent Events (SSE) streaming (`stream: true`).
     * Emite cada pedaço/token recebido em tempo real.
     */
    fun streamChat(
        messages: List<ChatMessage>,
        settings: ApiSettings
    ): Flow<String> = flow {
        val apiKey = settings.effectiveLlmKey
        if (apiKey.isBlank()) {
            throw IllegalStateException("API Key do LLM não configurada.")
        }

        val cleanBaseUrl = settings.llmBaseUrl.trim().removeSuffix("/")
        val url = if (cleanBaseUrl.endsWith("/chat/completions")) {
            cleanBaseUrl
        } else {
            "$cleanBaseUrl/chat/completions"
        }

        val jsonPayload = JSONObject().apply {
            put("model", settings.llmModel)
            put("temperature", settings.llmTemperature.toDouble())
            val maxTokensLimit = if (settings.deepseekThinking) maxOf(settings.llmMaxTokens, 600) else settings.llmMaxTokens
            put("max_tokens", maxTokensLimit)
            put("stream", true)

            if (settings.llmProvider == "deepseek" || settings.llmBaseUrl.contains("deepseek")) {
                if (settings.deepseekThinking) {
                    put("reasoning_effort", "medium")
                    put("thinking", JSONObject().apply {
                        put("type", "enabled")
                    })
                } else {
                    put("thinking", JSONObject().apply {
                        put("type", "disabled")
                    })
                }
            }

            val msgsArray = JSONArray()
            for (msg in messages) {
                msgsArray.put(JSONObject().apply {
                    put("role", msg.role)
                    put("content", msg.content)
                })
            }
            put("messages", msgsArray)
        }

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(jsonPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            Log.e(TAG, "Falha na chamada LLM streaming HTTP ${response.code}: $errorBody")
            throw Exception("LLM Error HTTP ${response.code}: $errorBody")
        }

        val responseBody = response.body ?: throw Exception("Corpo da resposta vazio")
        val reader = BufferedReader(InputStreamReader(responseBody.byteStream(), Charsets.UTF_8))

        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val current = line?.trim() ?: continue
                if (current.isEmpty()) continue
                if (!current.startsWith("data:")) continue

                val data = current.removePrefix("data:").trim()
                if (data == "[DONE]") {
                    break
                }

                try {
                    val chunkJson = JSONObject(data)
                    val choices = chunkJson.optJSONArray("choices") ?: continue
                    if (choices.length() > 0) {
                        val firstChoice = choices.getJSONObject(0)
                        val delta = firstChoice.optJSONObject("delta")
                        if (delta != null && !delta.isNull("content")) {
                            val content = delta.optString("content", "")
                            if (content.isNotEmpty() && content != "null") {
                                emit(content)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignora chunks parciais ou malformados
                }
            }
        } finally {
            try { reader.close() } catch (_: Exception) {}
            try { responseBody.close() } catch (_: Exception) {}
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Chamada única não-streaming para testes de conectividade ou fallback.
     */
    suspend fun generateReply(
        messages: List<ChatMessage>,
        settings: ApiSettings
    ): Result<String> = chat(messages, settings)

    suspend fun chat(
        messages: List<ChatMessage>,
        settings: ApiSettings
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = settings.effectiveLlmKey
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("API Key do LLM não configurada."))
        }

        val cleanBaseUrl = settings.llmBaseUrl.trim().removeSuffix("/")
        val url = if (cleanBaseUrl.endsWith("/chat/completions")) {
            cleanBaseUrl
        } else {
            "$cleanBaseUrl/chat/completions"
        }

        try {
            val jsonPayload = JSONObject().apply {
                put("model", settings.llmModel)
                put("temperature", settings.llmTemperature.toDouble())
                val maxTokensLimit = if (settings.deepseekThinking) maxOf(settings.llmMaxTokens, 600) else settings.llmMaxTokens
                put("max_tokens", maxTokensLimit)
                put("stream", false)

                if (settings.llmProvider == "deepseek" || settings.llmBaseUrl.contains("deepseek")) {
                    if (settings.deepseekThinking) {
                        put("reasoning_effort", "medium")
                        put("thinking", JSONObject().apply {
                            put("type", "enabled")
                        })
                    } else {
                        put("thinking", JSONObject().apply {
                            put("type", "disabled")
                        })
                    }
                }

                val msgsArray = JSONArray()
                for (msg in messages) {
                    msgsArray.put(JSONObject().apply {
                        put("role", msg.role)
                        put("content", msg.content)
                    })
                }
                put("messages", msgsArray)
            }

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(jsonPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "Falha no chat LLM HTTP ${response.code}: $responseBody")
                return@withContext Result.failure(Exception("LLM HTTP ${response.code}: $responseBody"))
            }

            val json = JSONObject(responseBody)
            val choices = json.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val message = choices.getJSONObject(0).optJSONObject("message")
                val content = if (message != null && !message.isNull("content")) {
                    val c = message.optString("content", "")
                    if (c == "null") "" else c
                } else ""
                Result.success(content.trim())
            } else {
                Result.failure(Exception("Resposta LLM sem choices: $responseBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exceção no chat LLM: ${e.message}", e)
            Result.failure(e)
        }
    }
}
