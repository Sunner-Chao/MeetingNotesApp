[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ServerHost,

    [Parameter(Mandatory = $true)]
    [string]$User,

    [Parameter(Mandatory = $true)]
    [string]$KeyPath,

    [Parameter(Mandatory = $true)]
    [string]$ModelRoot,

    [ValidateRange(1, 65535)]
    [int]$Port = 22
)

$ErrorActionPreference = "Stop"
$serverRoot = Split-Path -Parent $PSScriptRoot
$manifestPath = Join-Path $serverRoot "speaker-diarization-model-manifest.sha256"
$resolvedModelRoot = (Resolve-Path -LiteralPath $ModelRoot).Path
$resolvedKey = (Resolve-Path -LiteralPath $KeyPath).Path
$deploymentId = [Guid]::NewGuid().ToString("N")
$tempRoot = Join-Path ([IO.Path]::GetTempPath()) "meetingnotes-speaker-$deploymentId"
$packageRoot = Join-Path $tempRoot "package"
$archive = Join-Path $tempRoot "speaker-diarization.tar"
$remoteArchive = "/tmp/meetingnotes-speaker-$deploymentId.tar"
$remoteManifest = "/tmp/meetingnotes-speaker-$deploymentId.sha256"
$target = "$User@$ServerHost"

if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    throw "Speaker model manifest is missing: $manifestPath"
}
foreach ($command in @("tar", "ssh", "scp")) {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) {
        throw "Required command is unavailable: $command"
    }
}

New-Item -ItemType Directory -Path $packageRoot | Out-Null
try {
    foreach ($line in Get-Content -LiteralPath $manifestPath) {
        if ($line -notmatch '^([0-9a-f]{64})\s{2}(.+)$') {
            throw "Invalid speaker model manifest entry: $line"
        }
        $expectedHash = $Matches[1]
        $relativePath = $Matches[2].Replace('/', [IO.Path]::DirectorySeparatorChar)
        $source = Join-Path $resolvedModelRoot $relativePath
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
            throw "Speaker model is missing: $source"
        }
        $actualHash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actualHash -ne $expectedHash) {
            throw "Speaker model checksum mismatch: $relativePath"
        }
        $destination = Join-Path $packageRoot $relativePath
        New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
        Copy-Item -LiteralPath $source -Destination $destination
    }

    & tar -cf $archive -C $packageRoot "speaker-diarization"
    if ($LASTEXITCODE -ne 0) { throw "Could not package speaker models" }

    $sshArgs = @(
        "-F", "NUL", "-i", $resolvedKey, "-p", $Port,
        "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=accept-new"
    )
    $scpArgs = @(
        "-F", "NUL", "-i", $resolvedKey, "-P", $Port,
        "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=accept-new"
    )

    & scp @scpArgs $archive "${target}:$remoteArchive"
    if ($LASTEXITCODE -ne 0) { throw "Speaker model upload failed" }
    & scp @scpArgs $manifestPath "${target}:$remoteManifest"
    if ($LASTEXITCODE -ne 0) { throw "Speaker model manifest upload failed" }

    $remoteScript = @"
set -euo pipefail
archive='$remoteArchive'
manifest='$remoteManifest'
model_root='/var/lib/meetingnotes-stt/models'
stage="`$model_root/.speaker-diarization-$deploymentId"
target="`$model_root/speaker-diarization"
backup="`$model_root/.speaker-diarization-backup-$deploymentId"
cleanup() { sudo -n rm -rf "`$stage" "`$archive" "`$manifest"; }
trap cleanup EXIT
sudo -n mkdir -p "`$stage"
sudo -n tar -xf "`$archive" -C "`$stage"
sudo -n cp "`$manifest" "`$stage/model-manifest.sha256"
sudo -n bash -c "cd '`$stage' && sha256sum -c model-manifest.sha256"
sudo -n chown -R meetingnotes:meetingnotes "`$stage/speaker-diarization"
sudo -n chmod -R u=rwX,g=rX,o= "`$stage/speaker-diarization"
if sudo -n test -e "`$target"; then sudo -n mv "`$target" "`$backup"; fi
if ! sudo -n mv "`$stage/speaker-diarization" "`$target"; then
  if sudo -n test -e "`$backup"; then sudo -n mv "`$backup" "`$target"; fi
  exit 1
fi
if ! sudo -n systemctl restart meetingnotes-stt.service; then
  sudo -n rm -rf "`$target"
  if sudo -n test -e "`$backup"; then sudo -n mv "`$backup" "`$target"; fi
  sudo -n systemctl restart meetingnotes-stt.service
  exit 1
fi
healthy=0
for _ in `$(seq 1 90); do
  if health=`$(curl --fail --silent http://127.0.0.1:8888/health 2>/dev/null) && \
     printf '%s' "`$health" | python3 -c 'import json,sys; data=json.load(sys.stdin); assert data["final_transcription"]["speaker_diarization"]["models_present"] is True' 2>/dev/null; then
    healthy=1
    break
  fi
  sleep 1
done
if [[ "`$healthy" -ne 1 ]]; then
  sudo -n systemctl stop meetingnotes-stt.service
  sudo -n rm -rf "`$target"
  if sudo -n test -e "`$backup"; then sudo -n mv "`$backup" "`$target"; fi
  sudo -n systemctl restart meetingnotes-stt.service
  exit 1
fi
sudo -n rm -rf "`$backup"
printf '%s' "`$health" | python3 -c 'import json,sys; data=json.load(sys.stdin); print(json.dumps(data["final_transcription"]["speaker_diarization"], ensure_ascii=False))'
"@
    $remoteScript = $remoteScript.Replace("`r", "")
    $remoteScript | & ssh @sshArgs $target "bash -s"
    if ($LASTEXITCODE -ne 0) { throw "Remote speaker model activation failed" }
} finally {
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force
    }
}
