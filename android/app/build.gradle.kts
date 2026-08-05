import java.awt.*
import java.awt.image.BufferedImage
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

fun loadRelayConfig(path: String): Map<String, String> {
    if (path.isBlank()) return emptyMap()
    val file = rootProject.file(path)
    if (!file.exists()) return emptyMap()
    return file.readLines()
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapNotNull { line ->
            val separator = when {
                line.contains("=") -> '='
                line.contains(":") -> ':'
                else -> return@mapNotNull null
            }
            val index = line.indexOf(separator)
            if (index <= 0) return@mapNotNull null
            val key = line.substring(0, index).trim().lowercase()
            val value = line.substring(index + 1).trim().removeSurrounding("\"")
            key to value
        }
        .toMap()
}

val claudeEnv = loadEnvFile("local.defaults.env")

android {
    namespace = "com.oa.automation"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.oa.automation"
        minSdk = 26
        targetSdk = 34
        versionCode = 10213
        versionName = "1.2.13"

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
    val relayConfig = loadRelayConfig(
        System.getenv("MEETINGNOTES_RELAY_CONFIG_FILE")
            ?: claudeEnv["MEETINGNOTES_RELAY_CONFIG_FILE"].orEmpty()
    )
    val anthropicBaseUrl = claudeEnv["ANTHROPIC_BASE_URL"] ?: ""
    val anthropicModel = claudeEnv["ANTHROPIC_MODEL"]
        ?: claudeEnv["ANTHROPIC_DEFAULT_SONNET_MODEL"]
        ?: ""
    val sttEndpoint = claudeEnv["MEETINGNOTES_STT_ENDPOINT"]
        ?.takeIf { it.isNotBlank() }
        ?: "http://localhost:8888"
    val sttModel = claudeEnv["MEETINGNOTES_STT_MODEL"]
        ?.takeIf { it.isNotBlank() }
        ?: "small"
    val sttCloudEndpoint = claudeEnv["MEETINGNOTES_STT_CLOUD_ENDPOINT"]
        ?.takeIf { it.isNotBlank() }
        ?: ""
    val sttCloudModel = claudeEnv["MEETINGNOTES_STT_CLOUD_MODEL"]
        ?.takeIf { it.isNotBlank() }
        ?: "whisper-1"
    val sttRemoteSwitchEnabled = claudeEnv["MEETINGNOTES_STT_REMOTE_SWITCH_ENABLED"]
        ?.toBooleanStrictOrNull()
        ?: false
    val sttSwitchTimeoutSeconds = claudeEnv["MEETINGNOTES_STT_SWITCH_TIMEOUT_SECONDS"]
        ?.toIntOrNull()
        ?.coerceIn(15, 600)
        ?: 90
    val sttStreamSwitchTimeoutSeconds = claudeEnv[
        "MEETINGNOTES_STT_STREAM_SWITCH_TIMEOUT_SECONDS"
    ]
        ?.toIntOrNull()
        ?.coerceIn(3, 120)
        ?: 20
    val sttStreamUiUpdateIntervalMs = claudeEnv[
        "MEETINGNOTES_STT_STREAM_UI_UPDATE_INTERVAL_MS"
    ]
        ?.toIntOrNull()
        ?.coerceIn(80, 1_000)
        ?: 180
    val transcriptPreviewMaxChars = claudeEnv[
        "MEETINGNOTES_TRANSCRIPT_PREVIEW_MAX_CHARS"
    ]
        ?.toIntOrNull()
        ?.coerceIn(800, 8_000)
        ?: 2_400
    val sttCloudConnectTimeoutSeconds = claudeEnv["MEETINGNOTES_STT_CLOUD_CONNECT_TIMEOUT_SECONDS"]
        ?.toIntOrNull()
        ?.coerceIn(5, 120)
        ?: 30
    val sttCloudReadTimeoutSeconds = claudeEnv["MEETINGNOTES_STT_CLOUD_READ_TIMEOUT_SECONDS"]
        ?.toIntOrNull()
        ?.coerceIn(60, 3600)
        ?: 1800
    val sttCloudWriteTimeoutSeconds = claudeEnv["MEETINGNOTES_STT_CLOUD_WRITE_TIMEOUT_SECONDS"]
        ?.toIntOrNull()
        ?.coerceIn(30, 1800)
        ?: 600
    val profileDisplayNameMaxLength = claudeEnv["MEETINGNOTES_PROFILE_NAME_MAX_LENGTH"]
        ?.toIntOrNull()
        ?.coerceIn(1, 100)
        ?: 40
    val profileAvatarMaxDimension = claudeEnv["MEETINGNOTES_PROFILE_AVATAR_MAX_DIMENSION"]
        ?.toIntOrNull()
        ?.coerceIn(128, 2048)
        ?: 512
    val profileAvatarJpegQuality = claudeEnv["MEETINGNOTES_PROFILE_AVATAR_JPEG_QUALITY"]
        ?.toIntOrNull()
        ?.coerceIn(50, 100)
        ?: 86
    val profileAvatarMaxBytes = claudeEnv["MEETINGNOTES_PROFILE_AVATAR_MAX_BYTES"]
        ?.toIntOrNull()
        ?.coerceIn(32 * 1024, 2 * 1024 * 1024)
        ?: 262_144

    buildConfigField("String", "DEFAULT_CLAUDE_BASE_URL", "\"$anthropicBaseUrl\"")
    // API keys must be entered at runtime; embedding one in an APK exposes it to every user.
    buildConfigField("String", "DEFAULT_CLAUDE_API_KEY", "\"\"")
    buildConfigField("String", "DEFAULT_CLAUDE_MODEL", "\"$anthropicModel\"")

    // STT endpoint/model are build-time environment defaults. Access tokens are
    // provisioned at runtime and must never be embedded in the APK.
    buildConfigField("String", "DEFAULT_STT_ENDPOINT", "\"$sttEndpoint\"")
    buildConfigField("String", "DEFAULT_STT_MODEL", "\"$sttModel\"")
    buildConfigField("String", "DEFAULT_STT_CLOUD_ENDPOINT", "\"$sttCloudEndpoint\"")
    buildConfigField("String", "DEFAULT_STT_CLOUD_MODEL", "\"$sttCloudModel\"")
    buildConfigField("boolean", "STT_REMOTE_SWITCH_ENABLED", sttRemoteSwitchEnabled.toString())
    buildConfigField("int", "STT_SWITCH_TIMEOUT_SECONDS", sttSwitchTimeoutSeconds.toString())
    buildConfigField(
        "int",
        "STT_STREAM_SWITCH_TIMEOUT_SECONDS",
        sttStreamSwitchTimeoutSeconds.toString()
    )
    buildConfigField(
        "int",
        "STT_STREAM_UI_UPDATE_INTERVAL_MS",
        sttStreamUiUpdateIntervalMs.toString()
    )
    buildConfigField(
        "int",
        "TRANSCRIPT_PREVIEW_MAX_CHARS",
        transcriptPreviewMaxChars.toString()
    )
    buildConfigField("int", "STT_CLOUD_CONNECT_TIMEOUT_SECONDS", sttCloudConnectTimeoutSeconds.toString())
    buildConfigField("int", "STT_CLOUD_READ_TIMEOUT_SECONDS", sttCloudReadTimeoutSeconds.toString())
    buildConfigField("int", "STT_CLOUD_WRITE_TIMEOUT_SECONDS", sttCloudWriteTimeoutSeconds.toString())
    buildConfigField("int", "PROFILE_NAME_MAX_LENGTH", profileDisplayNameMaxLength.toString())
    buildConfigField("int", "PROFILE_AVATAR_MAX_DIMENSION", profileAvatarMaxDimension.toString())
    buildConfigField("int", "PROFILE_AVATAR_JPEG_QUALITY", profileAvatarJpegQuality.toString())
    buildConfigField("int", "PROFILE_AVATAR_MAX_BYTES", profileAvatarMaxBytes.toString())

    val defaultAgentEndpoint = claudeEnv["MEETINGNOTES_AGENT_ENDPOINT"]
        ?.takeIf { it.isNotBlank() }
        ?: "http://localhost:8090/api/agent"
    val defaultAccountEndpoint = claudeEnv["MEETINGNOTES_ACCOUNT_ENDPOINT"]
        ?.takeIf { it.isNotBlank() }
        ?: "http://localhost:8090/api"
    val defaultAppUpdateEndpoint = claudeEnv["MEETINGNOTES_APP_UPDATE_ENDPOINT"]
        ?.takeIf { it.isNotBlank() }
        ?: "${defaultAccountEndpoint.trimEnd('/')}/app-update/android"
    val defaultRelayBaseUrl = relayConfig["baseurl"]
        ?.takeIf { it.isNotBlank() }
        ?: claudeEnv["MEETINGNOTES_RELAY_BASE_URL"]?.takeIf { it.isNotBlank() }
        ?: ""

    // Agent requests are routed through a server-side gateway. The gateway owns
    // Codex CLI / Claude CLI credentials and returns scoped user responses.
    buildConfigField("String", "DEFAULT_AGENT_ENDPOINT", "\"$defaultAgentEndpoint\"")
    buildConfigField("String", "DEFAULT_ACCOUNT_ENDPOINT", "\"$defaultAccountEndpoint\"")
    buildConfigField("String", "DEFAULT_APP_UPDATE_ENDPOINT", "\"$defaultAppUpdateEndpoint\"")
    buildConfigField("String", "DEFAULT_RELAY_BASE_URL", "\"$defaultRelayBaseUrl\"")

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

// Generate legacy launcher PNGs from the project-owned high-resolution master.
val densities = mapOf(
    "mipmap-mdpi" to 48,
    "mipmap-hdpi" to 72,
    "mipmap-xhdpi" to 96,
    "mipmap-xxhdpi" to 144,
    "mipmap-xxxhdpi" to 192
)

tasks.register("generateIconPngs") {
    description = "Generate launcher icon PNGs from the configured master image"
    group = "icon"
    doLast {
        val configuredMaster = providers.gradleProperty("launcherIconMaster")
            .orElse(providers.environmentVariable("MEETINGNOTES_ICON_MASTER"))
            .orElse("src/main/icon/launcher-master.png")
            .get()
        val masterFile = file(configuredMaster)
        require(masterFile.isFile) {
            "Launcher icon master not found: ${masterFile.absolutePath}"
        }
        val master = ImageIO.read(masterFile)
        val contentScale = providers.gradleProperty("launcherIconContentScale")
            .orElse(providers.environmentVariable("MEETINGNOTES_ICON_CONTENT_SCALE"))
            .orElse("1.0")
            .get()
            .toDouble()
        require(contentScale in 0.5..1.0) {
            "Launcher icon content scale must be between 0.5 and 1.0"
        }
        val brandCropScale = providers.gradleProperty("launcherBrandCropScale")
            .orElse(providers.environmentVariable("MEETINGNOTES_BRAND_ICON_CROP_SCALE"))
            .orElse("0.82")
            .get()
            .toDouble()
        require(brandCropScale in 0.7..1.0) {
            "Brand icon crop scale must be between 0.7 and 1.0"
        }
        val launcherCanvasSize = maxOf(master.width, master.height)
        val launcherArtwork = BufferedImage(
            launcherCanvasSize,
            launcherCanvasSize,
            BufferedImage.TYPE_INT_ARGB
        )
        val launcherGraphics = launcherArtwork.createGraphics()
        launcherGraphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        launcherGraphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        launcherGraphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        val availableSize = launcherCanvasSize * contentScale
        val imageScale = minOf(availableSize / master.width, availableSize / master.height)
        val targetWidth = (master.width * imageScale).toInt()
        val targetHeight = (master.height * imageScale).toInt()
        val left = (launcherCanvasSize - targetWidth) / 2
        val top = (launcherCanvasSize - targetHeight) / 2
        launcherGraphics.drawImage(master, left, top, targetWidth, targetHeight, null)
        launcherGraphics.dispose()
        densities.forEach { (folder, size) ->
            val outDir = file("src/main/res/$folder")
            outDir.mkdirs()
            listOf("ic_launcher.png", "ic_launcher_round.png").forEach { fileName ->
                val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
                val g = img.createGraphics()
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                g.drawImage(launcherArtwork, 0, 0, size, size, null)
                g.dispose()
                val outFile = outDir.resolve(fileName)
                ImageIO.write(img, "png", outFile)
                println("Generated: ${outFile.absolutePath}")
            }
        }
        val brandIcon = file("src/main/res/drawable-nodpi/brand_icon.png")
        brandIcon.parentFile.mkdirs()
        val brandCropSize = (minOf(master.width, master.height) * brandCropScale).toInt()
        val brandCropLeft = (master.width - brandCropSize) / 2
        val brandCropTop = (master.height - brandCropSize) / 2
        val brandArtwork = master.getSubimage(
            brandCropLeft,
            brandCropTop,
            brandCropSize,
            brandCropSize
        )
        ImageIO.write(brandArtwork, "png", brandIcon)
        println("Generated: ${brandIcon.absolutePath} (${brandCropSize}x${brandCropSize} crop)")
        println("Generated: ${brandIcon.absolutePath}")
        val adaptiveForeground = file("src/main/res/drawable-nodpi/launcher_foreground_art.png")
        ImageIO.write(launcherArtwork, "png", adaptiveForeground)
        println("Generated: ${adaptiveForeground.absolutePath}")
    }
}

tasks.named("preBuild").configure {
    dependsOn("generateIconPngs")
}
