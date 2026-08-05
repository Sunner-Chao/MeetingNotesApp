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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.isSystemInDarkTheme
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {
    private val sharedTextImportCoordinator: SharedTextImportCoordinator by inject()
    private val configDataStore: ConfigDataStore by inject()
    private val recordingController: RecordingSessionController by inject()

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

        setContent {
            val themeMode = configDataStore.appThemeModeFlow.collectAsStateWithLifecycle(
                initialValue = com.oa.automation.domain.model.AppThemeMode.SYSTEM
            ).value
            OAAutomationTheme(darkTheme = themeMode.usesDarkColors(isSystemInDarkTheme())) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    OAAutomationNavHost()
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
}
