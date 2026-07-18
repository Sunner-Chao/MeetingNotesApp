package com.oa.automation.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.domain.model.AppConfig
import com.oa.automation.domain.model.AgentProvider
import com.oa.automation.domain.model.CloudApiFormat
import com.oa.automation.domain.model.DiscoveredSTTServer
import com.oa.automation.domain.model.LLMConfig
import com.oa.automation.domain.model.LLMEngineType
import com.oa.automation.domain.model.ReportTemplate
import com.oa.automation.domain.model.STTConfig
import com.oa.automation.domain.model.STTEngineType
import com.oa.automation.infrastructure.llm.OllamaEngine
import com.oa.automation.infrastructure.llm.AgentGatewayEngine
import com.oa.automation.infrastructure.stt.STTServiceClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings Screen UI State
 */
data class SettingsUiState(
    val appConfig: AppConfig = AppConfig.DEFAULT,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val isTestingSTT: Boolean = false,
    val isTestingLLM: Boolean = false,
    val isScanningSTT: Boolean = false,
    val isSwitchingSTT: Boolean = false,
    val discoveredServers: List<DiscoveredSTTServer> = emptyList(),
    val message: String? = null,
    val isLoggedOut: Boolean = false
)

/**
 * ViewModel for Settings Screen
 */
class SettingsViewModel(
    private val configDataStore: ConfigDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadConfig()
    }

    /**
     * Load configuration from DataStore
     */
    private fun loadConfig() {
        viewModelScope.launch {
            configDataStore.appConfigFlow.collect { config ->
                _uiState.value = _uiState.value.copy(
                    appConfig = config,
                    isLoading = false
                )
            }
        }
    }

    /**
     * Update STT engine type
     */
    fun updateSTTEngineType(engineType: STTEngineType) {
        val currentConfig = _uiState.value.appConfig.sttConfig
        val nextConfig = currentConfig.copy(
            engineType = engineType,
            localModel = engineType.defaultModel.ifBlank { currentConfig.localModel }
        )
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, isSwitchingSTT = true)
            try {
                configDataStore.updateSTTConfig(nextConfig)
                val switchResult = withContext(Dispatchers.IO) {
                    requestRemoteSttSwitch(nextConfig)
                }
                _uiState.value = _uiState.value.copy(
                    appConfig = _uiState.value.appConfig.copy(sttConfig = nextConfig),
                    isSaving = false,
                    isSwitchingSTT = false,
                    message = switchResult.fold(
                        onSuccess = { it },
                        onFailure = { "STT 配置已保存，PC 服务切换失败: ${it.message}" }
                    )
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    isSwitchingSTT = false,
                    message = "保存失败: ${e.message}"
                )
            }
        }
    }

    /**
     * Update STT local endpoint
     */
    fun updateSTTLocalEndpoint(endpoint: String) {
        updateSTTConfig(_uiState.value.appConfig.sttConfig.copy(localEndpoint = endpoint))
    }

    /**
     * Update STT local model
     */
    fun updateSTTLocalModel(model: String) {
        updateSTTConfig(_uiState.value.appConfig.sttConfig.copy(localModel = model))
    }

    fun updateSTTApiToken(apiToken: String?) {
        updateSTTConfig(_uiState.value.appConfig.sttConfig.copy(apiToken = apiToken))
    }

    /**
     * Update STT cloud endpoint
     */
    fun updateSTTCloudEndpoint(endpoint: String?) {
        updateSTTConfig(_uiState.value.appConfig.sttConfig.copy(cloudEndpoint = endpoint))
    }

    /**
     * Update STT cloud API key
     */
    fun updateSTTCloudApiKey(apiKey: String?) {
        updateSTTConfig(_uiState.value.appConfig.sttConfig.copy(cloudApiKey = apiKey))
    }

    /**
     * Update LLM engine type
     */
    fun updateLLMEngineType(engineType: LLMEngineType) {
        updateLLMConfig(_uiState.value.appConfig.llmConfig.copy(engineType = engineType))
    }

    fun updateAgentEndpoint(endpoint: String) {
        updateLLMConfig(_uiState.value.appConfig.llmConfig.copy(agentEndpoint = endpoint))
    }

    fun updateAgentAccessToken(token: String?) {
        updateLLMConfig(_uiState.value.appConfig.llmConfig.copy(agentAccessToken = token))
    }

    fun updateAgentProvider(provider: AgentProvider) {
        updateLLMConfig(_uiState.value.appConfig.llmConfig.copy(agentProvider = provider))
    }

    /**
     * Update LLM local endpoint
     */
    fun updateLLMLocalEndpoint(endpoint: String) {
        updateLLMConfig(_uiState.value.appConfig.llmConfig.copy(localEndpoint = endpoint))
    }

    /**
     * Update LLM local model
     */
    fun updateLLMLocalModel(model: String) {
        updateLLMConfig(_uiState.value.appConfig.llmConfig.copy(localModel = model))
    }

    /**
     * Update LLM cloud endpoint
     */
    fun updateLLMCloudEndpoint(endpoint: String?) {
        updateLLMConfig(_uiState.value.appConfig.llmConfig.copy(cloudEndpoint = endpoint))
    }

    /**
     * Update LLM cloud API key
     */
    fun updateLLMCloudApiKey(apiKey: String?) {
        updateLLMConfig(_uiState.value.appConfig.llmConfig.copy(cloudApiKey = apiKey))
    }

    /**
     * Update LLM cloud model
     */
    fun updateLLMCloudModel(model: String?) {
        updateLLMConfig(_uiState.value.appConfig.llmConfig.copy(cloudModel = model))
    }

    fun updateLLMCloudApiFormat(format: CloudApiFormat) {
        updateLLMConfig(_uiState.value.appConfig.llmConfig.copy(cloudApiFormat = format))
    }

    fun updateReportTemplate(template: ReportTemplate) {
        updateLLMConfig(_uiState.value.appConfig.llmConfig.copy(reportTemplate = template))
    }

    /**
     * Save STT config
     */
    private fun updateSTTConfig(config: STTConfig) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            try {
                configDataStore.updateSTTConfig(config)
                _uiState.value = _uiState.value.copy(
                    appConfig = _uiState.value.appConfig.copy(sttConfig = config),
                    isSaving = false,
                    message = "STT 配置已保存"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    message = "保存失败: ${e.message}"
                )
            }
        }
    }

    /**
     * Save LLM config
     */
    private fun updateLLMConfig(config: LLMConfig) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            try {
                configDataStore.updateLLMConfig(config)
                _uiState.value = _uiState.value.copy(
                    appConfig = _uiState.value.appConfig.copy(llmConfig = config),
                    isSaving = false,
                    message = "LLM 配置已保存"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    message = "保存失败: ${e.message}"
                )
            }
        }
    }

    /**
     * Reset to default configuration
     */
    fun resetToDefault() {
        viewModelScope.launch {
            configDataStore.resetToDefault()
            _uiState.value = _uiState.value.copy(message = "已重置为默认配置")
        }
    }

    /**
     * Clear message
     */
    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    /**
     * Logout - clear saved username
     */
    fun logout() {
        viewModelScope.launch {
            configDataStore.clearUsername()
            _uiState.value = _uiState.value.copy(isLoggedOut = true)
        }
    }

    /**
     * Scan for available STT servers
     */
    fun scanSTTServers() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isScanningSTT = true)
            try {
                val servers = withContext(Dispatchers.IO) {
                    STTServiceClient.scanForServers()
                }
                _uiState.value = _uiState.value.copy(
                    isScanningSTT = false,
                    discoveredServers = servers,
                    message = if (servers.isEmpty()) {
                        "未发现运行中的 STT 服务"
                    } else {
                        "发现 ${servers.size} 个 STT 服务"
                    }
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isScanningSTT = false,
                    message = "扫描失败: ${e.message}"
                )
            }
        }
    }

    /**
     * Apply discovered server configuration - auto-fills all STT fields
     */
    fun applyDiscoveredServer(server: DiscoveredSTTServer) {
        val engineType = when {
            server.engine.contains("sense", ignoreCase = true) -> STTEngineType.SENSE_VOICE
            server.engine.contains("whisper", ignoreCase = true) -> STTEngineType.FASTER_WHISPER
            server.port == 8888 -> STTEngineType.FASTER_WHISPER  // Port 8888 is typical for Faster-Whisper
            server.port == 8000 -> STTEngineType.SENSE_VOICE     // Port 8000 is typical for SenseVoice
            else -> _uiState.value.appConfig.sttConfig.engineType
        }
        val model = server.model.ifBlank { engineType.defaultModel }
        updateSTTConfig(
            _uiState.value.appConfig.sttConfig.copy(
                engineType = engineType,
                localEndpoint = server.endpoint,
                localModel = model
            )
        )
        _uiState.value = _uiState.value.copy(discoveredServers = emptyList())
    }

    /**
     * Clear discovered servers list
     */
    fun clearDiscoveredServers() {
        _uiState.value = _uiState.value.copy(discoveredServers = emptyList())
    }

    /**
     * Test STT connection
     */
    fun testSTTConnection() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isTestingSTT = true)
            try {
                val config = _uiState.value.appConfig.sttConfig
                val result = withContext(Dispatchers.IO) {
                    STTServiceClient.testConnection(config.localEndpoint, config.apiToken)
                }
                _uiState.value = _uiState.value.copy(
                    isTestingSTT = false,
                    message = result.fold(
                        onSuccess = { "STT 服务连接成功 ✓" },
                        onFailure = { "STT 服务连接失败: ${it.message}" }
                    )
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isTestingSTT = false,
                    message = "STT 连接失败: ${e.message}"
                )
            }
        }
    }

    /**
     * Test LLM connection
     */
    fun testLLMConnection() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isTestingLLM = true)
            try {
                val config = _uiState.value.appConfig.llmConfig
                val result = withContext(Dispatchers.IO) { testLlmByCurrentMode(config) }
                _uiState.value = _uiState.value.copy(
                    isTestingLLM = false,
                    message = result.fold(
                        onSuccess = { "LLM 服务连接成功 ✓" },
                        onFailure = { "LLM 服务连接失败: ${it.message}" }
                    )
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isTestingLLM = false,
                    message = "LLM 连接失败: ${e.message}"
                )
            }
        }
    }

    private fun testLlmByCurrentMode(config: LLMConfig): Result<Boolean> {
        return when (config.engineType) {
            LLMEngineType.AGENT_GATEWAY -> AgentGatewayEngine.testConnection(config)
            LLMEngineType.LOCAL_OLLAMA -> {
                val success = OllamaEngine.testConnection(config.localEndpoint)
                if (success) Result.success(true) else Result.failure(Exception("无法连接到 Ollama 服务"))
            }
            LLMEngineType.CLOUD_API -> testCloudLlm(config)
        }
    }

    private fun testCloudLlm(config: LLMConfig): Result<Boolean> {
        val endpoint = config.cloudEndpoint?.trim()?.removeSuffix("/")
        if (endpoint.isNullOrBlank()) {
            return Result.failure(Exception("云端 API 地址未配置"))
        }

        val apiKey = config.cloudApiKey?.trim().orEmpty()
        if (apiKey.isBlank()) {
            return Result.failure(Exception("云端 API Key 未配置"))
        }

        // 设置超时，避免网络请求卡住
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        try {
            val request = when (config.cloudApiFormat) {
                CloudApiFormat.OPENAI_COMPAT -> {
                    // 正确处理 endpoint URL：确保路径是 /v1/models
                    val modelsUrl = if (endpoint.endsWith("/v1")) {
                        "$endpoint/models"
                    } else {
                        "$endpoint/v1/models"
                    }
                    Request.Builder()
                        .url(modelsUrl)
                        .addHeader("Authorization", "Bearer $apiKey")
                        .get()
                        .build()
                }
                CloudApiFormat.CLAUDE_MESSAGES -> {
                    val model = config.cloudModel?.trim().orEmpty()
                    if (model.isBlank()) {
                        return Result.failure(Exception("Claude 模型名称未配置"))
                    }
                    val messagesUrl = if (endpoint.endsWith("/v1")) {
                        "$endpoint/messages"
                    } else {
                        "$endpoint/v1/messages"
                    }
                    Request.Builder()
                        .url(messagesUrl)
                        .addHeader("x-api-key", apiKey)
                        .addHeader("Authorization", "Bearer $apiKey")
                        .addHeader("anthropic-version", "2023-06-01")
                        .addHeader("content-type", "application/json")
                        .post(
                            """{"model":"$model","max_tokens":16,"messages":[{"role":"user","content":"ping"}]}"""
                                .toRequestBody("application/json".toMediaType())
                        )
                        .build()
                }
            }

            client.newCall(request).execute().use { response ->
                return when {
                    response.isSuccessful -> Result.success(true)
                    response.code == 401 -> Result.failure(Exception("API Key 无效或已过期 (HTTP 401)"))
                    response.code == 403 -> Result.failure(Exception("无权限访问该 API (HTTP 403)"))
                    response.code == 404 -> Result.failure(Exception("API 地址不存在 (HTTP 404)，请检查 URL 是否正确"))
                    response.code >= 500 -> Result.failure(Exception("服务器错误 (HTTP ${response.code})"))
                    else -> {
                        val errorBody = response.body?.string()?.take(200) ?: "未知错误"
                        Result.failure(Exception("请求失败 (HTTP ${response.code}): $errorBody"))
                    }
                }
            }
        } catch (e: java.net.UnknownHostException) {
            return Result.failure(Exception("无法解析主机名，请检查网络连接或 API 地址是否正确"))
        } catch (e: java.net.ConnectException) {
            return Result.failure(Exception("无法连接到服务器，请检查 API 地址是否正确"))
        } catch (e: java.net.SocketTimeoutException) {
            return Result.failure(Exception("连接超时，请检查网络连接"))
        } catch (e: Exception) {
            return Result.failure(Exception("连接失败: ${e.message}"))
        }
    }

    private fun requestRemoteSttSwitch(config: STTConfig): Result<String> {
        if (config.engineType == STTEngineType.CLOUD_ASR) {
            return Result.success("STT 已切换为云端 ASR")
        }

        val endpoint = config.localEndpoint.trim().trimEnd('/')
        if (endpoint.isBlank()) {
            return Result.failure(Exception("STT 服务地址为空"))
        }

        val engine = when (config.engineType) {
            STTEngineType.FASTER_WHISPER -> "faster-whisper"
            STTEngineType.SENSE_VOICE -> "sensevoice"
            STTEngineType.CLOUD_ASR -> return Result.success("STT 已切换为云端 ASR")
        }
        val model = config.localModel.ifBlank { config.engineType.defaultModel }
        val body = """{"engine":"$engine","model":"$model"}"""

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.MINUTES)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        return try {
            val requestBuilder = Request.Builder()
                .url("$endpoint/admin/stt/switch")
                .post(body.toRequestBody("application/json".toMediaType()))
            config.apiToken?.takeIf { it.isNotBlank() }?.let {
                requestBuilder.addHeader("Authorization", "Bearer $it")
            }
            val request = requestBuilder.build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    waitForRemoteSttReady(client, endpoint, engine, config.engineType.displayName)
                } else {
                    Result.failure(Exception("HTTP ${response.code}: ${response.body?.string()?.take(120).orEmpty()}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun waitForRemoteSttReady(
        client: OkHttpClient,
        endpoint: String,
        expectedEngine: String,
        displayName: String
    ): Result<String> {
        var lastError: String? = null
        repeat(60) { attempt ->
            if (attempt > 0) {
                Thread.sleep(2000)
            }
            try {
                val healthRequest = Request.Builder()
                    .url("$endpoint/health")
                    .get()
                    .build()
                client.newCall(healthRequest).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        lastError = "HTTP ${response.code}: ${text.take(120)}"
                        return@use
                    }
                    val json = JSONObject(text)
                    val currentEngine = json.optString("engine")
                    val model = json.optString("model")
                    val isLoaded = json.optBoolean("model_loaded", false)
                    val modelError = json.optString("model_error")
                    if (currentEngine == expectedEngine && isLoaded) {
                        return Result.success("STT 已成功切换为 $displayName，PC 服务已恢复")
                    }
                    if (currentEngine == expectedEngine && !isLoaded && modelError.isNotBlank()) {
                        return Result.failure(Exception("PC 服务已切到 $displayName / $model，但模型不可用: $modelError"))
                    }
                    lastError = "当前服务仍为 $currentEngine / $model"
                }
            } catch (e: Exception) {
                lastError = e.message
            }
        }
        return Result.failure(Exception("等待 PC 服务恢复超时${lastError?.let { ": $it" }.orEmpty()}"))
    }
}
