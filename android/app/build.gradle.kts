import java.awt.*
import java.awt.image.BufferedImage
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import java.io.FileInputStream
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Properties
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

fun endpointHost(endpoint: String): String = runCatching {
    URI(endpoint).host.orEmpty().trim().trimEnd('.').lowercase()
}.getOrDefault("")

data class SigningSpec(
    val variant: String,
    val storeFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
    val storeType: String = KeyStore.getDefaultType()
)

fun resolveSigningFile(path: String): File {
    val expanded = path
        .replace("\${user.home}", System.getProperty("user.home"))
        .replace("\${USER_HOME}", System.getProperty("user.home"))
    val candidate = File(expanded)
    return if (candidate.isAbsolute) candidate else rootProject.file(expanded)
}

val defaultSigningPropertiesFile = File(
    System.getProperty("user.home"),
    ".meetingnotes/signing.properties"
)
val signingPropertiesPath = providers.gradleProperty("meetingnotesSigningPropertiesFile")
    .orElse(providers.environmentVariable("MEETINGNOTES_SIGNING_PROPERTIES_FILE"))
    .orElse(defaultSigningPropertiesFile.absolutePath)
    .get()
val signingPropertiesFile = rootProject.file(signingPropertiesPath)
val signingProperties = Properties().apply {
    if (signingPropertiesFile.isFile) {
        FileInputStream(signingPropertiesFile).use { load(it) }
    }
}
val signingFingerprintsFile = rootProject.file("signing-fingerprints.properties")
val signingFingerprints = Properties().apply {
    require(signingFingerprintsFile.isFile) {
        "Missing versioned signing fingerprint registry: ${signingFingerprintsFile.absolutePath}"
    }
    FileInputStream(signingFingerprintsFile).use { load(it) }
}

fun signingValue(variant: String, field: String): String? {
    val normalizedVariant = variant.uppercase()
    val normalizedField = when (field) {
        "storeFile" -> "STORE_FILE"
        "storePassword" -> "STORE_PASSWORD"
        "keyAlias" -> "KEY_ALIAS"
        "keyPassword" -> "KEY_PASSWORD"
        "storeType" -> "STORE_TYPE"
        else -> error("Unsupported signing field: $field")
    }
    val gradlePropertyName = "meetingnotes${variant.replaceFirstChar { it.uppercase() }}${field.replaceFirstChar { it.uppercase() }}"
    return System.getenv("MEETINGNOTES_${normalizedVariant}_${normalizedField}")
        ?.takeIf { it.isNotBlank() }
        ?: providers.gradleProperty(gradlePropertyName).orNull?.takeIf { it.isNotBlank() }
        ?: signingProperties.getProperty("${variant.lowercase()}.$field")?.trim()?.takeIf { it.isNotBlank() }
}

fun loadSigningSpec(variant: String): SigningSpec? {
    val storeFile = signingValue(variant, "storeFile")
    val storePassword = signingValue(variant, "storePassword")
    val keyAlias = signingValue(variant, "keyAlias")
    val keyPassword = signingValue(variant, "keyPassword")
    val storeType = signingValue(variant, "storeType") ?: KeyStore.getDefaultType()
    val values = listOf(storeFile, storePassword, keyAlias, keyPassword)
    if (values.all { it == null }) return null
    require(values.all { !it.isNullOrBlank() }) {
        "Incomplete $variant signing configuration. Set all four ${variant.lowercase()} signing values " +
            "in android/signing.properties or MEETINGNOTES_${variant.uppercase()}_* environment variables."
    }
    return SigningSpec(
        variant = variant.lowercase(),
        storeFile = resolveSigningFile(storeFile!!),
        storePassword = storePassword!!,
        keyAlias = keyAlias!!,
        keyPassword = keyPassword!!,
        storeType = storeType
    )
}

val debugSigning = loadSigningSpec("debug")
val releaseSigning = loadSigningSpec("release")

fun signingFingerprint(spec: SigningSpec): String {
    require(spec.storeFile.isFile) {
        "${spec.variant} signing keystore does not exist: ${spec.storeFile.absolutePath}"
    }
    val keyStore = KeyStore.getInstance(spec.storeType)
    FileInputStream(spec.storeFile).use { input ->
        keyStore.load(input, spec.storePassword.toCharArray())
    }
    val certificate = keyStore.getCertificate(spec.keyAlias)
        ?: error("${spec.variant} signing alias not found: ${spec.keyAlias}")
    return MessageDigest.getInstance("SHA-256")
        .digest(certificate.encoded)
        .joinToString(":") { byte -> "%02X".format(byte) }
}

fun verifiedSigningFingerprint(spec: SigningSpec): String {
    val expected = signingFingerprints.getProperty("${spec.variant}.sha256")
        ?.replace(":", "")
        ?.trim()
        ?.lowercase()
        .orEmpty()
    require(expected.matches(Regex("[0-9a-f]{64}"))) {
        "No pinned ${spec.variant} certificate SHA-256 exists in " +
            "${signingFingerprintsFile.name}. Register the public fingerprint before building this APK."
    }
    val actual = signingFingerprint(spec)
    require(actual.replace(":", "").lowercase() == expected) {
        "${spec.variant} signing certificate does not match the versioned SHA-256 registry. " +
            "Refusing to produce an incompatible upgrade."
    }
    return actual
}

val claudeEnv = loadEnvFile("local.defaults.env")
val legacySttEndpoint = claudeEnv["MEETINGNOTES_STT_ENDPOINT"]
    ?.takeIf { it.isNotBlank() }
val debugSttEndpoint = claudeEnv["MEETINGNOTES_STT_DEBUG_ENDPOINT"]
    ?.takeIf { it.isNotBlank() }
    ?: legacySttEndpoint
    ?: "http://10.0.2.2:8888"
val releaseSttEndpoint = claudeEnv["MEETINGNOTES_STT_RELEASE_ENDPOINT"]
    ?.takeIf { it.isNotBlank() }
    .orEmpty()
val debugSttRelayAddress = claudeEnv["MEETINGNOTES_STT_DEBUG_IPV4_RELAY_ADDRESS"]
    ?.takeIf { it.isNotBlank() }
    .orEmpty()
val releaseSttRelayAddress = claudeEnv["MEETINGNOTES_STT_IPV4_RELAY_ADDRESS"]
    ?.takeIf { it.isNotBlank() }
    .orEmpty()

android {
    namespace = "com.oa.automation"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.oa.automation"
        minSdk = 26
        targetSdk = 34
        versionCode = 10227
        versionName = "1.2.27"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        debugSigning?.let { spec ->
            create("meetingNotesDebug") {
                storeFile = spec.storeFile
                storePassword = spec.storePassword
                keyAlias = spec.keyAlias
                keyPassword = spec.keyPassword
                storeType = spec.storeType
            }
        }
        releaseSigning?.let { spec ->
            create("meetingNotesRelease") {
                storeFile = spec.storeFile
                storePassword = spec.storePassword
                keyAlias = spec.keyAlias
                keyPassword = spec.keyPassword
                storeType = spec.storeType
            }
        }
    }

    buildTypes {
        getByName("debug") {
            buildConfigField("String", "DEFAULT_STT_ENDPOINT", "\"$debugSttEndpoint\"")
            buildConfigField("String", "DEFAULT_STT_RELAY_HOST", "\"${endpointHost(debugSttEndpoint)}\"")
            buildConfigField("String", "DEFAULT_STT_IPV4_RELAY_ADDRESS", "\"$debugSttRelayAddress\"")
            debugSigning?.let {
                signingConfig = signingConfigs.getByName("meetingNotesDebug")
            }
        }
        release {
            buildConfigField("String", "DEFAULT_STT_ENDPOINT", "\"$releaseSttEndpoint\"")
            buildConfigField("String", "DEFAULT_STT_RELAY_HOST", "\"${endpointHost(releaseSttEndpoint)}\"")
            buildConfigField("String", "DEFAULT_STT_IPV4_RELAY_ADDRESS", "\"$releaseSttRelayAddress\"")
            releaseSigning?.let {
                signingConfig = signingConfigs.getByName("meetingNotesRelease")
            }
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
        getByName("androidTest") {
            assets.srcDirs(
                "../../docs/test-materials/report-fixtures",
                "../../docs/test-materials/images/curated"
            )
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

fun requireSigning(variant: String, spec: SigningSpec?): SigningSpec {
    return spec ?: throw GradleException(
        "No stable $variant signing configuration was found. " +
            "Configure android/signing.properties or the MEETINGNOTES_${variant.uppercase()}_* environment variables " +
            "before building an APK. Refusing to produce an APK that cannot be upgraded safely."
    )
}

val verifyDebugSigning = tasks.register("verifyDebugSigning") {
    group = "verification"
    description = "Verify the stable certificate used by debug APKs"
    doLast {
        val spec = requireSigning("debug", debugSigning)
        println("debug signing certificate SHA-256: ${verifiedSigningFingerprint(spec)}")
    }
}

val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    group = "verification"
    description = "Verify the stable certificate used by release APKs"
    doLast {
        val spec = requireSigning("release", releaseSigning)
        println("release signing certificate SHA-256: ${verifiedSigningFingerprint(spec)}")
    }
}

val verifyReleaseServiceEndpoints = tasks.register("verifyReleaseServiceEndpoints") {
    group = "verification"
    description = "Verify that release service endpoints are explicitly configured"
    doLast {
        require(releaseSttEndpoint.startsWith("https://")) {
            "MEETINGNOTES_STT_RELEASE_ENDPOINT must be configured with an HTTPS URL before building a release."
        }
        require(endpointHost(releaseSttEndpoint).isNotBlank()) {
            "MEETINGNOTES_STT_RELEASE_ENDPOINT must contain a valid hostname."
        }
        require(releaseSttRelayAddress.matches(Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"))) {
            "MEETINGNOTES_STT_IPV4_RELAY_ADDRESS must be configured as an IPv4 address before building a release."
        }
    }
}

tasks.register("verifySigningConfig") {
    group = "verification"
    description = "Verify both debug and release signing certificates"
    dependsOn(verifyDebugSigning, verifyReleaseSigning)
}

tasks.matching { it.name == "preDebugBuild" }.configureEach {
    dependsOn(verifyDebugSigning)
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyReleaseSigning, verifyReleaseServiceEndpoints)
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
    val sttModel = claudeEnv["MEETINGNOTES_STT_MODEL"]
        ?.takeIf { it.isNotBlank() }
        ?: "large-v3-turbo"
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
        ?: 120
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
        ?.coerceIn(60, 21_600)
        ?: 14_400
    val sttCloudWriteTimeoutSeconds = claudeEnv["MEETINGNOTES_STT_CLOUD_WRITE_TIMEOUT_SECONDS"]
        ?.toIntOrNull()
        ?.coerceIn(30, 7_200)
        ?: 3_600
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
            .orElse("src/main/icon/img.png")
            .get()
        val masterFile = file(configuredMaster)
        require(masterFile.isFile) {
            "Launcher icon master not found: ${masterFile.absolutePath}"
        }
        val master = ImageIO.read(masterFile)
        val contentScale = providers.gradleProperty("launcherIconContentScale")
            .orElse(providers.environmentVariable("MEETINGNOTES_ICON_CONTENT_SCALE"))
            // Keep the logo inside the adaptive-icon safe zone so the wordmark remains legible.
            .orElse("0.56")
            .get()
            .toDouble()
        require(contentScale in 0.5..1.0) {
            "Launcher icon content scale must be between 0.5 and 1.0"
        }
        val masterCropScale = providers.gradleProperty("launcherIconMasterCropScale")
            .orElse(providers.environmentVariable("MEETINGNOTES_ICON_MASTER_CROP_SCALE"))
            .orElse("0.88")
            .get()
            .toDouble()
        require(masterCropScale in 0.7..1.0) {
            "Launcher icon master crop scale must be between 0.7 and 1.0"
        }
        val brandCropScale = providers.gradleProperty("launcherBrandCropScale")
            .orElse(providers.environmentVariable("MEETINGNOTES_BRAND_ICON_CROP_SCALE"))
            .orElse("1.0")
            .get()
            .toDouble()
        require(brandCropScale in 0.7..1.0) {
            "Brand icon crop scale must be between 0.7 and 1.0"
        }
        val masterCropSize = (minOf(master.width, master.height) * masterCropScale).toInt()
        val masterCropLeft = (master.width - masterCropSize) / 2
        val masterCropTop = (master.height - masterCropSize) / 2
        val croppedMaster = master.getSubimage(
            masterCropLeft,
            masterCropTop,
            masterCropSize,
            masterCropSize
        )
        val launcherCanvasSize = masterCropSize
        val launcherArtwork = BufferedImage(
            launcherCanvasSize,
            launcherCanvasSize,
            BufferedImage.TYPE_INT_ARGB
        )
        val launcherGraphics = launcherArtwork.createGraphics()
        launcherGraphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        launcherGraphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        launcherGraphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        launcherGraphics.color = Color(0x02, 0x37, 0x88)
        launcherGraphics.fillRect(0, 0, launcherCanvasSize, launcherCanvasSize)
        val availableSize = launcherCanvasSize * contentScale
        val imageScale = minOf(availableSize / croppedMaster.width, availableSize / croppedMaster.height)
        val targetWidth = (croppedMaster.width * imageScale).toInt()
        val targetHeight = (croppedMaster.height * imageScale).toInt()
        val left = (launcherCanvasSize - targetWidth) / 2
        val top = (launcherCanvasSize - targetHeight) / 2
        launcherGraphics.drawImage(croppedMaster, left, top, targetWidth, targetHeight, null)
        launcherGraphics.dispose()

        val legacyArtwork = BufferedImage(
            launcherCanvasSize,
            launcherCanvasSize,
            BufferedImage.TYPE_INT_ARGB
        )
        val legacyGraphics = legacyArtwork.createGraphics()
        legacyGraphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        legacyGraphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        legacyGraphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        legacyGraphics.color = Color(0x02, 0x37, 0x88)
        legacyGraphics.fillRect(0, 0, launcherCanvasSize, launcherCanvasSize)
        legacyGraphics.drawImage(launcherArtwork, 0, 0, null)
        legacyGraphics.dispose()

        fun roundedArtwork(source: BufferedImage, size: Int): BufferedImage {
            val rounded = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val graphics = rounded.createGraphics()
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            val cornerDiameter = size * 0.47
            graphics.clip = RoundRectangle2D.Double(
                0.0,
                0.0,
                size.toDouble(),
                size.toDouble(),
                cornerDiameter,
                cornerDiameter
            )
            graphics.drawImage(source, 0, 0, size, size, null)
            graphics.dispose()
            return rounded
        }

        fun circularArtwork(source: BufferedImage, size: Int): BufferedImage {
            val circular = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val graphics = circular.createGraphics()
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.clip = Ellipse2D.Double(0.0, 0.0, size.toDouble(), size.toDouble())
            graphics.drawImage(source, 0, 0, size, size, null)
            graphics.dispose()
            return circular
        }

        densities.forEach { (folder, size) ->
            val outDir = file("src/main/res/$folder")
            outDir.mkdirs()
            val launcherFile = outDir.resolve("ic_launcher.png")
            ImageIO.write(roundedArtwork(legacyArtwork, size), "png", launcherFile)
            println("Generated: ${launcherFile.absolutePath}")
            val roundLauncherFile = outDir.resolve("ic_launcher_round.png")
            ImageIO.write(circularArtwork(legacyArtwork, size), "png", roundLauncherFile)
            println("Generated: ${roundLauncherFile.absolutePath}")
        }
        val brandIcon = file("src/main/res/drawable-nodpi/brand_icon.png")
        brandIcon.parentFile.mkdirs()
        val brandCropSize = (minOf(croppedMaster.width, croppedMaster.height) * brandCropScale).toInt()
        val brandCropLeft = (croppedMaster.width - brandCropSize) / 2
        val brandCropTop = (croppedMaster.height - brandCropSize) / 2
        val brandArtwork = croppedMaster.getSubimage(
            brandCropLeft,
            brandCropTop,
            brandCropSize,
            brandCropSize
        )
        val roundedBrandArtwork = roundedArtwork(brandArtwork, brandCropSize)
        ImageIO.write(roundedBrandArtwork, "png", brandIcon)
        println("Generated: ${brandIcon.absolutePath} (${brandCropSize}x${brandCropSize} crop)")
        val adaptiveForeground = file("src/main/res/drawable-nodpi/launcher_foreground_art.png")
        ImageIO.write(launcherArtwork, "png", adaptiveForeground)
        println("Generated: ${adaptiveForeground.absolutePath}")
    }
}

tasks.named("preBuild").configure {
    dependsOn("generateIconPngs")
}
