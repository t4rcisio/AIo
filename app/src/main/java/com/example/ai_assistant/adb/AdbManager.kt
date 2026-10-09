package com.example.ai_assistant.adb

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Estados do ciclo de vida do cliente ADB local.
 */
enum class AdbState {
    DISCONNECTED,
    PAIRING,
    CONNECTED,
    ERROR
}

/**
 * Informações e diagnóstico da sessão ADB para exibição na UI.
 */
data class AdbInfo(
    val state: AdbState = AdbState.DISCONNECTED,
    val statusMessage: String = "Detectando ADB automaticamente...",
    val uid: String? = null,
    val sdkVersion: String? = null,
    val model: String? = null,
    val lastCommandOutput: String? = null,
    val errorMessage: String? = null,
    val isTesting: Boolean = false,
    val isAutoSearching: Boolean = false,
    val discoveredConnectPort: Int? = null,
    val discoveredPairingPort: Int? = null
)

/**
 * AdbManager é o coordenador central automatizado do ADB local no aplicativo.
 *
 * Funcionalidades automáticas:
 * 1. Descoberta contínua via mDNS (AdbMdns / NsdManager) do serviço de conexão (adb-tls-connect);
 * 2. Reconexão e auto-conexão imediata no boot/onResume caso o aparelho já tenha sido pareado;
 * 3. Descoberta automática da porta de pareamento (adb-tls-pairing) sem o usuário precisar digitar portas ou IPs;
 * 4. Persistência da última porta funcional para reconexão ultrarrápida.
 */
class AdbManager(private val context: Context) {

    companion object {
        private const val TAG = "AICallADB"
        private const val PREFS_NAME = "ai_call_adb_prefs"
        private const val KEY_LAST_CONNECT_PORT = "last_connect_port"
        private const val KEY_IS_PAIRED = "is_paired"

        @Volatile
        private var instance: AdbManager? = null

        fun getInstance(context: Context): AdbManager {
            return instance ?: synchronized(this) {
                instance ?: AdbManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val identity = AdbIdentity(context)
    private val transport = AdbTransport(identity)
    private val scope = CoroutineScope(Dispatchers.IO)
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _adbInfo = MutableStateFlow(AdbInfo())
    val adbInfo: StateFlow<AdbInfo> = _adbInfo.asStateFlow()

    private var mdnsConnect: AdbMdns? = null
    private var mdnsPairing: AdbMdns? = null
    private var autoDiscoveryJob: Job? = null
    private var activeHost = "127.0.0.1"

    init {
        setupMdnsListeners()
        startAutoDiscoveryAndConnect()
    }

    private fun setupMdnsListeners() {
        try {
            // Listener para detecção automática da porta de CONEXÃO da Depuração sem fio
            mdnsConnect = AdbMdns(context, AdbMdns.SERVICE_TYPE_TLS_CONNECT) { address, port ->
                if (port in 1024..65535) {
                    val ip = address?.hostAddress?.takeIf { it.isNotBlank() } ?: "127.0.0.1"
                    Log.i(TAG, "[AICALL ADB] mDNS detectou porta de conexão ADB: $ip:$port")
                    activeHost = ip
                    _adbInfo.value = _adbInfo.value.copy(discoveredConnectPort = port)

                    // Se não estiver conectado, tenta conectar imediatamente e de forma transparente
                    if (!transport.isConnected && _adbInfo.value.state != AdbState.CONNECTED) {
                        scope.launch {
                            tryConnect(ip, port)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[AICALL ADB] Não foi possível iniciar mDNS Connect: ${e.message}")
        }

        try {
            // Listener para detecção automática da porta de PAREAMENTO da Depuração sem fio
            mdnsPairing = AdbMdns(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { address, port ->
                if (port in 1024..65535) {
                    val ip = address?.hostAddress?.takeIf { it.isNotBlank() } ?: "127.0.0.1"
                    Log.i(TAG, "[AICALL ADB] mDNS detectou porta de pareamento ADB: $ip:$port")
                    activeHost = ip
                    _adbInfo.value = _adbInfo.value.copy(
                        state = AdbState.PAIRING,
                        discoveredPairingPort = port,
                        statusMessage = "Porta de pareamento detectada ($port). Digite o código de 6 dígitos."
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[AICALL ADB] Não foi possível iniciar mDNS Pairing: ${e.message}")
        }
    }

    /**
     * Inicia a descoberta e auto-conexão automática (chamado no início e no onResume).
     * Otimizado com timeouts curtos e portas prioritárias para nunca travar a UI (evita ANR).
     */
    fun startAutoDiscoveryAndConnect() {
        if (transport.isConnected) {
            return
        }

        autoDiscoveryJob?.cancel()
        autoDiscoveryJob = scope.launch {
            com.example.ai_assistant.logging.InAppLogger.info("ADB", "Verificando conexão ADB local...")
            _adbInfo.value = _adbInfo.value.copy(
                isAutoSearching = true,
                statusMessage = "Buscando ADB local automaticamente...",
                errorMessage = null
            )

            // Inicia listeners mDNS
            try {
                mdnsConnect?.start()
                mdnsPairing?.start()
            } catch (e: Exception) {
                Log.w(TAG, "[AICALL ADB] Erro ao iniciar mDNS discovery: ${e.message}")
            }

            // 1. Prioridade máxima: Tenta a porta padrão 5555 (modo TCP/IP persistente no loopback)
            if (isPortOpen("127.0.0.1", 5555, timeoutMs = 60)) {
                Log.i(TAG, "[AICALL ADB] Porta 5555 ativa em loopback! Conectando modo TCP/IP persistente...")
                if (tryConnect("127.0.0.1", 5555)) {
                    return@launch
                }
            }

            if (transport.isConnected || _adbInfo.value.state == AdbState.CONNECTED) {
                return@launch
            }

            // 2. Tenta a última porta salva no cache (se diferente de 5555)
            val savedPort = prefs.getInt(KEY_LAST_CONNECT_PORT, -1)
            if (savedPort in 1024..65535 && savedPort != 5555) {
                Log.i(TAG, "[AICALL ADB] Testando última porta conhecida: $savedPort")
                if (isPortOpen("127.0.0.1", savedPort, timeoutMs = 60)) {
                    if (tryConnect("127.0.0.1", savedPort)) {
                        return@launch
                    }
                }
            }

            if (transport.isConnected || _adbInfo.value.state == AdbState.CONNECTED) {
                return@launch
            }

            // 3. Se houver porta descoberta por mDNS previamente, tenta ela
            val mdnsPort = _adbInfo.value.discoveredConnectPort
            if (mdnsPort != null && mdnsPort in 1024..65535 && mdnsPort != 5555 && mdnsPort != savedPort) {
                if (isPortOpen(activeHost, mdnsPort, timeoutMs = 60)) {
                    if (tryConnect(activeHost, mdnsPort)) {
                        return@launch
                    }
                }
            }

            // Se ainda não conectou após a busca rápida das portas prioritárias
            val failMsg = if (prefs.getBoolean(KEY_IS_PAIRED, false)) {
                "ADB Offline (Ative Depuração sem Fio ou reconecte ao Wi-Fi)"
            } else {
                "Não configurado (Ative a Depuração sem Fio)"
            }
            com.example.ai_assistant.logging.InAppLogger.warn("ADB", failMsg)
            _adbInfo.value = _adbInfo.value.copy(
                isAutoSearching = false,
                statusMessage = failMsg
            )
        }
    }

    /**
     * Tenta conexão direta a uma porta detectada.
     */
    private suspend fun tryConnect(host: String, port: Int): Boolean {
        if (port !in 1024..65535) return false
        if (transport.isConnected && _adbInfo.value.state == AdbState.CONNECTED) {
            return true
        }

        var result = transport.connectDevice(host, port)
        // Fallback para loopback caso o IP do Wi-Fi falhe
        if (result.isFailure && host != "127.0.0.1") {
            result = transport.connectDevice("127.0.0.1", port)
        }

        return if (result.isSuccess) {
            autoDiscoveryJob?.cancel()

            // Salva porta e marca pareado
            prefs.edit()
                .putInt(KEY_LAST_CONNECT_PORT, port)
                .putBoolean(KEY_IS_PAIRED, true)
                .apply()

            com.example.ai_assistant.logging.InAppLogger.success("ADB", "Conectado ao ADB local na porta $port!")
            _adbInfo.value = _adbInfo.value.copy(
                state = AdbState.CONNECTED,
                statusMessage = "Conectado (porta $port)",
                isAutoSearching = false,
                errorMessage = null
            )

            // Se conectou através de uma porta dinâmica de depuração sem fio (ex: 35000-45000),
            // solicita ao daemon para ligar o modo TCP na porta 5555.
            if (port != 5555) {
                scope.launch {
                    try {
                        delay(200L)
                        transport.switchTcpMode(5555)
                    } catch (e: Exception) {
                        Log.w(TAG, "[AICALL ADB] Não foi possível ativar tcpip 5555: ${e.message}")
                    }
                }
            }

            // Executa diagnóstico automaticamente
            testConnectionInternal()
            true
        } else {
            val ex = result.exceptionOrNull()
            if (ex is AdbPairingRequiredException) {
                Log.i(TAG, "[AICALL ADB] Pareamento necessário detectado pelo daemon.")
                _adbInfo.value = _adbInfo.value.copy(
                    state = AdbState.PAIRING,
                    isAutoSearching = false,
                    statusMessage = "Aparelho requer pareamento (única vez).",
                    errorMessage = null
                )
            }
            false
        }
    }

    /**
     * Executa o pareamento usando o código de 6 dígitos.
     * Caso customPort seja fornecido, utiliza-o; caso contrário, utiliza a porta descoberta via mDNS/varredura.
     */
    fun pairWithCode(pairingCode: String, customPort: Int? = null) {
        val code = pairingCode.trim()
        if (code.length < 6) {
            _adbInfo.value = _adbInfo.value.copy(
                errorMessage = "O código de pareamento deve conter 6 dígitos."
            )
            return
        }

        scope.launch {
            _adbInfo.value = _adbInfo.value.copy(
                state = AdbState.PAIRING,
                statusMessage = "Pareando aparelho com o código...",
                errorMessage = null
            )

            // 1. Identifica a porta de pareamento (manual, via mDNS ou fallback)
            val pairingPort = customPort?.takeIf { it in 1024..65535 }
                ?: _adbInfo.value.discoveredPairingPort?.takeIf { it in 1024..65535 }
                ?: probePairingPort()

            if (pairingPort == null || pairingPort !in 1024..65535) {
                _adbInfo.value = _adbInfo.value.copy(
                    state = AdbState.PAIRING,
                    statusMessage = "Janela de pareamento não encontrada",
                    errorMessage = "A janela 'Parear com o dispositivo' do Android não foi detectada. Certifique-se de que a janela está aberta na tela (dica: use Tela Dividida ou Janela Suspensa)."
                )
                return@launch
            }

            Log.i(TAG, "[AICALL ADB] Executando pareamento na porta $pairingPort")
            var pairResult = transport.pairDevice(activeHost, pairingPort, code)
            if (pairResult.isFailure && activeHost != "127.0.0.1") {
                pairResult = transport.pairDevice("127.0.0.1", pairingPort, code)
            }

            if (pairResult.isFailure) {
                val rawErr = pairResult.exceptionOrNull()?.message ?: "Falha no pareamento"
                val friendlyErr = if (rawErr.contains("recusada", ignoreCase = true) || rawErr.contains("refused", ignoreCase = true)) {
                    "Conexão recusada na porta $pairingPort. A janela de pareamento foi fechada pelo Android antes de concluir. Mantenha a janela aberta (ex: usando Tela Dividida) e tente novamente."
                } else rawErr

                _adbInfo.value = _adbInfo.value.copy(
                    state = AdbState.PAIRING,
                    statusMessage = "Erro no pareamento",
                    errorMessage = friendlyErr
                )
                return@launch
            }

            // 2. Pareamento concluído com sucesso! Conecta imediatamente na porta de depuração
            delay(300L) // Breve intervalo para o daemon registrar a chave
            val connectPort = _adbInfo.value.discoveredConnectPort?.takeIf { it in 1024..65535 }
                ?: prefs.getInt(KEY_LAST_CONNECT_PORT, -1).takeIf { it in 1024..65535 }

            if (connectPort != null && connectPort != pairingPort) {
                var connectResult = transport.connectDevice(activeHost, connectPort)
                if (connectResult.isFailure && activeHost != "127.0.0.1") {
                    connectResult = transport.connectDevice("127.0.0.1", connectPort)
                }

                if (connectResult.isSuccess) {
                    prefs.edit()
                        .putInt(KEY_LAST_CONNECT_PORT, connectPort)
                        .putBoolean(KEY_IS_PAIRED, true)
                        .apply()

                    _adbInfo.value = _adbInfo.value.copy(
                        state = AdbState.CONNECTED,
                        statusMessage = "Conectado",
                        errorMessage = null
                    )
                    testConnectionInternal()
                } else {
                    _adbInfo.value = _adbInfo.value.copy(
                        state = AdbState.CONNECTED,
                        statusMessage = "Pareado com sucesso!",
                        errorMessage = null
                    )
                    startAutoDiscoveryAndConnect()
                }
            } else {
                _adbInfo.value = _adbInfo.value.copy(
                    state = AdbState.CONNECTED,
                    statusMessage = "Pareado com sucesso!",
                    errorMessage = null
                )
                startAutoDiscoveryAndConnect()
            }
        }
    }

    /**
     * Abre a tela de Opções do Desenvolvedor do Android diretamente.
     */
    fun openDeveloperSettings() {
        // Tenta abrir diretamente a tela de Depuração por Wi-Fi (Android 11+)
        try {
            val wirelessIntent = Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(wirelessIntent)
            return
        } catch (_: Exception) {}

        // Fallback: Opções do Desenvolvedor geral
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "[AICALL ADB] Não foi possível abrir Developer Settings: ${e.message}")
        }
    }

    /**
     * Desconecta manualmente a sessão ADB.
     */
    fun disconnect() {
        scope.launch {
            transport.disconnectDevice()
            _adbInfo.value = _adbInfo.value.copy(
                state = AdbState.DISCONNECTED,
                statusMessage = "Desconectado",
                uid = null,
                sdkVersion = null,
                model = null,
                lastCommandOutput = null,
                errorMessage = null
            )
        }
    }

    val isConnected: Boolean
        get() = transport.isConnected

    suspend fun executeCommand(command: String): Result<String> {
        return transport.executeCommand(command)
    }

    suspend fun pushFile(bytes: ByteArray, remotePath: String): Result<Unit> {
        return transport.pushFile(bytes, remotePath)
    }

    /**
     * Executa os comandos inofensivos de diagnóstico da sessão.
     */
    fun testConnection() {
        scope.launch {
            testConnectionInternal()
        }
    }

    private suspend fun testConnectionInternal() {
        if (!transport.isConnected) {
            _adbInfo.value = _adbInfo.value.copy(
                state = AdbState.DISCONNECTED,
                statusMessage = "Não configurado",
                errorMessage = "ADB não conectado"
            )
            return
        }

        _adbInfo.value = _adbInfo.value.copy(isTesting = true, errorMessage = null)

        var uidValue: String? = null
        var sdkValue: String? = null
        var modelValue: String? = null
        var lastOutput: String? = null
        var errorEncountered: String? = null

        try {
            // 1. Comando: id
            val idResult = transport.executeCommand("id")
            idResult.onSuccess { output ->
                lastOutput = output
                uidValue = if (output.contains("uid=2000")) {
                    "2000 (shell)"
                } else {
                    output.substringBefore(" ").ifEmpty { output }
                }
            }.onFailure { e ->
                errorEncountered = "Falha ao executar 'id': ${e.message}"
            }

            // 2. Comando: getprop ro.build.version.sdk
            if (errorEncountered == null) {
                delay(80L)
                val sdkResult = transport.executeCommand("getprop ro.build.version.sdk")
                sdkResult.onSuccess { output ->
                    sdkValue = output
                }.onFailure { e ->
                    errorEncountered = "Falha ao executar 'getprop sdk': ${e.message}"
                }
            }

            // 3. Comando: getprop ro.product.model
            if (errorEncountered == null) {
                delay(80L)
                val modelResult = transport.executeCommand("getprop ro.product.model")
                modelResult.onSuccess { output ->
                    modelValue = output
                }.onFailure { e ->
                    errorEncountered = "Falha ao executar 'getprop model': ${e.message}"
                }
            }
        } finally {
            _adbInfo.value = _adbInfo.value.copy(
                isTesting = false,
                uid = uidValue ?: _adbInfo.value.uid,
                sdkVersion = sdkValue ?: _adbInfo.value.sdkVersion,
                model = modelValue ?: _adbInfo.value.model,
                lastCommandOutput = lastOutput ?: _adbInfo.value.lastCommandOutput,
                errorMessage = errorEncountered
            )
        }
    }

    /**
     * Verificação ultrarrápida de portas prioritárias locais (5555, porta do cache ou mDNS).
     * Evita varreduras extensivas de milhares de portas que esgotam descritores de arquivo e travam a UI (ANR).
     */
    private suspend fun probeLocalAdbPorts(): Int? = withContext(Dispatchers.IO) {
        val priorityPorts = listOfNotNull(
            5555,
            prefs.getInt(KEY_LAST_CONNECT_PORT, -1).takeIf { it in 1024..65535 && it != 5555 },
            _adbInfo.value.discoveredConnectPort?.takeIf { it in 1024..65535 && it != 5555 }
        )
        for (port in priorityPorts) {
            if (isPortOpen("127.0.0.1", port, timeoutMs = 60)) {
                return@withContext port
            }
        }
        null
    }

    private suspend fun probePairingPort(): Int? = withContext(Dispatchers.IO) {
        val discovered = _adbInfo.value.discoveredPairingPort
        if (discovered != null && discovered in 1024..65535) {
            if (isPortOpen("127.0.0.1", discovered, timeoutMs = 60)) {
                return@withContext discovered
            }
        }
        null
    }

    private fun isPortOpen(host: String, port: Int, timeoutMs: Int = 60): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}
