package com.oa.automation.infrastructure.account

import android.app.Activity
import com.alipay.sdk.app.EnvUtils
import com.alipay.sdk.app.PayTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Thin client-side adapter. Order signing remains exclusively on the server. */
object AlipaySdkClient {

    private const val ENVIRONMENT_SANDBOX = "sandbox"
    private const val ENVIRONMENT_PRE_SANDBOX = "pre_sandbox"

    suspend fun pay(
        activity: Activity,
        orderString: String,
        environment: String
    ): Map<String, String> = withContext(Dispatchers.IO) {
        EnvUtils.setEnv(resolveEnvironment(environment))
        PayTask(activity).payV2(orderString, true)
    }

    /**
     * The SDK targets the production gateway unless told otherwise, so a sandbox order
     * string would be rejected before the cashier can open. Anything other than an
     * explicit sandbox marker stays on the production gateway.
     */
    internal fun resolveEnvironment(environment: String): EnvUtils.EnvEnum =
        when (environment.trim().lowercase()) {
            ENVIRONMENT_SANDBOX -> EnvUtils.EnvEnum.SANDBOX
            ENVIRONMENT_PRE_SANDBOX -> EnvUtils.EnvEnum.PRE_SANDBOX
            else -> EnvUtils.EnvEnum.ONLINE
        }
}
