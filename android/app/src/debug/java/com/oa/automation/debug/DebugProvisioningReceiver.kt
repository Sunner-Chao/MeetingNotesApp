package com.oa.automation.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.oa.automation.data.local.ConfigDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class DebugProvisioningReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PROVISION_STT) return
        val token = intent.getStringExtra(EXTRA_STT_TOKEN)?.trim().orEmpty()
        val endpoint = intent.getStringExtra(EXTRA_STT_ENDPOINT)?.trim().orEmpty()
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

    companion object {
        private const val TAG = "DebugProvisioning"
        const val ACTION_PROVISION_STT = "com.oa.automation.debug.PROVISION_STT"
        const val EXTRA_STT_TOKEN = "stt_api_token"
        const val EXTRA_STT_ENDPOINT = "stt_endpoint"
    }
}
