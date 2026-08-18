[CmdletBinding()]
param(
    [string]$AdbPath = "",
    [string]$ServerEnvPath = "",
    [string]$LocalDefaultsPath = "",
    [string]$PackageName = "com.oa.automation",
    [string]$DeviceSerial = ""
)

$ErrorActionPreference = "Stop"
$AndroidRoot = Split-Path -Parent $PSScriptRoot
$WorkspaceRoot = Split-Path -Parent $AndroidRoot

function Read-EnvValue {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Name
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return "" }
    $line = Get-Content -LiteralPath $Path |
        Where-Object { $_ -like "$Name=*" } |
        Select-Object -Last 1
    if (-not $line) { return "" }
    return $line.Substring($line.IndexOf("=") + 1).Trim().Trim('"')
}

function Resolve-AdbPath {
    param([string]$RequestedPath)

    $candidates = @(
        $RequestedPath,
        $env:ANDROID_ADB_PATH,
        $(if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME "platform-tools\adb.exe" }),
        $(if ($env:ANDROID_SDK_ROOT) { Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe" })
    ) | Where-Object { $_ }
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    throw "ADB was not found. Set -AdbPath, ANDROID_ADB_PATH, ANDROID_HOME, or ANDROID_SDK_ROOT."
}

if (-not $ServerEnvPath) {
    $ServerEnvPath = Join-Path $WorkspaceRoot "server\.env.remote"
}
if (-not $LocalDefaultsPath) {
    $LocalDefaultsPath = Join-Path $AndroidRoot "local.defaults.env"
}
if (-not (Test-Path -LiteralPath $ServerEnvPath -PathType Leaf)) {
    throw "Server environment file not found: $ServerEnvPath"
}
if (-not (Test-Path -LiteralPath $LocalDefaultsPath -PathType Leaf)) {
    throw "Android defaults file not found: $LocalDefaultsPath"
}

$resolvedAdb = Resolve-AdbPath -RequestedPath $AdbPath
$accountEndpoint = (Read-EnvValue -Path $LocalDefaultsPath -Name "MEETINGNOTES_ACCOUNT_ENDPOINT").TrimEnd('/')
$agentEndpoint = (Read-EnvValue -Path $LocalDefaultsPath -Name "MEETINGNOTES_AGENT_ENDPOINT").TrimEnd('/')
$username = Read-EnvValue -Path $ServerEnvPath -Name "ACCOUNT_ADMIN_USERNAME"
$password = Read-EnvValue -Path $ServerEnvPath -Name "ACCOUNT_ADMIN_PASSWORD"
if (-not $accountEndpoint -or -not $agentEndpoint -or -not $username -or -not $password) {
    throw "Account endpoint, Agent endpoint, or admin credentials are missing from dynamic configuration."
}

$loginBody = @{ username = $username; password = $password } | ConvertTo-Json -Compress
$session = Invoke-RestMethod `
    -Method Post `
    -Uri "$accountEndpoint/auth/login" `
    -ContentType "application/json; charset=utf-8" `
    -NoProxy `
    -Body $loginBody
$agentToken = [string]$session.agent_access_token
if (-not $agentToken) {
    throw "The account service did not return an Agent access token."
}
if ($session.user -and $session.user.PSObject.Properties.Name -contains "avatar_data_url") {
    $session.user.avatar_data_url = $null
}
$sessionJson = $session | ConvertTo-Json -Depth 12 -Compress
$sessionBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($sessionJson))

$deviceArgs = if ($DeviceSerial) { @("-s", $DeviceSerial) } else { @() }
& $resolvedAdb @deviceArgs shell run-as $PackageName true | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "The installed app is not a debuggable build or the selected device is unavailable."
}

& $resolvedAdb @deviceArgs shell am start --user 0 -n "$PackageName/.ui.MainActivity" | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Could not start the debug app before provisioning."
}
Start-Sleep -Seconds 1

& $resolvedAdb @deviceArgs shell am broadcast --user 0 --include-stopped-packages `
    -a com.oa.automation.debug.PROVISION_STUDY_TOUR_DEMO `
    -n "$PackageName/.debug.DebugProvisioningReceiver" `
    --es account_endpoint $accountEndpoint `
    --es account_session_base64 $sessionBase64 `
    --es agent_endpoint $agentEndpoint `
    --es agent_access_token $agentToken | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Debug study-tour provisioning broadcast failed."
}

Start-Sleep -Seconds 2
Write-Host "[OK] Study-tour demo data and dynamic Agent configuration were provisioned."
