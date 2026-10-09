package com.example.ai_assistant.api

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AutoAnswerRule(val id: String, val title: String, val description: String) {
    UNSAVED_ONLY(
        "unsaved_only",
        "Apenas contatos não salvos",
        "O assistente atende apenas números desconhecidos que não estão na sua agenda telefônica."
    ),
    ALL_EXCEPT_SELECTED(
        "all_except_selected",
        "Todos, exceto selecionados",
        "O assistente atende todos os números, menos as pessoas selecionadas na sua lista de exceções."
    ),
    ONLY_SELECTED(
        "only_selected",
        "Apenas contatos selecionados",
        "O assistente atende exclusivamente as ligações dos contatos selecionados na sua lista."
    ),
    ALL(
        "all",
        "Todos os números",
        "O assistente atende chamadas de qualquer número telefônico automaticamente."
    ),
    DISABLED(
        "disabled",
        "Desativado",
        "O assistente não atende nenhuma chamada automaticamente."
    );

    companion object {
        fun fromId(id: String): AutoAnswerRule {
            return values().firstOrNull { it.id == id || it.name.equals(id, ignoreCase = true) } ?: UNSAVED_ONLY
        }
    }
}

/**
 * Configurações completas das APIs de STT (Gemini / Whisper), LLM (DeepSeek) e TTS (Gemini / OpenAI).
 */
data class ApiSettings(
    // Provedor e Chaves
    val geminiApiKey: String = "",
    val deepseekApiKey: String = "",
    val apiKey: String = "", // Fallback genérico

    // STT - Transcrição de Áudio
    val sttProvider: String = "gemini", // "gemini" ou "whisper"
    val sttModel: String = "gemini-3.8-flash",
    val sttLanguage: String = "pt",
    val sttBaseUrl: String = "https://api.openai.com/v1",
    val sttApiKey: String = "",

    // LLM - Geração de Respostas em Texto (DeepSeek)
    val llmProvider: String = "deepseek", // "deepseek" ou "openai"
    val llmBaseUrl: String = "https://api.deepseek.com",
    val llmApiKey: String = "",
    val llmModel: String = "deepseek-flash",
    val llmTemperature: Float = 0.7f,
    val llmMaxTokens: Int = 150,
    val deepseekThinking: Boolean = false,

    // TTS - Geração de Voz (Gemini Speech Generation)
    val ttsProvider: String = "gemini", // "gemini", "system" ou "openai"
    val ttsModel: String = "gemini-2.5-flash-preview-tts",
    val ttsVoice: String = "Puck", // Puck, Aoede, Charon, Fenrir, Kore
    val ttsSpeed: Float = 1.05f,
    val ttsBaseUrl: String = "https://api.openai.com/v1",
    val ttsApiKey: String = "",
    val ttsResponseFormat: String = "pcm",

    // Comportamento Telefônico
    val autoAnswer: Boolean = true,
    val autoAnswerRule: AutoAnswerRule = AutoAnswerRule.UNSAVED_ONLY,
    val selectedContacts: Set<String> = emptySet(),
    val autoAnswerWhatsApp: Boolean = false,
    val autoSpeakerphone: Boolean = true,
    val pauseThresholdMs: Int = 800
) {
    val effectiveGeminiKey: String
        get() = geminiApiKey.ifBlank { apiKey }.trim()

    val effectiveDeepseekKey: String
        get() {
            val raw = deepseekApiKey.ifBlank { apiKey }.trim()
            val skIndex = raw.indexOf("sk-")
            return if (skIndex >= 0) raw.substring(skIndex).trim() else raw
        }

    val effectiveSttKey: String
        get() = if (sttProvider == "gemini") effectiveGeminiKey else sttApiKey.ifBlank { effectiveGeminiKey }.trim()

    val effectiveLlmKey: String
        get() = if (llmProvider == "deepseek") effectiveDeepseekKey else llmApiKey.ifBlank { effectiveDeepseekKey }.trim()

    val effectiveTtsKey: String
        get() = if (ttsProvider == "gemini") effectiveGeminiKey else ttsApiKey.ifBlank { effectiveGeminiKey }.trim()
}

/**
 * Gerenciador de persistência via SharedPreferences com defaults prontos para Gemini e DeepSeek.
 */
class ApiConfigManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "ai_assistant_api_config"

        private const val KEY_GEMINI_KEY = "gemini_api_key"
        private const val KEY_DEEPSEEK_KEY = "deepseek_api_key"
        private const val KEY_API_KEY = "api_key"

        private const val KEY_STT_PROVIDER = "stt_provider"
        private const val KEY_STT_MODEL = "stt_model"
        private const val KEY_STT_LANG = "stt_language"
        private const val KEY_STT_URL = "stt_base_url"
        private const val KEY_STT_KEY = "stt_api_key"

        private const val KEY_LLM_PROVIDER = "llm_provider"
        private const val KEY_LLM_URL = "llm_base_url"
        private const val KEY_LLM_KEY = "llm_api_key"
        private const val KEY_LLM_MODEL = "llm_model"
        private const val KEY_LLM_TEMP = "llm_temperature"
        private const val KEY_LLM_MAX_TOKENS = "llm_max_tokens"
        private const val KEY_DEEPSEEK_THINKING = "deepseek_thinking"

        private const val KEY_TTS_PROVIDER = "tts_provider"
        private const val KEY_TTS_MODEL = "tts_model"
        private const val KEY_TTS_VOICE = "tts_voice"
        private const val KEY_TTS_SPEED = "tts_speed"
        private const val KEY_TTS_URL = "tts_base_url"
        private const val KEY_TTS_KEY = "tts_api_key"
        private const val KEY_TTS_FORMAT = "tts_response_format"

        private const val KEY_AUTO_ANSWER = "auto_answer"
        private const val KEY_AUTO_ANSWER_RULE = "auto_answer_rule"
        private const val KEY_SELECTED_CONTACTS = "selected_contacts"
        private const val KEY_AUTO_ANSWER_WHATSAPP = "auto_answer_whatsapp"
        private const val KEY_AUTO_SPEAKER = "auto_speakerphone"
        private const val KEY_PAUSE_MS = "pause_threshold_ms"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<ApiSettings> = _settings.asStateFlow()

    private fun loadSettings(): ApiSettings {
        return ApiSettings(
            geminiApiKey = prefs.getString(KEY_GEMINI_KEY, "") ?: "",
            deepseekApiKey = prefs.getString(KEY_DEEPSEEK_KEY, "") ?: "",
            apiKey = prefs.getString(KEY_API_KEY, "") ?: "",

            sttProvider = prefs.getString(KEY_STT_PROVIDER, "gemini") ?: "gemini",
            sttModel = prefs.getString(KEY_STT_MODEL, "gemini-3.8-flash") ?: "gemini-3.8-flash",
            sttLanguage = prefs.getString(KEY_STT_LANG, "pt") ?: "pt",
            sttBaseUrl = prefs.getString(KEY_STT_URL, "https://api.openai.com/v1") ?: "https://api.openai.com/v1",
            sttApiKey = prefs.getString(KEY_STT_KEY, "") ?: "",

            llmProvider = prefs.getString(KEY_LLM_PROVIDER, "deepseek") ?: "deepseek",
            llmBaseUrl = prefs.getString(KEY_LLM_URL, "https://api.deepseek.com") ?: "https://api.deepseek.com",
            llmApiKey = prefs.getString(KEY_LLM_KEY, "") ?: "",
            llmModel = prefs.getString(KEY_LLM_MODEL, "deepseek-flash") ?: "deepseek-flash",
            llmTemperature = prefs.getFloat(KEY_LLM_TEMP, 0.7f),
            llmMaxTokens = prefs.getInt(KEY_LLM_MAX_TOKENS, 150),
            deepseekThinking = false,

            ttsProvider = prefs.getString(KEY_TTS_PROVIDER, "gemini") ?: "gemini",
            ttsModel = prefs.getString(KEY_TTS_MODEL, "gemini-2.5-flash-preview-tts") ?: "gemini-2.5-flash-preview-tts",
            ttsVoice = prefs.getString(KEY_TTS_VOICE, "Puck") ?: "Puck",
            ttsSpeed = prefs.getFloat(KEY_TTS_SPEED, 1.05f),
            ttsBaseUrl = prefs.getString(KEY_TTS_URL, "https://api.openai.com/v1") ?: "https://api.openai.com/v1",
            ttsApiKey = prefs.getString(KEY_TTS_KEY, "") ?: "",
            ttsResponseFormat = prefs.getString(KEY_TTS_FORMAT, "pcm") ?: "pcm",

            autoAnswer = prefs.getBoolean(KEY_AUTO_ANSWER, true),
            autoAnswerRule = AutoAnswerRule.fromId(prefs.getString(KEY_AUTO_ANSWER_RULE, "unsaved_only") ?: "unsaved_only"),
            selectedContacts = prefs.getStringSet(KEY_SELECTED_CONTACTS, emptySet()) ?: emptySet(),
            autoAnswerWhatsApp = prefs.getBoolean(KEY_AUTO_ANSWER_WHATSAPP, false),
            autoSpeakerphone = prefs.getBoolean(KEY_AUTO_SPEAKER, true),
            pauseThresholdMs = prefs.getInt(KEY_PAUSE_MS, 800)
        )
    }

    fun updateSettings(newSettings: ApiSettings) {
        prefs.edit().apply {
            putString(KEY_GEMINI_KEY, newSettings.geminiApiKey)
            putString(KEY_DEEPSEEK_KEY, newSettings.deepseekApiKey)
            putString(KEY_API_KEY, newSettings.apiKey)

            putString(KEY_STT_PROVIDER, newSettings.sttProvider)
            putString(KEY_STT_MODEL, newSettings.sttModel)
            putString(KEY_STT_LANG, newSettings.sttLanguage)
            putString(KEY_STT_URL, newSettings.sttBaseUrl)
            putString(KEY_STT_KEY, newSettings.sttApiKey)

            putString(KEY_LLM_PROVIDER, newSettings.llmProvider)
            putString(KEY_LLM_URL, newSettings.llmBaseUrl)
            putString(KEY_LLM_KEY, newSettings.llmApiKey)
            putString(KEY_LLM_MODEL, newSettings.llmModel)
            putFloat(KEY_LLM_TEMP, newSettings.llmTemperature)
            putInt(KEY_LLM_MAX_TOKENS, newSettings.llmMaxTokens)
            putBoolean(KEY_DEEPSEEK_THINKING, newSettings.deepseekThinking)

            putString(KEY_TTS_PROVIDER, newSettings.ttsProvider)
            putString(KEY_TTS_MODEL, newSettings.ttsModel)
            putString(KEY_TTS_VOICE, newSettings.ttsVoice)
            putFloat(KEY_TTS_SPEED, newSettings.ttsSpeed)
            putString(KEY_TTS_URL, newSettings.ttsBaseUrl)
            putString(KEY_TTS_KEY, newSettings.ttsApiKey)
            putString(KEY_TTS_FORMAT, newSettings.ttsResponseFormat)

            putBoolean(KEY_AUTO_ANSWER, newSettings.autoAnswer)
            putString(KEY_AUTO_ANSWER_RULE, newSettings.autoAnswerRule.id)
            putStringSet(KEY_SELECTED_CONTACTS, newSettings.selectedContacts)
            putBoolean(KEY_AUTO_ANSWER_WHATSAPP, newSettings.autoAnswerWhatsApp)
            putBoolean(KEY_AUTO_SPEAKER, newSettings.autoSpeakerphone)
            putInt(KEY_PAUSE_MS, newSettings.pauseThresholdMs)
            apply()
        }
        _settings.value = newSettings
    }

    fun updateSetting(transform: ApiSettings.() -> ApiSettings) {
        updateSettings(_settings.value.transform())
    }
}
