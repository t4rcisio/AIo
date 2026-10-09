package com.example.ai_assistant.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reprodutor de áudio dedicado exclusivamente para prévias e testes na interface (UI).
 * Utiliza AudioTrack no canal multimídia (STREAM_MUSIC / USAGE_MEDIA) para que o usuário
 * possa escutar nitidamente a resposta da IA e os testes de vozes pelo alto-falante.
 * 
 * Este player é completamente isolado da injeção de áudio silenciosa em chamadas reais.
 */
object AudioPreviewPlayer {

    private const val TAG = "AudioPreviewPlayer"

    private var activeTrack: AudioTrack? = null
    private var playbackThread: Thread? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    @Synchronized
    fun play(samples: FloatArray, sampleRate: Int, onComplete: (() -> Unit)? = null) {
        stop()

        if (samples.isEmpty()) {
            onComplete?.invoke()
            return
        }

        try {
            val pcm16 = ShortArray(samples.size) { i ->
                (samples[i].coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()
            }

            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBuf * 2, pcm16.size * 2)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            activeTrack = track
            _isPlaying.value = true
            track.play()

            playbackThread = Thread {
                try {
                    track.write(pcm16, 0, pcm16.size, AudioTrack.WRITE_BLOCKING)
                    // Aguarda término da reprodução física
                    val durationMs = (samples.size * 1000L) / sampleRate
                    Thread.sleep(durationMs + 100)
                } catch (e: InterruptedException) {
                    Log.d(TAG, "Reprodução de prévia interrompida.")
                } catch (e: Exception) {
                    Log.e(TAG, "Erro durante reprodução de áudio: ${e.message}", e)
                } finally {
                    try {
                        track.stop()
                        track.release()
                    } catch (ignored: Exception) {}
                    synchronized(this@AudioPreviewPlayer) {
                        if (activeTrack == track) {
                            activeTrack = null
                            _isPlaying.value = false
                        }
                    }
                    onComplete?.invoke()
                }
            }.apply {
                name = "AioAudioPreviewThread"
                start()
            }

            Log.i(TAG, "[PREVIEW AUDIO] Reproduzindo ${samples.size} amostras ($sampleRate Hz) no alto-falante.")
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao iniciar AudioTrack para teste: ${e.message}", e)
            _isPlaying.value = false
            onComplete?.invoke()
        }
    }

    @Synchronized
    fun stop() {
        try {
            playbackThread?.interrupt()
            playbackThread = null
            activeTrack?.stop()
            activeTrack?.release()
        } catch (ignored: Exception) {}
        activeTrack = null
        _isPlaying.value = false
    }
}
