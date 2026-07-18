import java.awt.*
import java.awt.image.BufferedImage
import java.io.FileOutputStream
import javax.imageio.ImageIO

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    kotlin("plugin.serialization") version "1.9.22"
}

fun loadEnvFile(path: String): Map<String, String> {
    val file = rootProject.file(path)
    if (!file.exists()) return emptyMap()
    return file.readLines()
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("#") && it.contains("=") }
        .associate { line ->
            val idx = line.indexOf("=")
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim().removeSurrounding("\"")
            key to value
        }
}

val claudeEnv = loadEnvFile("local.defaults.env")

android {
    namespace = "com.oa.automation"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.oa.automation"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets")
        }
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

android.defaultConfig {
    val anthropicBaseUrl = claudeEnv["ANTHROPIC_BASE_URL"] ?: ""
    val anthropicModel = claudeEnv["ANTHROPIC_MODEL"]
        ?: claudeEnv["ANTHROPIC_DEFAULT_SONNET_MODEL"]
        ?: ""

    buildConfigField("String", "DEFAULT_CLAUDE_BASE_URL", "\"$anthropicBaseUrl\"")
    // API keys must be entered at runtime; embedding one in an APK exposes it to every user.
    buildConfigField("String", "DEFAULT_CLAUDE_API_KEY", "\"\"")
    buildConfigField("String", "DEFAULT_CLAUDE_MODEL", "\"$anthropicModel\"")

    // The public STT endpoint is intentionally not a credential. Commercial access
    // tokens must be provisioned by the service, never embedded in an APK.
    buildConfigField("String", "DEFAULT_STT_ENDPOINT", "\"http://118.25.43.185:8888\"")
    buildConfigField("String", "DEFAULT_STT_MODEL", "\"small\"")
    buildConfigField("String", "DEFAULT_STT_TRIAL_TOKEN", "\"meetingnotes-trial\"")

    // Agent requests are routed through a server-side gateway. The gateway owns
    // Codex CLI / Claude CLI credentials and returns scoped user responses.
    buildConfigField("String", "DEFAULT_AGENT_ENDPOINT", "\"https://118.25.43.185/api/agent\"")

    // Default LLM cloud settings
    buildConfigField("String", "DEFAULT_LLM_CLOUD_ENDPOINT", "\"https://api.minimaxi.com/anthropic\"")
    buildConfigField("String", "DEFAULT_LLM_CLOUD_API_KEY", "\"\"")
    buildConfigField("String", "DEFAULT_LLM_CLOUD_MODEL", "\"MiniMax-M2.5\"")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("io.insert-koin:koin-android:3.5.3")
    implementation("io.insert-koin:koin-androidx-compose:3.5.3")

    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // DataStore for configuration persistence
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // OkHttp for HTTP client (STT/LLM API calls)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Generate launcher icon PNGs with book + "悟" character
val densities = mapOf(
    "mipmap-hdpi" to 72,
    "mipmap-xhdpi" to 96,
    "mipmap-xxhdpi" to 144,
    "mipmap-xxxhdpi" to 192
)

tasks.register("generateIconPngs") {
    description = "Generate launcher icon PNGs with book and 悟 character"
    group = "icon"
    doLast {
        densities.forEach { (folder, size) ->
            val outDir = file("src/main/res/$folder")
            outDir.mkdirs()
            listOf("ic_launcher.png", "ic_launcher_round.png").forEach { fileName ->
                val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
                val g = img.createGraphics()
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)

                val s = size.toFloat() / 108f

                // Background
                g.color = Color(0x2563EB)
                g.fillRoundRect(0, 0, size, size, size / 5, size / 5)

                // Book spine
                g.color = Color(0x1E3A8A)
                g.fillRect((28 * s).toInt(), (30 * s).toInt(), (5 * s).toInt(), (48 * s).toInt())

                // Binding holes
                g.color = Color(0x3B82F6)
                val holeR = (1.2f * s).toInt()
                for (cy in listOf(40f, 54f, 68f)) {
                    g.fillOval((29.5f * s - holeR).toInt(), (cy * s - holeR).toInt(), holeR * 2, holeR * 2)
                }

                // Page layers
                g.color = Color(0x93C5FD)
                g.fillRect((76 * s).toInt(), (33 * s).toInt(), (4 * s).toInt(), (42 * s).toInt())
                g.color = Color(0xBFDBFE)
                g.fillRect((74 * s).toInt(), (31.5f * s).toInt(), (4 * s).toInt(), (45 * s).toInt())

                // Main page
                g.color = Color.WHITE
                g.fillRoundRect((33 * s).toInt(), (30 * s).toInt(), (43 * s).toInt(), (48 * s).toInt(), (2 * s).toInt(), (2 * s).toInt())

                // Character 悟
                g.color = Color(0x1E40AF)
                g.font = Font("Microsoft YaHei", Font.BOLD, (30 * s).toInt())
                val fm = g.fontMetrics
                val textW = fm.stringWidth("悟")
                val textX = (53.5f * s - textW / 2f).toInt()
                val textY = (54f * s - (fm.ascent + fm.descent) / 2f).toInt()
                g.drawString("悟", textX, textY)

                g.dispose()
                val outFile = outDir.resolve(fileName)
                val os = FileOutputStream(outFile)
                ImageIO.write(img, "png", os)
                os.close()
                println("Generated: ${outFile.absolutePath}")
            }
        }
    }
}
