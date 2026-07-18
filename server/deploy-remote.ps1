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

    [switch]$WithBackend,
    [switch]$OpenFirewall,
    [switch]$SkipModels,
    [switch]$SkipPackages,
    [switch]$NoSudo
)

$ErrorActionPreference = "Stop"
$ServerRoot = $PSScriptRoot
$VersionFile = Join-Path $ServerRoot "VERSION"
$Installer = Join-Path $ServerRoot "scripts\install-native.sh"

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
$ResolvedKey = (Resolve-Path -LiteralPath $KeyPath).Path
if ($ConfigFile) {
    $ConfigFile = (Resolve-Path -LiteralPath $ConfigFile).Path
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

New-Item -ItemType Directory -Path $TempRoot | Out-Null
try {
    Write-Host "[1/6] Packaging Server $ReleaseId"
    $tarArgs = @(
        "-czf", $Archive,
        "--exclude=./.env",
        "--exclude=./.env.*",
        "--exclude=./models",
        "--exclude=./data",
        "--exclude=./logs",
        "--exclude=./stt-service/runtime",
        "--exclude=./stt-service/pip",
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
        "."
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

    Write-Host "[2/6] Testing key-based SSH access"
    & ssh @sshArgs $Target "printf connected"
    if ($LASTEXITCODE -ne 0) { throw "SSH connection failed." }
    Write-Host ""
    if (-not $NoSudo) {
        & ssh @sshArgs $Target "sudo -n true"
        if ($LASTEXITCODE -ne 0) {
            throw "The deployment account needs temporary passwordless sudo; model upload has not started."
        }
    }

    Write-Host "[3/6] Uploading release archive"
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
        Write-Host "[4/6] Uploading frozen STT models"
        & tar -cf $ModelsArchive -C $ModelsRoot "faster-whisper/small" "faster-whisper/tiny"
        if ($LASTEXITCODE -ne 0) { throw "Model packaging failed." }
        & scp @scpArgs $ModelsArchive "${Target}:$RemoteModels"
        if ($LASTEXITCODE -ne 0) { throw "Model upload failed." }
    } else {
        Write-Host "[4/6] Remote model already present; skipping model upload"
    }

    if ($ConfigFile) {
        & scp @scpArgs $ConfigFile "${Target}:$RemoteConfig"
        if ($LASTEXITCODE -ne 0) { throw "Config upload failed." }
        & ssh @sshArgs $Target "chmod 600 $RemoteConfig"
        if ($LASTEXITCODE -ne 0) { throw "Could not secure the uploaded config file." }
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
    $remoteCleanup = "rm -f $RemoteArchive $RemoteInstaller $RemoteModels $RemoteConfig"
    $remoteCommand = "trap 'status=`$?; $remoteCleanup; exit `$status' EXIT; ${Privilege}bash $RemoteInstaller " + ($installArgs -join " ")

    Write-Host "[5/6] Installing and health-checking the native systemd release"
    & ssh @sshArgs $Target $remoteCommand
    if ($LASTEXITCODE -ne 0) { throw "Remote installation failed; the installer attempted automatic rollback." }

    Write-Host "[6/6] Synchronizing deployment metadata and managed config"
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

    & ssh @sshArgs $Target "rm -f $RemoteArchive $RemoteInstaller $RemoteModels $RemoteConfig"
    Write-Host "[OK] Release $ReleaseId is synchronized and ready at http://${ServerHost}:8888"
} finally {
    if (Test-Path -LiteralPath $TempRoot) {
        Remove-Item -LiteralPath $TempRoot -Recurse -Force
    }
}
