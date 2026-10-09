package com.example.ai_assistant.persona

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Gerenciador responsável pelo ciclo de vida das Personas do sistema:
 * carregamento, persistência em arquivo JSON (personas.json), seleção,
 * criação, edição e exclusão.
 */
class PersonaManager(private val context: Context) {

    companion object {
        private const val TAG = "PersonaManager"
        private const val FILE_NAME = "personas.json"
        private const val PREFS_NAME = "persona_preferences"
        private const val KEY_ACTIVE_PERSONA_ID = "active_persona_id"

        @Volatile
        private var instance: PersonaManager? = null

        fun getInstance(context: Context): PersonaManager {
            return instance ?: synchronized(this) {
                instance ?: PersonaManager(context.applicationContext).also { instance = it }
            }
        }

        val DEFAULT_PERSONAS = listOf(
            Persona(
                id = "assistente_ironico",
                name = "Assistente (Irônico & Ácido)",
                description = "Atendimento pessoal direto. Zero subserviência, humor ácido, devolve grosseria na mesma moeda.",
                systemPrompt = "Você é o assistente pessoal atendendo uma ligação no celular do titular.\n\n" +
                        "QUEM VOCÊ É:\n" +
                        "- Você fala com a autoridade de quem atende o celular do titular.\n" +
                        "- Você NÃO é um robô de telemarketing e NÃO presta suporte ao cliente genérico.\n" +
                        "- Você NÃO está aqui para bajular ninguém. NUNCA diga frases como \"Como posso ajudar?\", \"Estou aqui para ajudar\" ou \"Posso fazer algo por você?\".\n\n" +
                        "COMPORTAMENTO E TOM:\n" +
                        "- Atenda com naturalidade e informalidade de brasileiro atendendo ligação.\n" +
                        "- Seja irônico, sarcástico e com humor ácido. Se quem ligou for folgado, enrolado, telemarketing ou grosseiro, DEVOLVA NA MESMA MOEDA com deboche e ironia, sem papas na língua.\n" +
                        "- Se for alguém conhecido ou conversa normal, seja autêntico, descontraído e direto na lata.\n\n" +
                        "REGRAS DE FALA AO TELEFONE:\n" +
                        "1. Respostas super curtas: fale apenas 1 ou no máximo 2 frases rápidas (máximo 15 a 20 palavras). Ninguém discursa ao telefone.\n" +
                        "2. Não use markdown, asteriscos, negrito ou listas.\n" +
                        "3. Vá direto à fala sem enrolação nem pensamentos internos.",
                isDefault = true
            ),
            Persona(
                id = "assistente_amigo",
                name = "Assistente (Descontraído / Amigo)",
                description = "Papo informal entre amigos brasileiros, fala direto e bem-humorado.",
                systemPrompt = "Você está conversando numa ligação com um amigo ou conhecido do titular.\n\n" +
                        "QUEM VOCÊ É:\n" +
                        "- Conversa de forma descontraída, informal e direta como num papo de WhatsApp/ligação entre amigos brasileiros.\n" +
                        "- Fala com bom humor e vai direto ao ponto sem enrolar.\n\n" +
                        "REGRAS:\n" +
                        "1. Apenas 1 ou 2 frases curtas (máximo 20 palavras).\n" +
                        "2. Sem markdown, sem formalismos, sem subserviência.\n" +
                        "3. Responda diretamente sem pensamentos internos.",
                isDefault = false
            ),
            Persona(
                id = "filtro_telemarketing",
                name = "Filtro Anti-Telemarketing (Hostil & Debochado)",
                description = "Impaciente com cobranças, planos e golpes. Corta na hora com deboche.",
                systemPrompt = "Você está atendendo uma ligação indesejada no celular.\n\n" +
                        "POSTURA:\n" +
                        "- Você odeia telemarketing, cobranças indevidas, planos de celular e tentativas de golpe.\n" +
                        "- Seja curto, grosso, debochado e corte o interlocutor imediatamente.\n" +
                        "- Não dê corda, desminta o que disserem e pergunte de onde tiraram o número com tom ácido.\n\n" +
                        "REGRAS:\n" +
                        "1. No máximo 1 ou 2 frases curtas e secas.\n" +
                        "2. Sem educação fingida, sem pedir desculpas.\n" +
                        "3. Vá direto ao ponto.",
                isDefault = false
            ),
            Persona(
                id = "assistente_pro",
                name = "Assistente (Profissional Direto)",
                description = "Comunicação profissional, séria e concisa, sem bajulação.",
                systemPrompt = "Você é um assistente atendendo uma ligação profissional no celular corporativo.\n\n" +
                        "POSTURA:\n" +
                        "- Profissional de tecnologia e negócios.\n" +
                        "- Seja seguro, objetivo, educado mas direto ao ponto.\n" +
                        "- Não seja subserviente nem fale como atendente de telemarketing.\n\n" +
                        "REGRAS:\n" +
                        "1. Responda em 1 ou 2 frases concisas (máximo 25 palavras).\n" +
                        "2. Vá direto ao assunto.",
                isDefault = false
            )
        )
    }

    private val personasFile: File
        get() = File(context.filesDir, FILE_NAME)

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _personas = MutableStateFlow<List<Persona>>(emptyList())
    val personas: StateFlow<List<Persona>> = _personas.asStateFlow()

    private val _activePersona = MutableStateFlow(DEFAULT_PERSONAS.first())
    val activePersona: StateFlow<Persona> = _activePersona.asStateFlow()

    init {
        loadPersonas()
    }

    /**
     * Carrega as personas do arquivo JSON ou inicializa com as personas padrão.
     */
    fun loadPersonas() {
        try {
            val file = personasFile
            if (!file.exists()) {
                Log.i(TAG, "Arquivo $FILE_NAME não existe. Criando com personas padrão...")
                savePersonasToFile(DEFAULT_PERSONAS)
                _personas.value = DEFAULT_PERSONAS
            } else {
                val jsonString = file.readText()
                val parsedList = parsePersonasJson(jsonString)
                if (parsedList.isNotEmpty()) {
                    _personas.value = parsedList
                } else {
                    _personas.value = DEFAULT_PERSONAS
                    savePersonasToFile(DEFAULT_PERSONAS)
                }
            }

            // Restaura persona ativa persistida
            val savedActiveId = prefs.getString(KEY_ACTIVE_PERSONA_ID, null)
            val selected = _personas.value.firstOrNull { it.id == savedActiveId }
                ?: _personas.value.firstOrNull { it.isDefault }
                ?: _personas.value.firstOrNull()
                ?: DEFAULT_PERSONAS.first()

            _activePersona.value = selected
            Log.i(TAG, "Personas carregadas: ${_personas.value.size}. Ativa: \"${selected.name}\"")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao carregar personas do JSON, usando defaults", e)
            _personas.value = DEFAULT_PERSONAS
            _activePersona.value = DEFAULT_PERSONAS.first()
        }
    }

    /**
     * Seleciona uma persona como ativa e salva a preferência.
     */
    fun selectPersona(id: String) {
        val target = _personas.value.firstOrNull { it.id == id } ?: return
        _activePersona.value = target
        prefs.edit().putString(KEY_ACTIVE_PERSONA_ID, id).apply()
        Log.i(TAG, "Persona ativa alterada para: \"${target.name}\"")
    }

    /**
     * Cria uma nova persona e persiste no JSON.
     */
    fun createPersona(name: String, description: String, systemPrompt: String): Persona {
        val newPersona = Persona(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { "Nova Persona" },
            description = description.trim(),
            systemPrompt = systemPrompt.trim(),
            isDefault = false
        )
        val updated = _personas.value + newPersona
        _personas.value = updated
        savePersonasToFile(updated)
        selectPersona(newPersona.id)
        Log.i(TAG, "Persona criada e salva: \"${newPersona.name}\"")
        return newPersona
    }

    /**
     * Atualiza uma persona existente e persiste no JSON.
     */
    fun updatePersona(id: String, name: String, description: String, systemPrompt: String): Persona? {
        val currentList = _personas.value
        val index = currentList.indexOfFirst { it.id == id }
        if (index < 0) return null

        val current = currentList[index]
        val updated = current.copy(
            name = name.trim().ifEmpty { current.name },
            description = description.trim(),
            systemPrompt = systemPrompt.trim().ifEmpty { current.systemPrompt }
        )

        val newList = currentList.toMutableList()
        newList[index] = updated
        _personas.value = newList
        savePersonasToFile(newList)

        if (_activePersona.value.id == id) {
            _activePersona.value = updated
        }

        Log.i(TAG, "Persona atualizada: \"${updated.name}\"")
        return updated
    }

    /**
     * Exclui uma persona (desde que não seja a única existente).
     */
    fun deletePersona(id: String): Boolean {
        val currentList = _personas.value
        if (currentList.size <= 1) {
            Log.w(TAG, "Não é possível excluir a única persona restante.")
            return false
        }

        val target = currentList.firstOrNull { it.id == id } ?: return false
        val newList = currentList.filter { it.id != id }
        _personas.value = newList
        savePersonasToFile(newList)

        // Se a deletada era a ativa, seleciona a primeira da lista restante
        if (_activePersona.value.id == id) {
            selectPersona(newList.first().id)
        }

        Log.i(TAG, "Persona excluída: \"${target.name}\"")
        return true
    }

    /**
     * Restaura as personas padrão de fábrica no personas.json.
     */
    fun resetToDefaults() {
        _personas.value = DEFAULT_PERSONAS
        savePersonasToFile(DEFAULT_PERSONAS)
        selectPersona(DEFAULT_PERSONAS.first().id)
        Log.i(TAG, "Personas restauradas para os padrões de fábrica.")
    }

    private fun parsePersonasJson(jsonString: String): List<Persona> {
        val list = mutableListOf<Persona>()
        val jsonArray = JSONArray(jsonString)
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            list.add(Persona.fromJson(obj))
        }
        return list
    }

    private fun savePersonasToFile(list: List<Persona>) {
        try {
            val jsonArray = JSONArray()
            for (p in list) {
                jsonArray.put(p.toJson())
            }
            val content = jsonArray.toString(2)
            val file = personasFile
            val tmpFile = File(context.filesDir, "$FILE_NAME.tmp")
            tmpFile.writeText(content)
            if (tmpFile.renameTo(file) || run { file.delete(); tmpFile.renameTo(file) }) {
                Log.d(TAG, "Arquivo $FILE_NAME gravado com sucesso (${list.size} personas)")
            } else {
                file.writeText(content)
                tmpFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao gravar personas no arquivo $FILE_NAME", e)
        }
    }
}
