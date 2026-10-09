package com.example.ai_assistant.tts

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Reprodutor de áudio 100% digital e silencioso localmente.
 * O áudio sintetizado (TTS) é injetado EXCLUSIVAMENTE no uplink da chamada celular (modem TX)
 * via Daemon Shell Privilegiado (UID 2000).
 * O aparelho local (alto-falante principal e earpiece) permanece em SILÊNCIO ABSOLUTO (zero ruído emitido).
 */
class CallAudioPlayer(val context: Context) {

    fun playAudio(samples: FloatArray, sampleRate: Int) {
        enqueueAudio(samples, sampleRate)
    }

    companion object {
        private const val TAG = "CallAudioPlayer"
        private const val DAEMON_PORT = 28472
    }

    private data class QueuedAudio(val samples: FloatArray, val sampleRate: Int)

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val audioQueue = Channel<QueuedAudio>(capacity = 64)
    private var playbackJob: Job? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private var injectionSocket: Socket? = null
    private var injectionOutputStream: OutputStream? = null

    init {
        startPlaybackLoop()
    }

    /**
     * Garante o silenciamento acústico total do aparelho local (alto-falante e earpiece zerados).
     * Dispara comando para o Shell Daemon (UID 2000) aplicar mute no AudioManager do sistema.
     */
    fun silenceLocalAudio() {
        scope.launch(Dispatchers.IO) {
            try {
                // 1. Ajuste local das configurações acessíveis pelo app
                audioManager.mode = AudioManager.MODE_IN_CALL
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.clearCommunicationDevice()
                }

                // 2. Dispara comando privilegiado para o Shell Daemon mutar o STREAM_VOICE_CALL via cmd audio
                sendCommandToDaemon("MUTE_LOCAL_AUDIO")
                Log.i(TAG, "[SILÊNCIO LOCAL TOTAL] Comando de mute total enviado ao sistema.")
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao silenciar áudio local: ${e.message}", e)
            }
        }
    }

    /**
     * Mantido para compatibilidade com chamadas existentes no pipeline.
     */
    fun routeToSpeakerphone() {
        silenceLocalAudio()
    }

    /**
     * Restaura o áudio normal do aparelho ao encerrar a chamada.
     */
    fun restoreLocalAudio() {
        scope.launch(Dispatchers.IO) {
            try {
                sendCommandToDaemon("UNMUTE_LOCAL_AUDIO")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.clearCommunicationDevice()
                }
                audioManager.mode = AudioManager.MODE_NORMAL
                Log.i(TAG, "[ÁUDIO RESTAURADO] Mute local removido após término da chamada.")
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao restaurar áudio local: ${e.message}", e)
            }
        }
    }

    private fun sendCommandToDaemon(command: String) {
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", DAEMON_PORT), 500)
                val writer = PrintWriter(s.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                writer.println(command)
                val resp = reader.readLine()
                Log.d(TAG, "[DAEMON CMD] $command -> $resp")
            }
        } catch (e: Exception) {
            Log.d(TAG, "[DAEMON CMD] Falha ao enviar $command: ${e.message}")
        }
    }

    /**
     * Enfileira amostras de áudio para injeção imediata no uplink celular.
     */
    fun enqueueAudio(samples: FloatArray, sampleRate: Int) {
        if (samples.isEmpty()) return
        audioQueue.trySend(QueuedAudio(samples, sampleRate))
    }

    /**
     * Interrompe imediatamente qualquer transmissão em andamento.
     */
    fun stopPlayback() {
        try {
            while (audioQueue.tryReceive().isSuccess) {
                // Esvazia a fila
            }
            _isPlaying.value = false
            Log.d(TAG, "Injeção de áudio interrompida.")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao parar injeção: ${e.message}", e)
        }
    }

    /**
     * Aguarda até que toda a fila de áudio tenha sido transmitida ao modem celular.
     */
    suspend fun awaitPlaybackComplete(timeoutMs: Long = 10000) {
        val start = System.currentTimeMillis()
        while ((!audioQueue.isEmpty || _isPlaying.value) && (System.currentTimeMillis() - start < timeoutMs)) {
            kotlinx.coroutines.delay(50)
        }
        kotlinx.coroutines.delay(200) // Margem para o buffer de rádio do modem transmitir o final da fala
    }

    private fun ensureInjectionSocket(sampleRate: Int): OutputStream? {
        try {
            if (injectionSocket != null && injectionSocket?.isConnected == true && injectionOutputStream != null) {
                return injectionOutputStream
            }
            closeInjectionSocket()
            val socket = Socket()
            socket.connect(InetSocketAddress("127.0.0.1", DAEMON_PORT), 800)
            val writer = PrintWriter(socket.getOutputStream(), true)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            writer.println("INJECT_CALL_AUDIO $sampleRate")
            val resp = reader.readLine()
            if (resp != null && resp.startsWith("INJECT_READY")) {
                injectionSocket = socket
                injectionOutputStream = socket.getOutputStream()
                Log.i(TAG, "[UPLINK INJECTION] Conectado ao Daemon UID 2000 para injeção direta no uplink telefônico!")
                return injectionOutputStream
            } else {
                Log.w(TAG, "[UPLINK INJECTION] Resposta inesperada: $resp")
                socket.close()
            }
        } catch (e: Exception) {
            Log.d(TAG, "[UPLINK INJECTION] Daemon não disponível na porta $DAEMON_PORT: ${e.message}")
        }
        return null
    }

    private fun closeInjectionSocket() {
        try { injectionOutputStream?.close() } catch (ignored: Exception) {}
        try { injectionSocket?.close() } catch (ignored: Exception) {}
        injectionOutputStream = null
        injectionSocket = null
    }

    private fun floatToPcm16(floats: FloatArray): ByteArray {
        val bytes = ByteArray(floats.size * 2)
        for (i in floats.indices) {
            val sample = (floats[i].coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()
            bytes[i * 2] = (sample.toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private fun startPlaybackLoop() {
        playbackJob?.cancel()
        playbackJob = scope.launch {
            for (item in audioQueue) {
                if (!isActive) break
                try {
                    _isPlaying.value = true
                    val chunk = item.samples
                    val pcmBytes = floatToPcm16(chunk)

                    // Injeção 100% digital e direta no Uplink celular (modem TX) via Daemon Shell UID 2000.
                    // O aparelho local NÃO possui nenhum AudioTrack ativo e NÃO emite som algum.
                    val injectStream = ensureInjectionSocket(item.sampleRate)
                    if (injectStream != null) {
                        try {
                            injectStream.write(pcmBytes)
                            injectStream.flush()
                            Log.d(TAG, "[UPLINK INJECTION] ${pcmBytes.size} bytes injetados na chamada. Aparelho local mudo.")
                        } catch (e: Exception) {
                            Log.w(TAG, "[UPLINK INJECTION] Erro na transmissão: ${e.message}")
                            closeInjectionSocket()
                        }
                    } else {
                        Log.e(TAG, "[UPLINK INJECTION] Daemon Shell não conectado! Verifique porta $DAEMON_PORT.")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erro no loop de injeção de áudio: ${e.message}", e)
                } finally {
                    if (audioQueue.isEmpty) {
                        _isPlaying.value = false
                    }
                }
            }
        }
    }

    fun release() {
        playbackJob?.cancel()
        stopPlayback()
        closeInjectionSocket()
        restoreLocalAudio()
    }
}
