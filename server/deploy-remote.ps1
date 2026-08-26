[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ServerHost,

    [Parameter(Mandatory = $true)]
    [string]$User,

    [Parameter(Mandatory = $true)]
    [string]$KeyPath,

    [ValidateRange(1, 65535)]
    [int]$Port = 22,

    [string]$ConfigFile = "",

    [string]$ReleaseId = "",

    [string]$PwaProject = "",

    [string]$AndroidApk = "",

    [string]$RemoteAppUpdateDirectory = "/var/lib/meetingnotes-stt/downloads",

    [string]$RemoteAppUpdateConfig = "/var/lib/meetingnotes-stt/app-update.json",

    [switch]$WithBackend,
    [switch]$OpenFirewall,
    [switch]$SkipModels,
    [switch]$SkipPackages,
    [switch]$NoSudo
)

$ErrorActionPreference = "Stop"
$ServerRoot = $PSScriptRoot
$ProjectRoot = Split-Path -Parent $ServerRoot
$AndroidRoot = Join-Path $ProjectRoot "android"
$VersionFile = Join-Path $ServerRoot "VERSION"
$Installer = Join-Path $ServerRoot "scripts\install-native.sh"
$AndroidSigningFingerprints = Join-Path $AndroidRoot "signing-fingerprints.properties"

function Resolve-AndroidBuildTool {
    param([Parameter(Mandatory = $true)][string]$Name)

    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }
    $sdkCandidates = @(
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT,
        (Join-Path $env:LOCALAPPDATA "Android\Sdk")
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) -and (Test-Path -LiteralPath $_ -PathType Container) }
    foreach ($sdkRoot in ($sdkCandidates | Select-Object -Unique)) {
        $buildToolsRoot = Join-Path $sdkRoot "build-tools"
        if (-not (Test-Path -LiteralPath $buildToolsRoot -PathType Container)) { continue }
        $tool = Get-ChildItem -LiteralPath $buildToolsRoot -Directory |
            Sort-Object Name -Descending |
            ForEach-Object {
                foreach ($extension in @(".bat", ".cmd", ".exe")) {
                    $candidate = Join-Path $_.FullName "$Name$extension"
                    if (Test-Path -LiteralPath $candidate -PathType Leaf) {
                        $candidate
                        break
                    }
                }
            } |
            Select-Object -First 1
        if ($tool) { return $tool }
    }
    throw "Android SDK build tool '$Name' is required to verify a release APK. Set ANDROID_HOME or add it to PATH."
}

function Get-ReleaseCertificateFingerprint {
    if (-not (Test-Path -LiteralPath $AndroidSigningFingerprints -PathType Leaf)) {
        throw "Missing Android signing fingerprint registry: $AndroidSigningFingerprints"
    }
    $line = Get-Content -LiteralPath $AndroidSigningFingerprints | Where-Object { $_ -match '^release\.sha256\s*=\s*([0-9a-fA-F]{64})\s*$' } | Select-Object -First 1
    if (-not $line -or $line -notmatch '^release\.sha256\s*=\s*([0-9a-fA-F]{64})\s*$') {
        throw "Android release SHA-256 fingerprint is missing or invalid."
    }
    return $Matches[1].ToLowerInvariant()
}

function Assert-AndroidReleaseApk {
    param(
        [Parameter(Mandatory = $true)][string]$ApkPath,
        [Parameter(Mandatory = $true)][int]$ExpectedVersionCode,
        [Parameter(Mandatory = $true)][string]$ExpectedVersionName
    )

    $apksigner = Resolve-AndroidBuildTool "apksigner"
    $aapt = Resolve-AndroidBuildTool "aapt"
    $signerOutput = & $apksigner verify --verbose --print-certs $ApkPath 2>&1
    if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed: $signerOutput" }
    $fingerprintLine = $signerOutput | Where-Object { $_ -match 'Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F:]+)' } | Select-Object -First 1
    if (-not $fingerprintLine -or $fingerprintLine -notmatch 'Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F:]+)') {
        throw "Could not read the APK signing certificate SHA-256."
    }
    $actualFingerprint = $Matches[1].Replace(":", "").ToLowerInvariant()
    if ($actualFingerprint -ne (Get-ReleaseCertificateFingerprint)) {
        throw "APK signing certificate does not match android/signing-fingerprints.properties."
    }

    $badging = & $aapt dump badging $ApkPath 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Could not read Android APK manifest: $badging" }
    $packageLine = $badging | Where-Object { $_ -match "^package: name='com\.oa\.automation' versionCode='([0-9]+)' versionName='([^']+)'" } | Select-Object -First 1
    if (-not $packageLine -or $packageLine -notmatch "^package: name='com\.oa\.automation' versionCode='([0-9]+)' versionName='([^']+)'") {
        throw "APK package must be com.oa.automation and expose versionCode/versionName."
    }
    if ([int]$Matches[1] -ne $ExpectedVersionCode -or $Matches[2] -ne $ExpectedVersionName) {
        throw "APK version does not match server/config/app-update.json."
    }
    $manifestTree = & $aapt dump xmltree $ApkPath AndroidManifest.xml 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Could not inspect Android APK manifest flags: $manifestTree" }
    if ($manifestTree -match 'debuggable\(0x0101000f\).*0xffffffff') {
        throw "Refusing to publish a debuggable APK through the release OTA channel."
    }
}

if ([string]::IsNullOrWhiteSpace($PwaProject)) {
    $PwaProject = Join-Path $ProjectRoot "pwa"
}
$PwaProject = (Resolve-Path -LiteralPath $PwaProject).Path
$PwaPackage = Join-Path $PwaProject "package.json"
$PwaDist = Join-Path $PwaProject "dist"
$AppUpdateConfig = Join-Path $ServerRoot "config\app-update.json"

foreach ($command in @("tar", "ssh", "scp")) {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) {
        throw "Required command is unavailable: $command"
    }
}
if (-not (Test-Path -LiteralPath $VersionFile -PathType Leaf)) {
    throw "Missing VERSION: $VersionFile"
}
if (-not (Test-Path -LiteralPath $Installer -PathType Leaf)) {
    throw "Missing installer: $Installer"
}
if (-not (Test-Path -LiteralPath $PwaPackage -PathType Leaf)) {
    throw "Missing PWA package: $PwaPackage"
}
if (-not (Get-Command "npm" -ErrorAction SilentlyContinue)) {
    throw "Required command is unavailable: npm"
}
$ResolvedKey = (Resolve-Path -LiteralPath $KeyPath).Path
if ($ConfigFile) {
    $ConfigFile = (Resolve-Path -LiteralPath $ConfigFile).Path
}
if ($AndroidApk) {
    $AndroidApk = (Resolve-Path -LiteralPath $AndroidApk).Path
    if (-not (Test-Path -LiteralPath $AppUpdateConfig -PathType Leaf)) {
        throw "Missing Android update manifest: $AppUpdateConfig"
    }
    if (-not $WithBackend) {
        throw "Publishing an Android update requires -WithBackend so the update endpoint is available."
    }
}

$Version = (Get-Content -LiteralPath $VersionFile -Raw).Trim()
if ($Version -notmatch '^\d+\.\d+\.\d+$') {
    throw "Invalid VERSION: $Version"
}
$Manifest = Get-Content -LiteralPath (Join-Path $ServerRoot "release-manifest.json") -Raw | ConvertFrom-Json
$ModelManifestPath = Join-Path $ServerRoot "model-manifest.sha256"
if ([string]::IsNullOrWhiteSpace($ReleaseId)) {
    $ReleaseId = "{0}-{1}" -f $Version, (Get-Date).ToUniversalTime().ToString("yyyyMMddHHmmssfff")
}
if ($ReleaseId -notmatch ('^' + [regex]::Escape($Version) + '-[A-Za-z0-9._+-]+$')) {
    throw "ReleaseId must start with VERSION ($Version) and contain only release-safe characters."
}
$TempRoot = Join-Path ([IO.Path]::GetTempPath()) "meetingnotes-$ReleaseId"
$Archive = Join-Path $TempRoot "meetingnotes-server-$ReleaseId.tar.gz"
$ModelsArchive = Join-Path $TempRoot "meetingnotes-models-$ReleaseId.tar"
$RemoteArchive = "/tmp/meetingnotes-server-$ReleaseId.tar.gz"
$RemoteInstaller = "/tmp/meetingnotes-install-$ReleaseId.sh"
$RemoteModels = "/tmp/meetingnotes-models-$ReleaseId.tar"
$RemoteConfig = "/tmp/meetingnotes-config-$ReleaseId.env"
$RemoteAndroidApk = "/tmp/meetingnotes-android-$ReleaseId.apk"
$RemoteAppUpdateManifest = "/tmp/meetingnotes-android-update-$ReleaseId.json"
$AppUpdateManifestForUpload = $AppUpdateConfig

New-Item -ItemType Directory -Path $TempRoot | Out-Null
try {
    Write-Host "[1/7] Building PWA"
    Push-Location $PwaProject
    try {
        & npm run build
        if ($LASTEXITCODE -ne 0) { throw "PWA build failed with exit code $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
    if (-not (Test-Path -LiteralPath (Join-Path $PwaDist "index.html") -PathType Leaf)) {
        throw "PWA build did not produce dist/index.html"
    }
    $PwaBundle = Join-Path $TempRoot "pwa-dist"
    Copy-Item -LiteralPath $PwaDist -Destination $PwaBundle -Recurse

    if ($AndroidApk) {
        $updateManifest = Get-Content -LiteralPath $AppUpdateConfig -Raw | ConvertFrom-Json
        $versionCode = [int]$updateManifest.version_code
        if ($versionCode -le 0 -or [string]::IsNullOrWhiteSpace([string]$updateManifest.version_name)) {
            throw "Android update manifest must provide a positive version_code and version_name."
        }
        Assert-AndroidReleaseApk -ApkPath $AndroidApk -ExpectedVersionCode $versionCode -ExpectedVersionName ([string]$updateManifest.version_name)
        $updateManifest | Add-Member -Force -NotePropertyName sha256 -NotePropertyValue (
            (Get-FileHash -LiteralPath $AndroidApk -Algorithm SHA256).Hash.ToLowerInvariant()
        )
        $updateManifest | Add-Member -Force -NotePropertyName apk_filename -NotePropertyValue (
            "ZhiWuBen-Android-$versionCode.apk"
        )
        $AppUpdateManifestForUpload = Join-Path $TempRoot "app-update.json"
        [IO.File]::WriteAllText(
            $AppUpdateManifestForUpload,
            ($updateManifest | ConvertTo-Json -Depth 8),
            [Text.UTF8Encoding]::new($false)
        )
    }

    Write-Host "[2/7] Packaging Server and PWA $ReleaseId"
    $tarArgs = @(
        "-czf", $Archive,
        "--exclude=./.env",
        "--exclude=./.env.*",
        "--exclude=./models",
        "--exclude=./data",
        "--exclude=./logs",
        "--exclude=./stt-service/runtime",
        "--exclude=./stt-service/pip",
        "--exclude=./stt-service/data",
        "--exclude=./stt-service/.switch_pending",
        "--exclude=./backend-service/runtime",
        "--exclude=./stt-service/__pycache__",
        "--exclude=./backend-service/__pycache__",
        "--exclude=./tests/__pycache__",
        "--exclude=./.pytest_cache",
        "--exclude=./.venv*",
        "--exclude=./tunnel_*.txt",
        "--exclude=./tunnel_*.log",
        "-C", $ServerRoot,
        ".",
        "-C", $TempRoot,
        "pwa-dist"
    )
    & tar @tarArgs
    if ($LASTEXITCODE -ne 0) { throw "tar failed with exit code $LASTEXITCODE" }

    $sshArgs = @(
        "-F", "NUL",
        "-i", $ResolvedKey,
        "-p", $Port,
        "-o", "BatchMode=yes",
        "-o", "StrictHostKeyChecking=accept-new"
    )
    $scpArgs = @(
        "-F", "NUL",
        "-i", $ResolvedKey,
        "-P", $Port,
        "-o", "BatchMode=yes",
        "-o", "StrictHostKeyChecking=accept-new"
    )
    $Target = "$User@$ServerHost"
    $Privilege = if ($NoSudo) { "" } else { "sudo " }

    Write-Host "[3/7] Testing key-based SSH access"
    & ssh @sshArgs $Target "printf connected"
    if ($LASTEXITCODE -ne 0) { throw "SSH connection failed." }
    Write-Host ""
    if (-not $NoSudo) {
        & ssh @sshArgs $Target "sudo -n true"
        if ($LASTEXITCODE -ne 0) {
            throw "The deployment account needs temporary passwordless sudo; model upload has not started."
        }
    }

    Write-Host "[4/7] Uploading release archive"
    & scp @scpArgs $Archive "${Target}:$RemoteArchive"
    if ($LASTEXITCODE -ne 0) { throw "Release upload failed." }
    & scp @scpArgs $Installer "${Target}:$RemoteInstaller"
    if ($LASTEXITCODE -ne 0) { throw "Installer upload failed." }

    $UploadModels = $false
    if (-not $SkipModels) {
        $manifestBytes = [Text.Encoding]::UTF8.GetBytes((Get-Content -LiteralPath $ModelManifestPath -Raw))
        $manifestBase64 = [Convert]::ToBase64String($manifestBytes)
        $verifyModels = "printf '%s' '$manifestBase64' | base64 -d | ${Privilege}bash -c 'cd /var/lib/meetingnotes-stt/models && sha256sum -c -'"
        & ssh @sshArgs $Target $verifyModels
        $UploadModels = $LASTEXITCODE -ne 0
    }
    if ($UploadModels) {
        $ModelsRoot = Join-Path $ServerRoot "models"
        if (-not (Test-Path -LiteralPath $ModelsRoot -PathType Container)) {
            throw "Remote model is missing and local models directory is unavailable."
        }
        Write-Host "[5/7] Uploading frozen STT models"
        & tar -cf $ModelsArchive -C $ModelsRoot "faster-whisper/small" "faster-whisper/tiny"
        if ($LASTEXITCODE -ne 0) { throw "Model packaging failed." }
        & scp @scpArgs $ModelsArchive "${Target}:$RemoteModels"
        if ($LASTEXITCODE -ne 0) { throw "Model upload failed." }
    } else {
        Write-Host "[5/7] Remote model already present; skipping model upload"
    }

    if ($ConfigFile) {
        & scp @scpArgs $ConfigFile "${Target}:$RemoteConfig"
        if ($LASTEXITCODE -ne 0) { throw "Config upload failed." }
        & ssh @sshArgs $Target "chmod 600 $RemoteConfig"
        if ($LASTEXITCODE -ne 0) { throw "Could not secure the uploaded config file." }
    }
    if ($AndroidApk) {
        & scp @scpArgs $AndroidApk "${Target}:$RemoteAndroidApk"
        if ($LASTEXITCODE -ne 0) { throw "Android APK upload failed." }
        & scp @scpArgs $AppUpdateManifestForUpload "${Target}:$RemoteAppUpdateManifest"
        if ($LASTEXITCODE -ne 0) { throw "Android update manifest upload failed." }
    }

    $installArgs = @(
        "--archive", $RemoteArchive,
        "--release-id", $ReleaseId
    )
    if ($UploadModels) { $installArgs += @("--models-archive", $RemoteModels) }
    if ($ConfigFile) { $installArgs += @("--config", $RemoteConfig) }
    if ($WithBackend) { $installArgs += "--with-backend" }
    if ($OpenFirewall) { $installArgs += "--open-firewall" }
    if ($SkipPackages) { $installArgs += "--skip-packages" }
    $remoteCleanup = "rm -f $RemoteArchive $RemoteInstaller $RemoteModels $RemoteConfig $RemoteAndroidApk $RemoteAppUpdateManifest"
    $remotePublishAndroid = if ($AndroidApk) {
        " && ${Privilege}bash /opt/meetingnotes-stt/current/scripts/publish-android-update.sh " +
        "--apk $RemoteAndroidApk --manifest $RemoteAppUpdateManifest " +
        "--downloads-dir $RemoteAppUpdateDirectory --config $RemoteAppUpdateConfig " +
        "--owner meetingnotes:meetingnotes --retain 2 && " +
        "${Privilege}systemctl restart meetingnotes-backend.service"
    } else { "" }
    $remoteCommand = "trap 'status=`$?; $remoteCleanup; exit `$status' EXIT; ${Privilege}bash $RemoteInstaller " + ($installArgs -join " ") + $remotePublishAndroid

    Write-Host "[6/7] Installing and health-checking the native systemd release"
    & ssh @sshArgs $Target $remoteCommand
    if ($LASTEXITCODE -ne 0) { throw "Remote installation failed; the installer attempted automatic rollback." }

    Write-Host "[7/7] Synchronizing deployment metadata and managed config"
    $verifyCommand = "${Privilege}bash /opt/meetingnotes-stt/current/scripts/verify-native.sh"
    & ssh @sshArgs $Target $verifyCommand
    if ($LASTEXITCODE -ne 0) { throw "Remote verification failed." }

    $RemoteEnv = & ssh @sshArgs $Target "${Privilege}cat /etc/meetingnotes-stt/stt.env"
    if ($LASTEXITCODE -ne 0) { throw "Could not synchronize the remote environment file." }
    $RemoteEnvPath = Join-Path $ServerRoot ".env.remote"
    [IO.File]::WriteAllLines($RemoteEnvPath, [string[]]$RemoteEnv, [Text.UTF8Encoding]::new($false))

    $State = [ordered]@{
        server = $ServerHost
        ssh_port = $Port
        ssh_user = $User
        version = $Version
        release = $ReleaseId
        deployed_at_utc = (Get-Date).ToUniversalTime().ToString("o")
        backend_enabled = [bool]$WithBackend
        config_snapshot = $RemoteEnvPath
    }
    $State | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $ServerRoot ".deployment-state.json") -Encoding utf8

    & ssh @sshArgs $Target "rm -f $RemoteArchive $RemoteInstaller $RemoteModels $RemoteConfig $RemoteAndroidApk $RemoteAppUpdateManifest"
    Write-Host "[OK] Release $ReleaseId is synchronized; PWA is packaged at /app/"
} finally {
    if (Test-Path -LiteralPath $TempRoot) {
        Remove-Item -LiteralPath $TempRoot -Recurse -Force
    }
}
