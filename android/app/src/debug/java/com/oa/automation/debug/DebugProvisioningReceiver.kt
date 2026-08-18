package com.oa.automation.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.domain.model.AgentProvider
import com.oa.automation.domain.model.AuthSession
import com.oa.automation.domain.model.CodexReasoningEffort
import com.oa.automation.domain.model.LLMEngineType
import com.oa.automation.domain.model.ReportTemplateConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

class DebugProvisioningReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PROVISION_STT -> provisionStt(context, intent)
            ACTION_PROVISION_STUDY_TOUR_DEMO -> provisionStudyTourDemo(context, intent)
        }
    }

    private fun provisionStt(context: Context, intent: Intent) {
        val token = intent.getStringExtra(EXTRA_STT_TOKEN)?.trim().orEmpty()
        val endpoint = intent.getStringExtra(EXTRA_STT_ENDPOINT)?.trim().orEmpty()
        val cloudEndpoint = intent.getStringExtra(EXTRA_STT_CLOUD_ENDPOINT)?.trim().orEmpty()
        if (token.isBlank()) {
            Log.w(TAG, "Ignoring empty debug STT token")
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val store = ConfigDataStore(context.applicationContext)
                val config = store.appConfigFlow.first().sttConfig
                store.updateSTTConfig(
                    config.copy(
                        localEndpoint = endpoint.ifBlank { config.localEndpoint },
                        cloudEndpoint = cloudEndpoint.ifBlank { config.cloudEndpoint },
                        apiToken = token
                    )
                )
                Log.i(TAG, "Debug STT configuration provisioned")
            } catch (error: Exception) {
                Log.e(TAG, "Debug STT provisioning failed", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun provisionStudyTourDemo(context: Context, intent: Intent) {
        val accountEndpoint = intent.getStringExtra(EXTRA_ACCOUNT_ENDPOINT)?.trim().orEmpty()
        val sessionBase64 = intent.getStringExtra(EXTRA_ACCOUNT_SESSION_BASE64)?.trim().orEmpty()
        val endpoint = intent.getStringExtra(EXTRA_AGENT_ENDPOINT)?.trim().orEmpty()
        val token = intent.getStringExtra(EXTRA_AGENT_TOKEN)?.trim().orEmpty()
        if (accountEndpoint.isBlank() || sessionBase64.isBlank() || endpoint.isBlank() || token.isBlank()) {
            Log.w(TAG, "Ignoring incomplete debug account or Agent configuration")
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val store = ConfigDataStore(context.applicationContext)
                val sessionJson = Base64.decode(sessionBase64, Base64.DEFAULT)
                    .toString(Charsets.UTF_8)
                val session = Gson().fromJson(sessionJson, AuthSession::class.java)
                require(session.agentAccessToken == token) {
                    "Account session and Agent token do not match"
                }
                store.saveAuthSession(session, accountEndpoint)
                val studyTourTemplate = store.loadPresetTemplates()
                    .firstOrNull { it.name == STUDY_TOUR_TEMPLATE_NAME }
                    ?: error("Study-tour report template is unavailable")
                val currentLlm = store.appConfigFlow.first().llmConfig
                store.updateLLMConfig(
                    currentLlm.copy(
                        engineType = LLMEngineType.AGENT_GATEWAY,
                        agentEndpoint = endpoint,
                        agentAccessToken = token,
                        agentProvider = AgentProvider.CODEX_CLI,
                        codexReasoningEffort = CodexReasoningEffort.HIGH
                    )
                )
                store.updateReportTemplate(
                    ReportTemplateConfig(
                        selectedName = studyTourTemplate.name,
                        content = studyTourTemplate.content,
                        isCustom = false
                    )
                )

                val seeder = GlobalContext.get().get<DevelopmentDemoDataSeeder>()
                seeder.clear().getOrThrow()
                val created = seeder.seed().getOrThrow()
                Log.i(TAG, "Debug study-tour demo provisioned ($created meetings created)")
            } catch (error: Exception) {
                Log.e(TAG, "Debug study-tour provisioning failed", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "DebugProvisioning"
        private const val STUDY_TOUR_TEMPLATE_NAME = "研学考察"
        const val ACTION_PROVISION_STT = "com.oa.automation.debug.PROVISION_STT"
        const val ACTION_PROVISION_STUDY_TOUR_DEMO =
            "com.oa.automation.debug.PROVISION_STUDY_TOUR_DEMO"
        const val EXTRA_STT_TOKEN = "stt_api_token"
        const val EXTRA_STT_ENDPOINT = "stt_endpoint"
        const val EXTRA_STT_CLOUD_ENDPOINT = "stt_cloud_endpoint"
        const val EXTRA_ACCOUNT_ENDPOINT = "account_endpoint"
        const val EXTRA_ACCOUNT_SESSION_BASE64 = "account_session_base64"
        const val EXTRA_AGENT_ENDPOINT = "agent_endpoint"
        const val EXTRA_AGENT_TOKEN = "agent_access_token"
    }
}
