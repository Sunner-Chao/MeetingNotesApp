[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ServerHost,

    [Parameter(Mandatory = $true)]
    [string]$User,

    [Parameter(Mandatory = $true)]
    [string]$KeyPath,

    [string]$ConfigFile = (Join-Path $PSScriptRoot "..\.env.remote"),

    [ValidateRange(1, 200)]
    [int]$RealtimeMaxConcurrent = 16,

    [switch]$EnablePrecision,

    [int]$PrecisionMonthlyLimitSeconds = 0,

    [ValidateRange(1, 65535)]
    [int]$Port = 22
)

$ErrorActionPreference = "Stop"
$ConfigFile = (Resolve-Path -LiteralPath $ConfigFile).Path
$KeyPath = (Resolve-Path -LiteralPath $KeyPath).Path
$appId = (Read-Host "Tencent Cloud AppID").Trim()
$secretIdSecure = Read-Host "Tencent Cloud SecretID" -AsSecureString
$secretKeySecure = Read-Host "Tencent Cloud SecretKey" -AsSecureString

function ConvertFrom-SecureValue([Security.SecureString]$Value) {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

function Set-EnvValue([Collections.Generic.List[string]]$Lines, [string]$Name, [string]$Value) {
    if ($Value -match "[\r\n]") { throw "$Name contains an invalid newline" }
    $prefix = "$Name="
    for ($index = 0; $index -lt $Lines.Count; $index++) {
        if ($Lines[$index].StartsWith($prefix, [StringComparison]::Ordinal)) {
            $Lines[$index] = "$prefix$Value"
            return
        }
    }
    $Lines.Add("$prefix$Value")
}

$secretId = ConvertFrom-SecureValue $secretIdSecure
$secretKey = ConvertFrom-SecureValue $secretKeySecure
if (-not $appId -or -not $secretId -or -not $secretKey) {
    throw "AppID, SecretID and SecretKey are required"
}
if ($EnablePrecision -and $PrecisionMonthlyLimitSeconds -lt 1) {
    throw "Precision ASR requires a positive PrecisionMonthlyLimitSeconds cap"
}

$lines = [Collections.Generic.List[string]]::new()
Get-Content -LiteralPath $ConfigFile | ForEach-Object { $lines.Add($_) }
Set-EnvValue $lines "TENCENT_ASR_ENABLED" "0"
Set-EnvValue $lines "TENCENT_ASR_APP_ID" $appId
Set-EnvValue $lines "TENCENT_ASR_SECRET_ID" $secretId
Set-EnvValue $lines "TENCENT_ASR_SECRET_KEY" $secretKey
Set-EnvValue $lines "TENCENT_ASR_ENGINE_TYPE" "16k_zh_en"
Set-EnvValue $lines "TENCENT_REALTIME_ASR_ENABLED" "0"
Set-EnvValue $lines "TENCENT_REALTIME_ASR_BASE_URL" "wss://asr.cloud.tencent.com/asr/v2"
Set-EnvValue $lines "TENCENT_REALTIME_ASR_ENGINE_TYPE" "16k_zh_en"
Set-EnvValue $lines "TENCENT_REALTIME_ASR_MAX_CONCURRENT" $RealtimeMaxConcurrent.ToString()
Set-EnvValue $lines "TENCENT_STANDARD_ASR_ENABLED" "1"
Set-EnvValue $lines "TENCENT_STANDARD_REALTIME_ASR_ENABLED" "1"
Set-EnvValue $lines "TENCENT_STANDARD_ASR_ENGINE_TYPE" "16k_zh"
Set-EnvValue $lines "TENCENT_STANDARD_REALTIME_ASR_ENGINE_TYPE" "16k_zh"
Set-EnvValue $lines "TENCENT_STANDARD_MONTHLY_LIMIT_SEC" "18000"
Set-EnvValue $lines "TENCENT_LEGACY_USAGE_TIER" "precision"
Set-EnvValue $lines "TENCENT_PRECISION_ASR_ENABLED" $(if ($EnablePrecision) { "1" } else { "0" })
Set-EnvValue $lines "TENCENT_PRECISION_REALTIME_ASR_ENABLED" $(if ($EnablePrecision) { "1" } else { "0" })
Set-EnvValue $lines "TENCENT_PRECISION_ASR_ENGINE_TYPE" "16k_zh_en"
Set-EnvValue $lines "TENCENT_PRECISION_REALTIME_ASR_ENGINE_TYPE" "16k_zh_en"
Set-EnvValue $lines "TENCENT_PRECISION_MONTHLY_LIMIT_SEC" $PrecisionMonthlyLimitSeconds.ToString()
Set-EnvValue $lines "TENCENT_ASR_USAGE_ENABLED" "1"
Set-EnvValue $lines "TENCENT_ASR_USAGE_API_ENDPOINT" "asr.tencentcloudapi.com"
Set-EnvValue $lines "TENCENT_ASR_USAGE_REGION" "ap-guangzhou"
Set-EnvValue $lines "TENCENT_ASR_USAGE_TIMEZONE" "Asia/Shanghai"
Set-EnvValue $lines "TENCENT_ASR_USAGE_CACHE_SEC" "300"
Set-EnvValue $lines "TENCENT_REALTIME_MONTHLY_FREE_SEC" "18000"
Set-EnvValue $lines "TENCENT_FLASH_MONTHLY_FREE_SEC" "18000"
[IO.File]::WriteAllLines($ConfigFile, $lines, [Text.UTF8Encoding]::new($false))

$remoteConfig = "/tmp/meetingnotes-tencent-asr-$([guid]::NewGuid().ToString('N')).env"
$target = "$User@$ServerHost"
$sshArgs = @("-F", "NUL", "-i", $KeyPath, "-p", $Port, "-o", "BatchMode=yes")
$scpArgs = @("-F", "NUL", "-i", $KeyPath, "-P", $Port, "-o", "BatchMode=yes")
try {
    & scp @scpArgs $ConfigFile "${target}:$remoteConfig"
    if ($LASTEXITCODE -ne 0) { throw "Could not upload the managed configuration" }
    & ssh @sshArgs $target "sudo install -m 0640 -o root -g meetingnotes $remoteConfig /etc/meetingnotes-stt/stt.env && rm -f $remoteConfig && sudo systemctl restart meetingnotes-stt.service"
    if ($LASTEXITCODE -ne 0) { throw "Could not activate Tencent Cloud ASR" }
    & ssh @sshArgs $target "curl --fail --silent http://127.0.0.1:8888/health | python3 -c 'import json,sys; value=json.load(sys.stdin); print(json.dumps({\"cloud_asr\":value[\"cloud_asr\"],\"realtime_asr\":value[\"realtime_asr\"],\"cloud_asr_usage\":value[\"cloud_asr_usage\"]}, ensure_ascii=False))'"
    if ($LASTEXITCODE -ne 0) { throw "Tencent Cloud ASR health verification failed" }
} finally {
    & ssh @sshArgs $target "rm -f $remoteConfig" 2>$null
    $secretId = $null
    $secretKey = $null
}
