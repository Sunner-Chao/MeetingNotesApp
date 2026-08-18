package com.oa.automation.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.oa.automation.ui.navigation.OAAutomationNavHost
import com.oa.automation.ui.theme.OAAutomationTheme
import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.locale.withSimplifiedChineseLocale
import com.oa.automation.infrastructure.textimport.SharedTextImportCoordinator
import com.oa.automation.infrastructure.service.FloatingStatusService
import com.oa.automation.infrastructure.service.RecordingSessionController
import com.oa.automation.infrastructure.update.AndroidAppUpdate
import com.oa.automation.infrastructure.update.AppUpdateCheck
import com.oa.automation.infrastructure.update.AppUpdateService
import com.oa.automation.infrastructure.update.newerAppUpdate
import com.oa.automation.infrastructure.update.shouldPromptForUpdate
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {
    private val sharedTextImportCoordinator: SharedTextImportCoordinator by inject()
    private val configDataStore: ConfigDataStore by inject()
    private val recordingController: RecordingSessionController by inject()
    private val appUpdateService: AppUpdateService by inject()
    private var updateCheckJob: Job? = null
    private var pendingAppUpdate by mutableStateOf<AndroidAppUpdate?>(null)
    private var isDownloadingAppUpdate by mutableStateOf(false)
    private var appUpdateProgress by mutableIntStateOf(0)
    private var appUpdateMessage by mutableStateOf<String?>(null)
    private var updateCheckQueued = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withSimplifiedChineseLocale())
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // Permission granted, continue
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request microphone permission if not granted
        requestAudioPermission()
        lifecycleScope.launch { sharedTextImportCoordinator.accept(intent) }
        observeAppUpdatesAfterLogin()

        setContent {
            val themeMode = configDataStore.appThemeModeFlow.collectAsStateWithLifecycle(
                initialValue = com.oa.automation.domain.model.AppThemeMode.SYSTEM
            ).value
            OAAutomationTheme(darkTheme = themeMode.usesDarkColors(isSystemInDarkTheme())) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        OAAutomationNavHost()
                        AppUpdatePrompt(
                            update = pendingAppUpdate,
                            isDownloading = isDownloadingAppUpdate,
                            progress = appUpdateProgress,
                            message = appUpdateMessage,
                            onUpdate = ::downloadAndInstallAppUpdate,
                            onLater = { pendingAppUpdate = null },
                            onIgnore = ::ignoreCurrentAppUpdate
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        lifecycleScope.launch { sharedTextImportCoordinator.accept(intent) }
    }

    override fun onStart() {
        super.onStart()
        FloatingStatusService.hide(this)
        checkForAppUpdateIfNeeded()
    }

    override fun onStop() {
        super.onStop()
        lifecycleScope.launch {
            val enabled = configDataStore.floatingBallEnabledFlow.first()
            val recordingState = recordingController.state.value
            val recordingActive = recordingState.isRecording ||
                recordingState.isStarting ||
                recordingState.isStopping
            if (!recordingActive && enabled && Settings.canDrawOverlays(this@MainActivity)) {
                FloatingStatusService.show(this@MainActivity, "")
            } else {
                FloatingStatusService.hide(this@MainActivity)
            }
        }
    }

    private fun requestAudioPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                // Permission already granted
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun observeAppUpdatesAfterLogin() {
        lifecycleScope.launch {
            configDataStore.authSessionFlow
                .map { session -> session?.user?.id }
                .distinctUntilChanged()
                .collect { accountId ->
                    if (!accountId.isNullOrBlank()) checkForAppUpdateIfNeeded()
                }
        }
    }

    private fun checkForAppUpdateIfNeeded() {
        if (isDownloadingAppUpdate) return
        if (updateCheckJob?.isActive == true) {
            updateCheckQueued = true
            return
        }
        val recordingState = recordingController.state.value
        if (recordingState.isRecording || recordingState.isStarting || recordingState.isStopping) return
        updateCheckJob = lifecycleScope.launch {
            val ignoredVersion = configDataStore.ignoredAppUpdateVersionFlow.first()
            val result = appUpdateService.checkForUpdate().getOrNull()
            val update = (result as? AppUpdateCheck.Available)?.update
            if (update != null && shouldPromptForUpdate(update, ignoredVersion)) {
                val current = pendingAppUpdate
                val latest = if (current == null) update else newerAppUpdate(current, update)
                if (latest !== current) {
                    pendingAppUpdate = latest
                    appUpdateMessage = null
                }
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (updateCheckJob !== job) return@invokeOnCompletion
                updateCheckJob = null
                if (updateCheckQueued) {
                    updateCheckQueued = false
                    checkForAppUpdateIfNeeded()
                } else {
                    updateCheckQueued = false
                }
            }
        }
    }

    private fun ignoreCurrentAppUpdate() {
        val update = pendingAppUpdate ?: return
        lifecycleScope.launch {
            configDataStore.ignoreAppUpdateVersion(update.versionCode)
            pendingAppUpdate = null
            appUpdateMessage = null
        }
    }

    private fun downloadAndInstallAppUpdate() {
        val update = pendingAppUpdate ?: return
        if (!appUpdateService.canInstallPackages()) {
            appUpdateService.requestInstallPermission()
            appUpdateMessage = "请允许智悟本安装未知来源应用后，再点击立即更新"
            return
        }
        if (isDownloadingAppUpdate) return
        isDownloadingAppUpdate = true
        appUpdateProgress = 0
        appUpdateMessage = null
        lifecycleScope.launch {
            val refreshed = (appUpdateService.checkForUpdate().getOrNull() as? AppUpdateCheck.Available)?.update
            val latest = if (refreshed == null) update else newerAppUpdate(update, refreshed)
            if (latest.versionCode != update.versionCode) {
                pendingAppUpdate = latest
            }
            appUpdateService.download(latest) { progress ->
                runOnUiThread { appUpdateProgress = progress }
            }.fold(
                onSuccess = { downloaded ->
                    isDownloadingAppUpdate = false
                    pendingAppUpdate = null
                    appUpdateService.install(downloaded)
                },
                onFailure = { error ->
                    isDownloadingAppUpdate = false
                    appUpdateMessage = "安装包下载失败：${error.message ?: "未知错误"}"
                }
            )
        }
    }
}
