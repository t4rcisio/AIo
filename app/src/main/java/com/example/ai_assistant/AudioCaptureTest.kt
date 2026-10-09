package com.example.ai_assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * AudioCaptureTest realiza a instrumentação de diagnóstico definitivo da política de áudio do Android.
 *
 * Monitora e registra:
 * - callState (NONE, RINGING, ACTIVE, DISCONNECTED)
 * - audioMode (MODE_NORMAL, MODE_IN_CALL, MODE_IN_COMMUNICATION, etc.)
 * - clientSilenced (isClientSilenced() do AudioRecordingConfiguration)
 * - device (getAudioDevice())
 * - format e clientFormat
 * - AudioRecord.getActiveRecordingConfiguration()
 * - AudioManager.getActiveRecordingConfigurations()
 */
class AudioCaptureTest(private val context: Context) {

    companion object {
        private const val TAG = "AudioCaptureTest"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val LOG_INTERVAL_MS = 500L
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var stateObservationJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val audioRecordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            super.onRecordingConfigChanged(configs)
            logAudioPolicy("onRecordingConfigChanged (Callback do Sistema)", configs)
        }
    }

    /**
     * Inicia o teste de captura e a instrumentação de diagnóstico de política de áudio.
     */
    fun startTest(): Boolean {
        if (_isCapturing.value) {
            Log.w(TAG, "Teste de captura já está em execução.")
            return false
        }

        // 1. Verificar permissão RECORD_AUDIO em runtime
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            Log.e(TAG, "[ERRO CAPTURA] Permissão android.permission.RECORD_AUDIO não concedida.")
            return false
        }

        val audioManager = context.getSystemService(AudioManager::class.java)

        // 2. Registrar o AudioRecordingCallback ANTES de startRecording()
        audioManager?.registerAudioRecordingCallback(
            audioRecordingCallback,
            Handler(Looper.getMainLooper())
        )

        val currentCallState = CallStateManager.callStateName.value
        Log.i(TAG, "==================================================")
        Log.i(TAG, "[INICIANDO DIAGNÓSTICO DEFINITIVO DE POLÍTICA DE ÁUDIO]")
        Log.i(TAG, "[AUDIO CAPTURE TEST] callState=$currentCallState | audioMode=${audioModeToString(audioManager?.mode ?: -1)}")
        Log.i(TAG, "Configuração: SampleRate=$SAMPLE_RATE Hz, Mono, PCM 16-bit, Source=MIC")

        logAudioPolicy("Antes do startRecording()", audioManager?.activeRecordingConfigurations)

        try {
            // 3. Calcular tamanho mínimo do buffer
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            )

            if (minBufferSize <= 0) {
                Log.e(
                    TAG,
                    "[ERRO CAPTURA] Falha ao obter minBufferSize do AudioRecord. Código: $minBufferSize."
                )
                unregisterCallback()
                return false
            }

            val bufferSize = maxOf(minBufferSize, SAMPLE_RATE * 2)

            // 4. Instanciar AudioRecord com fonte MediaRecorder.AudioSource.MIC
            @Suppress("MissingPermission")
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            // 5. Verificar estado de inicialização do AudioRecord
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(
                    TAG,
                    "[ERRO CAPTURA] AudioRecord.STATE_UNINITIALIZED (Estado: ${recorder.state})."
                )
                recorder.release()
                unregisterCallback()
                return false
            }

            // 6. Chamar startRecording()
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(
                    TAG,
                    "[ERRO CAPTURA] AudioRecord falhou ao iniciar gravação (RecordingState: ${recorder.recordingState})."
                )
                recorder.release()
                unregisterCallback()
                return false
            }

            audioRecord = recorder
            _isCapturing.value = true

            logAudioPolicy("Após o startRecording()", audioManager?.activeRecordingConfigurations)

            Log.i(TAG, "[AUDIO CAPTURE TEST] AudioRecord iniciado com SUCESSO. Leitura de buffers em andamento...")

            // 7. Observar transições do estado da chamada em tempo real durante a gravação
            stateObservationJob = coroutineScope.launch {
                CallStateManager.callStateName.collect { stateName ->
                    if (_isCapturing.value) {
                        logAudioPolicy("Transição do Estado da Chamada -> $stateName")
                    }
                }
            }

            // 8. Iniciar coroutine para leitura contínua de buffers
            recordingJob = coroutineScope.launch {
                readAudioLoop(recorder)
            }

            return true

        } catch (e: SecurityException) {
            Log.e(TAG, "[ERRO CAPTURA] SecurityException ao instanciar/iniciar AudioRecord: ${e.message}", e)
            cleanUp()
            return false
        } catch (e: IllegalStateException) {
            Log.e(TAG, "[ERRO CAPTURA] IllegalStateException no AudioRecord: ${e.message}", e)
            cleanUp()
            return false
        } catch (e: Exception) {
            Log.e(TAG, "[ERRO CAPTURA] Exceção inesperada no AudioRecord: ${e.message}", e)
            cleanUp()
            return false
        }
    }

    /**
     * Registra com precisão o estado da política de áudio [AUDIO POLICY].
     */
    private fun logAudioPolicy(reason: String, configsParam: List<AudioRecordingConfiguration>? = null) {
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return
        val currentCallState = CallStateManager.callStateName.value
        val currentAudioMode = audioModeToString(audioManager.mode)
        val configs = configsParam ?: audioManager.activeRecordingConfigurations

        Log.i(TAG, "--------------------------------------------------")
        Log.i(TAG, "[AUDIO POLICY] Motivo: $reason")
        Log.i(TAG, "[AUDIO POLICY] callState=$currentCallState")
        Log.i(TAG, "[AUDIO POLICY] audioMode=$currentAudioMode")

        // 1. Diagnóstico direto via AudioRecord.getActiveRecordingConfiguration()
        val ownConfig = audioRecord?.activeRecordingConfiguration
        if (ownConfig != null) {
            val device = ownConfig.audioDevice
            val deviceStr = if (device != null) {
                "Nome: \"${device.productName}\" | Tipo: ${deviceTypeToString(device.type)} (${device.type}) | ID: ${device.id}"
            } else {
                "Dispositivo Padrão / Indisponível"
            }

            Log.i(TAG, "[AUDIO POLICY] AudioRecord.getActiveRecordingConfiguration():")
            Log.i(TAG, "  clientSilenced = ${ownConfig.isClientSilenced}")
            Log.i(TAG, "  audioDevice = $deviceStr")
            Log.i(TAG, "  format = ${ownConfig.format}")
            Log.i(TAG, "  clientFormat = ${ownConfig.clientFormat}")
        } else {
            Log.i(TAG, "[AUDIO POLICY] AudioRecord.getActiveRecordingConfiguration(): null")
        }

        // 2. Diagnóstico via AudioManager.getActiveRecordingConfigurations()
        Log.i(TAG, "[AUDIO POLICY] AudioManager.getActiveRecordingConfigurations() (Total: ${configs.size}):")
        if (configs.isEmpty()) {
            Log.i(TAG, "  - Nenhuma gravação ativa no AudioManager.")
        } else {
            val currentSessionId = audioRecord?.audioSessionId

            configs.forEachIndexed { index, config ->
                val isOurClient = currentSessionId != null && config.clientAudioSessionId == currentSessionId
                val tag = if (isOurClient) " (Nosso AudioRecord)" else ""

                val device = config.audioDevice
                val deviceStr = if (device != null) {
                    "Nome: \"${device.productName}\" | Tipo: ${deviceTypeToString(device.type)} (${device.type}) | ID: ${device.id}"
                } else {
                    "Dispositivo Padrão"
                }

                Log.i(TAG, "  Config [$index]$tag:")
                Log.i(TAG, "    clientSilenced = ${config.isClientSilenced}")
                Log.i(TAG, "    audioDevice = $deviceStr")
                Log.i(TAG, "    format = ${config.format}")
                Log.i(TAG, "    clientFormat = ${config.clientFormat}")
                Log.i(TAG, "    clientAudioSource = ${config.clientAudioSource}")
                Log.i(TAG, "    clientAudioSessionId = ${config.clientAudioSessionId}")
            }
        }
        Log.i(TAG, "--------------------------------------------------")
    }

    private fun audioModeToString(mode: Int): String {
        return when (mode) {
            AudioManager.MODE_NORMAL -> "MODE_NORMAL"
            AudioManager.MODE_RINGTONE -> "MODE_RINGTONE"
            AudioManager.MODE_IN_CALL -> "MODE_IN_CALL"
            AudioManager.MODE_IN_COMMUNICATION -> "MODE_IN_COMMUNICATION"
            AudioManager.MODE_CALL_SCREENING -> "MODE_CALL_SCREENING"
            else -> "MODE_$mode"
        }
    }

    private fun deviceTypeToString(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
            AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
            else -> "TYPE_$type"
        }
    }

    /**
     * Loop de leitura de buffers PCM e cálculo de métricas locais.
     */
    private fun CoroutineScope.readAudioLoop(recorder: AudioRecord) {
        val readBuffer = ShortArray(1024)
        var totalFramesRead = 0L
        var totalBuffersRead = 0L

        var lastLogTime = System.currentTimeMillis()
        var currentWindowFrames = 0L
        var currentWindowSumSquares = 0.0
        var currentWindowPeak = 0

        val audioManager = context.getSystemService(AudioManager::class.java)

        while (isActive && _isCapturing.value) {
            val shortsRead = recorder.read(readBuffer, 0, readBuffer.size)

            if (shortsRead > 0) {
                totalBuffersRead++
                totalFramesRead += shortsRead
                currentWindowFrames += shortsRead

                for (i in 0 until shortsRead) {
                    val sample = readBuffer[i].toInt()
                    val absSample = abs(sample)
                    if (absSample > currentWindowPeak) {
                        currentWindowPeak = absSample
                    }
                    currentWindowSumSquares += sample.toDouble() * sample.toDouble()
                }

                val now = System.currentTimeMillis()
                if (now - lastLogTime >= LOG_INTERVAL_MS) {
                    val rms = if (currentWindowFrames > 0) {
                        sqrt(currentWindowSumSquares / currentWindowFrames)
                    } else 0.0

                    val recordStateStr = when (recorder.recordingState) {
                        AudioRecord.RECORDSTATE_RECORDING -> "RECORDING"
                        AudioRecord.RECORDSTATE_STOPPED -> "STOPPED"
                        else -> "UNKNOWN(${recorder.recordingState})"
                    }

                    val currentCallState = CallStateManager.callStateName.value
                    val currentAudioMode = audioModeToString(audioManager?.mode ?: -1)

                    Log.i(
                        TAG,
                        "[AUDIO CAPTURE TEST] callState=$currentCallState | audioMode=$currentAudioMode | state=$recordStateStr | buffers=$totalBuffersRead | frames=$totalFramesRead | rms=${"%.2f".format(rms)} | peak=$currentWindowPeak"
                    )

                    // Reset janelas locais
                    lastLogTime = now
                    currentWindowFrames = 0L
                    currentWindowSumSquares = 0.0
                    currentWindowPeak = 0
                }
            } else if (shortsRead < 0) {
                val errorMsg = when (shortsRead) {
                    AudioRecord.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION (-3)"
                    AudioRecord.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE (-2)"
                    AudioRecord.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT (-6)"
                    AudioRecord.ERROR -> "ERROR (-1)"
                    else -> "CÓDIGO ($shortsRead)"
                }
                Log.e(TAG, "[AUDIO CAPTURE TEST] Erro na leitura do buffer: $errorMsg")
            }
        }
    }

    /**
     * Encerra o teste e desregistra os callbacks.
     */
    fun stopTest() {
        if (!_isCapturing.value) return

        Log.i(TAG, "==================================================")
        Log.i(TAG, "[PARANDO TESTE DE CAPTURA DO MICROFONE]")

        _isCapturing.value = false
        recordingJob?.cancel()
        recordingJob = null
        stateObservationJob?.cancel()
        stateObservationJob = null

        cleanUp()

        Log.i(TAG, "[AUDIO CAPTURE TEST] Recursos do AudioRecord liberados com sucesso.")
        Log.i(TAG, "==================================================")
    }

    private fun unregisterCallback() {
        try {
            val audioManager = context.getSystemService(AudioManager::class.java)
            audioManager?.unregisterAudioRecordingCallback(audioRecordingCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao desregistrar AudioRecordingCallback: ${e.message}")
        }
    }

    private fun cleanUp() {
        unregisterCallback()
        try {
            audioRecord?.let { recorder ->
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop()
                }
                recorder.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao liberar recursos do AudioRecord: ${e.message}", e)
        } finally {
            audioRecord = null
            _isCapturing.value = false
        }
    }
}
