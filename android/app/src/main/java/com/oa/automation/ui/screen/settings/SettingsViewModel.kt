package com.oa.automation.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oa.automation.BuildConfig
import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.domain.model.AppConfig
import com.oa.automation.domain.model.AppThemeMode
import com.oa.automation.domain.model.AgentProvider
import com.oa.automation.domain.model.CloudApiFormat
import com.oa.automation.domain.model.ClaudeReasoningEffort
import com.oa.automation.domain.model.CodexReasoningEffort
import com.oa.automation.domain.model.DiscoveredSTTServer
import com.oa.automation.domain.model.LLMConfig
import com.oa.automation.domain.model.LLMEngineType
import com.oa.automation.domain.model.ReportTemplate
import com.oa.automation.domain.model.STTConfig
import com.oa.automation.domain.model.STTEngineType
import com.oa.automation.domain.model.TencentAsrBudgetPolicy
import com.oa.automation.domain.model.TencentAsrTier
import com.oa.automation.domain.model.serviceEndpointFor
import com.oa.automation.debug.DevelopmentDemoDataSeeder
import com.oa.automation.infrastructure.account.AccountSessionSynchronizer
import com.oa.automation.infrastructure.llm.OllamaEngine
import com.oa.automation.infrastructure.llm.AgentGatewayEngine
import com.oa.automation.infrastructure.stt.STTServiceClient
import com.oa.automation.infrastructure.stt.CloudSTTEngine
import com.oa.automation.infrastructure.stt.SttAuthorizationException
import com.oa.automation.infrastructure.update.AppUpdateCheck
import com.oa.automation.infrastructure.update.AppUpdateService
import com.oa.automation.infrastructure.update.AndroidAppUpdate
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    val isLoadingTencentAsrPolicy: Boolean = false,
    val tencentAsrPolicy: TencentAsrBudgetPolicy? = null,
    val tencentAsrPolicyError: String? = null,
    val discoveredServers: List<DiscoveredSTTServer> = emptyList(),
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val floatingBallEnabled: Boolean = false,
    val templateWorkflowReducedMotion: Boolean = false,
    val isCheckingUpdate: Boolean = false,
    val isDownloadingUpdate: Boolean = false,
    val updateProgress: Int? = null,
    val availableUpdate: AndroidAppUpdate? = null,
    val isUpdatingDemoData: Boolean = false,
    val message: String? = null
)

/**
 * ViewModel for Settings Screen
 */
class SettingsViewModel(
    private val configDataStore: ConfigDataStore,
    private val appUpdateService: AppUpdateService,
    private val accountSessionSynchronizer: AccountSessionSynchronizer,
    private val demoDataSeeder: DevelopmentDemoDataSeeder? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    private var tencentPolicyRefreshJob: Job? = null
    private var lastTencentPolicyConfigKey: String? = null

    init {
        loadConfig()
        viewModelScope.launch {
            configDataStore.appThemeModeFlow.collect { mode ->
                _uiState.value = _uiState.value.copy(themeMode = mode)
            }
        }
        viewModelScope.launch {
            configDataStore.floatingBallEnabledFlow.collect { enabled ->
                _uiState.value = _uiState.value.copy(floatingBallEnabled = enabled)
            }
        }
        viewModelScope.launch {
            configDataStore.templateWorkflowPreferencesFlow.collect { preferences ->
                _uiState.value = _uiState.value.copy(
                    templateWorkflowReducedMotion = preferences.reducedMotion
                )
            }
        }
    }

    fun updateThemeMode(mode: AppThemeMode) {
        viewModelScope.launch { configDataStore.updateAppThemeMode(mode) }
    }

    fun updateFloatingBallEnabled(enabled: Boolean) {
        viewModelScope.launch { configDataStore.updateFloatingBallEnabled(enabled) }
    }

    fun updateTemplateWorkflowReducedMotion(enabled: Boolean) {
        viewModelScope.launch { configDataStore.updateTemplateWorkflowReducedMotion(enabled) }
    }

    fun checkForAppUpdate() {
        if (_uiState.value.isCheckingUpdate) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isCheckingUpdate = true, message = null)
            appUpdateService.checkForUpdate().fold(
                onSuccess = { result ->
                    _uiState.value = _uiState.value.copy(
                        isCheckingUpdate = false,
                        availableUpdate = (result as? AppUpdateCheck.Available)?.update,
                        message = if (result is AppUpdateCheck.UpToDate) "已是最新版本" else null
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isCheckingUpdate = false,
                        message = "版本检查失败: ${error.message ?: "未知错误"}"
                    )
                }
            )
        }
    }

    fun downloadAndInstallUpdate() {
        val update = _uiState.value.availableUpdate ?: return
        if (!appUpdateService.canInstallPackages()) {
            appUpdateService.requestInstallPermission()
            _uiState.value = _uiState.value.copy(message = "请允许智悟本安装未知来源应用后重新点击更新")
            return
        }
        if (_uiState.value.isDownloadingUpdate) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDownloadingUpdate = true, updateProgress = 0, message = null)
            appUpdateService.download(update) { progress ->
                _uiState.value = _uiState.value.copy(updateProgress = progress)
            }.fold(
                onSuccess = { downloaded ->
                    _uiState.value = _uiState.value.copy(isDownloadingUpdate = false, updateProgress = 100)
                    appUpdateService.install(downloaded)
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isDownloadingUpdate = false,
                        updateProgress = null,
                        message = "安装包下载失败: ${error.message ?: "未知错误"}"
                    )
                }
            )
        }
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
                scheduleTencentAsrStatusRefresh(config.sttConfig)
            }
        }
    }

    private fun scheduleTencentAsrStatusRefresh(config: STTConfig) {
        val configKey = config.tencentStatusConfigKey()
        if (configKey == null) {
            tencentPolicyRefreshJob?.cancel()
            lastTencentPolicyConfigKey = null
            _uiState.value = _uiState.value.copy(
                isLoadingTencentAsrPolicy = false,
                tencentAsrPolicy = null,
                tencentAsrPolicyError = null
            )
            return
        }
        if (configKey == lastTencentPolicyConfigKey) return
        lastTencentPolicyConfigKey = configKey
        tencentPolicyRefreshJob?.cancel()
        tencentPolicyRefreshJob = viewModelScope.launch {
            delay(600)
            loadTencentAsrStatus(config)
        }
    }

    fun refreshTencentAsrStatus() {
        val config = _uiState.value.appConfig.sttConfig
        val configKey = config.tencentStatusConfigKey() ?: return
        lastTencentPolicyConfigKey = configKey
        tencentPolicyRefreshJob?.cancel()
        tencentPolicyRefreshJob = viewModelScope.launch {
            loadTencentAsrStatus(config)
        }
    }

    private suspend fun loadTencentAsrStatus(config: STTConfig) {
        _uiState.value = _uiState.value.copy(
            isLoadingTencentAsrPolicy = true,
            tencentAsrPolicyError = null
        )
        val status = withContext(Dispatchers.IO) {
            loadTencentAsrStatusWithSessionRetry(config)
        }
        if (_uiState.value.appConfig.sttConfig.tencentStatusConfigKey() != status.configKey) return
        lastTencentPolicyConfigKey = status.configKey
        _uiState.value = _uiState.value.copy(
            isLoadingTencentAsrPolicy = false,
            tencentAsrPolicy = status.policy.getOrNull(),
            tencentAsrPolicyError = status.policy.exceptionOrNull()?.message
                ?: if (status.policy.isFailure) "智悟增强云模型状态查询失败" else null
        )
    }

    private suspend fun loadTencentAsrStatusWithSessionRetry(
        initialConfig: STTConfig
    ): TencentAsrStatusResult {
        var config = initialConfig
        var result = queryTencentAsrStatus(config)
        val authorizationFailed = result.policy.hasSttAuthorizationFailure()
        if (!authorizationFailed || !configDataStore.sttUsesAccountTokenFlow.first()) {
            return result
        }

        val refresh = accountSessionSynchronizer.refresh()
        if (refresh.isFailure) {
            val message = refresh.exceptionOrNull()?.message.orEmpty()
            val failure = IllegalStateException(
                if (message.isBlank()) "登录会话续期失败，请重新登录" else "登录会话续期失败：$message"
            )
            return result.replaceAuthorizationFailures(failure)
        }

        config = configDataStore.appConfigFlow.first().sttConfig
        result = queryTencentAsrStatus(config)
        return result
    }

    private fun queryTencentAsrStatus(config: STTConfig) = TencentAsrStatusResult(
        configKey = config.tencentStatusConfigKey(),
        policy = STTServiceClient.fetchTencentAsrPolicy(
            config.serviceEndpointFor(STTEngineType.TENCENT_HYBRID),
            config.apiToken
        )
    )

    /**
     * Update STT engine type
     */
    fun updateSTTEngineType(engineType: STTEngineType) {
        val currentConfig = _uiState.value.appConfig.sttConfig
        if (engineType == currentConfig.engineType) {
            _uiState.value = _uiState.value.copy(
                message = "当前已是 ${engineType.displayName}，无需重复切换"
            )
            return
        }
        val usesManagedTencent = engineType == STTEngineType.TENCENT_HYBRID
        val nextConfig = currentConfig.copy(
            engineType = engineType,
            localModel = engineType.defaultModel.ifBlank { currentConfig.localModel },
            cloudEndpoint = if (usesManagedTencent) {
                currentConfig.cloudEndpoint ?: STTConfig.DEFAULT_CLOUD_ENDPOINT
            } else {
                currentConfig.cloudEndpoint
            },
            cloudApiKey = if (usesManagedTencent) null else currentConfig.cloudApiKey,
            cloudModel = if (usesManagedTencent) {
                currentConfig.tencentAsrTier.cloudModel
            } else {
                currentConfig.cloudModel
            }
        )
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, isSwitchingSTT = true)
            try {
                val switchResult = withContext(Dispatchers.IO) {
                    requestRemoteSttSwitch(nextConfig)
                }
                val successMessage = switchResult.getOrElse { throw it }
                configDataStore.updateSTTConfig(nextConfig)
                _uiState.value = _uiState.value.copy(
                    appConfig = _uiState.value.appConfig.copy(sttConfig = nextConfig),
                    isSaving = false,
                    isSwitchingSTT = false,
                    message = successMessage
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    isSwitchingSTT = false,
                    message = "STT 切换失败，已保留原配置: ${e.message}"
                )
            }
        }
    }

    /**
     * Update STT local endpoint
     */
    fun updateSTTLocalEndpoint(endpoint: String) {
        val config = _uiState.value.appConfig.sttConfig
        updateSTTConfig(config.copy(localEndpoint = endpoint))
    }

    /**
     * Update STT local model
     */
    fun updateSTTLocalModel(model: String) {
        updateSTTConfig(_uiState.value.appConfig.sttConfig.copy(localModel = model))
    }

    fun updateSTTAudioEnhancement(enabled: Boolean) {
        updateSTTConfig(
            _uiState.value.appConfig.sttConfig.copy(audioEnhancementEnabled = enabled)
        )
    }

    fun updateSTTSpeakerDiarization(enabled: Boolean) {
        updateSTTConfig(
            _uiState.value.appConfig.sttConfig.copy(speakerDiarizationEnabled = enabled)
        )
    }

    fun updateSTTApiToken(apiToken: String?) {
        updateSTTConfig(
            _uiState.value.appConfig.sttConfig.copy(apiToken = apiToken),
            manualApiToken = true
        )
    }

    fun updateSTTCloudModel(model: String) {
        updateSTTConfig(_uiState.value.appConfig.sttConfig.copy(cloudModel = model))
    }

    fun updateTencentAsrTier(tier: TencentAsrTier) {
        val currentConfig = _uiState.value.appConfig.sttConfig
        if (currentConfig.tencentAsrTier == tier && currentConfig.cloudModel == tier.cloudModel) return
        updateSTTConfig(
            currentConfig.copy(
                tencentAsrTier = tier,
                cloudModel = tier.cloudModel,
                cloudApiKey = null
            )
        )
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

    fun updateCodexReasoningEffort(effort: CodexReasoningEffort) {
        updateLLMConfig(_uiState.value.appConfig.llmConfig.copy(codexReasoningEffort = effort))
    }

    fun updateClaudeReasoningEffort(effort: ClaudeReasoningEffort) {
        updateLLMConfig(_uiState.value.appConfig.llmConfig.copy(claudeReasoningEffort = effort))
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
    private fun updateSTTConfig(config: STTConfig, manualApiToken: Boolean = false) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            try {
                configDataStore.updateSTTConfig(config)
                if (manualApiToken) configDataStore.updateManualSttApiToken(config.apiToken)
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

    fun seedDemoData() {
        val seeder = demoDataSeeder ?: return
        if (_uiState.value.isUpdatingDemoData) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isUpdatingDemoData = true, message = null)
            seeder.seed().fold(
                onSuccess = { created ->
                    _uiState.value = _uiState.value.copy(
                        isUpdatingDemoData = false,
                        message = if (created == 0) "演示数据已存在（未重复创建）" else "已注入 $created 组演示数据"
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(isUpdatingDemoData = false, message = "演示数据注入失败: ${error.message ?: "未知错误"}")
                }
            )
        }
    }

    fun clearDemoData() {
        val seeder = demoDataSeeder ?: return
        if (_uiState.value.isUpdatingDemoData) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isUpdatingDemoData = true, message = null)
            seeder.clear().fold(
                onSuccess = { removed ->
                    _uiState.value = _uiState.value.copy(
                        isUpdatingDemoData = false,
                        message = if (removed == 0) "没有需要清理的演示数据" else "已清理 $removed 组演示数据"
                    )
                },
                onFailure = { error -> _uiState.value = _uiState.value.copy(isUpdatingDemoData = false, message = "演示数据清理失败: ${error.message ?: "未知错误"}") }
            )
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
            server.engine.contains("whisper", ignoreCase = true) -> STTEngineType.FASTER_WHISPER
            server.port == 8888 -> STTEngineType.FASTER_WHISPER  // Port 8888 is typical for Faster-Whisper
            else -> _uiState.value.appConfig.sttConfig.engineType
        }
        val model = server.model
            .takeUnless { it.equals("small", ignoreCase = true) }
            ?.takeIf { it.isNotBlank() }
            ?: engineType.defaultModel
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
                    when (config.engineType) {
                        STTEngineType.TENCENT_HYBRID -> CloudSTTEngine.testHybridConnection(config)
                        else -> STTServiceClient.testConnection(config.localEndpoint, config.apiToken)
                    }
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
        if (config.engineType == STTEngineType.TENCENT_HYBRID) {
            return Result.success("STT 已成功切换为 ${config.engineType.displayName}")
        }
        if (!BuildConfig.STT_REMOTE_SWITCH_ENABLED) {
            return Result.success("STT 已成功切换为 ${config.engineType.displayName}")
        }

        val endpoint = config.localEndpoint.trim().trimEnd('/')
        if (endpoint.isBlank()) {
            return Result.failure(Exception("STT 服务地址为空"))
        }

        val engine = when (config.engineType) {
            STTEngineType.FASTER_WHISPER -> "faster-whisper"
            STTEngineType.TENCENT_HYBRID -> return Result.success("已切换为智悟增强云模型")
        }
        val model = config.localModel.ifBlank { config.engineType.defaultModel }
        val body = JSONObject()
            .put("engine", engine)
            .put("model", model)
            .toString()

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(BuildConfig.STT_SWITCH_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
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
        val attempts = (BuildConfig.STT_SWITCH_TIMEOUT_SECONDS / 2).coerceIn(1, 8)
        repeat(attempts) { attempt ->
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

private fun STTConfig.tencentStatusConfigKey(): String? {
    if (engineType != STTEngineType.TENCENT_HYBRID) return null
    val endpoint = serviceEndpointFor(STTEngineType.TENCENT_HYBRID).trimEnd('/')
    val token = apiToken?.trim().orEmpty()
    if (endpoint.isBlank() || token.isBlank()) return null
    return "$endpoint|${token.hashCode()}"
}

private data class TencentAsrStatusResult(
    val configKey: String?,
    val policy: Result<TencentAsrBudgetPolicy>
) {
    fun replaceAuthorizationFailures(failure: Throwable) = copy(
        policy = if (policy.hasSttAuthorizationFailure()) Result.failure(failure) else policy
    )
}

private fun Result<*>.hasSttAuthorizationFailure(): Boolean =
    exceptionOrNull() is SttAuthorizationException
