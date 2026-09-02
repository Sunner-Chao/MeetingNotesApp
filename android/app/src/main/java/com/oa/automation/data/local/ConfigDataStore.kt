package com.oa.automation.data.local

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.oa.automation.BuildConfig
import com.oa.automation.domain.model.AppConfig
import com.oa.automation.domain.model.AppThemeMode
import com.oa.automation.domain.model.AgentProvider
import com.oa.automation.domain.model.AccountProfile
import com.oa.automation.domain.model.AccountSessionCredentials
import com.oa.automation.domain.model.AuthSession
import com.oa.automation.domain.model.CloudApiFormat
import com.oa.automation.domain.model.ClaudeReasoningEffort
import com.oa.automation.domain.model.CodexReasoningEffort
import com.oa.automation.domain.model.LLMConfig
import com.oa.automation.domain.model.LLMEngineType
import com.oa.automation.domain.model.PresetReportTemplate
import com.oa.automation.domain.model.ReportTemplate
import com.oa.automation.domain.model.ReportTemplateConfig
import com.oa.automation.domain.model.STTConfig
import com.oa.automation.domain.model.STTEngineType
import com.oa.automation.domain.model.STTLanguage
import com.oa.automation.domain.model.TencentAsrTier
import com.oa.automation.domain.model.TemplateWorkflowPreferences
import com.oa.automation.domain.model.isDevelopmentOnlySttEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "oa_automation_settings")

private data class ReportTemplateAsset(
    val name: String,
    val fileName: String,
    val subtitle: String
)

internal fun resolveAgentGatewayEndpoint(
    savedEndpoint: String?,
    accountEndpoint: String?,
    defaultEndpoint: String
): String {
    val saved = savedEndpoint?.trim()?.trimEnd('/').orEmpty()
    val accountGateway = accountEndpoint
        ?.trim()
        ?.trimEnd('/')
        ?.takeIf { it.isNotBlank() }
        ?.let { "$it/agent" }
    val fallback = defaultEndpoint.trim().trimEnd('/')

    return when {
        saved.isBlank() -> accountGateway ?: fallback
        saved.isLoopbackUrl() && accountGateway?.isLoopbackUrl() == false -> accountGateway
        else -> saved
    }
}

private fun String.isLoopbackUrl(): Boolean {
    val host = runCatching { URI(this).host.orEmpty() }.getOrDefault("")
    return host.equals("localhost", ignoreCase = true) ||
        host == "127.0.0.1" ||
        host == "::1" ||
        host == "0:0:0:0:0:0:0:1"
}

private fun String.isKnownPublicSttEndpoint(): Boolean {
    val host = runCatching { URI(trim()).host.orEmpty().lowercase() }.getOrDefault("")
    return host == "lstwin.space" || host == "lstwin.cloud" || host == "118.25.43.185"
}

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
    private val defaultRelayBaseUrl = BuildConfig.DEFAULT_RELAY_BASE_URL.takeIf { it.isNotBlank() }
    // 常见 STT 服务本地端口
    private val commonSttPorts = listOf(8888, 8000, 8001, 8002, 8889, 8890)
    private val isAndroidEmulator: Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.startsWith("unknown") ||
            Build.MODEL.contains("google_sdk", ignoreCase = true) ||
            Build.MODEL.contains("emulator", ignoreCase = true) ||
            Build.MANUFACTURER.contains("Genymotion", ignoreCase = true) ||
            Build.PRODUCT.contains("sdk_gphone", ignoreCase = true) ||
            Build.DEVICE.startsWith("emu", ignoreCase = true)

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
        private val STT_LANGUAGE = stringPreferencesKey("stt_language")
        private val STT_LOCAL_ENDPOINT = stringPreferencesKey("stt_local_endpoint")
        private val STT_LOCAL_MODEL = stringPreferencesKey("stt_local_model")
        private val STT_API_TOKEN = stringPreferencesKey("stt_api_token")
        private val STT_CLOUD_ENDPOINT = stringPreferencesKey("stt_cloud_endpoint")
        private val STT_CLOUD_API_KEY = stringPreferencesKey("stt_cloud_api_key")
        private val STT_CLOUD_MODEL = stringPreferencesKey("stt_cloud_model")
        private val STT_TENCENT_ASR_TIER = stringPreferencesKey("stt_tencent_asr_tier")
        private val STT_AUDIO_ENHANCEMENT_ENABLED = booleanPreferencesKey("stt_audio_enhancement_enabled")
        private val STT_SPEAKER_DIARIZATION_ENABLED = booleanPreferencesKey("stt_speaker_diarization_enabled")

        // LLM Config Keys
        private val LLM_ENGINE_TYPE = stringPreferencesKey("llm_engine_type")
        private val LLM_AGENT_ENDPOINT = stringPreferencesKey("llm_agent_endpoint")
        private val LLM_AGENT_ACCESS_TOKEN = stringPreferencesKey("llm_agent_access_token")
        private val LLM_AGENT_PROVIDER = stringPreferencesKey("llm_agent_provider")
        private val LLM_CODEX_REASONING_EFFORT = stringPreferencesKey("llm_codex_reasoning_effort")
        private val LLM_CLAUDE_REASONING_EFFORT = stringPreferencesKey("llm_claude_reasoning_effort")
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
        private val TEMPLATE_WORKFLOW_REDUCED_MOTION = booleanPreferencesKey("template_workflow_reduced_motion")
        private val TEMPLATE_WORKFLOW_SEEN = stringSetPreferencesKey("template_workflow_seen")
        private val STT_DEFAULT_ENDPOINT_MIGRATED = stringPreferencesKey("stt_default_endpoint_migrated")
        private val DEFAULT_PROFILE_VERSION = stringPreferencesKey("default_profile_version")
        private val LOGGED_IN_USERNAME = stringPreferencesKey("logged_in_username")
        private val ACCOUNT_SESSION_JSON = stringPreferencesKey("account_session_json")
        private val ACCOUNT_ENDPOINT = stringPreferencesKey("account_endpoint")
        private val ACCOUNT_STT_ACCESS_TOKEN = stringPreferencesKey("account_stt_access_token")
        private val STT_USE_ACCOUNT_TOKEN = stringPreferencesKey("stt_use_account_token")
        private val SEEN_NOTIFICATION_EVENTS = stringSetPreferencesKey("seen_notification_events")
        private val SEEN_GROWTH_CAMPAIGNS = stringSetPreferencesKey("seen_growth_campaigns")
        private val APP_THEME_MODE = stringPreferencesKey("app_theme_mode")
        private val FLOATING_BALL_ENABLED = booleanPreferencesKey("floating_ball_enabled")
        private val IGNORED_APP_UPDATE_VERSION = stringPreferencesKey("ignored_app_update_version")

        private val PRESET_TEMPLATE_ASSETS = listOf(
            ReportTemplateAsset(
                "通用会议",
                "通用会议.md",
                "议题、结论、观点与行动项"
            ),
            ReportTemplateAsset(
                "项目管理",
                "孔爵团队版表格会议纪要.md",
                "推演节点、风险与行动项"
            ),
            ReportTemplateAsset(
                "宣贯·落实会",
                "行政会议.md",
                "指令、责任与时间节点"
            ),
            ReportTemplateAsset(
                "推演·进度会",
                "孔爵团队版表格会议纪要.md",
                "里程碑、风险与行动项"
            ),
            ReportTemplateAsset(
                "启迪·共创会",
                "头脑风暴.md",
                "创意池、观点聚类与验证"
            ),
            ReportTemplateAsset(
                "博弈·洽谈会",
                "博弈洽谈会.md",
                "立场、条款与可观察互动信号"
            ),
            ReportTemplateAsset(
                "复盘·分析会",
                "复盘分析会.md",
                "时间线、根因与预防措施"
            ),
            ReportTemplateAsset(
                "敏捷·站会",
                "敏捷站会.md",
                "昨日、今日与阻塞项"
            ),
            ReportTemplateAsset(
                "论坛会议",
                "论坛会议.md",
                "主持串场、主题演讲与问答脉络"
            ),
            ReportTemplateAsset(
                "研学考察",
                "参观考察游记.md",
                "分段旅程、图文游记与阶段续写"
            )
        )
        private val LEGACY_CORE_TEMPLATE_NAMES = mapOf(
            "通用会议纪要" to "通用会议",
            "行政会议" to "通用会议",
            "头脑风暴" to "通用会议",
            "讲座论坛纪要" to "论坛会议",
            "论坛会议纪要" to "论坛会议",
            "孔爵团队版表格会议纪要" to "项目管理",
            "项目管理纪要" to "项目管理",
            "参观考察（游记）" to "研学考察",
            "参观考察类会议" to "研学考察"
        )
        private val RETIRED_PRESET_NAMES = setOf(
            "政策解读纪要",
            "技术交流纪要",
            "学术报告纪要"
        )

        private val VIP_TEMPLATE_ASSETS = listOf(
            ReportTemplateAsset(
                "工程/建筑 施工/设计日志",
                "工程建筑施工设计日志.md",
                "进度、质量与安全闭环"
            ),
            ReportTemplateAsset(
                "监理会例会日志",
                "监理会例会日志.md",
                "旁站、验收与整改节点"
            )
        )
        private val LEGACY_VIP_TEMPLATE_NAMES = mapOf(
            "工程行业施工日志" to "工程/建筑 施工/设计日志",
            "建筑专业设计日志" to "工程/建筑 施工/设计日志"
        )
    }

    /**
     * Get app configuration as Flow
     */
    val appConfigFlow: Flow<AppConfig> = context.dataStore.data.map { preferences ->
        val defaultTemplate = loadPresetTemplates().firstOrNull()
        val rawSavedTemplateName = preferences[REPORT_TEMPLATE_NAME]
        val savedTemplateIsCustom = preferences[REPORT_TEMPLATE_IS_CUSTOM]
            ?.toBooleanStrictOrNull() ?: false
        val migratedCoreTemplate = if (savedTemplateIsCustom) {
            null
        } else {
            LEGACY_CORE_TEMPLATE_NAMES[rawSavedTemplateName]?.let { migratedName ->
                loadPresetTemplates().firstOrNull { it.name == migratedName }
            }
        }
        val savedTemplateName = migratedCoreTemplate?.name ?: rawSavedTemplateName

        val migratedVipTemplate = if (savedTemplateIsCustom) {
            null
        } else {
            LEGACY_VIP_TEMPLATE_NAMES[savedTemplateName]?.let { migratedName ->
                loadVipTemplates().firstOrNull { it.name == migratedName }
            }
        }
        val isRetiredPreset = !savedTemplateIsCustom && savedTemplateName in RETIRED_PRESET_NAMES
        val accountSttToken = preferences[ACCOUNT_STT_ACCESS_TOKEN]?.takeIf { it.isNotBlank() }
        val useAccountSttToken = preferences[STT_USE_ACCOUNT_TOKEN]
            ?.toBooleanStrictOrNull() ?: (accountSttToken != null)
        val effectiveSttToken = if (useAccountSttToken) {
            accountSttToken
        } else {
            preferences[STT_API_TOKEN]
        }

        val templateName = when {
            savedTemplateName.isNullOrBlank() -> defaultTemplate?.name ?: ReportTemplateConfig().selectedName
            migratedVipTemplate != null -> migratedVipTemplate.name
            isRetiredPreset -> defaultTemplate?.name ?: ReportTemplateConfig().selectedName
            else -> savedTemplateName
        }

        val templateContent = when {
            migratedCoreTemplate != null -> migratedCoreTemplate.content
            migratedVipTemplate != null -> migratedVipTemplate.content
            isRetiredPreset -> defaultTemplate?.content ?: ""
            preferences[REPORT_TEMPLATE_CONTENT].isNullOrBlank() -> defaultTemplate?.content ?: ""
            else -> preferences[REPORT_TEMPLATE_CONTENT] ?: ""
        }
        val savedSttEngineName = preferences[STT_ENGINE_TYPE]
        val rawSavedSttEndpoint = preferences[STT_LOCAL_ENDPOINT]
            ?.takeUnless {
                it == STTConfig.PREVIOUS_PUBLIC_ENDPOINT ||
                    it == STTConfig.LEGACY_LOCAL_ENDPOINT ||
                    !BuildConfig.DEBUG && it.isDevelopmentOnlySttEndpoint()
            }
        val savedSttEndpoint = rawSavedSttEndpoint
            ?.takeUnless { BuildConfig.DEBUG && isAndroidEmulator && it.isKnownPublicSttEndpoint() }
            ?: if (BuildConfig.DEBUG && isAndroidEmulator) {
                STTConfig.AVD_HOST_ENDPOINT
            } else {
                STTConfig.DEFAULT_LOCAL_ENDPOINT
            }
        val savedCloudEndpoint = preferences[STT_CLOUD_ENDPOINT] ?: STTConfig.DEFAULT_CLOUD_ENDPOINT
        val savedCloudModel = preferences[STT_CLOUD_MODEL] ?: STTConfig.DEFAULT_CLOUD_MODEL
        val savedTencentTier = preferences[STT_TENCENT_ASR_TIER]?.let {
            runCatching { TencentAsrTier.valueOf(it) }.getOrNull()
        } ?: if (savedCloudModel == TencentAsrTier.PRECISION_PAID.cloudModel) {
            TencentAsrTier.PRECISION_PAID
        } else {
            TencentAsrTier.STANDARD_FREE
        }
        val isManagedTencentCloud = preferences[STT_CLOUD_API_KEY].isNullOrBlank() &&
            savedCloudModel in setOf(
                "tencent-flash",
                TencentAsrTier.STANDARD_FREE.cloudModel,
                TencentAsrTier.PRECISION_PAID.cloudModel
            ) && !savedCloudEndpoint.isNullOrBlank()
        val effectiveSttEngine = when {
            savedSttEngineName == "CLOUD_ASR" && isManagedTencentCloud -> {
                STTEngineType.TENCENT_HYBRID
            }
            savedSttEngineName == "CLOUD_ASR" -> STTEngineType.FASTER_WHISPER
            else -> savedSttEngineName?.let {
                runCatching { STTEngineType.valueOf(it) }.getOrNull()
            } ?: STTEngineType.FASTER_WHISPER
        }

        AppConfig(
            sttConfig = STTConfig(
                engineType = effectiveSttEngine,
                language = preferences[STT_LANGUAGE]?.let {
                    runCatching { STTLanguage.valueOf(it) }.getOrNull()
                } ?: STTLanguage.CHINESE,
                localEndpoint = savedSttEndpoint,
                localModel = preferences[STT_LOCAL_MODEL] ?: BuildConfig.DEFAULT_STT_MODEL,
                apiToken = effectiveSttToken,
                cloudEndpoint = savedCloudEndpoint,
                cloudApiKey = preferences[STT_CLOUD_API_KEY],
                cloudModel = if (effectiveSttEngine == STTEngineType.TENCENT_HYBRID) {
                    savedTencentTier.cloudModel
                } else {
                    savedCloudModel
                },
                tencentAsrTier = savedTencentTier,
                audioEnhancementEnabled = preferences[STT_AUDIO_ENHANCEMENT_ENABLED] ?: true,
                speakerDiarizationEnabled = preferences[STT_SPEAKER_DIARIZATION_ENABLED] ?: true
            ),
            llmConfig = LLMConfig(
                engineType = preferences[LLM_ENGINE_TYPE]?.let {
                    runCatching { LLMEngineType.valueOf(it) }.getOrNull()
                } ?: LLMEngineType.AGENT_GATEWAY,
                agentEndpoint = resolveAgentGatewayEndpoint(
                    savedEndpoint = preferences[LLM_AGENT_ENDPOINT],
                    accountEndpoint = preferences[ACCOUNT_ENDPOINT],
                    defaultEndpoint = BuildConfig.DEFAULT_AGENT_ENDPOINT
                ),
                agentAccessToken = preferences[LLM_AGENT_ACCESS_TOKEN],
                agentProvider = preferences[LLM_AGENT_PROVIDER]?.let {
                    runCatching { AgentProvider.valueOf(it) }.getOrNull()
                } ?: AgentProvider.CODEX_CLI,
                codexReasoningEffort = preferences[LLM_CODEX_REASONING_EFFORT]?.let {
                    runCatching { CodexReasoningEffort.valueOf(it) }.getOrNull()
                } ?: CodexReasoningEffort.HIGH,
                claudeReasoningEffort = preferences[LLM_CLAUDE_REASONING_EFFORT]?.let {
                    runCatching { ClaudeReasoningEffort.valueOf(it) }.getOrNull()
                } ?: ClaudeReasoningEffort.HIGH,
                localEndpoint = preferences[LLM_LOCAL_ENDPOINT] ?: "http://localhost:11434",
                localModel = preferences[LLM_LOCAL_MODEL] ?: "qwen2.5:7b",
                cloudEndpoint = preferences[LLM_CLOUD_ENDPOINT]
                    ?: defaultRelayBaseUrl
                    ?: defaultLlmCloudEndpoint
                    ?: defaultClaudeEndpoint,
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
                isCustom = savedTemplateIsCustom
            ),
            templateWorkflowPreferences = TemplateWorkflowPreferences(
                reducedMotion = preferences[TEMPLATE_WORKFLOW_REDUCED_MOTION] ?: false,
                seenTemplateNames = preferences[TEMPLATE_WORKFLOW_SEEN] ?: emptySet()
            )
        )
    }

    val templateWorkflowPreferencesFlow: Flow<TemplateWorkflowPreferences> = context.dataStore.data.map { preferences ->
        TemplateWorkflowPreferences(
            reducedMotion = preferences[TEMPLATE_WORKFLOW_REDUCED_MOTION] ?: false,
            seenTemplateNames = preferences[TEMPLATE_WORKFLOW_SEEN] ?: emptySet()
        )
    }

    val appThemeModeFlow: Flow<AppThemeMode> = context.dataStore.data.map { preferences ->
        preferences[APP_THEME_MODE]?.let { saved ->
            runCatching { AppThemeMode.valueOf(saved) }.getOrNull()
        } ?: AppThemeMode.SYSTEM
    }

    val sttUsesAccountTokenFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[STT_USE_ACCOUNT_TOKEN]?.toBooleanStrictOrNull()
            ?: !preferences[ACCOUNT_STT_ACCESS_TOKEN].isNullOrBlank()
    }

    val floatingBallEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[FLOATING_BALL_ENABLED] ?: false
    }

    val ignoredAppUpdateVersionFlow: Flow<Int?> = context.dataStore.data.map { preferences ->
        preferences[IGNORED_APP_UPDATE_VERSION]?.toIntOrNull()
    }

    suspend fun updateAppThemeMode(mode: AppThemeMode) {
        context.dataStore.edit { preferences ->
            preferences[APP_THEME_MODE] = mode.name
        }
    }

    suspend fun updateFloatingBallEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[FLOATING_BALL_ENABLED] = enabled
        }
    }

    suspend fun ignoreAppUpdateVersion(versionCode: Int) {
        context.dataStore.edit { preferences ->
            preferences[IGNORED_APP_UPDATE_VERSION] = versionCode.toString()
        }
    }

    fun loadPresetTemplates(): List<PresetReportTemplate> {
        return PRESET_TEMPLATE_ASSETS.mapNotNull { asset ->
            runCatching {
                context.assets.open(asset.fileName).bufferedReader(Charsets.UTF_8).use { reader ->
                    PresetReportTemplate(
                        name = asset.name,
                        content = reader.readText(),
                        subtitle = asset.subtitle
                    )
                }
            }.getOrNull()
        }
    }

    /**
     * 加载VIP专用模板
     */
    fun loadVipTemplates(): List<PresetReportTemplate> {
        return VIP_TEMPLATE_ASSETS.mapNotNull { asset ->
            runCatching {
                context.assets.open(asset.fileName).bufferedReader(Charsets.UTF_8).use { reader ->
                    PresetReportTemplate(
                        name = asset.name,
                        content = reader.readText(),
                        subtitle = asset.subtitle
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
        val vipTemplatesByName = loadVipTemplates().associateBy { it.name }
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
            if (profileVersion < 5) {
                preferences[LLM_AGENT_PROVIDER] = AgentProvider.CODEX_CLI.name
            }
            if (profileVersion < 6 && defaultReportTemplate != null) {
                val savedTemplateName = preferences[REPORT_TEMPLATE_NAME]
                val savedTemplateIsCustom = preferences[REPORT_TEMPLATE_IS_CUSTOM]
                    ?.toBooleanStrictOrNull() ?: false
                if (!savedTemplateIsCustom &&
                    (savedTemplateName.isNullOrBlank() || savedTemplateName in RETIRED_PRESET_NAMES)
                ) {
                    preferences[REPORT_TEMPLATE_NAME] = defaultReportTemplate.name
                    preferences[REPORT_TEMPLATE_CONTENT] = defaultReportTemplate.content
                    preferences[REPORT_TEMPLATE_IS_CUSTOM] = false.toString()
                }
            }
            if (profileVersion < 7) {
                val savedTemplateName = preferences[REPORT_TEMPLATE_NAME]
                val savedTemplateIsCustom = preferences[REPORT_TEMPLATE_IS_CUSTOM]
                    ?.toBooleanStrictOrNull() ?: false
                val migratedTemplate = if (savedTemplateIsCustom) {
                    null
                } else {
                    LEGACY_VIP_TEMPLATE_NAMES[savedTemplateName]
                        ?.let(vipTemplatesByName::get)
                }
                if (migratedTemplate != null) {
                    preferences[REPORT_TEMPLATE_NAME] = migratedTemplate.name
                    preferences[REPORT_TEMPLATE_CONTENT] = migratedTemplate.content
                    preferences[REPORT_TEMPLATE_IS_CUSTOM] = false.toString()
                }
            }
            if (profileVersion < 8) {
                val savedModel = preferences[STT_CLOUD_MODEL]
                val savedEngine = preferences[STT_ENGINE_TYPE]
                if (
                    savedEngine == STTEngineType.TENCENT_HYBRID.name ||
                    savedEngine == "CLOUD_ASR" ||
                    savedModel == "tencent-flash"
                ) {
                    preferences[STT_TENCENT_ASR_TIER] = TencentAsrTier.STANDARD_FREE.name
                    preferences[STT_CLOUD_MODEL] = TencentAsrTier.STANDARD_FREE.cloudModel
                }
            }
            if (profileVersion < 9) {
                val savedCodexEffort = preferences[LLM_CODEX_REASONING_EFFORT]
                if (savedCodexEffort.isNullOrBlank() || savedCodexEffort == CodexReasoningEffort.MEDIUM.name) {
                    preferences[LLM_CODEX_REASONING_EFFORT] = CodexReasoningEffort.HIGH.name
                }
                val savedClaudeEffort = preferences[LLM_CLAUDE_REASONING_EFFORT]
                if (savedClaudeEffort.isNullOrBlank() || savedClaudeEffort == ClaudeReasoningEffort.MEDIUM.name) {
                    preferences[LLM_CLAUDE_REASONING_EFFORT] = ClaudeReasoningEffort.HIGH.name
                }
            }
            if (profileVersion < 10) {
                val savedTemplateName = preferences[REPORT_TEMPLATE_NAME]
                val savedTemplateIsCustom = preferences[REPORT_TEMPLATE_IS_CUSTOM]
                    ?.toBooleanStrictOrNull() ?: false
                val targetName = if (savedTemplateIsCustom) {
                    null
                } else {
                    LEGACY_CORE_TEMPLATE_NAMES[savedTemplateName]
                }
                val migratedTemplate = loadPresetTemplates().firstOrNull { it.name == targetName }
                if (migratedTemplate != null) {
                    preferences[REPORT_TEMPLATE_NAME] = migratedTemplate.name
                    preferences[REPORT_TEMPLATE_CONTENT] = migratedTemplate.content
                    preferences[REPORT_TEMPLATE_IS_CUSTOM] = false.toString()
                }
            }
            if (profileVersion < 11) {
                val savedEndpoint = preferences[LLM_AGENT_ENDPOINT]
                val resolvedEndpoint = resolveAgentGatewayEndpoint(
                    savedEndpoint = savedEndpoint,
                    accountEndpoint = preferences[ACCOUNT_ENDPOINT],
                    defaultEndpoint = BuildConfig.DEFAULT_AGENT_ENDPOINT
                )
                if (savedEndpoint.isNullOrBlank() || savedEndpoint.isLoopbackUrl()) {
                    preferences[LLM_AGENT_ENDPOINT] = resolvedEndpoint
                }
                if (preferences[LLM_AGENT_PROVIDER].isNullOrBlank()) {
                    preferences[LLM_AGENT_PROVIDER] = AgentProvider.CODEX_CLI.name
                }
                if (preferences[LLM_CODEX_REASONING_EFFORT].isNullOrBlank()) {
                    preferences[LLM_CODEX_REASONING_EFFORT] = CodexReasoningEffort.HIGH.name
                }
            }
            if (profileVersion < 12) {
                val savedLocalEndpoint = preferences[STT_LOCAL_ENDPOINT]
                    ?.trim()
                    ?.trimEnd('/')
                    .orEmpty()
                val savedCloudEndpoint = preferences[STT_CLOUD_ENDPOINT]
                    ?.trim()
                    ?.trimEnd('/')
                val savedCloudModel = preferences[STT_CLOUD_MODEL]
                val savedEngine = preferences[STT_ENGINE_TYPE]
                val managedTencentProfile = preferences[STT_CLOUD_API_KEY].isNullOrBlank() && (
                    savedEngine == STTEngineType.TENCENT_HYBRID.name ||
                        savedEngine == "CLOUD_ASR" ||
                        savedCloudModel in setOf(
                            "tencent-flash",
                            TencentAsrTier.STANDARD_FREE.cloudModel,
                            TencentAsrTier.PRECISION_PAID.cloudModel
                        )
                    )
                val legacyManagedEndpoint = savedCloudEndpoint == "$savedLocalEndpoint/cloud-asr" ||
                    savedCloudEndpoint?.endsWith("/cloud-asr") == true
                val defaultCloudEndpoint = STTConfig.DEFAULT_CLOUD_ENDPOINT
                if (managedTencentProfile &&
                    !defaultCloudEndpoint.isNullOrBlank() &&
                    (savedCloudEndpoint.isNullOrBlank() || legacyManagedEndpoint)
                ) {
                    preferences[STT_CLOUD_ENDPOINT] = defaultCloudEndpoint
                }
            }
            if (profileVersion < 13 && !BuildConfig.DEBUG) {
                val savedEndpoint = preferences[STT_LOCAL_ENDPOINT]
                if (savedEndpoint.isNullOrBlank() || savedEndpoint.isDevelopmentOnlySttEndpoint()) {
                    preferences[STT_LOCAL_ENDPOINT] = STTConfig.DEFAULT_LOCAL_ENDPOINT
                }
            }
            if (profileVersion < 14) {
                val savedEngine = preferences[STT_ENGINE_TYPE]
                val savedEndpoint = preferences[STT_LOCAL_ENDPOINT]
                    ?.trim()
                    ?.trimEnd('/')
                    .orEmpty()
                val normalizedEndpoint = savedEndpoint.lowercase()
                val managedLegacyCloudProfile = preferences[STT_CLOUD_API_KEY].isNullOrBlank() && (
                    savedEngine == STTEngineType.TENCENT_HYBRID.name ||
                        savedEngine == "CLOUD_ASR"
                    )
                val endpointWasCloudService = normalizedEndpoint.endsWith("/stt-cloud") ||
                    normalizedEndpoint.endsWith("/cloud-asr")
                if (managedLegacyCloudProfile || endpointWasCloudService) {
                    preferences[STT_ENGINE_TYPE] = STTEngineType.FASTER_WHISPER.name
                    preferences[STT_LOCAL_ENDPOINT] = STTConfig.DEFAULT_LOCAL_ENDPOINT
                    preferences[STT_LOCAL_MODEL] = BuildConfig.DEFAULT_STT_MODEL
                }
            }
            if (profileVersion < 15) {
                val savedEngine = preferences[STT_ENGINE_TYPE]
                val savedModel = preferences[STT_LOCAL_MODEL].orEmpty()
                if (
                    savedEngine == "SENSE_VOICE" ||
                        savedModel.equals("SenseVoiceSmall", ignoreCase = true) ||
                        savedModel.equals("iic/SenseVoiceSmall", ignoreCase = true)
                ) {
                    preferences[STT_ENGINE_TYPE] = STTEngineType.FASTER_WHISPER.name
                    preferences[STT_LOCAL_MODEL] = STTEngineType.FASTER_WHISPER.defaultModel
                }
            }
            preferences[DEFAULT_PROFILE_VERSION] = "15"
        }
    }

    /**
     * Update STT configuration
     */
    suspend fun updateSTTConfig(config: STTConfig) {
        context.dataStore.edit { preferences ->
            preferences[STT_ENGINE_TYPE] = config.engineType.name
            preferences[STT_LANGUAGE] = config.language.name
            preferences[STT_LOCAL_ENDPOINT] = config.localEndpoint
            preferences[STT_LOCAL_MODEL] = config.localModel
            val usesAccountToken = preferences[STT_USE_ACCOUNT_TOKEN]
                ?.toBooleanStrictOrNull() == true
            if (!usesAccountToken) {
                config.apiToken?.takeIf { it.isNotBlank() }
                    ?.let { preferences[STT_API_TOKEN] = it }
                    ?: preferences.remove(STT_API_TOKEN)
            }
            config.cloudEndpoint?.takeIf { it.isNotBlank() }
                ?.let { preferences[STT_CLOUD_ENDPOINT] = it }
                ?: preferences.remove(STT_CLOUD_ENDPOINT)
            config.cloudApiKey?.takeIf { it.isNotBlank() }
                ?.let { preferences[STT_CLOUD_API_KEY] = it }
                ?: preferences.remove(STT_CLOUD_API_KEY)
            config.cloudModel.takeIf { it.isNotBlank() }
                ?.let { preferences[STT_CLOUD_MODEL] = it }
                ?: preferences.remove(STT_CLOUD_MODEL)
            preferences[STT_TENCENT_ASR_TIER] = config.tencentAsrTier.name
            preferences[STT_AUDIO_ENHANCEMENT_ENABLED] = config.audioEnhancementEnabled
            preferences[STT_SPEAKER_DIARIZATION_ENABLED] = config.speakerDiarizationEnabled
        }
    }

    suspend fun updateManualSttApiToken(apiToken: String?) {
        context.dataStore.edit { preferences ->
            apiToken?.takeIf { it.isNotBlank() }
                ?.let { preferences[STT_API_TOKEN] = it }
                ?: preferences.remove(STT_API_TOKEN)
            preferences[STT_USE_ACCOUNT_TOKEN] = false.toString()
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
            preferences[LLM_CODEX_REASONING_EFFORT] = config.codexReasoningEffort.name
            preferences[LLM_CLAUDE_REASONING_EFFORT] = config.claudeReasoningEffort.name
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

    suspend fun updateTemplateWorkflowReducedMotion(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[TEMPLATE_WORKFLOW_REDUCED_MOTION] = enabled
        }
    }

    suspend fun markTemplateWorkflowSeen(templateName: String) {
        val normalized = templateName.trim()
        if (normalized.isBlank()) return
        context.dataStore.edit { preferences ->
            preferences[TEMPLATE_WORKFLOW_SEEN] = (preferences[TEMPLATE_WORKFLOW_SEEN] ?: emptySet()) + normalized
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
            val accountSession = preferences[ACCOUNT_SESSION_JSON]
            val accountEndpoint = preferences[ACCOUNT_ENDPOINT]
            val accountAgentToken = preferences[LLM_AGENT_ACCESS_TOKEN]
            val accountSttToken = preferences[ACCOUNT_STT_ACCESS_TOKEN]
            val useAccountSttToken = preferences[STT_USE_ACCOUNT_TOKEN]
            preferences.clear()
            if (username != null) {
                preferences[LOGGED_IN_USERNAME] = username
            }
            if (accountSession != null) preferences[ACCOUNT_SESSION_JSON] = accountSession
            if (accountEndpoint != null) preferences[ACCOUNT_ENDPOINT] = accountEndpoint
            if (accountAgentToken != null) preferences[LLM_AGENT_ACCESS_TOKEN] = accountAgentToken
            if (accountSttToken != null) preferences[ACCOUNT_STT_ACCESS_TOKEN] = accountSttToken
            if (useAccountSttToken != null) preferences[STT_USE_ACCOUNT_TOKEN] = useAccountSttToken
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

    val authSessionFlow: Flow<AuthSession?> = context.dataStore.data.map { preferences ->
        preferences[ACCOUNT_SESSION_JSON]?.let { json ->
            runCatching { gson.fromJson(json, AuthSession::class.java) }.getOrNull()
        }
    }

    val accountEndpointFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[ACCOUNT_ENDPOINT]
            ?.takeIf { it.isNotBlank() }
            ?: BuildConfig.DEFAULT_ACCOUNT_ENDPOINT
    }

    val seenNotificationEventsFlow: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[SEEN_NOTIFICATION_EVENTS].orEmpty()
    }

    suspend fun saveSeenNotificationEvents(events: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[SEEN_NOTIFICATION_EVENTS] = events
        }
    }

    /** Campaign ids that the user has opened from the notification center. */
    val seenGrowthCampaignIdsFlow: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[SEEN_GROWTH_CAMPAIGNS].orEmpty()
    }

    suspend fun saveSeenGrowthCampaignIds(campaignIds: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[SEEN_GROWTH_CAMPAIGNS] = campaignIds
        }
    }

    suspend fun saveAuthSession(
        session: AuthSession,
        endpoint: String = BuildConfig.DEFAULT_ACCOUNT_ENDPOINT
    ) {
        context.dataStore.edit { preferences ->
            preferences[ACCOUNT_SESSION_JSON] = gson.toJson(session)
            preferences[ACCOUNT_ENDPOINT] = endpoint.trim().trimEnd('/')
            preferences[LOGGED_IN_USERNAME] = session.user.username
            preferences[LLM_AGENT_ACCESS_TOKEN] = session.agentAccessToken
            val sttAccessToken = session.sttAccessToken?.takeIf { it.isNotBlank() }
            if (sttAccessToken == null) {
                preferences.remove(ACCOUNT_STT_ACCESS_TOKEN)
                preferences.remove(STT_USE_ACCOUNT_TOKEN)
            } else {
                val token = sttAccessToken
                preferences[ACCOUNT_STT_ACCESS_TOKEN] = token
                preferences[STT_USE_ACCOUNT_TOKEN] = true.toString()
            }
        }
    }

    suspend fun updateAccountSession(credentials: AccountSessionCredentials) {
        context.dataStore.edit { preferences ->
            val session = preferences[ACCOUNT_SESSION_JSON]?.let { json ->
                runCatching { gson.fromJson(json, AuthSession::class.java) }.getOrNull()
            } ?: return@edit
            val updatedSession = session.copy(
                agentAccessToken = credentials.agentAccessToken,
                sttAccessToken = credentials.sttAccessToken,
                expiresAt = credentials.expiresAt,
                user = credentials.user
            )
            preferences[ACCOUNT_SESSION_JSON] = gson.toJson(updatedSession)
            preferences[LOGGED_IN_USERNAME] = credentials.user.username
            preferences[LLM_AGENT_ACCESS_TOKEN] = credentials.agentAccessToken
            preferences[ACCOUNT_STT_ACCESS_TOKEN] = credentials.sttAccessToken
            preferences[STT_USE_ACCOUNT_TOKEN] = true.toString()
        }
    }

    suspend fun updateAccountProfile(profile: AccountProfile) {
        context.dataStore.edit { preferences ->
            val session = preferences[ACCOUNT_SESSION_JSON]?.let { json ->
                runCatching { gson.fromJson(json, AuthSession::class.java) }.getOrNull()
            } ?: return@edit
            preferences[ACCOUNT_SESSION_JSON] = gson.toJson(session.copy(user = profile))
            preferences[LOGGED_IN_USERNAME] = profile.username
        }
    }

    /**
     * Clear saved username (logout)
     */
    suspend fun clearUsername() {
        context.dataStore.edit { preferences ->
            preferences.remove(LOGGED_IN_USERNAME)
            preferences.remove(ACCOUNT_SESSION_JSON)
            preferences.remove(LLM_AGENT_ACCESS_TOKEN)
            preferences.remove(ACCOUNT_STT_ACCESS_TOKEN)
            preferences.remove(STT_USE_ACCOUNT_TOKEN)
        }
    }
}
