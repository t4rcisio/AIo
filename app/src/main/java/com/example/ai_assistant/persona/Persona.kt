package com.example.ai_assistant.persona

import org.json.JSONObject

/**
 * Representa uma persona conversacional customizável com prompt de sistema específico,
 * persistida no arquivo JSON.
 */
data class Persona(
    val id: String,
    val name: String,
    val description: String,
    val systemPrompt: String,
    val isDefault: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("description", description)
        put("systemPrompt", systemPrompt)
        put("isDefault", isDefault)
    }

    companion object {
        fun fromJson(json: JSONObject): Persona = Persona(
            id = json.optString("id", java.util.UUID.randomUUID().toString()),
            name = json.optString("name", "Nova Persona"),
            description = json.optString("description", ""),
            systemPrompt = json.optString("systemPrompt", ""),
            isDefault = json.optBoolean("isDefault", false)
        )
    }
}
