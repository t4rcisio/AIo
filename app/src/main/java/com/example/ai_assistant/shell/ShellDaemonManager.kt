package com.example.ai_assistant.shell

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.media.MediaScannerConnection
import android.util.Base64
import android.util.Log
import androidx.core.content.FileProvider
import com.example.ai_assistant.adb.AdbManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Estados do ciclo de vida do Shell Daemon.
 */
enum class ShellDaemonState {
    NOT_STARTED,
    STARTING,
    RUNNING,
    ERROR
}

/**
 * Métricas do teste de captura de áudio executado dentro do daemon shell via VOICE_CALL.
 */
data class ShellAudioMetrics(
    val status: String,
    val state: String,
    val sampleRate: Int,
    val channels: Int,
    val bytes: Long,
    val frames: Long,
    val rms: Double,
    val peak: Int,
    val durationMs: Long,
    val filePath: String?,
    val error: String?
)

/**
 * Informações e diagnóstico do Shell Daemon executado via app_process.
 */
data class ShellDaemonInfo(
    val state: ShellDaemonState = ShellDaemonState.NOT_STARTED,
    val uid: String? = null,
    val sdk: String? = null,
    val model: String? = null,
    val pid: String? = null,
    val version: String? = null,
    val port: Int = 28472,
    val pingStatus: String? = null,
    val statusMessage: String = "Daemon não iniciado",
    val errorMessage: String? = null,
    val logs: List<String> = emptyList(),
    val isTesting: Boolean = false,
    val isAudioTesting: Boolean = false,
    val audioMetrics: ShellAudioMetrics? = null,
    val audioTestMessage: String? = null,
    val isPlayingAudio: Boolean = false
)

/**
 * Coordenador do bootstrap e comunicação com o daemon shell via app_process.
 */
class ShellDaemonManager(
    private val context: Context,
    private val adbManager: AdbManager
) {
    companion object {
        private const val TAG = "AICallDaemon"
        private const val DAEMON_PORT = 28472
        private const val DAEMON_JAR_NAME = "aicall-shellserver.jar"
        private const val TARGET_JAR_PATH = "/data/local/tmp/aicall-shellserver.jar"

        @Volatile
        private var instance: ShellDaemonManager? = null

        fun getInstance(context: Context, adbManager: AdbManager): ShellDaemonManager {
            return instance ?: synchronized(this) {
                instance ?: ShellDaemonManager(context.applicationContext, adbManager).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private val _daemonInfo = MutableStateFlow(ShellDaemonInfo())
    val daemonInfo: StateFlow<ShellDaemonInfo> = _daemonInfo.asStateFlow()

    init {
        // Verifica status inicial do daemon e monitora periodicamente via loopback 127.0.0.1 (opera em 4G, 5G ou Wi-Fi)
        scope.launch {
            while (isActive) {
                checkStatusSilent()
                delay(5000L)
            }
        }

        // Auto-start do daemon quando o ADB estiver conectado
        scope.launch {
            adbManager.adbInfo.collect { info ->
                if (info.state == com.example.ai_assistant.adb.AdbState.CONNECTED) {
                    val isRunning = checkStatusSilent()
                    if (!isRunning && _daemonInfo.value.state != ShellDaemonState.STARTING && _daemonInfo.value.state != ShellDaemonState.RUNNING) {
                        Log.i(TAG, "[DAEMON AUTO-START] ADB conectado detectado. Iniciando Shell Daemon automaticamente...")
                        startDaemon()
                    }
                }
            }
        }
    }

    /**
     * Adiciona uma mensagem ao histórico de logs do daemon.
     */
    private fun log(message: String) {
        Log.i(TAG, message)
        val currentLogs = _daemonInfo.value.logs.takeLast(40).toMutableList()
        currentLogs.add(message)
        _daemonInfo.value = _daemonInfo.value.copy(logs = currentLogs)
        val cleanMsg = message.removePrefix("[DAEMON] ").trim()
        if (cleanMsg.contains("Erro", ignoreCase = true) || cleanMsg.contains("Falha", ignoreCase = true)) {
            com.example.ai_assistant.logging.InAppLogger.error("Módulo", cleanMsg)
        } else if (cleanMsg.contains("sucesso", ignoreCase = true) || cleanMsg.contains("Rodando", ignoreCase = true)) {
            com.example.ai_assistant.logging.InAppLogger.success("Módulo", cleanMsg)
        } else {
            com.example.ai_assistant.logging.InAppLogger.info("Módulo", cleanMsg)
        }
    }

    /**
     * Inicia o daemon via bootstrap ADB (ou reutiliza caso já esteja em execução).
     */
    fun startDaemon() {
        scope.launch {
            _daemonInfo.value = _daemonInfo.value.copy(
                state = ShellDaemonState.STARTING,
                statusMessage = "Verificando se o daemon já está ativo...",
                errorMessage = null
            )
            log("[DAEMON] Verificando daemon na porta $DAEMON_PORT...")

            // 1. Testa se o daemon já está rodando
            val existingInfo = testPingAndInfoInternal()
            if (existingInfo != null && existingInfo["version"] == "5") {
                log("[DAEMON] Daemon v5 já em execução (PID=${existingInfo["pid"]}, UID=${existingInfo["uid"]})")
                _daemonInfo.value = _daemonInfo.value.copy(
                    state = ShellDaemonState.RUNNING,
                    uid = existingInfo["uid"] ?: "2000",
                    sdk = existingInfo["sdk"],
                    model = existingInfo["model"],
                    pid = existingInfo["pid"],
                    version = existingInfo["version"],
                    pingStatus = "OK",
                    statusMessage = "Rodando (v5)",
                    errorMessage = null
                )
                return@launch
            } else if (existingInfo != null) {
                log("[DAEMON] Daemon versão anterior detectado (v${existingInfo["version"]}, PID=${existingInfo["pid"]}). Reiniciando para v5...")
                try {
                    sendShutdownCommand()
                } catch (_: Exception) {}
                delay(400L)
            }

            // 2. Verifica se a conexão ADB está estabelecida ou tenta reconectar
            if (!adbManager.isConnected && !adbManager.ensureConnected()) {
                val err = "ADB local não conectado. Conecte o ADB local antes de iniciar o daemon."
                log("[DAEMON] $err")
                _daemonInfo.value = _daemonInfo.value.copy(
                    state = ShellDaemonState.ERROR,
                    statusMessage = "Erro: ADB desconectado",
                    errorMessage = err
                )
                return@launch
            }

            _daemonInfo.value = _daemonInfo.value.copy(
                statusMessage = "Transferindo JAR para /data/local/tmp...",
            )

            // 3. Lê o JAR embutido dos assets e transfere para o aparelho
            val jarBytes = try {
                context.assets.open(DAEMON_JAR_NAME).use { it.readBytes() }
            } catch (e: Exception) {
                val err = "Falha ao ler asset $DAEMON_JAR_NAME: ${e.message}"
                log("[DAEMON] $err")
                _daemonInfo.value = _daemonInfo.value.copy(
                    state = ShellDaemonState.ERROR,
                    statusMessage = "Erro no asset",
                    errorMessage = err
                )
                return@launch
            }

            log("[DAEMON] JAR lido (${jarBytes.size} bytes). Transferindo via ADB stream...")
            val pushResult = adbManager.pushFile(jarBytes, TARGET_JAR_PATH)
            if (pushResult.isFailure) {
                val err = "Falha ao transferir JAR via ADB: ${pushResult.exceptionOrNull()?.message}"
                log("[DAEMON] $err")
                _daemonInfo.value = _daemonInfo.value.copy(
                    state = ShellDaemonState.ERROR,
                    statusMessage = "Falha na transferência",
                    errorMessage = err
                )
                return@launch
            }

            adbManager.executeCommand("chmod 644 $TARGET_JAR_PATH")

            // Finaliza instâncias anteriores para evitar erro EADDRINUSE na porta
            try {
                adbManager.executeCommand("pkill -f com.aicall.shell.Main")
                delay(300L)
            } catch (_: Exception) {}

            _daemonInfo.value = _daemonInfo.value.copy(
                statusMessage = "Executando app_process via ADB...",
            )

            // 4. Executa o daemon via app_process com nohup em background, desanexando stdin e permitindo inicialização
            log("[DAEMON] Iniciando app_process com nohup e desanexação...")
            val launchCmd = "export CLASSPATH=$TARGET_JAR_PATH; nohup app_process / com.aicall.shell.Main < /dev/null > /data/local/tmp/aicall-shellserver.log 2>&1 & sleep 1"
            val launchResult = adbManager.executeCommand(launchCmd)
            log("[DAEMON] Comando de lançamento executado: ${launchResult.getOrNull() ?: launchResult.exceptionOrNull()?.message}")

            // 5. Aguarda inicialização do ServerSocket no loopback (tenta por até 4 segundos)
            _daemonInfo.value = _daemonInfo.value.copy(
                statusMessage = "Aguardando socket 127.0.0.1:$DAEMON_PORT..."
            )

            var infoMap: Map<String, String>? = null
            for (attempt in 1..10) {
                delay(350L)
                log("[DAEMON] Tentativa de conexão local #$attempt no socket...")
                infoMap = testPingAndInfoInternal()
                if (infoMap != null) break
            }

            if (infoMap != null) {
                log("[DAEMON] Daemon inicializado com sucesso! UID=${infoMap["uid"]}, PID=${infoMap["pid"]}")
                _daemonInfo.value = _daemonInfo.value.copy(
                    state = ShellDaemonState.RUNNING,
                    uid = infoMap["uid"] ?: "2000",
                    sdk = infoMap["sdk"],
                    model = infoMap["model"],
                    pid = infoMap["pid"],
                    version = infoMap["version"],
                    pingStatus = "OK",
                    statusMessage = "Rodando (v${infoMap["version"] ?: "5"})",
                    errorMessage = null
                )
            } else {
                val logOutput = try {
                    adbManager.executeCommand("cat /data/local/tmp/aicall-shellserver.log").getOrNull()?.trim()
                } catch (_: Exception) { null }
                val detail = if (!logOutput.isNullOrBlank()) "\nLog do daemon: $logOutput" else ""
                val err = "O daemon não respondeu na porta $DAEMON_PORT após o lançamento.$detail"
                log("[DAEMON] $err")
                _daemonInfo.value = _daemonInfo.value.copy(
                    state = ShellDaemonState.ERROR,
                    statusMessage = "Timeout ao aguardar socket",
                    errorMessage = err
                )
            }
        }
    }

    /**
     * Testa o daemon diretamente via Socket TCP no loopback (sem usar ADB).
     * Funciona mesmo com o ADB desconectado!
     */
    fun testDaemon() {
        scope.launch {
            _daemonInfo.value = _daemonInfo.value.copy(isTesting = true, errorMessage = null)
            log("[DAEMON] Testando comunicação TCP em 127.0.0.1:$DAEMON_PORT...")

            val infoMap = testPingAndInfoInternal()
            if (infoMap != null) {
                log("[DAEMON] Teste concluído: PONG recebido! UID=${infoMap["uid"]}, PID=${infoMap["pid"]}")
                _daemonInfo.value = _daemonInfo.value.copy(
                    state = ShellDaemonState.RUNNING,
                    uid = infoMap["uid"] ?: "2000",
                    sdk = infoMap["sdk"],
                    model = infoMap["model"],
                    pid = infoMap["pid"],
                    pingStatus = "OK",
                    statusMessage = "Rodando",
                    isTesting = false,
                    errorMessage = null
                )
            } else {
                val err = "Falha na conexão: o daemon não respondeu em 127.0.0.1:$DAEMON_PORT."
                log("[DAEMON] $err")
                _daemonInfo.value = _daemonInfo.value.copy(
                    state = ShellDaemonState.NOT_STARTED,
                    pingStatus = "FALHOU",
                    statusMessage = "Não iniciado ou finalizado",
                    isTesting = false,
                    errorMessage = err
                )
            }
        }
    }

    /**
     * Verificação silenciosa do estado de execução do daemon.
     */
    private suspend fun checkStatusSilent(): Boolean {
        val info = testPingAndInfoInternal()
        return if (info != null) {
            _daemonInfo.value = _daemonInfo.value.copy(
                state = ShellDaemonState.RUNNING,
                uid = info["uid"] ?: "2000",
                sdk = info["sdk"],
                model = info["model"],
                pid = info["pid"],
                pingStatus = "OK",
                statusMessage = "Rodando"
            )
            true
        } else {
            if (_daemonInfo.value.state == ShellDaemonState.RUNNING) {
                _daemonInfo.value = _daemonInfo.value.copy(
                    state = ShellDaemonState.NOT_STARTED,
                    pingStatus = "FALHOU",
                    statusMessage = "Daemon não iniciado"
                )
            }
            false
        }
    }

    /**
     * Conecta diretamente ao socket 127.0.0.1:DAEMON_PORT, envia PING e INFO e retorna os dados.
     */
    private suspend fun testPingAndInfoInternal(): Map<String, String>? = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", DAEMON_PORT), 800)
                socket.soTimeout = 1200

                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = PrintWriter(socket.getOutputStream(), true)

                // 1. PING -> PONG
                writer.println("PING")
                val pong = reader.readLine()
                if (pong == null || !pong.trim().equals("PONG", ignoreCase = true)) {
                    return@withContext null
                }

                // 2. INFO -> dados
                writer.println("INFO")
                val infoLine = reader.readLine() ?: ""

                // 3. QUIT
                writer.println("QUIT")

                val map = mutableMapOf<String, String>()
                infoLine.split(";").forEach { part ->
                    val pair = part.split("=", limit = 2)
                    if (pair.size == 2) {
                        map[pair[0].trim()] = pair[1].trim()
                    }
                }
                map
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Envia o comando SHUTDOWN para encerrar um daemon ativo.
     */
    private suspend fun sendShutdownCommand(): Unit = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", DAEMON_PORT), 800)
                socket.soTimeout = 1000
                val writer = PrintWriter(socket.getOutputStream(), true)
                writer.println("SHUTDOWN")
            }
        } catch (_: Exception) {}
    }

    private var mediaPlayer: MediaPlayer? = null

    /**
     * Executa o teste experimental de captura de áudio com AudioRecord(VOICE_CALL) no daemon shell.
     * Grava 5 segundos de áudio da chamada e salva em formato WAV.
     */
    fun testCallAudio(isCallActive: Boolean) {
        if (!isCallActive) {
            _daemonInfo.value = _daemonInfo.value.copy(
                audioTestMessage = "Nenhuma chamada ativa para teste."
            )
            return
        }

        scope.launch {
            _daemonInfo.value = _daemonInfo.value.copy(
                isAudioTesting = true,
                audioTestMessage = "Capturando áudio VOICE_CALL no shell daemon (5s)..."
            )
            log("[DAEMON] Solicitando AUDIO_TEST (5s) ao daemon em 127.0.0.1:$DAEMON_PORT...")

            val metrics = testAudioInternal(5)
            _daemonInfo.value = _daemonInfo.value.copy(
                isAudioTesting = false,
                audioMetrics = metrics,
                audioTestMessage = if (metrics == null) {
                    "Falha ao comunicar com o Shell Daemon."
                } else if (metrics.error != null && metrics.error != "NONE") {
                    "Aviso no AudioRecord: ${metrics.error}"
                } else {
                    null
                }
            )

            if (metrics != null) {
                log("[DAEMON] Teste concluído: state=${metrics.state}, bytes=${metrics.bytes}, frames=${metrics.frames}, RMS=${metrics.rms}, peak=${metrics.peak}")
                if (metrics.filePath != null && metrics.filePath != "NONE") {
                    log("[DAEMON] Prova de áudio salva em: ${metrics.filePath}")
                    try {
                        MediaScannerConnection.scanFile(
                            context,
                            arrayOf(
                                "/sdcard/Download/aicall_prova_chamada.wav",
                                "/sdcard/Recordings/aicall_prova_chamada.wav"
                            ),
                            arrayOf("audio/wav", "audio/wav")
                        ) { path, uri ->
                            Log.i(TAG, "Mídia escaneada pelo Android: $path -> $uri")
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "Aviso ao notificar MediaScanner: ${t.message}")
                    }
                }
            }
        }
    }

    private suspend fun testAudioInternal(durationSeconds: Int = 5): ShellAudioMetrics? = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", DAEMON_PORT), 1500)
                // Timeout com margem de segurança para a duração solicitada
                socket.soTimeout = (durationSeconds + 5) * 1000

                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = PrintWriter(socket.getOutputStream(), true)

                writer.println("AUDIO_TEST $durationSeconds")
                val response = reader.readLine() ?: return@withContext null
                if (!response.startsWith("AUDIO_TEST_RESULT:")) {
                    return@withContext null
                }

                val payload = response.removePrefix("AUDIO_TEST_RESULT:").trim()
                val map = mutableMapOf<String, String>()
                payload.split(";").forEach { part ->
                    val pair = part.split("=", limit = 2)
                    if (pair.size == 2) {
                        map[pair[0].trim()] = pair[1].trim()
                    }
                }

                ShellAudioMetrics(
                    status = map["status"] ?: "UNKNOWN",
                    state = map["state"] ?: "UNKNOWN",
                    sampleRate = map["sample_rate"]?.toIntOrNull() ?: 16000,
                    channels = map["channels"]?.toIntOrNull() ?: 1,
                    bytes = map["bytes"]?.toLongOrNull() ?: 0L,
                    frames = map["frames"]?.toLongOrNull() ?: 0L,
                    rms = map["rms"]?.toDoubleOrNull() ?: 0.0,
                    peak = map["peak"]?.toIntOrNull() ?: 0,
                    durationMs = map["duration_ms"]?.toLongOrNull() ?: 0L,
                    filePath = map["file_path"],
                    error = map["error"]
                )
            }
        } catch (e: Exception) {
            log("[DAEMON] Erro no teste de áudio: ${e.message}")
            null
        }
    }

    /**
     * Localiza o arquivo de áudio gravado em um dos caminhos conhecidos.
     */
    fun getRecordedAudioFile(): File? {
        val candidates = listOf(
            File(context.getExternalFilesDir(null), "aicall_prova_chamada.wav"),
            File("/sdcard/Download/aicall_prova_chamada.wav"),
            File("/sdcard/Recordings/aicall_prova_chamada.wav")
        )
        return candidates.firstOrNull { it.exists() && it.length() > 44 }
    }

    /**
     * Reproduz o arquivo de áudio gravado diretamente no app via MediaPlayer.
     */
    fun playRecordedAudio() {
        val file = getRecordedAudioFile()
        if (file == null) {
            log("[AUDIO] Arquivo WAV não encontrado para reprodução.")
            return
        }

        try {
            stopAudioPlayback()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    _daemonInfo.value = _daemonInfo.value.copy(isPlayingAudio = false)
                    it.release()
                    mediaPlayer = null
                }
                start()
            }
            _daemonInfo.value = _daemonInfo.value.copy(isPlayingAudio = true)
            log("[AUDIO] Reproduzindo gravação da chamada (${file.length()} bytes)...")
        } catch (e: Exception) {
            log("[AUDIO] Erro ao reproduzir gravação: ${e.message}")
            _daemonInfo.value = _daemonInfo.value.copy(isPlayingAudio = false)
        }
    }

    /**
     * Interrompe a reprodução de áudio.
     */
    fun stopAudioPlayback() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
        } catch (_: Exception) {}
        mediaPlayer = null
        _daemonInfo.value = _daemonInfo.value.copy(isPlayingAudio = false)
    }

    /**
     * Abre a planilha de compartilhamento do Android para enviar a prova de áudio (.wav).
     */
    fun shareRecordedAudio() {
        val file = getRecordedAudioFile()
        if (file == null) {
            log("[AUDIO] Nenhum arquivo WAV para compartilhar.")
            return
        }

        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(intent, "Compartilhar prova de áudio").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            log("[AUDIO] Erro ao compartilhar áudio: ${e.message}")
        }
    }

    /**
     * Abre o arquivo de áudio no player padrão do sistema.
     */
    fun openRecordedAudio() {
        val file = getRecordedAudioFile()
        if (file == null) {
            log("[AUDIO] Nenhum arquivo WAV para abrir.")
            return
        }

        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "audio/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            log("[AUDIO] Erro ao abrir reprodutor externo: ${e.message}")
        }
    }

    private var streamSocket: Socket? = null
    private var isStreaming = false
    private var streamJob: kotlinx.coroutines.Job? = null

    /**
     * Inicia o streaming contínuo de áudio PCM16 (16kHz mono) a partir do shell daemon via loopback TCP.
     * Alimenta diretamente o motor ASR local no processo do app.
     */
    /**
     * Inicia o streaming contínuo de áudio PCM16 (16kHz) a partir do shell daemon via loopback TCP.
     * Suporta separação de canais: speakerId 1 = Você (Uplink), 2 = Interlocutor (Downlink).
     */
    fun startAudioStream(onPcmChunk: (speakerId: Int, chunk: ByteArray) -> Unit) {
        if (isStreaming) return
        isStreaming = true
        streamJob = scope.launch(Dispatchers.IO) {
            try {
                log("[STREAM] Conectando ao daemon para streaming de áudio da chamada...")
                Socket().use { socket ->
                    streamSocket = socket
                    socket.connect(InetSocketAddress("127.0.0.1", DAEMON_PORT), 2500)
                    socket.soTimeout = 0 // Streaming contínuo sem timeout

                    val os = socket.getOutputStream()
                    val inputStream = socket.getInputStream()
                    val writer = PrintWriter(os, true)
                    writer.println("STREAM_CALL_AUDIO")

                    // Lê o cabeçalho de inicialização linha por linha diretamente dos bytes
                    val headerBuilder = StringBuilder()
                    while (true) {
                        val b = inputStream.read()
                        if (b == -1 || b == '\n'.code) break
                        if (b != '\r'.code) {
                            headerBuilder.append(b.toChar())
                        }
                    }
                    val header = headerBuilder.toString()
                    if (!header.contains("STREAM_STARTED")) {
                        log("[STREAM] Resposta inesperada do daemon: $header")
                        return@use
                    }
                    val isSeparated = header.contains("mode=separated")
                    log("[STREAM] Streaming ativo! Modo: ${if (isSeparated) "Dois Canais Separados (Você vs Interlocutor)" else "Misto"}")

                    if (isSeparated) {
                        while (isStreaming && !socket.isClosed) {
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
                                onPcmChunk(trackId, chunk)
                            }
                        }
                    } else {
                        val buffer = ByteArray(3200) // 100ms de áudio
                        while (isStreaming && !socket.isClosed) {
                            val bytesRead = inputStream.read(buffer)
                            if (bytesRead > 0) {
                                val chunk = buffer.copyOf(bytesRead)
                                onPcmChunk(1, chunk)
                            } else if (bytesRead < 0) {
                                break
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                log("[STREAM] Conexão de streaming encerrada: ${e.message}")
            } finally {
                isStreaming = false
                streamSocket = null
                log("[STREAM] Streaming finalizado.")
            }
        }
    }

    /**
     * Interrompe o streaming de áudio da chamada.
     */
    fun stopAudioStream() {
        isStreaming = false
        try {
            streamSocket?.close()
        } catch (_: Exception) {}
        streamJob?.cancel()
        streamJob = null
    }
}
