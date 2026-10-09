package com.example.ai_assistant.logging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

enum class LogLevel(val label: String) {
    INFO("INFO"),
    WARN("AVISO"),
    ERROR("ERRO"),
    SUCCESS("SUCESSO")
}

data class LogEntry(
    val id: Long,
    val timestamp: Long,
    val level: LogLevel,
    val tag: String,
    val message: String
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}

/**
 * Registrador de logs e diagnóstico em tempo real exibido diretamente na interface do app.
 */
object InAppLogger {
    private const val MAX_LOGS = 300
    private val counter = AtomicLong(0)
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    fun log(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(
            id = counter.incrementAndGet(),
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message
        )
        val current = _logs.value
        val updated = if (current.size >= MAX_LOGS) {
            current.drop(current.size - MAX_LOGS + 1) + entry
        } else {
            current + entry
        }
        _logs.value = updated
    }

    fun info(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun warn(tag: String, message: String) = log(LogLevel.WARN, tag, message)
    fun error(tag: String, message: String) = log(LogLevel.ERROR, tag, message)
    fun success(tag: String, message: String) = log(LogLevel.SUCCESS, tag, message)

    fun clear() {
        _logs.value = emptyList()
    }

    fun getAllAsText(): String {
        return _logs.value.joinToString("\n") {
            "[${it.formattedTime}] [${it.level.label}] [${it.tag}] ${it.message}"
        }
    }
}
