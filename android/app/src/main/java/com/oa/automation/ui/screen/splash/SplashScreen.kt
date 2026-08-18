package com.oa.automation.ui.screen.splash

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import com.oa.automation.ui.component.AppLauncherIcon
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(
    onSplashFinished: () -> Unit
) {
    var showIcon by remember { mutableStateOf(false) }
    var showSubtitle by remember { mutableStateOf(false) }
    var startExit by remember { mutableStateOf(false) }

    val iconScale = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        showIcon = true
        iconScale.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 900,
                easing = FastOutSlowInEasing
            )
        )
        delay(350)
        showSubtitle = true
        delay(1_400)
        startExit = true
        delay(500)
        onSplashFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(
            visible = !startExit,
            exit = fadeOut(animationSpec = tween(500))
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Animated icon
                AnimatedVisibility(
                    visible = showIcon,
                    enter = scaleIn(
                        animationSpec = tween(900, easing = FastOutSlowInEasing)
                    ) + fadeIn(animationSpec = tween(900))
                ) {
                    AppLauncherIcon(
                        modifier = Modifier
                            .size(92.dp)
                            .scale(iconScale.value),
                        contentDescription = "智悟本应用图标"
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Subtitle
                AnimatedVisibility(
                    visible = showSubtitle,
                    enter = slideInVertically(
                        initialOffsetY = { it / 3 },
                        animationSpec = tween(550, easing = FastOutSlowInEasing)
                    ) + fadeIn(animationSpec = tween(550))
                ) {
                    Text(
                        text = "记录我的成长",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
