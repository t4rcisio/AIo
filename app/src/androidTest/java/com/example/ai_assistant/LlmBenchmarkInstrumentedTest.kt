package com.example.ai_assistant

import android.os.Environment
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ai_assistant.llm.ModelBenchmarkReport
import com.example.ai_assistant.llm.ModelBenchmarkRunner
import com.example.ai_assistant.llm.ModelRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Teste instrumentado que executa a bateria oficial de calibração técnica dos 5 modelos locais.
 */
@RunWith(AndroidJUnit4::class)
class LlmBenchmarkInstrumentedTest {

    companion object {
        private const val TAG = "LlmBenchmarkTest"
    }

    @Test
    fun runFullModelBenchmark() = runBlocking {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val runner = ModelBenchmarkRunner(appContext)

        val modelsDir = File(appContext.filesDir, "models")
        Log.i(TAG, "Iniciando benchmark dos modelos disponíveis em ${modelsDir.absolutePath}...")

        val reports = mutableListOf<ModelBenchmarkReport>()

        for (profile in ModelRegistry.ALL_PROFILES) {
            val modelFile = File(modelsDir, profile.fileName)
            if (!modelFile.exists()) {
                Log.w(TAG, "Modelo ${profile.name} (${profile.fileName}) não encontrado. Pulando...")
                continue
            }

            Log.i(TAG, "Testando modelo ${profile.name}...")
            val report = runner.runBenchmark(profile, iterationsPerPrompt = 5)
            reports.add(report)
        }

        if (reports.isNotEmpty()) {
            val markdown = runner.formatMarkdownComparison(reports)
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadDir, "ai_call_benchmark_report.md")
            file.writeText(markdown)
            Log.i(TAG, "Relatório salvo em: ${file.absolutePath}")
            Log.i(TAG, "\n$markdown")
        }
    }
}
