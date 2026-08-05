package com.oa.automation.ui.examples

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oa.automation.ui.component.*

// ════════════════════════════════════════════════════════════════════════════
// UI组件使用示例
// 本文件展示所有新增美化组件的使用方法
// ════════════════════════════════════════════════════════════════════════════

/**
 * UI组件展示页面
 *
 * 用途：开发测试、设计评审、组件文档
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UIComponentShowcase() {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("UI组件展示") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // ─── 1. 渐变按钮示例 ───────────────────────────────────────────
            SectionTitle("1. 渐变按钮 (GradientButton)")

            // 主渐变按钮
            GradientButton(
                onClick = { /* action */ },
                gradient = listOf(
                    MaterialTheme.colorScheme.primary,
                    MaterialTheme.colorScheme.secondary
                )
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text("创建会议", fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 次级渐变按钮
            GradientButton(
                onClick = { /* action */ },
                gradient = listOf(
                    MaterialTheme.colorScheme.secondary,
                    MaterialTheme.colorScheme.tertiary
                )
            ) {
                Icon(Icons.Default.Mic, contentDescription = null)
                Text("开始录音", fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 禁用状态
            GradientButton(
                onClick = { /* action */ },
                enabled = false
            ) {
                Icon(Icons.Default.CloudUpload, contentDescription = null)
                Text("上传中...", fontWeight = FontWeight.Bold)
            }

            Divider()

            // ─── 2. 骨架屏示例 ─────────────────────────────────────────────
            SectionTitle("2. 骨架屏加载 (ShimmerEffect)")

            MeetingCardSkeleton()
            Spacer(modifier = Modifier.height(12.dp))
            StatsCardSkeleton()

            Divider()

            // ─── 3. 波浪进度条示例 ─────────────────────────────────────────
            SectionTitle("3. 波浪进度条 (WaveProgressIndicator)")

            var waveProgress by remember { mutableStateOf(0.35f) }

            WaveProgressIndicator(
                progress = waveProgress,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Slider(
                value = waveProgress,
                onValueChange = { waveProgress = it },
                modifier = Modifier.fillMaxWidth()
            )

            Divider()

            // ─── 4. 圆形渐变进度示例 ───────────────────────────────────────
            SectionTitle("4. 圆形渐变进度 (CircularGradientProgress)")

            var circularProgress by remember { mutableStateOf(0.65f) }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                CircularGradientProgress(
                    progress = circularProgress,
                    size = 100.dp
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${(circularProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "生成中",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Slider(
                value = circularProgress,
                onValueChange = { circularProgress = it },
                modifier = Modifier.fillMaxWidth()
            )

            Divider()

            // ─── 5. 脉冲动画示例 ───────────────────────────────────────────
            SectionTitle("5. 脉冲动画 (PulsingContainer)")

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                PulsingContainer(
                    modifier = Modifier.size(80.dp),
                    pulseColor = MaterialTheme.colorScheme.error
                ) {
                    FloatingActionButton(
                        onClick = { /* action */ },
                        containerColor = MaterialTheme.colorScheme.error
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = "录音中")
                    }
                }

                PulsingContainer(
                    modifier = Modifier.size(80.dp),
                    pulseColor = MaterialTheme.colorScheme.tertiary
                ) {
                    FloatingActionButton(
                        onClick = { /* action */ },
                        containerColor = MaterialTheme.colorScheme.tertiary
                    ) {
                        Icon(Icons.Default.Notifications, contentDescription = "新通知")
                    }
                }
            }

            Divider()

            // ─── 6. 呼吸灯示例 ─────────────────────────────────────────────
            SectionTitle("6. 呼吸灯效果 (BreathingLight)")

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BreathingLight(color = MaterialTheme.colorScheme.error)
                    Text("录音中", style = MaterialTheme.typography.bodyMedium)
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BreathingLight(color = MaterialTheme.colorScheme.tertiary)
                    Text("在线", style = MaterialTheme.typography.bodyMedium)
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BreathingLight(color = MaterialTheme.colorScheme.secondary)
                    Text("处理中", style = MaterialTheme.typography.bodyMedium)
                }
            }

            Divider()

            // ─── 7. 粒子爆炸示例 ───────────────────────────────────────────
            SectionTitle("7. 粒子爆炸效果 (ParticleExplosion)")

            var showExplosion by remember { mutableStateOf(false) }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                ParticleExplosion(
                    trigger = showExplosion,
                    modifier = Modifier.fillMaxSize(),
                    onAnimationComplete = { showExplosion = false }
                )

                Button(onClick = { showExplosion = true }) {
                    Icon(Icons.Default.Celebration, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("触发爆炸动画")
                }
            }

            // 底部留白
            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
}

// ════════════════════════════════════════════════════════════════════════════
// 实际使用场景示例
// ════════════════════════════════════════════════════════════════════════════

/**
 * 示例1: 录音页面 - 使用脉冲动画和呼吸灯
 */
@Composable
fun RecordingScreenExample() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 录音状态指示
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BreathingLight(color = MaterialTheme.colorScheme.error)
            Text("正在录音...", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(modifier = Modifier.height(40.dp))

        // 录音按钮
        PulsingContainer(
            modifier = Modifier.size(120.dp),
            pulseColor = MaterialTheme.colorScheme.error
        ) {
            FloatingActionButton(
                onClick = { /* stop recording */ },
                modifier = Modifier.size(80.dp),
                containerColor = MaterialTheme.colorScheme.error
            ) {
                Icon(
                    Icons.Default.Stop,
                    contentDescription = "停止录音",
                    modifier = Modifier.size(40.dp)
                )
            }
        }
    }
}

/**
 * 示例2: 报告生成页面 - 使用圆形进度和波浪进度
 */
@Composable
fun ReportGeneratingExample() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularGradientProgress(
            progress = 0.75f,
            size = 160.dp
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "75%",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "生成报告中",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(40.dp))

        WaveProgressIndicator(
            progress = 0.75f,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * 示例3: 完成庆祝页面 - 使用粒子爆炸
 */
@Composable
fun CompletionCelebrationExample() {
    var celebrate by remember { mutableStateOf(true) }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        ParticleExplosion(
            trigger = celebrate,
            modifier = Modifier.fillMaxSize(),
            onAnimationComplete = { celebrate = false }
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.tertiary
            )

            Text(
                text = "报告生成成功！",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            GradientButton(
                onClick = { /* view report */ },
                gradient = listOf(
                    MaterialTheme.colorScheme.tertiary,
                    MaterialTheme.colorScheme.primary
                )
            ) {
                Text("查看报告", fontWeight = FontWeight.Bold)
            }
        }
    }
}
