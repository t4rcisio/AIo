package com.example.ai_assistant.history

import android.content.Context
import android.util.Log
import com.example.ai_assistant.llm.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class CallHistoryItem(
    val id: String,
    val phoneNumber: String,
    val callerName: String,
    val timestamp: Long,
    val durationSeconds: Long,
    val messages: List<ChatMessage>,
    val audioFilePath: String? = null
)

class CallHistoryManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "CallHistoryManager"
        private const val FILE_NAME = "call_history.json"

        @Volatile
        private var instance: CallHistoryManager? = null

        fun getInstance(context: Context): CallHistoryManager {
            return instance ?: synchronized(this) {
                instance ?: CallHistoryManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val historyFile = File(context.filesDir, FILE_NAME)

    private val _calls = MutableStateFlow<List<CallHistoryItem>>(emptyList())
    val calls: StateFlow<List<CallHistoryItem>> = _calls.asStateFlow()

    init {
        loadHistory()
    }

    @Synchronized
    private fun loadHistory() {
        try {
            if (!historyFile.exists()) {
                _calls.value = emptyList()
                return
            }
            val jsonStr = historyFile.readText()
            if (jsonStr.isBlank()) {
                _calls.value = emptyList()
                return
            }
            val jsonArray = JSONArray(jsonStr)
            val list = mutableListOf<CallHistoryItem>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.optString("id", UUID.randomUUID().toString())
                val phoneNumber = obj.optString("phoneNumber", "")
                val callerName = obj.optString("callerName", "Desconhecido")
                val timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                val durationSeconds = obj.optLong("durationSeconds", 0L)
                val audioFilePath = obj.optString("audioFilePath").takeIf { it.isNotBlank() }

                val messagesList = mutableListOf<ChatMessage>()
                val msgsArray = obj.optJSONArray("messages")
                if (msgsArray != null) {
                    for (j in 0 until msgsArray.length()) {
                        val mObj = msgsArray.getJSONObject(j)
                        messagesList.add(
                            ChatMessage(
                                role = mObj.optString("role", "user"),
                                content = mObj.optString("content", "")
                            )
                        )
                    }
                }

                list.add(
                    CallHistoryItem(
                        id = id,
                        phoneNumber = phoneNumber,
                        callerName = callerName,
                        timestamp = timestamp,
                        durationSeconds = durationSeconds,
                        messages = messagesList,
                        audioFilePath = audioFilePath
                    )
                )
            }
            // Ordena mais recentes no topo
            _calls.value = list.sortedByDescending { it.timestamp }
            Log.i(TAG, "Histórico carregado com ${_calls.value.size} ligações.")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao carregar histórico: ${e.message}", e)
            _calls.value = emptyList()
        }
    }

    private fun persistToFile() {
        scope.launch {
            try {
                val jsonArray = JSONArray()
                _calls.value.forEach { item ->
                    val obj = JSONObject().apply {
                        put("id", item.id)
                        put("phoneNumber", item.phoneNumber)
                        put("callerName", item.callerName)
                        put("timestamp", item.timestamp)
                        put("durationSeconds", item.durationSeconds)
                        put("audioFilePath", item.audioFilePath ?: "")

                        val msgsArray = JSONArray()
                        item.messages.forEach { msg ->
                            msgsArray.put(JSONObject().apply {
                                put("role", msg.role)
                                put("content", msg.content)
                            })
                        }
                        put("messages", msgsArray)
                    }
                    jsonArray.put(obj)
                }
                historyFile.writeText(jsonArray.toString(2))
                Log.d(TAG, "Histórico gravado no arquivo com ${_calls.value.size} ligações.")
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao salvar histórico: ${e.message}", e)
            }
        }
    }

    fun addCallRecord(
        phoneNumber: String,
        callerName: String,
        durationSeconds: Long,
        messages: List<ChatMessage>,
        audioFilePath: String? = null
    ) {
        val record = CallHistoryItem(
            id = UUID.randomUUID().toString(),
            phoneNumber = phoneNumber,
            callerName = callerName,
            timestamp = System.currentTimeMillis(),
            durationSeconds = durationSeconds,
            messages = messages,
            audioFilePath = audioFilePath
        )
        _calls.value = listOf(record) + _calls.value
        persistToFile()
        Log.i(TAG, "Nova ligação registrada no histórico: $phoneNumber ($durationSeconds seg)")
    }

    fun deleteCallRecord(id: String) {
        _calls.value = _calls.value.filter { it.id != id }
        persistToFile()
    }

    fun clearAll() {
        _calls.value = emptyList()
        persistToFile()
    }
}
