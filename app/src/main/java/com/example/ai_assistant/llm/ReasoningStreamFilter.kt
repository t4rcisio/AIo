package com.example.ai_assistant.llm

/**
 * Filtro de streaming que separa o raciocínio interno do modelo (thinking/reasoning como <think>...</think>)
 * da resposta final visível para exibição na UI e síntese de voz (TTS).
 */
class ReasoningStreamFilter {
    private var inThinking = false
    private var isTextualThinking = false
    private var thinkDone = false
    private val buffer = StringBuilder()
    private var initialChecked = false

    private val _rawOutput = StringBuilder()
    private val _rawReasoning = StringBuilder()
    private val _finalAnswer = StringBuilder()

    var reasoningTokens: Int = 0
        private set
    var visibleTokens: Int = 0
        private set

    val rawOutput: String get() = _rawOutput.toString()
    val rawReasoning: String get() = _rawReasoning.toString()
    val finalAnswer: String get() = _finalAnswer.toString().trim()
    val totalTokens: Int get() = reasoningTokens + visibleTokens

    private val xmlThinkStart = listOf(
        "<think>", "<|think|>", "<thought>", "<|thought|>", "<reasoning>", "<|reasoning|>"
    )

    private val textThinkStart = listOf(
        "thinking process:", "**thinking process:**", "thinking process",
        "thought process:", "thought:", "thinking:",
        "processo de pensamento:", "pensamento:", "raciocínio:", "pensando:"
    )

    private val explicitEndMarkers = listOf(
        "</think>", "<|end_of_thought|>", "</thought>", "</reasoning>",
        "<turn|>", "<end_of_turn>", "<|im_end|>", "<|eot_id|>",
        "final answer:", "response:", "resposta:", "answer:"
    )

    /**
     * Processa um token recebido do LLM.
     * Retorna os fragmentos limpos que devem ser exibidos na UI e enviados ao TTS.
     */
    fun processToken(token: String): List<String> {
        _rawOutput.append(token)
        val output = mutableListOf<String>()
        buffer.append(token)

        while (buffer.isNotEmpty()) {
            if (!initialChecked) {
                val currentStr = buffer.toString()
                val currentLower = currentStr.lowercase().trimStart()

                val matchedEndXml = explicitEndMarkers.firstOrNull { currentLower.contains(it) }
                if (matchedEndXml != null) {
                    val idx = currentLower.indexOf(matchedEndXml)
                    val thinkPart = currentStr.substring(0, idx)
                    _rawReasoning.append(thinkPart)
                    reasoningTokens++

                    buffer.delete(0, idx + matchedEndXml.length)
                    inThinking = false
                    isTextualThinking = false
                    thinkDone = true
                    initialChecked = true
                    while (buffer.isNotEmpty() && (buffer[0] == '\n' || buffer[0] == '\r')) {
                        buffer.deleteCharAt(0)
                    }
                    continue
                }

                val matchedXml = xmlThinkStart.firstOrNull { currentLower.startsWith(it) || currentLower.contains(it) }
                val matchedText = textThinkStart.firstOrNull { currentLower.startsWith(it) }

                val isPotentialPrefix = xmlThinkStart.any { it.startsWith(currentLower) } ||
                        textThinkStart.any { it.startsWith(currentLower) }

                if (matchedXml != null) {
                    val idx = currentLower.indexOf(matchedXml)
                    val before = currentStr.substring(0, idx)
                    if (before.isNotBlank()) {
                        output.add(before)
                        _finalAnswer.append(before)
                        visibleTokens++
                    }
                    buffer.delete(0, idx + matchedXml.length)
                    inThinking = true
                    isTextualThinking = false
                    initialChecked = true
                    reasoningTokens++
                } else if (matchedText != null) {
                    buffer.delete(0, matchedText.length)
                    inThinking = true
                    isTextualThinking = true
                    initialChecked = true
                    reasoningTokens++
                } else if (isPotentialPrefix && currentStr.length < 25) {
                    break
                } else {
                    initialChecked = true
                }
            }

            if (inThinking) {
                val current = buffer.toString()
                val currentLower = current.lowercase()

                var endIdx = -1
                var matchLen = 0
                for (endTag in explicitEndMarkers) {
                    val idx = currentLower.indexOf(endTag)
                    if (idx >= 0) {
                        endIdx = idx
                        matchLen = endTag.length
                        break
                    }
                }

                if (endIdx < 0 && isTextualThinking) {
                    val dblBreak = current.indexOf("\n\n")
                    if (dblBreak >= 0 && dblBreak + 2 < current.length) {
                        val after = current.substring(dblBreak + 2).trimStart()
                        if (after.isNotEmpty() &&
                            !after.startsWith("*") &&
                            !after.startsWith("-") &&
                            !after.startsWith("#")
                        ) {
                            endIdx = dblBreak + 2
                            matchLen = 0
                        }
                    }
                }

                if (endIdx >= 0) {
                    val thinkText = current.substring(0, endIdx)
                    _rawReasoning.append(thinkText)
                    reasoningTokens++

                    buffer.delete(0, endIdx + matchLen)
                    inThinking = false
                    isTextualThinking = false
                    thinkDone = true
                    while (buffer.isNotEmpty() && (buffer[0] == '\n' || buffer[0] == '\r')) {
                        buffer.deleteCharAt(0)
                    }
                } else {
                    val matchPrefix = explicitEndMarkers.firstNotNullOfOrNull { tag ->
                        tag.indices.reversed().firstOrNull { len -> currentLower.endsWith(tag.substring(0, len)) }?.let { len -> tag to len }
                    }
                    if (matchPrefix != null && matchPrefix.second > 0) {
                        val keepFrom = current.length - matchPrefix.second
                        val consumed = current.substring(0, keepFrom)
                        _rawReasoning.append(consumed)
                        reasoningTokens++
                        buffer.delete(0, keepFrom)
                    } else if (!isTextualThinking) {
                        _rawReasoning.append(current)
                        reasoningTokens++
                        buffer.clear()
                    }
                    break
                }
            }

            if (!inThinking && initialChecked) {
                val current = buffer.toString()
                val currentLower = current.lowercase()

                val leakedEnd = explicitEndMarkers.firstOrNull { currentLower.contains(it) }
                if (leakedEnd != null) {
                    val idx = currentLower.indexOf(leakedEnd)
                    buffer.delete(0, idx + leakedEnd.length)
                    while (buffer.isNotEmpty() && (buffer[0] == '\n' || buffer[0] == '\r')) {
                        buffer.deleteCharAt(0)
                    }
                    continue
                }

                val matchedStart = xmlThinkStart.firstOrNull { currentLower.contains(it) }
                if (matchedStart != null) {
                    val idx = currentLower.indexOf(matchedStart)
                    val before = current.substring(0, idx)
                    if (before.isNotEmpty()) {
                        output.add(before)
                        _finalAnswer.append(before)
                        visibleTokens++
                    }
                    buffer.delete(0, idx + matchedStart.length)
                    inThinking = true
                    isTextualThinking = false
                    reasoningTokens++
                } else {
                    var textToEmit = buffer.toString()
                    val tagsToStrip = listOf(
                        "<turn|>", "<end_of_turn>", "<|im_end|>", "<|eot_id|>",
                        "<think>", "</think>", "<|think|>", "<|end_of_thought|>",
                        "<thought>", "</thought>", "<reasoning>", "</reasoning>"
                    )
                    for (tag in tagsToStrip) {
                        if (textToEmit.contains(tag, ignoreCase = true)) {
                            textToEmit = textToEmit.replace(tag, "", ignoreCase = true)
                        }
                    }
                    if (textToEmit.isNotEmpty()) {
                        output.add(textToEmit)
                        _finalAnswer.append(textToEmit)
                        visibleTokens++
                    }
                    buffer.clear()
                }
            }
        }
        return output
    }

    fun finish(): List<String> {
        val output = mutableListOf<String>()
        if (!inThinking && buffer.isNotEmpty()) {
            var text = buffer.toString()
            val tagsToStrip = listOf(
                "<turn|>", "<end_of_turn>", "<|im_end|>", "<|eot_id|>",
                "<think>", "</think>", "<|think|>", "<|end_of_thought|>",
                "<thought>", "</thought>", "<reasoning>", "</reasoning>"
            )
            for (tag in tagsToStrip) {
                text = text.replace(tag, "", ignoreCase = true)
            }
            if (text.isNotBlank()) {
                output.add(text)
                _finalAnswer.append(text)
                visibleTokens++
            }
            buffer.clear()
        }
        return output
    }

    fun reset() {
        inThinking = false
        isTextualThinking = false
        thinkDone = false
        buffer.clear()
        initialChecked = false
        _rawOutput.clear()
        _rawReasoning.clear()
        _finalAnswer.clear()
        reasoningTokens = 0
        visibleTokens = 0
    }

    fun isThinking(): Boolean = inThinking
}
