package com.example.ai_assistant.callconversation

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Gravador de áudio para chamadas telefônicas.
 * Grava toda a ligação (voz do interlocutor + voz do assistente) em um único arquivo WAV (16kHz, 16-bit, Mono).
 */
class CallAudioRecorder(private val context: Context) {

    companion object {
        private const val TAG = "CallAudioRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
        private const val RECORDINGS_DIR = "call_recordings"
    }

    private var currentFile: File? = null
    private var randomAccessFile: RandomAccessFile? = null
    private var totalPcmBytesWritten: Long = 0L

    @Volatile
    var isRecording: Boolean = false
        private set

    /**
     * Inicia a gravação de uma nova chamada telefônica.
     * Retorna o arquivo onde o áudio está sendo gravado.
     */
    @Synchronized
    fun startRecording(phoneNumber: String): File? {
        try {
            stopRecording() // Garante encerramento de gravação anterior

            val dir = File(context.filesDir, RECORDINGS_DIR).apply {
                if (!exists()) mkdirs()
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val safePhone = phoneNumber.replace(Regex("[^0-9a-zA-Z]"), "_").take(15).ifEmpty { "desconhecido" }
            val file = File(dir, "chamada_${timestamp}_${safePhone}.wav")

            val raf = RandomAccessFile(file, "rw")
            raf.setLength(0) // Limpa conteúdo anterior se houver
            writeWavHeader(raf, 0L) // Cabeçalho inicial com tamanho provisório 0

            randomAccessFile = raf
            currentFile = file
            totalPcmBytesWritten = 0L
            isRecording = true

            Log.i(TAG, "[GRAVAÇÃO] Iniciada gravação da chamada em: ${file.absolutePath}")
            return file
        } catch (e: Exception) {
            Log.e(TAG, "[GRAVAÇÃO] Erro ao iniciar gravação da chamada: ${e.message}", e)
            return null
        }
    }

    /**
     * Grava um chunk de áudio PCM 16-bit 16kHz do interlocutor remoto.
     */
    @Synchronized
    fun writeRemotePcm(pcmBytes: ByteArray, length: Int = pcmBytes.size) {
        if (!isRecording || randomAccessFile == null) return
        try {
            randomAccessFile?.write(pcmBytes, 0, length)
            totalPcmBytesWritten += length
        } catch (e: Exception) {
            Log.e(TAG, "[GRAVAÇÃO] Erro ao gravar chunk remoto: ${e.message}")
        }
    }

    /**
     * Grava amostras float do sintetizador TTS do assistente (converte para PCM 16-bit e resample se necessário).
     */
    @Synchronized
    fun writeAssistantSamples(samples: FloatArray, sampleRate: Int) {
        if (!isRecording || randomAccessFile == null || samples.isEmpty()) return
        try {
            val pcmBytes = if (sampleRate == SAMPLE_RATE) {
                floatToPcm16(samples)
            } else {
                // Resample simples linear para 16kHz se o TTS for 24kHz
                val resampled = resampleFloatArray(samples, sampleRate, SAMPLE_RATE)
                floatToPcm16(resampled)
            }

            randomAccessFile?.write(pcmBytes)
            totalPcmBytesWritten += pcmBytes.size
        } catch (e: Exception) {
            Log.e(TAG, "[GRAVAÇÃO] Erro ao gravar amostras do assistente: ${e.message}")
        }
    }

    /**
     * Finaliza a gravação, atualiza o cabeçalho WAV com a contagem real de bytes e fecha o arquivo.
     * Retorna o caminho absoluto do arquivo gravado, ou null se não houver áudio.
     */
    @Synchronized
    fun stopRecording(): String? {
        if (!isRecording && randomAccessFile == null) return null
        isRecording = false

        val file = currentFile
        val raf = randomAccessFile

        try {
            if (raf != null && totalPcmBytesWritten > 0L) {
                raf.seek(0)
                writeWavHeader(raf, totalPcmBytesWritten)
                raf.close()
                Log.i(TAG, "[GRAVAÇÃO CONCLUÍDA] Arquivo: ${file?.absolutePath} (${totalPcmBytesWritten} bytes PCM)")
                randomAccessFile = null
                currentFile = null
                return file?.absolutePath
            } else {
                raf?.close()
                file?.delete() // Remove arquivo vazio
                Log.w(TAG, "[GRAVAÇÃO] Arquivo de chamada vazio descartado.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[GRAVAÇÃO] Erro ao finalizar cabeçalho WAV: ${e.message}", e)
        } finally {
            try { raf?.close() } catch (_: Exception) {}
            randomAccessFile = null
            currentFile = null
        }
        return null
    }

    private fun writeWavHeader(raf: RandomAccessFile, pcmDataLength: Long) {
        val totalDataLen = pcmDataLength + 36
        val byteRate = SAMPLE_RATE * CHANNELS * (BITS_PER_SAMPLE / 8)
        val blockAlign = CHANNELS * (BITS_PER_SAMPLE / 8)

        val header = ByteBuffer.allocate(44).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            put("RIFF".toByteArray())
            putInt(totalDataLen.toInt())
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16) // Subchunk1Size para PCM
            putShort(1.toShort()) // AudioFormat = 1 (PCM)
            putShort(CHANNELS.toShort())
            putInt(SAMPLE_RATE)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(BITS_PER_SAMPLE.toShort())
            put("data".toByteArray())
            putInt(pcmDataLength.toInt())
        }.array()

        raf.write(header)
    }

    private fun floatToPcm16(samples: FloatArray): ByteArray {
        val buffer = ByteArray(samples.size * 2)
        var outIdx = 0
        for (sample in samples) {
            val clamped = sample.coerceIn(-1.0f, 1.0f)
            val pcm = (clamped * 32767.0f).toInt().toShort()
            buffer[outIdx++] = (pcm.toInt() and 0xFF).toByte()
            buffer[outIdx++] = ((pcm.toInt() shr 8) and 0xFF).toByte()
        }
        return buffer
    }

    private fun resampleFloatArray(input: FloatArray, inRate: Int, outRate: Int): FloatArray {
        if (inRate == outRate) return input
        val ratio = inRate.toDouble() / outRate.toDouble()
        val outLen = (input.size / ratio).toInt()
        val output = FloatArray(outLen)

        for (i in 0 until outLen) {
            val srcIdx = i * ratio
            val index0 = srcIdx.toInt().coerceIn(0, input.size - 1)
            val index1 = (index0 + 1).coerceIn(0, input.size - 1)
            val frac = (srcIdx - index0).toFloat()
            output[i] = input[index0] * (1f - frac) + input[index1] * frac
        }
        return output
    }
}
