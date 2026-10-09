package com.example.ai_assistant.callconversation

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.example.ai_assistant.CallStateManager
import com.example.ai_assistant.api.ApiConfigManager
import com.example.ai_assistant.api.ApiSettings
import com.example.ai_assistant.api.LlmApiClient
import com.example.ai_assistant.api.SttApiClient
import com.example.ai_assistant.api.TtsApiClient
import com.example.ai_assistant.llm.ChatMessage
import com.example.ai_assistant.llm.ReasoningStreamFilter
import com.example.ai_assistant.tts.CallAudioPlayer
import com.example.ai_assistant.tts.SentenceStreamSynthesizer
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/**
 * Estados do ciclo de vida da Conversação por Chamada via APIs.
 */
enum class CallConversationState(val label: String, val icon: String) {
    WAITING_CALL("Aguardando chamada", "○"),
    CALL_ACTIVE("Chamada ativa", "●"),
    LISTENING_REMOTE("Ouvindo interlocutor", "●"),
    TRANSCRIBING("Transcrevendo (STT API)", "●"),
    GENERATING("Gerando resposta (LLM API)", "●"),
    SPEAKING("Falando na ligação (TTS API)", "●"),
    ERROR("Erro na API", "●")
}

/**
 * Diagnóstico da fonte de áudio durante a chamada (Isolamento total do microfone local).
 */
data class CallAudioSourceDiagnostics(
    val inputMode: String = "CALL_REMOTE_ONLY",
    val source: String = "VOICE_DOWNLINK",
    val track: String = "REMOTE",
    val isDownlinkActive: Boolean = false,
    val isUplinkIgnored: Boolean = true,
    val isMicUsed: Boolean = false,
    val localFramesSentToAsr: Long = 0L,
    val remoteFramesCount: Long = 0L,
    val remoteBytesCount: Long = 0L,
    val localFramesIgnored: Long = 0L,
    val captureMethod: String = "NENHUM"
)

/**
 * Métricas em tempo real do pipeline de chamadas telefônicas via APIs.
 */
data class CallConversationMetrics(
    val lastSttLatencyMs: Long = 0L,
    val lastSpeechDurationMs: Long = 0L,
    val lastLlmTtftMs: Long = 0L,
    val lastLlmTotalTimeMs: Long = 0L,
    val lastTtsLatencyMs: Long = 0L,
    val totalAudioProcessedSec: Float = 0f,
    val remotePcmFrames: Long = 0L,
    val remotePcmBytes: Long = 0L,
    val totalInteractions: Int = 0
)

private data class PendingCallUtterance(
    val segmentId: String,
    val text: String,
    val speechDurationMs: Long,
    val sttLatencyMs: Long,
    val speechEndTime: Long
)

/**
 * Gerenciador dedicado para Conversação Telefônica com APIs em Nuvem (STT Whisper -> LLM OpenAI-like -> TTS Áudio -> Injeção na Chamada).
 */
class CallConversationManager(
    private val context: Context,
    val apiConfigManager: ApiConfigManager,
    val sttApiClient: SttApiClient = SttApiClient(),
    val llmApiClient: LlmApiClient = LlmApiClient(),
    val ttsApiClient: TtsApiClient = TtsApiClient(context = context),
    val audioPlayer: CallAudioPlayer = CallAudioPlayer(context),
    val audioRecorder: CallAudioRecorder = CallAudioRecorder(context)
) {
    companion object {
        private const val TAG = "CallConvManager"
        private const val SAMPLE_RATE = 16000
        private const val DAEMON_PORT = 28472

        @Volatile
        private var instance: CallConversationManager? = null

        fun getInstance(context: Context, apiConfigManager: ApiConfigManager): CallConversationManager {
            return instance ?: synchronized(this) {
                instance ?: CallConversationManager(context.applicationContext, apiConfigManager).also { instance = it }
            }
        }

        const val DEFAULT_CALL_SYSTEM_PROMPT =
            "Você é o assistente pessoal atendendo uma ligação no celular do titular.\n\n" +
            "QUEM VOCÊ É:\n" +
            "- Você fala com a autoridade de quem atende o celular do titular.\n" +
            "- Você NÃO é um robô de telemarketing e NÃO presta suporte ao cliente genérico.\n" +
            "- Você NÃO está aqui para bajular ninguém. NUNCA diga frases como \"Como posso ajudar?\", \"Estou aqui para ajudar\" ou \"Posso fazer algo por você?\".\n\n" +
            "COMPORTAMENTO E TOM:\n" +
            "- Atenda com naturalidade e informalidade de brasileiro atendendo ligação.\n" +
            "- Seja irônico, sarcástico e com humor ácido se quem ligar for chato, telemarketing ou enrolado.\n" +
            "- Se for conversa normal, seja autêntico, descontraído e direto na lata.\n\n" +
            "REGRAS DE FALA AO TELEFONE:\n" +
            "1. Respostas super curtas: fale apenas 1 ou no máximo 2 frases rápidas (máximo 15 a 20 palavras). Ninguém discursa ao telefone.\n" +
            "2. Não use markdown, asteriscos, negrito ou listas.\n" +
            "3. Vá direto à fala sem enrolação nem pensamentos internos."
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _state = MutableStateFlow(CallConversationState.WAITING_CALL)
    val state: StateFlow<CallConversationState> = _state.asStateFlow()

    private val _diagnostics = MutableStateFlow(CallAudioSourceDiagnostics())
    val diagnostics: StateFlow<CallAudioSourceDiagnostics> = _diagnostics.asStateFlow()

    private val _metrics = MutableStateFlow(CallConversationMetrics())
    val metrics: StateFlow<CallConversationMetrics> = _metrics.asStateFlow()

    private val _conversationHistory = MutableStateFlow<List<ChatMessage>>(emptyList())
    val conversationHistory: StateFlow<List<ChatMessage>> = _conversationHistory.asStateFlow()

    private val _partialTranscript = MutableStateFlow("")
    val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()

    private val _currentStreamResponse = MutableStateFlow("")
    val currentStreamResponse: StateFlow<String> = _currentStreamResponse.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val personaManager = com.example.ai_assistant.persona.PersonaManager.getInstance(context)

    private val _systemPrompt = MutableStateFlow(DEFAULT_CALL_SYSTEM_PROMPT)
    val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()

    private val _liveRms = MutableStateFlow(0f)
    val liveRms: StateFlow<Float> = _liveRms.asStateFlow()

    private val _isSpeechDetected = MutableStateFlow(false)
    val isSpeechDetected: StateFlow<Boolean> = _isSpeechDetected.asStateFlow()

    private val _isAutoModeEnabled = MutableStateFlow(true)
    val isAutoModeEnabled: StateFlow<Boolean> = _isAutoModeEnabled.asStateFlow()

    // Controle de concorrência e áudio
    private var directAudioRecord: AudioRecord? = null
    private var streamSocket: Socket? = null
    private var audioCaptureJob: Job? = null
    private var llmWorkerJob: Job? = null
    private var callStateObserverJob: Job? = null

    private val sttMutex = Mutex()
    private val accumulatorMutex = Mutex()
    private val deduplicationMutex = Mutex()
    private val speechAccumulator = StringBuilder()
    private var llmChannel = Channel<PendingCallUtterance>(Channel.UNLIMITED)

    @Volatile
    private var isLlmBusy = false

    @Volatile
    private var isCapturing = false

    private var lastTranscribedText = ""
    private var lastTranscribedTime = 0L

    // VAD states para o áudio REMOTE (DOWNLINK)
    private val utteranceBuffer = ByteArrayOutputStream()
    private var hasActiveSpeech = false
    private var isRemoteSpeaking = false
    private var silenceDurationMs = 0
    private var speechDurationMs = 0L
    private var noiseFloor = 0.0

    // Contadores de Isolamento
    @Volatile
    private var remoteFramesCount = 0L
    @Volatile
    private var remoteBytesCount = 0L
    @Volatile
    private var localFramesIgnoredCount = 0L

    init {
        val initialPersona = personaManager.activePersona.value
        _systemPrompt.value = initialPersona.systemPrompt
        Log.i(TAG, "CallConversationManager inicializado com persona ativa: \"${initialPersona.name}\"")

        scope.launch {
            personaManager.activePersona.collect { active ->
                _systemPrompt.value = active.systemPrompt
                Log.i(TAG, "System prompt sincronizado em tempo real com PersonaManager: \"${active.name}\"")
            }
        }

        startLlmWorker()
        observeCallState()

        scope.launch {
            audioPlayer.isPlaying.collect { playing ->
                if (playing && _state.value == CallConversationState.GENERATING) {
                    _state.value = CallConversationState.SPEAKING
                }
            }
        }
    }

    fun setSystemPrompt(prompt: String) {
        val trimmed = prompt.trim()
        if (trimmed.isNotEmpty()) {
            _systemPrompt.value = trimmed
            Log.i(TAG, "System prompt da chamada atualizado: ${trimmed.take(60)}...")
        }
    }

    fun setAutoModeEnabled(enabled: Boolean) {
        _isAutoModeEnabled.value = enabled
        Log.i(TAG, "Modo automático de Conversação por Chamada: $enabled")
        if (CallStateManager.isCallActive.value) {
            if (enabled) {
                // Robô ativado na ligação: muta o microfone físico para isolar barulhos externos
                com.example.ai_assistant.CallRepository.setCallMicrophoneMute(true)
                startCallConversation()
            } else {
                // Robô pausado pelo usuário: desmuta o microfone físico imediatamente para o usuário falar
                com.example.ai_assistant.CallRepository.setCallMicrophoneMute(false)
                stopAudioCaptureInternal()
                _state.value = CallConversationState.CALL_ACTIVE
            }
        }
    }

    fun clearConversation() {
        _conversationHistory.value = emptyList()
        _partialTranscript.value = ""
        _currentStreamResponse.value = ""
        _errorMessage.value = null
        lastTranscribedText = ""
        lastTranscribedTime = 0L
        scope.launch {
            accumulatorMutex.withLock {
                speechAccumulator.clear()
                isLlmBusy = false
            }
        }
        Log.i(TAG, "[CONVERSA] Histórico de mensagens completamente zerado para a ligação.")
    }

    /**
     * Observa mudanças no estado da chamada telefônica gerenciadas pelo InCallService.
     */
    private fun observeCallState() {
        callStateObserverJob?.cancel()
        callStateObserverJob = scope.launch {
            CallStateManager.callStateName.collect { stateName ->
                Log.d(TAG, "CallStateManager estado alterado para: $stateName (AutoMode=${_isAutoModeEnabled.value})")
                when (stateName) {
                    "RINGING" -> {
                        Log.i(TAG, "[INTEGRAÇÃO CHAMADA] STATE_RINGING detectado. Zerando histórico e sincronizando persona ativa.")
                        clearConversation()
                        val current = personaManager.activePersona.value
                        _systemPrompt.value = current.systemPrompt
                        Log.i(TAG, "[INTEGRAÇÃO CHAMADA] Persona ativa para a chamada: \"${current.name}\"")
                        _state.value = CallConversationState.WAITING_CALL
                    }
                    "ACTIVE" -> {
                        if (_isAutoModeEnabled.value) {
                            Log.i(TAG, "[INTEGRAÇÃO CHAMADA] STATE_ACTIVE detectado! Iniciando captura e pipeline de API...")
                            startCallConversation()
                        } else {
                            _state.value = CallConversationState.CALL_ACTIVE
                        }
                    }
                    "DISCONNECTED", "NONE" -> {
                        Log.i(TAG, "[INTEGRAÇÃO CHAMADA] $stateName detectado. Finalizando pipeline da chamada...")
                        stopCallConversation(clearHistory = true)
                    }
                }
            }
        }
    }

    /**
     * Inicia o pipeline de conversação baseado no áudio remoto (DOWNLINK).
     */
    fun startCallConversation() {
        if (isCapturing) {
            Log.w(TAG, "[AUDIO INPUT] Captura de chamada já está em andamento. Ignorando chamada concorrente duplicada.")
            return
        }
        isCapturing = true

        stopAudioCaptureInternal()
        // Garante que a cada ligação a lista de mensagens zera
        clearConversation()

        // Garante que o system prompt da persona ativa configurada pelo usuário esteja no topo
        val currentPersona = personaManager.activePersona.value
        _systemPrompt.value = currentPersona.systemPrompt
        Log.i(TAG, "[INÍCIO DA CHAMADA] Histórico zerado (0 mensagens). Persona ativa: \"${currentPersona.name}\"")

        // Inicia a gravação em arquivo único da chamada
        val callInfo = com.example.ai_assistant.CallRepository.activeCallState.value
        val phone = callInfo?.phoneNumber?.takeIf { it.isNotBlank() } ?: "desconhecido"
        audioRecorder.startRecording(phone)
        com.example.ai_assistant.logging.InAppLogger.info("Ligação", "Chamada iniciada ($phone). Gravação ativada.")

        _errorMessage.value = null
        _state.value = CallConversationState.CALL_ACTIVE

        // Muta o microfone físico para que os barulhos externos do ambiente não se misturem à ligação
        com.example.ai_assistant.CallRepository.setCallMicrophoneMute(true)

        Log.i(TAG, "==================================================")
        Log.i(TAG, "[AUDIO MODE] CALL_REMOTE_ONLY (APIs: Whisper -> LLM -> TTS)")
        Log.i(TAG, "[AUDIO INPUT] source=VOICE_DOWNLINK | track=REMOTE")
        Log.i(TAG, "==================================================")

        _diagnostics.value = CallAudioSourceDiagnostics(
            inputMode = "CALL_REMOTE_ONLY",
            source = "VOICE_DOWNLINK",
            track = "REMOTE",
            isDownlinkActive = true,
            isUplinkIgnored = true,
            isMicUsed = false,
            localFramesSentToAsr = 0L,
            remoteFramesCount = 0L,
            remoteBytesCount = 0L,
            localFramesIgnored = 0L,
            captureMethod = "INICIANDO"
        )

        remoteFramesCount = 0L
        remoteBytesCount = 0L
        localFramesIgnoredCount = 0L
        resetVad()

        // 1. Tenta inicializar AudioRecord(VOICE_DOWNLINK) diretamente
        val directStarted = tryStartDirectDownlinkRecord()
        if (directStarted) {
            Log.i(TAG, "[AUDIO INPUT] Captura direta via AudioRecord(VOICE_DOWNLINK) ativa com sucesso!")
            _state.value = CallConversationState.LISTENING_REMOTE
            return
        }

        // 2. Se a permissão no processo do app não permitir, conecta ao Shell Daemon (UID 2000)
        Log.i(TAG, "[AUDIO INPUT] AudioRecord direto indisponível. Conectando ao Shell Daemon na porta $DAEMON_PORT...")
        startDaemonDownlinkStream()
    }

    @SuppressLint("MissingPermission")
    private fun tryStartDirectDownlinkRecord(): Boolean {
        try {
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) return false
            val bufSize = maxOf(minBuf * 2, 8192)

            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_DOWNLINK,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return false
            }

            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                record.release()
                return false
            }

            directAudioRecord = record
            _diagnostics.value = _diagnostics.value.copy(
                captureMethod = "AUDIO_RECORD_DOWNLINK",
                isDownlinkActive = true
            )

            audioCaptureJob = scope.launch(Dispatchers.IO) {
                val shortBuffer = ShortArray(1600)
                val byteBuffer = ByteArray(3200)

                while (isActive && directAudioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val read = record.read(shortBuffer, 0, 1600)
                    if (read > 0) {
                        for (i in 0 until read) {
                            val s = shortBuffer[i]
                            byteBuffer[i * 2] = (s.toInt() and 0xFF).toByte()
                            byteBuffer[i * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
                        }
                        val chunk = byteBuffer.copyOf(read * 2)
                        processRemotePcmChunk(chunk)
                    } else if (read < 0) {
                        Log.e(TAG, "Erro de leitura AudioRecord direto: $read")
                        break
                    }
                }
            }
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Exceção ao instanciar AudioRecord(VOICE_DOWNLINK): ${e.message}")
            return false
        }
    }

    private fun startDaemonDownlinkStream() {
        audioCaptureJob = scope.launch(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                socket = Socket()
                streamSocket = socket
                socket.connect(InetSocketAddress("127.0.0.1", DAEMON_PORT), 2500)
                socket.soTimeout = 0

                val os = socket.getOutputStream()
                val inputStream: InputStream = socket.getInputStream()
                val writer = PrintWriter(os, true)
                writer.println("STREAM_CALL_AUDIO")

                val headerBuilder = StringBuilder()
                while (true) {
                    val b = inputStream.read()
                    if (b == -1 || b == '\n'.code) break
                    if (b != '\r'.code) headerBuilder.append(b.toChar())
                }
                val header = headerBuilder.toString().trim()
                Log.i(TAG, "[DAEMON STREAM] Resposta do daemon: $header")

                if (!header.contains("mode=separated")) {
                    Log.e(TAG, "[ERRO] Daemon não retornou mode=separated: $header")
                    withContext(Dispatchers.Main) {
                        _errorMessage.value = "Áudio remoto da chamada indisponível no daemon."
                        _state.value = CallConversationState.ERROR
                        _diagnostics.value = _diagnostics.value.copy(
                            isDownlinkActive = false,
                            captureMethod = "INDISPONÍVEL"
                        )
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    _diagnostics.value = _diagnostics.value.copy(
                        captureMethod = "SHELL_DAEMON_DOWNLINK",
                        isDownlinkActive = true
                    )
                    _state.value = CallConversationState.LISTENING_REMOTE
                }

                while (isActive && !socket.isClosed) {
                    val trackId = inputStream.read()
                    if (trackId == -1) break
                    val b1 = inputStream.read()
                    val b2 = inputStream.read()
                    if (b1 == -1 || b2 == -1) break
                    val len = ((b1 and 0xFF) shl 8) or (b2 and 0xFF)
                    if (len <= 0 || len > 16384) break

                    val chunk = ByteArray(len)
                    var readTotal = 0
                    while (readTotal < len) {
                        val count = inputStream.read(chunk, readTotal, len - readTotal)
                        if (count <= 0) break
                        readTotal += count
                    }

                    if (readTotal == len) {
                        if (trackId == 2) {
                            processRemotePcmChunk(chunk)
                        } else {
                            localFramesIgnoredCount++
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[DAEMON STREAM] Conexão com daemon falhou: ${e.message}")
                if (CallStateManager.isCallActive.value) {
                    withContext(Dispatchers.Main) {
                        _errorMessage.value = "Áudio remoto indisponível (Módulo Privilegiado offline ao sair do Wi-Fi)."
                        _state.value = CallConversationState.ERROR
                        _diagnostics.value = _diagnostics.value.copy(
                            isDownlinkActive = false
                        )
                    }
                    com.example.ai_assistant.logging.InAppLogger.warn(
                        "Ligação",
                        "Atenção: Módulo Privilegiado offline na porta $DAEMON_PORT. O robô não conseguirá ouvir a chamada."
                    )
                }
            } finally {
                try { socket?.close() } catch (_: Exception) {}
                streamSocket = null
            }
        }
    }

    /**
     * Processa um chunk de áudio REMOTE (PCM 16-bit 16kHz Little Endian) pelo algoritmo de VAD.
     */
    private fun processRemotePcmChunk(chunk: ByteArray) {
        remoteFramesCount++
        remoteBytesCount += chunk.size

        // Grava o áudio do interlocutor no arquivo único da chamada
        audioRecorder.writeRemotePcm(chunk)

        if (remoteFramesCount % 50 == 0L) {
            _diagnostics.value = _diagnostics.value.copy(
                remoteFramesCount = remoteFramesCount,
                remoteBytesCount = remoteBytesCount,
                localFramesIgnored = localFramesIgnoredCount,
                localFramesSentToAsr = 0L
            )
        }

        val numSamples = chunk.size / 2
        if (numSamples == 0) return

        var sumSquares = 0.0
        for (i in 0 until numSamples) {
            val low = chunk[i * 2].toInt() and 0xFF
            val high = chunk[i * 2 + 1].toInt()
            val sample = (high shl 8) or low
            sumSquares += sample.toDouble() * sample.toDouble()
        }

        val rms = Math.sqrt(sumSquares / numSamples)
        val chunkDurationMs = (numSamples * 1000L) / SAMPLE_RATE

        _liveRms.value = rms.toFloat()

        if (noiseFloor == 0.0) {
            noiseFloor = rms
        } else if (rms < noiseFloor) {
            noiseFloor = noiseFloor * 0.90 + rms * 0.10
        } else {
            noiseFloor = noiseFloor * 0.998 + rms * 0.002
        }

        val dynamicThreshold = maxOf(85.0, noiseFloor + 45.0)
        val isSpeech = rms >= dynamicThreshold
        _isSpeechDetected.value = isSpeech

        if (isSpeech) {
            silenceDurationMs = 0
            hasActiveSpeech = true
            isRemoteSpeaking = true
            speechDurationMs += chunkDurationMs
            utteranceBuffer.write(chunk)

            // O assistente SEMPRE conclui a frase inteira sem ser interrompido pelo áudio da linha.

            val durationSec = speechDurationMs / 1000f
            _partialTranscript.value = "Ouvindo interlocutor (${String.format("%.1f", durationSec)}s)..."
            if (!isLlmBusy) {
                _state.value = CallConversationState.LISTENING_REMOTE
            }
        } else {
            if (hasActiveSpeech) {
                silenceDurationMs += chunkDurationMs.toInt()
                utteranceBuffer.write(chunk)

                val pauseThreshold = apiConfigManager.settings.value.pauseThresholdMs
                val shouldCut = (silenceDurationMs >= pauseThreshold && utteranceBuffer.size() >= 12800) || speechDurationMs >= 8000L

                if (shouldCut) {
                    val fullUtteranceBytes = utteranceBuffer.toByteArray()
                    val currentSpeechMs = speechDurationMs
                    val speechEndTime = System.currentTimeMillis() - silenceDurationMs

                    Log.i(TAG, ">>> [PAUSA DETECTADA] Fala do interlocutor: ${fullUtteranceBytes.size} bytes (${currentSpeechMs}ms). Despachando para STT API...")

                    utteranceBuffer.reset()
                    hasActiveSpeech = false
                    isRemoteSpeaking = false
                    silenceDurationMs = 0
                    speechDurationMs = 0L

                    processFinalRemoteUtterance(fullUtteranceBytes, currentSpeechMs, speechEndTime)
                }
            }
        }
    }

    /**
     * Envia o trecho capturado para a API de Transcrição (Whisper STT).
     */
    private fun processFinalRemoteUtterance(pcmBytes: ByteArray, speechDurationMs: Long, speechEndTime: Long) {
        val segmentId = UUID.randomUUID().toString()

        scope.launch(Dispatchers.Default) {
            val (finalText, sttLatency) = sttMutex.withLock {
                if (!isLlmBusy) {
                    _state.value = CallConversationState.TRANSCRIBING
                    _partialTranscript.value = "Transcrevendo fala (STT API)..."
                }

                val sttStartTime = System.currentTimeMillis()
                val result = sttApiClient.transcribePcm(
                    pcm16Bytes = pcmBytes,
                    sampleRate = SAMPLE_RATE,
                    settings = apiConfigManager.settings.value
                )

                val latency = System.currentTimeMillis() - sttStartTime
                val text = result.getOrElse { e ->
                    Log.e(TAG, "Erro no STT API: ${e.message}")
                    withContext(Dispatchers.Main) {
                        _errorMessage.value = "Erro STT: ${e.message}"
                    }
                    ""
                }
                Pair(text, latency)
            }

            if (finalText.isBlank()) {
                Log.i(TAG, "[REMOTE STT] Nenhum texto detectado. Ruído descartado.")
                _partialTranscript.value = ""
                if (!isLlmBusy) {
                    _state.value = if (CallStateManager.isCallActive.value) CallConversationState.LISTENING_REMOTE else CallConversationState.WAITING_CALL
                }
                return@launch
            }

            val trimmedText = finalText.trim()
            val now = System.currentTimeMillis()
            val isDuplicate = deduplicationMutex.withLock {
                if (trimmedText.equals(lastTranscribedText, ignoreCase = true) && (now - lastTranscribedTime) < 4500L) {
                    true
                } else {
                    lastTranscribedText = trimmedText
                    lastTranscribedTime = now
                    false
                }
            }

            if (isDuplicate) {
                Log.w(TAG, "[REMOTE STT] Fala duplicada descartada (detectada em menos de 4.5s): \"$trimmedText\"")
                _partialTranscript.value = ""
                if (!isLlmBusy) {
                    _state.value = if (CallStateManager.isCallActive.value) CallConversationState.LISTENING_REMOTE else CallConversationState.WAITING_CALL
                }
                return@launch
            }

            Log.i(TAG, "[REMOTE STT SUCESSO] ($segmentId, latência: ${sttLatency}ms): \"$trimmedText\"")

            val wasBusy = accumulatorMutex.withLock {
                if (isLlmBusy) {
                    if (speechAccumulator.isNotEmpty()) {
                        speechAccumulator.append(" ")
                    }
                    speechAccumulator.append(trimmedText)
                    Log.i(TAG, "[ACCUMULATOR] LLM ocupado. Retido: \"$speechAccumulator\"")
                    true
                } else {
                    isLlmBusy = true
                    false
                }
            }

            if (!wasBusy) {
                withContext(Dispatchers.Main) {
                    _partialTranscript.value = ""
                    _conversationHistory.value = _conversationHistory.value + ChatMessage(role = "user", content = trimmedText)
                }

                val utterance = PendingCallUtterance(
                    segmentId = segmentId,
                    text = trimmedText,
                    speechDurationMs = speechDurationMs,
                    sttLatencyMs = sttLatency,
                    speechEndTime = speechEndTime
                )
                llmChannel.trySend(utterance)
            } else {
                _partialTranscript.value = ""
            }
        }
    }

    /**
     * Worker sequencial: recebe texto transcrito, executa LLM API e aciona TTS API + Injeção de Áudio.
     */
    private fun startLlmWorker() {
        llmWorkerJob = scope.launch(Dispatchers.Default) {
            for (utterance in llmChannel) {
                try {
                    processLlmAndTts(utterance)
                } catch (e: Exception) {
                    Log.e(TAG, "Erro no worker LLM/TTS: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        _errorMessage.value = "Erro no pipeline: ${e.message}"
                    }
                } finally {
                    val nextText = accumulatorMutex.withLock {
                        val accumulated = speechAccumulator.toString().trim()
                        speechAccumulator.clear()
                        if (accumulated.isNotEmpty()) {
                            accumulated
                        } else {
                            isLlmBusy = false
                            null
                        }
                    }

                    if (nextText != null) {
                        Log.i(TAG, "[ACCUMULATOR] Despachando fala acumulada: \"$nextText\"")
                        withContext(Dispatchers.Main) {
                            _conversationHistory.value = _conversationHistory.value + ChatMessage(role = "user", content = nextText)
                        }
                        val nextUtterance = PendingCallUtterance(
                            segmentId = UUID.randomUUID().toString(),
                            text = nextText,
                            speechDurationMs = 0L,
                            sttLatencyMs = 0L,
                            speechEndTime = System.currentTimeMillis()
                        )
                        llmChannel.trySend(nextUtterance)
                    } else {
                        withContext(Dispatchers.Main) {
                            _state.value = if (CallStateManager.isCallActive.value) CallConversationState.LISTENING_REMOTE else CallConversationState.WAITING_CALL
                        }
                    }
                }
            }
        }
    }

    /**
     * Executa a inferência LLM via API OpenAI-like e despacha frases para a API TTS para injeção de áudio na ligação.
     */
    private suspend fun processLlmAndTts(item: PendingCallUtterance) {
        isLlmBusy = true
        _state.value = CallConversationState.GENERATING
        _currentStreamResponse.value = ""

        val settings = apiConfigManager.settings.value
        audioPlayer.silenceLocalAudio()

        // Sincroniza o prompt da Persona Ativa e garante que ele seja a primeira mensagem enviada
        val activePersona = personaManager.activePersona.value
        val activePrompt = activePersona.systemPrompt.ifBlank { _systemPrompt.value }
        val currentCallHistory = _conversationHistory.value.takeLast(50)

        // Estrutura das mensagens para o LLM:
        // [0] -> role: "system", content: systemPrompt da persona ativa
        // [1..N] -> histórico da chamada atual (user / assistant)
        val messages = mutableListOf<ChatMessage>().apply {
            add(ChatMessage(role = "system", content = activePrompt))
            addAll(currentCallHistory)
        }

        Log.i(TAG, "[LLM INFERÊNCIA] Persona: \"${activePersona.name}\" | Mensagens no payload: ${messages.size} (system prompt + ${currentCallHistory.size} histórico)")

        val assistantBuilder = StringBuilder()
        val filter = ReasoningStreamFilter()
        val synthesizer = SentenceStreamSynthesizer(
            ttsApiClient = ttsApiClient,
            audioPlayer = audioPlayer,
            settings = settings,
            cacheDir = context.cacheDir,
            onAudioSynthesized = { samples, sampleRate ->
                audioRecorder.writeAssistantSamples(samples, sampleRate)
            }
        )

        val llmStartTime = System.currentTimeMillis()
        var firstTokenTime: Long? = null
        var tokenCount = 0

        try {
            llmApiClient.streamChat(messages, settings).collect { rawToken ->
                val cleanPieces = filter.processToken(rawToken)
                for (piece in cleanPieces) {
                    if (piece.isNotEmpty()) {
                        if (firstTokenTime == null) {
                            firstTokenTime = System.currentTimeMillis()
                        }
                        tokenCount++
                        assistantBuilder.append(piece)
                        _currentStreamResponse.value = assistantBuilder.toString()
                        synthesizer.onToken(piece)
                    }
                }
            }

            for (residual in filter.finish()) {
                if (residual.isNotEmpty()) {
                    tokenCount++
                    assistantBuilder.append(residual)
                    _currentStreamResponse.value = assistantBuilder.toString()
                    synthesizer.onToken(residual)
                }
            }
            synthesizer.awaitAllSynthesized()
            audioPlayer.awaitPlaybackComplete()

            val llmEndTime = System.currentTimeMillis()
            val finalReply = assistantBuilder.toString().trim()

            if (finalReply.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    _conversationHistory.value = _conversationHistory.value + ChatMessage(role = "assistant", content = finalReply)
                }
                com.example.ai_assistant.logging.InAppLogger.success("LLM", "Resposta: \"$finalReply\"")
            }

            val ttft = if (firstTokenTime != null) firstTokenTime - llmStartTime else 0L
            val totalTime = llmEndTime - llmStartTime

            _metrics.value = _metrics.value.copy(
                lastSttLatencyMs = item.sttLatencyMs,
                lastSpeechDurationMs = item.speechDurationMs,
                lastLlmTtftMs = ttft,
                lastLlmTotalTimeMs = totalTime,
                totalAudioProcessedSec = _metrics.value.totalAudioProcessedSec + (item.speechDurationMs / 1000f),
                remotePcmFrames = remoteFramesCount,
                remotePcmBytes = remoteBytesCount,
                totalInteractions = _metrics.value.totalInteractions + 1
            )

            Log.i(TAG, "[LLM + TTS SUCESSO] TTFT=${ttft}ms, Tokens=$tokenCount, Total=${totalTime}ms: \"$finalReply\"")

        } catch (e: Exception) {
            Log.e(TAG, "Falha na chamada LLM/TTS: ${e.message}", e)
            withContext(Dispatchers.Main) {
                _errorMessage.value = "Falha LLM API: ${e.message}"
            }
        } finally {
            _currentStreamResponse.value = ""
        }
    }

    /**
     * Envia uma mensagem de teste manual (ex: digitada na UI) para validar o pipeline LLM -> TTS -> Áudio.
     */
    fun sendManualTestMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        scope.launch {
            withContext(Dispatchers.Main) {
                _conversationHistory.value = _conversationHistory.value + ChatMessage(role = "user", content = trimmed)
            }
            val utterance = PendingCallUtterance(
                segmentId = UUID.randomUUID().toString(),
                text = trimmed,
                speechDurationMs = 0L,
                sttLatencyMs = 0L,
                speechEndTime = System.currentTimeMillis()
            )
            llmChannel.trySend(utterance)
        }
    }

    private fun resetVad() {
        utteranceBuffer.reset()
        hasActiveSpeech = false
        isRemoteSpeaking = false
        silenceDurationMs = 0
        speechDurationMs = 0L
        _partialTranscript.value = ""
        _isSpeechDetected.value = false
        _liveRms.value = 0f
    }

    private fun stopAudioCaptureInternal() {
        isCapturing = false
        try {
            directAudioRecord?.stop()
            directAudioRecord?.release()
        } catch (_: Exception) {}
        directAudioRecord = null

        try {
            streamSocket?.close()
        } catch (_: Exception) {}
        streamSocket = null

        audioCaptureJob?.cancel()
        audioCaptureJob = null
    }

    fun stopCallConversation(clearHistory: Boolean = true) {
        Log.i(TAG, "Encerrando pipeline de Conversação por Chamada...")
        // Desmuta o microfone físico para que o usuário possa falar caso queira
        com.example.ai_assistant.CallRepository.setCallMicrophoneMute(false)
        audioPlayer.stopPlayback()
        stopAudioCaptureInternal()
        resetVad()

        scope.launch {
            accumulatorMutex.withLock {
                speechAccumulator.clear()
                isLlmBusy = false
            }
        }

        _diagnostics.value = _diagnostics.value.copy(isDownlinkActive = false)

        val recordedPath = audioRecorder.stopRecording()
        val historyToSave = _conversationHistory.value
        if (historyToSave.isNotEmpty() || recordedPath != null) {
            val callInfo = com.example.ai_assistant.CallRepository.activeCallState.value
            val phone = callInfo?.phoneNumber?.takeIf { it.isNotBlank() } ?: "Chamada Telefônica"
            val caller = callInfo?.callerName?.takeIf { it.isNotBlank() && it != "Sem Nome" } ?: phone
            com.example.ai_assistant.history.CallHistoryManager.getInstance(context).addCallRecord(
                phoneNumber = phone,
                callerName = caller,
                durationSeconds = _metrics.value.totalAudioProcessedSec.toLong(),
                messages = historyToSave,
                audioFilePath = recordedPath
            )
            com.example.ai_assistant.logging.InAppLogger.info("Ligação", "Chamada encerrada. Gravação salva: ${recordedPath ?: "Sem áudio"}")
        }

        if (clearHistory) {
            _conversationHistory.value = emptyList()
        }

        _partialTranscript.value = ""
        _currentStreamResponse.value = ""
        _state.value = CallConversationState.WAITING_CALL
    }

    fun release() {
        stopCallConversation()
        audioPlayer.release()
        callStateObserverJob?.cancel()
        llmWorkerJob?.cancel()
        scope.cancel()
    }
}
