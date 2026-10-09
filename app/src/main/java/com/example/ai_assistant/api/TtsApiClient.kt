package com.example.ai_assistant.api

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Cliente HTTP para API de Síntese de Voz (Text-to-Speech).
 * Suporta Gemini Speech Generation, OpenAI (/v1/audio/speech) e fallback nativo para Android System TTS.
 */
class TtsApiClient(
    private val context: Context? = null,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TAG = "TtsApiClient"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private var systemTts: TextToSpeech? = null
    @Volatile
    private var isSystemTtsReady = false

    init {
        context?.let { ctx ->
            try {
                systemTts = TextToSpeech(ctx.applicationContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        systemTts?.let { tts ->
                            val result = tts.setLanguage(Locale("pt", "BR"))
                            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                                tts.setLanguage(Locale.getDefault())
                            }
                            isSystemTtsReady = true
                            Log.i(TAG, "Android System TTS Fallback inicializado com sucesso!")
                        }
                    } else {
                        Log.w(TAG, "Falha ao inicializar System TTS, status: $status")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Exceção ao instanciar fallback TextToSpeech: ${e.message}")
            }
        }
    }

    /**
     * Resultado da síntese de voz: amostras de áudio float e sample rate correspondente.
     */
    data class AudioResult(
        val samples: FloatArray,
        val sampleRate: Int
    )

    /**
     * Sintetiza o texto em amostras de áudio PCM FloatArray prontas para injeção imediata no AudioTrack.
     */
    suspend fun synthesizeToPcm(
        text: String,
        settings: ApiSettings,
        cacheDir: File? = null
    ): Result<AudioResult> = withContext(Dispatchers.IO) {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Texto para síntese está vazio."))
        }

        if (settings.ttsProvider == "system") {
            return@withContext synthesizeWithSystemTts(cleanText, cacheDir)
        }

        val apiKey = settings.effectiveTtsKey
        if (apiKey.isBlank()) {
            Log.w(TAG, "API Key do TTS vazia. Usando Android System TTS...")
            return@withContext synthesizeWithSystemTts(cleanText, cacheDir)
        }

        val primaryResult = if (settings.ttsProvider == "gemini") {
            synthesizeWithGemini(cleanText, apiKey, settings, cacheDir)
        } else {
            synthesizeWithOpenAi(cleanText, apiKey, settings, cacheDir)
        }

        if (primaryResult.isFailure) {
            val err = primaryResult.exceptionOrNull()
            Log.w(TAG, "Provedor TTS principal (${settings.ttsProvider}) falhou: ${err?.message}")

            // Tenta uma segunda vez com o provedor principal para evitar trocar a voz no meio da frase
            if (settings.ttsProvider == "gemini") {
                Log.i(TAG, "Tentando novamente síntese com Gemini TTS...")
                kotlinx.coroutines.delay(350)
                val retry = synthesizeWithGemini(cleanText, apiKey, settings, cacheDir)
                if (retry.isSuccess) {
                    return@withContext retry
                }
            }

            // Apenas aciona fallback do Sistema se o provedor escolhido for system ou se a chave estiver em branco
            if (settings.ttsProvider == "system" || apiKey.isBlank()) {
                Log.w(TAG, "Acionando motor nativo do Sistema Android...")
                val fallback = synthesizeWithSystemTts(cleanText, cacheDir)
                if (fallback.isSuccess) {
                    return@withContext fallback
                }
            }
        }

        return@withContext primaryResult
    }

    /**
     * Síntese offline de alta velocidade via motor nativo de fala do Android (TTS do sistema).
     * Ilimitado, sem custo de cota e com latência mínima.
     */
    private suspend fun synthesizeWithSystemTts(
        text: String,
        cacheDir: File?
    ): Result<AudioResult> = withContext(Dispatchers.IO) {
        val tts = systemTts ?: return@withContext Result.failure(Exception("Android System TTS não disponível."))
        
        var waitCount = 0
        while (!isSystemTtsReady && waitCount < 20) {
            kotlinx.coroutines.delay(100)
            waitCount++
        }
        if (!isSystemTtsReady) {
            return@withContext Result.failure(Exception("Android System TTS não ficou pronto a tempo."))
        }

        val tempFile = File.createTempFile("tts_fallback_", ".wav", cacheDir)
        try {
            val utteranceId = UUID.randomUUID().toString()
            val deferred = CompletableDeferred<Boolean>()

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(id: String?) {
                    if (id == utteranceId) deferred.complete(true)
                }
                override fun onError(id: String?) {
                    if (id == utteranceId) deferred.complete(false)
                }
            })

            val params = Bundle()
            val status = tts.synthesizeToFile(text, params, tempFile, utteranceId)
            if (status != TextToSpeech.SUCCESS) {
                return@withContext Result.failure(Exception("Falha no synthesizeToFile status=$status"))
            }

            val completed = withTimeoutOrNull(4000) { deferred.await() } ?: false
            if (!completed || !tempFile.exists() || tempFile.length() == 0L) {
                return@withContext Result.failure(Exception("Timeout ou arquivo vazio do System TTS."))
            }

            val decoded = decodeAudioFileToFloat(tempFile)
            Result.success(decoded)
        } catch (e: Exception) {
            Log.e(TAG, "Exceção no fallback System TTS: ${e.message}", e)
            Result.failure(e)
        } finally {
            try { tempFile.delete() } catch (_: Exception) {}
        }
    }

    /**
     * Síntese de voz em tempo real via Gemini Speech Generation (ex: gemini-2.5-flash-preview-tts).
     * Retorna PCM linear 16-bit 24kHz bruto decodificado diretamente em FloatArray.
     */
    private fun synthesizeWithGemini(
        cleanText: String,
        apiKey: String,
        settings: ApiSettings,
        cacheDir: File?
    ): Result<AudioResult> {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/${settings.ttsModel}:generateContent?key=$apiKey"

        val promptText = "Read aloud: $cleanText"

        val jsonPayload = JSONObject().apply {
            put("contents", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", org.json.JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", promptText)
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("responseModalities", org.json.JSONArray().apply {
                    put("AUDIO")
                })
                put("speechConfig", JSONObject().apply {
                    put("voiceConfig", JSONObject().apply {
                        put("prebuiltVoiceConfig", JSONObject().apply {
                            put("voiceName", settings.ttsVoice)
                        })
                    })
                })
            })
        }

        return try {
            val request = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(jsonPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            var response = client.newCall(request).execute()
            var responseBody = response.body?.string() ?: ""

            val isQuotaExhausted = responseBody.contains("QuotaFailure") || responseBody.contains("RESOURCE_EXHAUSTED") || responseBody.contains("quotaMetric")

            // Se receber 429 (Rate limit) ou 5xx (temporário), aguarda brevemente apenas se não for cota diária esgotada
            if (!isQuotaExhausted && !response.isSuccessful && (response.code == 429 || response.code >= 500)) {
                Log.w(TAG, "Gemini TTS ocupado ou cota momentânea (HTTP ${response.code}). Aguardando 1200ms para retry...")
                Thread.sleep(1200)
                response = client.newCall(request).execute()
                responseBody = response.body?.string() ?: ""
            }

            if (!response.isSuccessful) {
                Log.e(TAG, "Falha na síntese Gemini TTS HTTP ${response.code}: $responseBody")
                return Result.failure(Exception("Gemini TTS HTTP ${response.code}: $responseBody"))
            }

            val json = JSONObject(responseBody)
            val candidates = json.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                return Result.failure(Exception("Gemini TTS sem candidatos na resposta."))
            }

            var inlineData: JSONObject? = null
            for (c in 0 until candidates.length()) {
                val candidate = candidates.getJSONObject(c)
                val content = candidate.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                if (parts != null) {
                    for (p in 0 until parts.length()) {
                        val part = parts.getJSONObject(p)
                        val data = part.optJSONObject("inlineData")
                        if (data != null && data.has("data")) {
                            inlineData = data
                            break
                        }
                    }
                }
                if (inlineData != null) break
            }

            if (inlineData == null) {
                Log.e(TAG, "Gemini TTS não retornou inlineData em nenhum part: $responseBody")
                return Result.failure(Exception("Gemini TTS sem inlineData de áudio."))
            }

            val mimeType = inlineData.optString("mimeType", "audio/L16;codec=pcm;rate=24000")
            val base64Audio = inlineData.getString("data")
            val audioBytes = android.util.Base64.decode(base64Audio, android.util.Base64.DEFAULT)

            if (mimeType.contains("pcm") || mimeType.startsWith("audio/L16")) {
                var sampleRate = 24000
                val rateRegex = Regex("rate=(\\d+)")
                val match = rateRegex.find(mimeType)
                if (match != null) {
                    sampleRate = match.groupValues[1].toIntOrNull() ?: 24000
                }
                val floatSamples = pcm16ToFloat(audioBytes)
                Result.success(AudioResult(samples = floatSamples, sampleRate = sampleRate))
            } else {
                val tempFile = File.createTempFile("tts_gemini_", ".audio", cacheDir)
                try {
                    FileOutputStream(tempFile).use { it.write(audioBytes) }
                    val decoded = decodeAudioFileToFloat(tempFile)
                    Result.success(decoded)
                } finally {
                    try { tempFile.delete() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exceção no Gemini TTS: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Síntese de voz via API padrão OpenAI (/v1/audio/speech).
     */
    private fun synthesizeWithOpenAi(
        cleanText: String,
        apiKey: String,
        settings: ApiSettings,
        cacheDir: File?
    ): Result<AudioResult> {
        val cleanBaseUrl = settings.ttsBaseUrl.trim().removeSuffix("/")
        val url = if (cleanBaseUrl.endsWith("/audio/speech")) {
            cleanBaseUrl
        } else {
            "$cleanBaseUrl/audio/speech"
        }

        val format = settings.ttsResponseFormat.lowercase().trim()

        val jsonPayload = JSONObject().apply {
            put("model", settings.ttsModel)
            put("input", cleanText)
            put("voice", settings.ttsVoice)
            put("speed", settings.ttsSpeed.toDouble())
            put("response_format", format)
        }

        return try {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(jsonPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                Log.e(TAG, "Falha na síntese TTS OpenAI HTTP ${response.code}: $errorBody")
                return Result.failure(Exception("TTS HTTP ${response.code}: $errorBody"))
            }

            val bodyBytes = response.body?.bytes()
                ?: return Result.failure(Exception("Resposta do TTS vazia"))

            if (format == "pcm") {
                val floatSamples = pcm16ToFloat(bodyBytes)
                Result.success(AudioResult(samples = floatSamples, sampleRate = 24000))
            } else {
                val tempFile = File.createTempFile("tts_audio_", ".mp3", cacheDir)
                try {
                    FileOutputStream(tempFile).use { it.write(bodyBytes) }
                    val decoded = decodeAudioFileToFloat(tempFile)
                    Result.success(decoded)
                } finally {
                    try { tempFile.delete() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exceção na síntese OpenAI TTS: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Converte PCM 16-bit Little-Endian para FloatArray normalizado (-1.0f a 1.0f).
     */
    private fun pcm16ToFloat(pcmBytes: ByteArray): FloatArray {
        val numSamples = pcmBytes.size / 2
        val floatSamples = FloatArray(numSamples)
        val byteBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until numSamples) {
            val sample = byteBuffer.short
            floatSamples[i] = sample / 32768.0f
        }
        return floatSamples
    }

    /**
     * Decodifica qualquer arquivo de áudio (MP3/AAC/OGG) suportado pelo Android para PCM FloatArray via MediaCodec.
     */
    private fun decodeAudioFileToFloat(audioFile: File): AudioResult {
        val extractor = MediaExtractor()
        extractor.setDataSource(audioFile.absolutePath)

        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = f
                break
            }
        }

        if (trackIndex < 0 || format == null) {
            extractor.release()
            throw IllegalStateException("Nenhuma trilha de áudio encontrada no arquivo decodificado.")
        }

        extractor.selectTrack(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/mpeg"
        val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else {
            24000
        }
        val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else {
            1
        }

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val allSamples = ArrayList<Float>()
        val info = MediaCodec.BufferInfo()
        var isEOS = false

        while (!isEOS) {
            val inIndex = codec.dequeueInputBuffer(10000)
            if (inIndex >= 0) {
                val inputBuf = codec.getInputBuffer(inIndex)
                if (inputBuf != null) {
                    val sampleSize = extractor.readSampleData(inputBuf, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        isEOS = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            var outIndex = codec.dequeueOutputBuffer(info, 10000)
            while (outIndex >= 0) {
                val outputBuf = codec.getOutputBuffer(outIndex)
                if (outputBuf != null && info.size > 0) {
                    outputBuf.position(info.offset)
                    outputBuf.limit(info.offset + info.size)
                    val shortBuf = outputBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val numShorts = info.size / 2
                    val step = maxOf(1, channelCount) // Mescla canais em mono se stereo
                    var i = 0
                    while (i < numShorts) {
                        val sample = shortBuf.get(i)
                        allSamples.add(sample / 32768.0f)
                        i += step
                    }
                }
                codec.releaseOutputBuffer(outIndex, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
                outIndex = codec.dequeueOutputBuffer(info, 10000)
            }
        }

        codec.stop()
        codec.release()
        extractor.release()

        val floatArray = FloatArray(allSamples.size)
        for (i in allSamples.indices) {
            floatArray[i] = allSamples[i]
        }
        return AudioResult(samples = floatArray, sampleRate = sampleRate)
    }
}
