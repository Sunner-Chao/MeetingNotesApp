[CmdletBinding()]
param(
    [string]$AdbPath = "D:\pro_sunner\demo_vscode\android-sdk\platform-tools\adb.exe",
    [string]$ServerEnvPath = "",
    [string]$PackageName = "com.oa.automation",
    [string]$DeviceSerial = ""
)

$ErrorActionPreference = "Stop"
$AndroidRoot = Split-Path -Parent $PSScriptRoot
$WorkspaceRoot = Split-Path -Parent $AndroidRoot
if (-not $ServerEnvPath) {
    $ServerEnvPath = Join-Path $WorkspaceRoot "server\.env.remote"
}

if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
    throw "ADB not found: $AdbPath"
}
if (-not (Test-Path -LiteralPath $ServerEnvPath -PathType Leaf)) {
    throw "Synchronized server environment not found: $ServerEnvPath"
}

$tokenLine = Get-Content -LiteralPath $ServerEnvPath |
    Where-Object { $_ -like "STT_API_TOKEN=*" } |
    Select-Object -Last 1
if (-not $tokenLine) {
    throw "STT_API_TOKEN is missing from the synchronized server environment."
}
$token = $tokenLine.Substring($tokenLine.IndexOf("=") + 1).Trim().Trim('"')
if (-not $token) {
    throw "STT_API_TOKEN is empty."
}
$endpointLine = Get-Content -LiteralPath $ServerEnvPath |
    Where-Object { $_ -like "PUBLIC_STT_URL=*" } |
    Select-Object -Last 1
$endpoint = if ($endpointLine) {
    $endpointLine.Substring($endpointLine.IndexOf("=") + 1).Trim().Trim('"').TrimEnd('/')
} else {
    ""
}
if (-not $endpoint) {
    $deploymentStatePath = Join-Path $WorkspaceRoot "server\.deployment-state.json"
    if (-not (Test-Path -LiteralPath $deploymentStatePath -PathType Leaf)) {
        throw "PUBLIC_STT_URL is empty and deployment metadata is unavailable."
    }
    $deploymentState = Get-Content -LiteralPath $deploymentStatePath -Raw | ConvertFrom-Json
    $portLine = Get-Content -LiteralPath $ServerEnvPath |
        Where-Object { $_ -like "STT_PORT=*" } |
        Select-Object -Last 1
    $port = if ($portLine) {
        $portLine.Substring($portLine.IndexOf("=") + 1).Trim().Trim('"')
    } else {
        "8888"
    }
    $endpoint = "http://$($deploymentState.server):$port"
}
if ($endpoint -notmatch '^https?://') {
    throw "PUBLIC_STT_URL must use http or https."
}

$deviceArgs = if ($DeviceSerial) { @("-s", $DeviceSerial) } else { @() }

& $AdbPath @deviceArgs shell run-as $PackageName true
if ($LASTEXITCODE -ne 0) {
    throw "The installed app is not a debuggable build or the device is unavailable."
}

& $AdbPath @deviceArgs shell am start --user 0 -n "$PackageName/.ui.MainActivity" | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Could not start the debug app before provisioning."
}
Start-Sleep -Seconds 1

& $AdbPath @deviceArgs shell am broadcast --user 0 --include-stopped-packages `
    -a com.oa.automation.debug.PROVISION_STT `
    -n "$PackageName/.debug.DebugProvisioningReceiver" `
    --es stt_api_token $token `
    --es stt_endpoint $endpoint | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Debug STT provisioning broadcast failed."
}
Start-Sleep -Seconds 2

Write-Host "[OK] Production STT credentials were provisioned to the debug device DataStore."
