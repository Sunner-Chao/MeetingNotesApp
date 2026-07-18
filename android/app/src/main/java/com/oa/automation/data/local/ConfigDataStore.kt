package com.oa.automation.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.oa.automation.BuildConfig
import com.oa.automation.domain.model.AppConfig
import com.oa.automation.domain.model.AgentProvider
import com.oa.automation.domain.model.CloudApiFormat
import com.oa.automation.domain.model.LLMConfig
import com.oa.automation.domain.model.LLMEngineType
import com.oa.automation.domain.model.PresetReportTemplate
import com.oa.automation.domain.model.ReportTemplate
import com.oa.automation.domain.model.ReportTemplateConfig
import com.oa.automation.domain.model.STTConfig
import com.oa.automation.domain.model.STTEngineType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "oa_automation_settings")

/**
 * DataStore wrapper for persisting app configuration
 */
class ConfigDataStore(private val context: Context) {

    private val gson = Gson()
    private val defaultClaudeEndpoint = BuildConfig.DEFAULT_CLAUDE_BASE_URL.takeIf { it.isNotBlank() }
    private val defaultClaudeApiKey = BuildConfig.DEFAULT_CLAUDE_API_KEY.takeIf { it.isNotBlank() }
    private val defaultClaudeModel = BuildConfig.DEFAULT_CLAUDE_MODEL.takeIf { it.isNotBlank() }
    private val hasClaudeDefaults =
        defaultClaudeEndpoint != null && defaultClaudeApiKey != null && defaultClaudeModel != null

    // Default LLM cloud settings from BuildConfig
    private val defaultLlmCloudEndpoint = BuildConfig.DEFAULT_LLM_CLOUD_ENDPOINT.takeIf { it.isNotBlank() }
    private val defaultLlmCloudApiKey = BuildConfig.DEFAULT_LLM_CLOUD_API_KEY.takeIf { it.isNotBlank() }
    private val defaultLlmCloudModel = BuildConfig.DEFAULT_LLM_CLOUD_MODEL.takeIf { it.isNotBlank() }
    // 常见 STT 服务本地端口
    private val commonSttPorts = listOf(8888, 8000, 8001, 8002, 8889, 8890)

    init {
        CoroutineScope(Dispatchers.IO).launch {
            migrateDefaultSttEndpoint()
        }
    }

    companion object {
        // 公网 STT 服务地址
        const val PUBLIC_STT_ENDPOINT = STTConfig.DEFAULT_LOCAL_ENDPOINT
        // 默认本地 endpoint（作为备选）
        const val LOCAL_STT_ENDPOINT_FALLBACK = "http://localhost:8888"
        // STT Config Keys
        private val STT_ENGINE_TYPE = stringPreferencesKey("stt_engine_type")
        private val STT_LOCAL_ENDPOINT = stringPreferencesKey("stt_local_endpoint")
        private val STT_LOCAL_MODEL = stringPreferencesKey("stt_local_model")
        private val STT_API_TOKEN = stringPreferencesKey("stt_api_token")
        private val STT_CLOUD_ENDPOINT = stringPreferencesKey("stt_cloud_endpoint")
        private val STT_CLOUD_API_KEY = stringPreferencesKey("stt_cloud_api_key")

        // LLM Config Keys
        private val LLM_ENGINE_TYPE = stringPreferencesKey("llm_engine_type")
        private val LLM_AGENT_ENDPOINT = stringPreferencesKey("llm_agent_endpoint")
        private val LLM_AGENT_ACCESS_TOKEN = stringPreferencesKey("llm_agent_access_token")
        private val LLM_AGENT_PROVIDER = stringPreferencesKey("llm_agent_provider")
        private val LLM_LOCAL_ENDPOINT = stringPreferencesKey("llm_local_endpoint")
        private val LLM_LOCAL_MODEL = stringPreferencesKey("llm_local_model")
        private val LLM_CLOUD_ENDPOINT = stringPreferencesKey("llm_cloud_endpoint")
        private val LLM_CLOUD_API_KEY = stringPreferencesKey("llm_cloud_api_key")
        private val LLM_CLOUD_MODEL = stringPreferencesKey("llm_cloud_model")
        private val LLM_CLOUD_API_FORMAT = stringPreferencesKey("llm_cloud_api_format")
        private val LLM_REPORT_TEMPLATE = stringPreferencesKey("llm_report_template")
        private val REPORT_TEMPLATE_NAME = stringPreferencesKey("report_template_name")
        private val REPORT_TEMPLATE_CONTENT = stringPreferencesKey("report_template_content")
        private val REPORT_TEMPLATE_IS_CUSTOM = stringPreferencesKey("report_template_is_custom")
        private val STT_DEFAULT_ENDPOINT_MIGRATED = stringPreferencesKey("stt_default_endpoint_migrated")
        private val DEFAULT_PROFILE_VERSION = stringPreferencesKey("default_profile_version")
        private val LOGGED_IN_USERNAME = stringPreferencesKey("logged_in_username")

        private val PRESET_TEMPLATE_FILES = listOf(
            "孔爵团队版表格会议纪要.md",
            "项目管理纪要.md",
            "讲座论坛纪要.md",
            "政策解读纪要.md",
            "技术交流纪要.md",
            "学术报告纪要.md"
        )

        // VIP专用模板
        private val VIP_TEMPLATE_FILES = listOf(
            "工程行业施工日志.md",
            "建筑专业设计日志.md"
        )
    }

    /**
     * Get app configuration as Flow
     */
    val appConfigFlow: Flow<AppConfig> = context.dataStore.data.map { preferences ->
        val defaultTemplate = loadPresetTemplates().firstOrNull()
        val savedTemplateName = preferences[REPORT_TEMPLATE_NAME]

        // Check if saved template is a VIP template or a removed preset.
        val vipTemplateNames = VIP_TEMPLATE_FILES.map { it.removeSuffix(".md") }
        val isVipTemplate = savedTemplateName in vipTemplateNames
        val isRemovedTemplate = savedTemplateName == "通用会议纪要"

        val templateName = when {
            savedTemplateName.isNullOrBlank() -> defaultTemplate?.name ?: ReportTemplateConfig().selectedName
            isVipTemplate || isRemovedTemplate -> defaultTemplate?.name ?: ReportTemplateConfig().selectedName
            else -> savedTemplateName
        }

        val templateContent = when {
            isVipTemplate || isRemovedTemplate -> defaultTemplate?.content ?: ""
            preferences[REPORT_TEMPLATE_CONTENT].isNullOrBlank() -> defaultTemplate?.content ?: ""
            else -> preferences[REPORT_TEMPLATE_CONTENT] ?: ""
        }

        AppConfig(
            sttConfig = STTConfig(
                engineType = preferences[STT_ENGINE_TYPE]?.let {
                    runCatching { STTEngineType.valueOf(it) }.getOrNull()
                } ?: STTEngineType.FASTER_WHISPER,
                localEndpoint = preferences[STT_LOCAL_ENDPOINT]
                    ?.takeUnless { it == STTConfig.LEGACY_LOCAL_ENDPOINT || it == STTConfig.PREVIOUS_PUBLIC_ENDPOINT }
                    ?: STTConfig.DEFAULT_LOCAL_ENDPOINT,
                localModel = preferences[STT_LOCAL_MODEL] ?: BuildConfig.DEFAULT_STT_MODEL,
                apiToken = preferences[STT_API_TOKEN]
                    ?: BuildConfig.DEFAULT_STT_TRIAL_TOKEN.takeIf { it.isNotBlank() },
                cloudEndpoint = preferences[STT_CLOUD_ENDPOINT],
                cloudApiKey = preferences[STT_CLOUD_API_KEY]
            ),
            llmConfig = LLMConfig(
                engineType = preferences[LLM_ENGINE_TYPE]?.let {
                    runCatching { LLMEngineType.valueOf(it) }.getOrNull()
                } ?: LLMEngineType.AGENT_GATEWAY,
                agentEndpoint = preferences[LLM_AGENT_ENDPOINT] ?: BuildConfig.DEFAULT_AGENT_ENDPOINT,
                agentAccessToken = preferences[LLM_AGENT_ACCESS_TOKEN],
                agentProvider = preferences[LLM_AGENT_PROVIDER]?.let {
                    runCatching { AgentProvider.valueOf(it) }.getOrNull()
                } ?: AgentProvider.CODEX_CLI,
                localEndpoint = preferences[LLM_LOCAL_ENDPOINT] ?: "http://localhost:11434",
                localModel = preferences[LLM_LOCAL_MODEL] ?: "qwen2.5:7b",
                cloudEndpoint = preferences[LLM_CLOUD_ENDPOINT] ?: defaultLlmCloudEndpoint ?: defaultClaudeEndpoint,
                cloudApiKey = preferences[LLM_CLOUD_API_KEY] ?: defaultLlmCloudApiKey ?: defaultClaudeApiKey,
                cloudModel = preferences[LLM_CLOUD_MODEL] ?: defaultLlmCloudModel ?: defaultClaudeModel,
                cloudApiFormat = preferences[LLM_CLOUD_API_FORMAT]?.let {
                    runCatching { CloudApiFormat.valueOf(it) }.getOrNull()
                } ?: CloudApiFormat.CLAUDE_MESSAGES,
                reportTemplate = preferences[LLM_REPORT_TEMPLATE]?.let {
                    runCatching { ReportTemplate.valueOf(it) }.getOrNull()
                } ?: ReportTemplate.STANDARD
            ),
            reportTemplateConfig = ReportTemplateConfig(
                selectedName = templateName,
                content = templateContent,
                isCustom = preferences[REPORT_TEMPLATE_IS_CUSTOM]?.toBooleanStrictOrNull() ?: false
            )
        )
    }

    fun loadPresetTemplates(): List<PresetReportTemplate> {
        return PRESET_TEMPLATE_FILES.mapNotNull { fileName ->
            runCatching {
                context.assets.open(fileName).bufferedReader(Charsets.UTF_8).use { reader ->
                    PresetReportTemplate(
                        name = fileName.removeSuffix(".md"),
                        content = reader.readText()
                    )
                }
            }.getOrNull()
        }
    }

    /**
     * 加载VIP专用模板
     */
    fun loadVipTemplates(): List<PresetReportTemplate> {
        return VIP_TEMPLATE_FILES.mapNotNull { fileName ->
            runCatching {
                context.assets.open(fileName).bufferedReader(Charsets.UTF_8).use { reader ->
                    PresetReportTemplate(
                        name = fileName.removeSuffix(".md"),
                        content = reader.readText()
                    )
                }
            }.getOrNull()
        }
    }

    /**
     * 检测并持久化可用的 STT 服务地址：优先公网花生壳，失败则尝试内网各端口
     * 检测结果会保存到 DataStore，下次启动直接使用
     */
    suspend fun detectAndPersistSttEndpoint() {
        val availableEndpoint = detectAvailableSttEndpoint()
        context.dataStore.edit { preferences ->
            preferences[STT_LOCAL_ENDPOINT] = availableEndpoint
        }
    }

    /**
     * 获取已保存的 STT endpoint（同步读取）
     */
    fun getSavedSttEndpoint(): String? {
        return try {
            val prefs = context.getSharedPreferences("oa_automation_settings", android.content.Context.MODE_PRIVATE)
            prefs.getString("stt_local_endpoint", null)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 检测可用的 STT 服务地址。
     * 优先级：用户保存的地址 > 公网服务 > 内网自动检测
     */
    suspend fun detectAvailableSttEndpoint(): String = withContext(Dispatchers.IO) {
        // 第一优先级：用户已在设置页面保存了自定义地址，直接用
        val savedEndpoint = getSavedSttEndpointFromDataStore()
        if (savedEndpoint != null) {
            android.util.Log.i("ConfigDataStore", "STT: 使用用户保存的地址 -> $savedEndpoint")
            return@withContext savedEndpoint
        }

        // 第二优先级：公网 STT 服务
        android.util.Log.i("ConfigDataStore", "STT: 尝试公网 -> $PUBLIC_STT_ENDPOINT")
        if (isEndpointReachable(PUBLIC_STT_ENDPOINT)) {
            android.util.Log.i("ConfigDataStore", "STT: 公网可用")
            return@withContext PUBLIC_STT_ENDPOINT
        }

        // 第三优先级：内网自动检测
        val localIp = getLocalIpAddress()
        android.util.Log.i("ConfigDataStore", "STT: 检测本机内网 IP = $localIp")

        if (localIp != null) {
            for (port in commonSttPorts) {
                val endpoint = "http://$localIp:$port"
                android.util.Log.i("ConfigDataStore", "STT: 尝试内网 -> $endpoint")
                if (isEndpointReachable(endpoint)) {
                    android.util.Log.i("ConfigDataStore", "STT: 内网可用 -> $endpoint")
                    return@withContext endpoint
                }
            }
            android.util.Log.w("ConfigDataStore", "STT: 内网所有端口均不可达")
        } else {
            android.util.Log.w("ConfigDataStore", "STT: 未检测到内网 IP（可能在 VPN/移动网络）")
        }

        // 兜底：公网地址
        android.util.Log.w("ConfigDataStore", "STT: 所有地址均不可达，使用公网兜底")
        return@withContext PUBLIC_STT_ENDPOINT
    }

    /**
     * 从 DataStore 读取用户手动保存的 STT endpoint
     */
    private suspend fun getSavedSttEndpointFromDataStore(): String? = withContext(Dispatchers.IO) {
        try {
            val prefs = context.dataStore.data.first()
            prefs[STT_LOCAL_ENDPOINT]?.takeIf { it.isNotBlank() && it != LOCAL_STT_ENDPOINT_FALLBACK }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 检测地址是否可达（仅做 HTTP HEAD 请求，超时 3s）
     */
    private fun isEndpointReachable(endpoint: String): Boolean {
        return try {
            val url = URL("${endpoint.removeSuffix("/")}/health")
            val connection = url.openConnection() as? HttpURLConnection
            connection?.let {
                it.requestMethod = "HEAD"
                it.connectTimeout = 3000
                it.readTimeout = 3000
                try {
                    it.connect()
                    val code = it.responseCode
                    (code in 200..499)
                } finally {
                    it.disconnect()
                }
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 获取本机在内网的 IP 地址
     */
    private fun getLocalIpAddress(): String? {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || networkInterface.isVirtual) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        val ip = address.hostAddress ?: continue
                        // 私有 IP 范围: 192.168.x.x, 10.x.x.x, 172.16-31.x.x
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") ||
                            ip.matches(Regex("^172\\.(1[6-9]|2\\d|3[0-1])\\.\\d+\\.\\d+$"))) {
                            return ip
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun migrateDefaultSttEndpoint() {
        val defaultReportTemplate = loadPresetTemplates().firstOrNull()
        context.dataStore.edit { preferences ->
            val profileVersion = preferences[DEFAULT_PROFILE_VERSION]?.toIntOrNull() ?: 0
            if (profileVersion < 2) {
                val savedEndpoint = preferences[STT_LOCAL_ENDPOINT]
                if (savedEndpoint.isNullOrBlank() ||
                    savedEndpoint == STTConfig.LEGACY_LOCAL_ENDPOINT ||
                    savedEndpoint == STTConfig.PREVIOUS_PUBLIC_ENDPOINT
                ) {
                    preferences[STT_LOCAL_ENDPOINT] = STTConfig.DEFAULT_LOCAL_ENDPOINT
                    preferences[STT_ENGINE_TYPE] = STTEngineType.FASTER_WHISPER.name
                    preferences[STT_LOCAL_MODEL] = BuildConfig.DEFAULT_STT_MODEL
                    if (preferences[STT_API_TOKEN].isNullOrBlank()) {
                        preferences[STT_API_TOKEN] = BuildConfig.DEFAULT_STT_TRIAL_TOKEN
                    }
                }
                preferences[STT_DEFAULT_ENDPOINT_MIGRATED] = true.toString()
            }
            if (profileVersion < 3) {
                val savedEngine = preferences[LLM_ENGINE_TYPE]
                val savedCloudEndpoint = preferences[LLM_CLOUD_ENDPOINT]
                val savedCloudKey = preferences[LLM_CLOUD_API_KEY]
                val hasLegacyBuiltInCloudProfile =
                    savedEngine == LLMEngineType.CLOUD_API.name &&
                        (savedCloudEndpoint.isNullOrBlank() || savedCloudEndpoint == BuildConfig.DEFAULT_LLM_CLOUD_ENDPOINT) &&
                        savedCloudKey.isNullOrBlank()
                if (savedEngine.isNullOrBlank() || hasLegacyBuiltInCloudProfile) {
                    preferences[LLM_ENGINE_TYPE] = LLMEngineType.AGENT_GATEWAY.name
                    preferences[LLM_AGENT_ENDPOINT] = BuildConfig.DEFAULT_AGENT_ENDPOINT
                    preferences[LLM_AGENT_PROVIDER] = AgentProvider.CODEX_CLI.name
                }
            }
            if (profileVersion < 4 && preferences[REPORT_TEMPLATE_NAME] == "通用会议纪要") {
                if (defaultReportTemplate == null) {
                    preferences.remove(REPORT_TEMPLATE_NAME)
                    preferences.remove(REPORT_TEMPLATE_CONTENT)
                } else {
                    preferences[REPORT_TEMPLATE_NAME] = defaultReportTemplate.name
                    preferences[REPORT_TEMPLATE_CONTENT] = defaultReportTemplate.content
                }
                preferences[REPORT_TEMPLATE_IS_CUSTOM] = false.toString()
            }
            preferences[DEFAULT_PROFILE_VERSION] = "4"
        }
    }

    /**
     * Update STT configuration
     */
    suspend fun updateSTTConfig(config: STTConfig) {
        context.dataStore.edit { preferences ->
            preferences[STT_ENGINE_TYPE] = config.engineType.name
            preferences[STT_LOCAL_ENDPOINT] = config.localEndpoint
            preferences[STT_LOCAL_MODEL] = config.localModel
            config.apiToken?.takeIf { it.isNotBlank() }
                ?.let { preferences[STT_API_TOKEN] = it }
                ?: preferences.remove(STT_API_TOKEN)
            config.cloudEndpoint?.takeIf { it.isNotBlank() }
                ?.let { preferences[STT_CLOUD_ENDPOINT] = it }
                ?: preferences.remove(STT_CLOUD_ENDPOINT)
            config.cloudApiKey?.takeIf { it.isNotBlank() }
                ?.let { preferences[STT_CLOUD_API_KEY] = it }
                ?: preferences.remove(STT_CLOUD_API_KEY)
        }
    }

    /**
     * Update LLM configuration
     */
    suspend fun updateLLMConfig(config: LLMConfig) {
        context.dataStore.edit { preferences ->
            preferences[LLM_ENGINE_TYPE] = config.engineType.name
            preferences[LLM_AGENT_ENDPOINT] = config.agentEndpoint
            config.agentAccessToken?.takeIf { it.isNotBlank() }
                ?.let { preferences[LLM_AGENT_ACCESS_TOKEN] = it }
                ?: preferences.remove(LLM_AGENT_ACCESS_TOKEN)
            preferences[LLM_AGENT_PROVIDER] = config.agentProvider.name
            preferences[LLM_LOCAL_ENDPOINT] = config.localEndpoint
            preferences[LLM_LOCAL_MODEL] = config.localModel
            config.cloudEndpoint?.takeIf { it.isNotBlank() }
                ?.let { preferences[LLM_CLOUD_ENDPOINT] = it }
                ?: preferences.remove(LLM_CLOUD_ENDPOINT)
            config.cloudApiKey?.takeIf { it.isNotBlank() }
                ?.let { preferences[LLM_CLOUD_API_KEY] = it }
                ?: preferences.remove(LLM_CLOUD_API_KEY)
            config.cloudModel?.takeIf { it.isNotBlank() }
                ?.let { preferences[LLM_CLOUD_MODEL] = it }
                ?: preferences.remove(LLM_CLOUD_MODEL)
            preferences[LLM_CLOUD_API_FORMAT] = config.cloudApiFormat.name
            preferences[LLM_REPORT_TEMPLATE] = config.reportTemplate.name
        }
    }

    suspend fun updateReportTemplate(config: ReportTemplateConfig) {
        context.dataStore.edit { preferences ->
            preferences[REPORT_TEMPLATE_NAME] = config.selectedName
            preferences[REPORT_TEMPLATE_CONTENT] = config.content
            preferences[REPORT_TEMPLATE_IS_CUSTOM] = config.isCustom.toString()
        }
    }

    suspend fun resetReportTemplate() {
        val defaultTemplate = loadPresetTemplates().firstOrNull()
        context.dataStore.edit { preferences ->
            if (defaultTemplate == null) {
                preferences.remove(REPORT_TEMPLATE_NAME)
                preferences.remove(REPORT_TEMPLATE_CONTENT)
            } else {
                preferences[REPORT_TEMPLATE_NAME] = defaultTemplate.name
                preferences[REPORT_TEMPLATE_CONTENT] = defaultTemplate.content
            }
            preferences[REPORT_TEMPLATE_IS_CUSTOM] = false.toString()
        }
    }

    /**
     * Update complete app configuration
     */
    suspend fun updateAppConfig(config: AppConfig) {
        updateSTTConfig(config.sttConfig)
        updateLLMConfig(config.llmConfig)
    }

    /**
     * Reset to default configuration (preserves login state)
     */
    suspend fun resetToDefault() {
        context.dataStore.edit { preferences ->
            val username = preferences[LOGGED_IN_USERNAME]
            preferences.clear()
            // Preserve login state
            if (username != null) {
                preferences[LOGGED_IN_USERNAME] = username
            }
        }
    }

    /**
     * Save logged-in username
     */
    suspend fun saveUsername(username: String) {
        context.dataStore.edit { preferences ->
            preferences[LOGGED_IN_USERNAME] = username
        }
    }

    /**
     * Get saved username as Flow
     */
    val usernameFlow: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[LOGGED_IN_USERNAME]
    }

    /**
     * Clear saved username (logout)
     */
    suspend fun clearUsername() {
        context.dataStore.edit { preferences ->
            preferences.remove(LOGGED_IN_USERNAME)
        }
    }
}
