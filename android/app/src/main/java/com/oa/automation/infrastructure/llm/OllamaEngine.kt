package com.oa.automation.infrastructure.llm

import com.google.gson.Gson
import com.oa.automation.domain.model.LLMConfig
import com.oa.automation.domain.model.LLMEngineType
import com.oa.automation.domain.model.ReportTemplateConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Ollama LLM Engine
 * Communicates with local Ollama API (http://localhost:11434)
 *
 * Ollama API format:
 * POST /api/generate
 * {
 *   "model": "qwen2.5:7b",
 *   "prompt": "...",
 *   "stream": false
 * }
 */
class OllamaEngine(
    private val config: LLMConfig
) : LLMReportEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS) // LLM can take time
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    override suspend fun generateReport(
        transcript: String,
        template: ReportTemplateConfig,
        attachments: List<AgentAttachment>,
        usageContext: AgentUsageContext?
    ): Result<ReportData> = withContext(Dispatchers.IO) {
        try {
            val endpoint = config.localEndpoint
            val model = config.localModel

            // Build prompt
            val systemPrompt = ReportPromptTemplates.SYSTEM_PROMPT
            val userPrompt = ReportPromptTemplates.buildUserPrompt(transcript, template)

            // Build request body for Ollama
            val requestBody = OllamaRequest(
                model = model,
                prompt = "System: $systemPrompt\n\nUser: $userPrompt",
                stream = false,
                options = OllamaOptions(
                    temperature = 0.7f,
                    num_predict = 2048
                )
            )

            val jsonBody = gson.toJson(requestBody)

            val request = Request.Builder()
                .url("$endpoint/api/generate")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("Ollama request failed: ${response.code} ${response.message}")
                    )
                }

                val responseBody = response.body?.string() ?: return@withContext Result.failure(
                    Exception("Empty response from Ollama")
                )

                val ollamaResponse = gson.fromJson(responseBody, OllamaResponse::class.java)
                val generatedText = ollamaResponse.response ?: return@withContext Result.failure(
                    Exception("No response text from Ollama")
                )

                val reportData = parseLLMOutput(generatedText, template.selectedName)
                Result.success(reportData)
            }

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getEngineType(): LLMEngineType = LLMEngineType.LOCAL_OLLAMA

    override fun getDisplayName(): String = "本地 Ollama"

    override fun isAvailable(): Boolean {
        return config.localEndpoint.isNotBlank() && config.localModel.isNotBlank()
    }

    override suspend fun chat(
        messages: List<ChatMessage>,
        attachments: List<AgentAttachment>
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val endpoint = config.localEndpoint
            val model = config.localModel

            // Build prompt from messages
            val prompt = messages.joinToString("\n\n") { msg ->
                when (msg.role) {
                    "system" -> "System: ${msg.content}"
                    "user" -> "User: ${msg.content}"
                    "assistant" -> "Assistant: ${msg.content}"
                    else -> msg.content
                }
            }

            val requestBody = OllamaRequest(
                model = model,
                prompt = prompt,
                stream = false,
                options = OllamaOptions(
                    temperature = 0.7f,
                    num_predict = 4096
                )
            )

            val jsonBody = gson.toJson(requestBody)

            val request = Request.Builder()
                .url("$endpoint/api/generate")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("Ollama chat failed: ${response.code} ${response.message}")
                    )
                }

                val responseBody = response.body?.string() ?: return@withContext Result.failure(
                    Exception("Empty response from Ollama")
                )

                val ollamaResponse = gson.fromJson(responseBody, OllamaResponse::class.java)
                val generatedText = ollamaResponse.response ?: return@withContext Result.failure(
                    Exception("No response text from Ollama")
                )

                Result.success(generatedText)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        private val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        /**
         * Test connection to Ollama service
         */
        fun testConnection(endpoint: String): Boolean {
            return try {
                val request = Request.Builder()
                    .url("$endpoint/api/tags")
                    .get()
                    .build()
                client.newCall(request).execute().use { it.isSuccessful }
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * Parse LLM output into structured ReportData
     */
    private fun parseLLMOutput(output: String, templateName: String): ReportData {
        val summary = extractSection(output, "会议概述", "关键要点") ?: ""
        val keyPoints = extractListSection(output, "关键要点")
        val decisions = extractListSection(output, "决策事项")
        val tasks = extractTasks(output)
        val actionItems = extractListSection(output, "行动项")

        return ReportData(
            summary = summary,
            keyPoints = keyPoints,
            tasks = tasks,
            decisions = decisions,
            actionItems = actionItems,
            rawContent = output.trim(),
            templateName = templateName
        )
    }

    private fun extractSection(output: String, startMarker: String, endMarker: String): String? {
        val startIndex = output.indexOf(startMarker)
        if (startIndex == -1) return null

        val contentStart = output.indexOf("\n", startIndex).takeIf { it != -1 } ?: startIndex + startMarker.length
        val endIndex = output.indexOf(endMarker, contentStart).takeIf { it != -1 } ?: output.length

        return output.substring(contentStart, endIndex).trim()
            .replace("^\\d+\\.?\\s*".toRegex(), "") // Remove leading numbers
            .replace("^[-*•]\\s*".toRegex(), "") // Remove bullet points
            .trim()
    }

    private fun extractListSection(output: String, sectionName: String): List<String> {
        val lines = output.lines()
        var inSection = false
        val items = mutableListOf<String>()

        for (line in lines) {
            when {
                line.contains(sectionName) -> inSection = true
                inSection && line.startsWith("#") -> break // Next section
                inSection && line.isNotBlank() -> {
                    val cleanLine = line
                        .trim()
                        .replace("^\\d+[.、]\\s*".toRegex(), "")
                        .replace("^[-*•]\\s*".toRegex(), "")
                    if (cleanLine.isNotBlank()) {
                        items.add(cleanLine)
                    }
                }
            }
        }

        return items
    }

    private fun extractTasks(output: String): List<TaskData> {
        val tasksSection = extractSection(output, "待办任务", "行动项") ?: return emptyList()
        val taskLines = tasksSection.lines().filter { it.isNotBlank() }

        return taskLines.mapNotNull { line ->
            // Try to parse task format: "任务内容 | 负责人 | 截止时间"
            val parts = line.split("|").map { it.trim() }
            when {
                parts.size >= 3 -> TaskData(
                    content = parts[0].replace("^\\d+[.、]\\s*".toRegex(), ""),
                    assignee = parts[1].takeIf { it.isNotBlank() && it != "无" },
                    due = parts[2].takeIf { it.isNotBlank() && it != "无" }
                )
                parts.size >= 1 -> TaskData(
                    content = parts[0].replace("^\\d+[.、]\\s*".toRegex(), "")
                )
                else -> null
            }
        }
    }

    // Ollama API models
    private data class OllamaRequest(
        val model: String,
        val prompt: String,
        val stream: Boolean = false,
        val options: OllamaOptions? = null
    )

    private data class OllamaOptions(
        val temperature: Float = 0.7f,
        val num_predict: Int = 2048
    )

    private data class OllamaResponse(
        val model: String?,
        val response: String?,
        val done: Boolean?
    )
}
