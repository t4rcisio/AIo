package com.example.ai_assistant.adb

import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import io.github.muntashirakon.adb.AdbPairingRequiredException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.concurrent.TimeUnit

/**
 * AdbTransport é responsável pela camada de transporte com o daemon ADB:
 * - Pareamento TLS (SPAKE2) via Wireless Debugging;
 * - Conexão e manutenção de sessão ADB em memória;
 * - Desconexão;
 * - Execução de comandos shell/exec.
 */
class AdbTransport(
    private val identity: AdbIdentity
) : AbsAdbConnectionManager() {

    companion object {
        private const val TAG = "AICallADB"
        private const val DEFAULT_TIMEOUT_SECONDS = 5L
    }

    init {
        setApi(android.os.Build.VERSION.SDK_INT)
        setTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    override fun getPrivateKey(): PrivateKey {
        return identity.getPrivateKey()
    }

    override fun getCertificate(): Certificate {
        return identity.getCertificate()
    }

    override fun getDeviceName(): String {
        return identity.getDeviceName()
    }

    /**
     * Executa o pareamento TLS (SPAKE2) com o serviço de pareamento da Depuração sem Fio.
     * Não registra o pairingCode nos logs por segurança.
     */
    fun pairDevice(host: String, port: Int, pairingCode: String): Result<Unit> {
        if (host.isBlank() || port !in 1..65535) {
            val err = "Host ou porta de pareamento inválidos: host='$host', port=$port"
            Log.e(TAG, "[AICALL ADB] $err")
            return Result.failure(IllegalArgumentException(err))
        }

        if (pairingCode.isBlank()) {
            val err = "Código de pareamento não pode ser vazio"
            Log.e(TAG, "[AICALL ADB] $err")
            return Result.failure(IllegalArgumentException(err))
        }

        Log.i(TAG, "[AICALL ADB] pairing started")

        return try {
            val success = pair(host, port, pairingCode)
            if (success) {
                Log.i(TAG, "[AICALL ADB] pairing success")
                Result.success(Unit)
            } else {
                val err = "Pareamento recusado ou código incorreto"
                Log.w(TAG, "[AICALL ADB] $err")
                Result.failure(SecurityException(err))
            }
        } catch (e: Exception) {
            val mappedError = mapException("pareamento", e)
            Log.e(TAG, "[AICALL ADB] Falha no pareamento: ${mappedError.message}")
            Result.failure(mappedError)
        }
    }

    /**
     * Conecta ao daemon ADB (adbd) na porta de depuração sem fio informada.
     * Mantém a sessão aberta na memória.
     */
    fun connectDevice(host: String, port: Int): Result<Unit> {
        if (host.isBlank() || port !in 1..65535) {
            val err = "Host ou porta de conexão inválidos: host='$host', port=$port"
            Log.e(TAG, "[AICALL ADB] $err")
            return Result.failure(IllegalArgumentException(err))
        }

        if (isConnected) {
            Log.i(TAG, "[AICALL ADB] Sessão anterior ativa detectada, desconectando antes de reconectar...")
            disconnectDevice()
        }

        Log.i(TAG, "[AICALL ADB] connecting")

        return try {
            val success = connect(host, port)
            if (success && isConnected) {
                Log.i(TAG, "[AICALL ADB] connected")
                Result.success(Unit)
            } else {
                val err = "Não foi possível estabelecer conexão com o daemon ADB"
                Log.w(TAG, "[AICALL ADB] $err")
                Result.failure(IOException(err))
            }
        } catch (e: Exception) {
            val mappedError = mapException("conexão", e)
            Log.e(TAG, "[AICALL ADB] Falha na conexão: ${mappedError.message}")
            Result.failure(mappedError)
        }
    }

    /**
     * Encerra a conexão ativa com o daemon ADB.
     */
    fun disconnectDevice() {
        try {
            if (isConnected) {
                disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "[AICALL ADB] Erro ao desconectar: ${e.message}")
        } finally {
            Log.i(TAG, "[AICALL ADB] disconnected")
        }
    }

    /**
     * Solicita ao daemon ADB que passe a ouvir na porta TCP informada (padrão: 5555).
     * O modo TCP/IP na porta 5555 permanece ativo no loopback 127.0.0.1 mesmo quando o Wi-Fi desconecta!
     */
    fun switchTcpMode(port: Int = 5555): Result<String> {
        if (!isConnected) {
            Log.w(TAG, "[AICALL ADB] Não conectado para enviar tcpip:$port")
            return Result.failure(IllegalStateException("ADB não conectado"))
        }

        Log.i(TAG, "[AICALL ADB] Solicitando switch para modo TCP na porta $port...")

        return try {
            val stream = openStream("tcpip:$port")
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(512)
            val inputStream = stream.openInputStream()

            try {
                val deadline = System.currentTimeMillis() + 1500L
                while (System.currentTimeMillis() < deadline) {
                    val available = try { stream.available() } catch (_: Exception) { 0 }
                    if (available > 0) {
                        val toRead = minOf(buffer.size, available)
                        val read = inputStream.read(buffer, 0, toRead)
                        if (read > 0) output.write(buffer, 0, read)
                    } else if (stream.isClosed || output.size() > 0) {
                        break
                    } else {
                        Thread.sleep(30L)
                    }
                }
            } finally {
                try {
                    stream.close()
                } catch (_: Exception) {}
            }

            val resp = output.toString(Charsets.UTF_8.name()).trim()
            Log.i(TAG, "[AICALL ADB] tcpip:$port resposta recebida: '$resp'")
            Result.success(resp)
        } catch (e: Exception) {
            val mappedError = mapException("alternância tcpip", e)
            Log.w(TAG, "[AICALL ADB] Falha ao enviar tcpip:$port: ${mappedError.message}")
            Result.failure(mappedError)
        }
    }

    /**
     * Executa um comando no daemon ADB através de stream exec: e retorna a saída textual.
     */
    fun executeCommand(command: String): Result<String> {
        if (!isConnected) {
            Log.w(TAG, "[AICALL ADB] disconnected")
            return Result.failure(IllegalStateException("ADB não conectado"))
        }

        Log.i(TAG, "[AICALL ADB] command=$command")

        return try {
            val stream = openStream("exec:$command")
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            val inputStream = stream.openInputStream()

            try {
                val deadline = System.currentTimeMillis() + 3000L
                while (System.currentTimeMillis() < deadline) {
                    val available = try { stream.available() } catch (_: Exception) { 0 }
                    if (available > 0) {
                        val toRead = minOf(buffer.size, available)
                        val read = try {
                            inputStream.read(buffer, 0, toRead)
                        } catch (e: IOException) {
                            if (e.message?.contains("Stream closed", ignoreCase = true) == true) -1 else throw e
                        }
                        if (read > 0) {
                            output.write(buffer, 0, read)
                        }
                    } else if (stream.isClosed) {
                        break
                    } else if (output.size() > 0) {
                        // Saída já capturada; aguarda brevemente por possíveis bytes residuais
                        Thread.sleep(50L)
                        if (stream.available() == 0) break
                    } else {
                        Thread.sleep(25L)
                    }
                }
            } finally {
                try {
                    stream.close()
                } catch (_: Exception) {}
            }

            val result = output.toString(Charsets.UTF_8.name()).trim()
            Log.i(TAG, "[AICALL ADB] result=$result")
            Result.success(result)
        } catch (e: Exception) {
            val mappedError = mapException("execução do comando", e)
            Log.e(TAG, "[AICALL ADB] Falha no comando '$command': ${mappedError.message}", e)
            Result.failure(mappedError)
        }
    }

    /**
     * Envia bytes diretamente para um arquivo no aparelho via stream 'exec:cat > remotePath'.
     */
    fun pushFile(bytes: ByteArray, remotePath: String): Result<Unit> {
        if (!isConnected) {
            return Result.failure(IllegalStateException("ADB não conectado"))
        }

        return try {
            val stream = openStream("exec:cat > $remotePath")
            try {
                stream.openOutputStream().use { os ->
                    os.write(bytes)
                    os.flush()
                }
            } finally {
                try {
                    stream.close()
                } catch (_: Exception) {}
            }
            Result.success(Unit)
        } catch (e: Exception) {
            val mappedError = mapException("envio de arquivo", e)
            Log.e(TAG, "[AICALL ADB] Falha ao enviar arquivo para $remotePath: ${mappedError.message}")
            Result.failure(mappedError)
        }
    }

    private fun mapException(stage: String, e: Throwable): Exception {
        return when (e) {
            is AdbPairingRequiredException -> {
                AdbPairingRequiredException("Pareamento necessário antes de conectar: ${e.message}")
            }
            is AdbAuthenticationFailedException -> {
                SecurityException("Autenticação/pareamento recusado ou código incorreto: ${e.message}", e)
            }
            is SocketTimeoutException -> {
                SocketTimeoutException("Tempo limite esgotado ao tentar $stage (timeout). Verifique se o IP e porta estão corretos.")
            }
            is ConnectException -> {
                ConnectException("Conexão recusada ao tentar $stage. Verifique se a Depuração sem fio está ativa nesta porta.")
            }
            is UnknownHostException -> {
                UnknownHostException("Host não encontrado: ${e.message}")
            }
            is IOException -> {
                IOException("Erro de E/S ou conexão perdida durante $stage: ${e.message}", e)
            }
            else -> {
                Exception("Falha inesperada durante $stage: ${e.message}", e)
            }
        }
    }
}
