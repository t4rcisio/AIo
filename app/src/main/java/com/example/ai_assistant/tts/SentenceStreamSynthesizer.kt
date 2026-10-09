package com.example.ai_assistant.tts

import android.util.Log
import com.example.ai_assistant.api.ApiSettings
import com.example.ai_assistant.api.TtsApiClient
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File

/**
 * Agrupa tokens de streaming do LLM em sentenças completas para síntese progressiva de voz via API TTS.
 * Processa a síntese em um worker assíncrono em background sem travar o loop de tokens do LLM.
 */
class SentenceStreamSynthesizer(
    private val ttsApiClient: TtsApiClient,
    private val audioPlayer: CallAudioPlayer,
    private val settings: ApiSettings,
    private val cacheDir: File? = null,
    private val onAudioSynthesized: ((FloatArray, Int) -> Unit)? = null
) {
    companion object {
        private const val TAG = "SentenceSynthesizer"
        private const val MIN_SENTENCE_CHARS = 6
        private val TERMINAL_PUNCTUATION = setOf('.', '!', '?', '\n', ':')
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val sentenceChannel = Channel<String>(capacity = 32)
    private var workerJob: Job? = null
    private val buffer = StringBuilder()

    @Volatile
    private var isSynthesizing = false

    init {
        workerJob = scope.launch {
            for (sentence in sentenceChannel) {
                if (!isActive) break
                val clean = sentence.replace(Regex("[#*`_~>\\[\\]]"), "").trim()
                if (clean.isEmpty() || !clean.any { it.isLetterOrDigit() }) continue
                isSynthesizing = true
                try {
                    Log.d(TAG, "Sintetizando sentença via TTS API: \"$clean\"")
                    val result = ttsApiClient.synthesizeToPcm(clean, settings, cacheDir)
                    result.onSuccess { audio ->
                        if (audio.samples.isNotEmpty()) {
                            audioPlayer.enqueueAudio(audio.samples, audio.sampleRate)
                            onAudioSynthesized?.invoke(audio.samples, audio.sampleRate)
                        }
                    }.onFailure { e ->
                        Log.e(TAG, "Falha na síntese da sentença: ${e.message}")
                    }
                } finally {
                    isSynthesizing = false
                }
            }
        }
    }

    /**
     * Processa um pedaço/token gerado pelo LLM. Enfileira imediatamente de forma não-bloqueante.
     */
    fun onToken(token: String) {
        buffer.append(token)
        val current = buffer.toString()

        var splitIndex = -1
        for (i in current.indices) {
            val c = current[i]
            if (c in TERMINAL_PUNCTUATION) {
                // Evita fatiar em números decimais como 3.5 ou R$ 10.00
                if (c == '.' && i > 0 && i + 1 < current.length && current[i - 1].isDigit() && current[i + 1].isDigit()) {
                    continue
                }
                // Sintetiza frases curtas ou sentenças com pontuação terminal sem reter no buffer
                val isLongEnough = i >= MIN_SENTENCE_CHARS
                val isExplicitNewline = c == '\n'

                if (isLongEnough || isExplicitNewline) {
                    // Confirma fim da sentença se há espaço seguinte ou quebra de linha
                    if (i + 1 < current.length || c == '\n') {
                        splitIndex = i + 1
                        break
                    }
                }
            } else if ((c == ',' || c == ';') && i >= 35) {
                // Se a oração for longa sem pontuação terminal, divide em vírgula para adiantar síntese
                if (i + 1 < current.length && current[i + 1].isWhitespace()) {
                    splitIndex = i + 1
                    break
                }
            }
        }

        if (splitIndex > 0) {
            val sentence = current.substring(0, splitIndex).trim()
            buffer.delete(0, splitIndex)
            if (sentence.isNotEmpty() && sentence.any { it.isLetterOrDigit() }) {
                sentenceChannel.trySend(sentence)
            }
        }
    }

    /**
     * Finaliza o fluxo e enfileira qualquer texto residual restante no buffer.
     */
    fun finish() {
        val remaining = buffer.toString().trim()
        buffer.clear()
        if (remaining.isNotEmpty() && remaining.any { it.isLetterOrDigit() }) {
            sentenceChannel.trySend(remaining)
        }
    }

    /**
     * Aguarda até que todas as sentenças enfileiradas tenham sido sintetizadas pela API de TTS.
     */
    suspend fun awaitAllSynthesized(timeoutMs: Long = 10000) {
        finish()
        val start = System.currentTimeMillis()
        while ((!sentenceChannel.isEmpty || isSynthesizing) && (System.currentTimeMillis() - start < timeoutMs)) {
            delay(50)
        }
    }

    fun reset() {
        buffer.clear()
        while (sentenceChannel.tryReceive().isSuccess) {}
    }

    fun release() {
        workerJob?.cancel()
        sentenceChannel.close()
        scope.cancel()
    }
}
