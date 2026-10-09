package com.example.ai_assistant.api

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Cliente HTTP para Transcrição de Voz (STT).
 * Suporta Gemini 3.8 Flash (via áudio multimodal em base64) e OpenAI/Groq Whisper.
 */
class SttApiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TAG = "SttApiClient"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    suspend fun transcribePcm(
        pcm16Bytes: ByteArray,
        sampleRate: Int = 16000,
        settings: ApiSettings
    ): Result<String> = withContext(Dispatchers.IO) {
        if (pcm16Bytes.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Buffer de áudio vazio."))
        }

        if (settings.sttProvider == "gemini") {
            transcribeWithGemini(pcm16Bytes, sampleRate, settings)
        } else {
            transcribeWithWhisper(pcm16Bytes, sampleRate, settings)
        }
    }

    /**
     * Transcrição ultra-rápida e precisa usando Gemini 3.8 Flash.
     */
    private fun transcribeWithGemini(
        pcm16Bytes: ByteArray,
        sampleRate: Int,
        settings: ApiSettings
    ): Result<String> {
        val apiKey = settings.effectiveGeminiKey
        if (apiKey.isBlank()) {
            return Result.failure(IllegalStateException("Chave de API do Gemini não configurada."))
        }

        return try {
            val wavBytes = pcm16ToWav(pcm16Bytes, sampleRate, channels = 1)
            val base64Wav = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
            val url = "https://generativelanguage.googleapis.com/v1beta/models/${settings.sttModel}:generateContent?key=$apiKey"

            val jsonPayload = JSONObject().apply {
                val parts = JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", "Transcreva exatamente o áudio em português. Retorne exclusivamente o texto falado, pontuado e sem comentários. Se não houver fala clara ou for apenas ruído de linha, retorne VAZIO.")
                    })
                    put(JSONObject().apply {
                        put("inlineData", JSONObject().apply {
                            put("mimeType", "audio/wav")
                            put("data", base64Wav)
                        })
                    })
                }
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", parts)
                    })
                })
            }

            val request = Request.Builder()
                .url(url)
                .post(jsonPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "Falha na transcrição Gemini HTTP ${response.code}: $responseBody")
                return Result.failure(Exception("Gemini STT HTTP ${response.code}: $responseBody"))
            }

            val json = JSONObject(responseBody)
            val candidates = json.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val content = candidates.getJSONObject(0).optJSONObject("content")
                val responseParts = content?.optJSONArray("parts")
                val text = responseParts?.getJSONObject(0)?.optString("text", "")?.trim() ?: ""

                if (text.equals("VAZIO", ignoreCase = true) || text.contains("[SILÊNCIO]", ignoreCase = true)) {
                    Result.success("")
                } else {
                    Result.success(text)
                }
            } else {
                Result.success("")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exceção no Gemini STT: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Transcrição via Whisper API (OpenAI / Groq).
     */
    private fun transcribeWithWhisper(
        pcm16Bytes: ByteArray,
        sampleRate: Int,
        settings: ApiSettings
    ): Result<String> {
        val apiKey = settings.effectiveSttKey
        if (apiKey.isBlank()) {
            return Result.failure(IllegalStateException("Chave do Whisper não configurada."))
        }

        return try {
            val wavBytes = pcm16ToWav(pcm16Bytes, sampleRate, channels = 1)
            val cleanBaseUrl = settings.sttBaseUrl.trim().removeSuffix("/")
            val url = if (cleanBaseUrl.endsWith("/audio/transcriptions")) cleanBaseUrl else "$cleanBaseUrl/audio/transcriptions"

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "audio.wav", wavBytes.toRequestBody("audio/wav".toMediaType()))
                .addFormDataPart("model", settings.sttModel)
                .apply {
                    if (settings.sttLanguage.isNotBlank()) {
                        addFormDataPart("language", settings.sttLanguage)
                    }
                    addFormDataPart("response_format", "json")
                }
                .build()

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return Result.failure(Exception("Whisper STT HTTP ${response.code}: $responseBody"))
            }

            val json = JSONObject(responseBody)
            val transcript = json.optString("text", "").trim()
            Result.success(transcript)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun pcm16ToWav(pcmData: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val byteRate = sampleRate * channels * 2
        val blockAlign = channels * 2
        val totalAudioLen = pcmData.size
        val totalDataLen = totalAudioLen + 36

        val header = ByteArray(44)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        buffer.put("RIFF".toByteArray())
        buffer.putInt(totalDataLen)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1.toShort())
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(16.toShort())
        buffer.put("data".toByteArray())
        buffer.putInt(totalAudioLen)

        val out = ByteArrayOutputStream(44 + totalAudioLen)
        out.write(header)
        out.write(pcmData)
        return out.toByteArray()
    }
}
