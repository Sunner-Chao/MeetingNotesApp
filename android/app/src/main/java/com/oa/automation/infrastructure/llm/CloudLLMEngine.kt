package com.oa.automation.infrastructure.llm

import com.google.gson.Gson
import com.oa.automation.domain.model.CloudApiFormat
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
 * Cloud LLM Engine
 * Supports various cloud LLM APIs (SiliconFlow, OpenAI compatible, etc.)
 *
 * Expected API format: OpenAI Chat Completions API compatible
 * POST /v1/chat/completions
 * {
 *   "model": "Qwen/Qwen2.5-7B-Instruct",
 *   "messages": [...],
 *   "temperature": 0.7
 * }
 */
class CloudLLMEngine(
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
        attachments: List<AgentAttachment>
    ): Result<ReportData> = withContext(Dispatchers.IO) {
        try {
            val endpoint = config.cloudEndpoint ?: return@withContext Result.failure(
                Exception("Cloud endpoint not configured")
            )

            val apiKey = config.cloudApiKey ?: return@withContext Result.failure(
                Exception("Cloud API key not configured")
            )

            val model = config.cloudModel ?: return@withContext Result.failure(
                Exception("Cloud model not configured")
            )

            val request = when (config.cloudApiFormat) {
                CloudApiFormat.OPENAI_COMPAT -> buildOpenAiRequest(endpoint, apiKey, model, transcript, template)
                CloudApiFormat.CLAUDE_MESSAGES -> buildClaudeRequest(endpoint, apiKey, model, transcript, template)
            }

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    return@withContext Result.failure(
                        Exception("Cloud LLM request failed: ${response.code} - $errorBody")
                    )
                }

                val responseBody = response.body?.string() ?: return@withContext Result.failure(
                    Exception("Empty response from cloud LLM service")
                )

                val generatedText = when (config.cloudApiFormat) {
                    CloudApiFormat.OPENAI_COMPAT -> {
                        val chatResponse = gson.fromJson(responseBody, ChatCompletionResponse::class.java)
                        chatResponse.choices.firstOrNull()?.message?.content
                    }
                    CloudApiFormat.CLAUDE_MESSAGES -> {
                        val claudeResponse = gson.fromJson(responseBody, ClaudeResponse::class.java)
                        claudeResponse.content.firstOrNull { it.type == "text" }?.text
                    }
                } ?: return@withContext Result.failure(Exception("No response content from cloud LLM"))

                val reportData = parseLLMOutput(generatedText, template.selectedName)
                Result.success(reportData)
            }

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getEngineType(): LLMEngineType = LLMEngineType.CLOUD_API

    override fun getDisplayName(): String = "云端大模型"

    override fun isAvailable(): Boolean {
        return !config.cloudEndpoint.isNullOrBlank() &&
                !config.cloudApiKey.isNullOrBlank() &&
                !config.cloudModel.isNullOrBlank()
    }

    override suspend fun chat(
        messages: List<ChatMessage>,
        attachments: List<AgentAttachment>
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val endpoint = config.cloudEndpoint ?: return@withContext Result.failure(
                Exception("Cloud endpoint not configured")
            )

            val apiKey = config.cloudApiKey ?: return@withContext Result.failure(
                Exception("Cloud API key not configured")
            )

            val model = config.cloudModel ?: return@withContext Result.failure(
                Exception("Cloud model not configured")
            )

            val request = when (config.cloudApiFormat) {
                CloudApiFormat.OPENAI_COMPAT -> buildChatRequest(endpoint, apiKey, model, messages)
                CloudApiFormat.CLAUDE_MESSAGES -> buildClaudeChatRequest(endpoint, apiKey, model, messages)
            }

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    return@withContext Result.failure(
                        Exception("Cloud LLM chat failed: ${response.code} - $errorBody")
                    )
                }

                val responseBody = response.body?.string() ?: return@withContext Result.failure(
                    Exception("Empty response from cloud LLM service")
                )

                val generatedText = when (config.cloudApiFormat) {
                    CloudApiFormat.OPENAI_COMPAT -> {
                        val chatResponse = gson.fromJson(responseBody, ChatCompletionResponse::class.java)
                        chatResponse.choices.firstOrNull()?.message?.content
                    }
                    CloudApiFormat.CLAUDE_MESSAGES -> {
                        val claudeResponse = gson.fromJson(responseBody, ClaudeResponse::class.java)
                        claudeResponse.content.firstOrNull { it.type == "text" }?.text
                    }
                } ?: return@withContext Result.failure(Exception("No response content from cloud LLM"))

                Result.success(generatedText)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildChatRequest(endpoint: String, apiKey: String, model: String, messages: List<ChatMessage>): Request {
        val apiMessages = messages.map { msg ->
            Message(role = msg.role, content = msg.content)
        }

        val requestBody = ChatCompletionRequest(
            model = model,
            messages = apiMessages,
            temperature = 0.7f,
            max_tokens = 4096
        )

        return Request.Builder()
            .url("${endpoint.trimEnd('/')}/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(gson.toJson(requestBody).toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun buildClaudeChatRequest(endpoint: String, apiKey: String, model: String, messages: List<ChatMessage>): Request {
        val systemMessage = messages.find { it.role == "system" }
        val chatMessages = messages.filter { it.role != "system" }.map { msg ->
            ClaudeMessage(role = msg.role, content = msg.content)
        }

        val requestBody = ClaudeRequest(
            model = model,
            max_tokens = 4096,
            system = systemMessage?.content ?: "",
            messages = chatMessages
        )

        val normalizedEndpoint = endpoint.trimEnd('/')
        val messagesUrl = if (normalizedEndpoint.endsWith("/v1")) {
            "$normalizedEndpoint/messages"
        } else {
            "$normalizedEndpoint/v1/messages"
        }

        return Request.Builder()
            .url(messagesUrl)
            .addHeader("x-api-key", apiKey)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .post(gson.toJson(requestBody).toRequestBody("application/json".toMediaType()))
            .build()
    }

    /**
     * Parse LLM output into structured ReportData
     * Same parsing logic as OllamaEngine
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
            .replace("^\\d+\\.?\\s*".toRegex(), "")
            .replace("^[-*•]\\s*".toRegex(), "")
            .trim()
    }

    private fun extractListSection(output: String, sectionName: String): List<String> {
        val lines = output.lines()
        val items = mutableListOf<String>()

        for (line in lines) {
            when {
                line.contains(sectionName) -> {} // Skip section header
                line.startsWith("#") -> break
                line.isNotBlank() -> {
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

    private fun buildOpenAiRequest(endpoint: String, apiKey: String, model: String, transcript: String, template: ReportTemplateConfig): Request {
        val messages = listOf(
            Message(role = "system", content = ReportPromptTemplates.SYSTEM_PROMPT),
            Message(role = "user", content = ReportPromptTemplates.buildUserPrompt(transcript, template))
        )

        val requestBody = ChatCompletionRequest(
            model = model,
            messages = messages,
            temperature = 0.7f,
            max_tokens = 2048
        )

        return Request.Builder()
            .url("${endpoint.trimEnd('/')}/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(gson.toJson(requestBody).toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun buildClaudeRequest(endpoint: String, apiKey: String, model: String, transcript: String, template: ReportTemplateConfig): Request {
        val requestBody = ClaudeRequest(
            model = model,
            max_tokens = 2048,
            system = ReportPromptTemplates.SYSTEM_PROMPT,
            messages = listOf(
                ClaudeMessage(
                    role = "user",
                    content = ReportPromptTemplates.buildUserPrompt(transcript, template)
                )
            )
        )
        val normalizedEndpoint = endpoint.trimEnd('/')
        val messagesUrl = if (normalizedEndpoint.endsWith("/v1")) {
            "$normalizedEndpoint/messages"
        } else {
            "$normalizedEndpoint/v1/messages"
        }

        return Request.Builder()
            .url(messagesUrl)
            .addHeader("x-api-key", apiKey)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .post(gson.toJson(requestBody).toRequestBody("application/json".toMediaType()))
            .build()
    }

    // OpenAI Chat Completions API models
    private data class ChatCompletionRequest(
        val model: String,
        val messages: List<Message>,
        val temperature: Float = 0.7f,
        val max_tokens: Int = 2048
    )

    private data class Message(
        val role: String,
        val content: String
    )

    private data class ChatCompletionResponse(
        val choices: List<Choice>
    )

    private data class Choice(
        val message: ResponseMessage
    )

    private data class ResponseMessage(
        val role: String?,
        val content: String?
    )

    private data class ClaudeRequest(
        val model: String,
        val max_tokens: Int,
        val system: String,
        val messages: List<ClaudeMessage>
    )

    private data class ClaudeMessage(
        val role: String,
        val content: String
    )

    private data class ClaudeResponse(
        val content: List<ClaudeContent>
    )

    private data class ClaudeContent(
        val type: String,
        val text: String?
    )
}
