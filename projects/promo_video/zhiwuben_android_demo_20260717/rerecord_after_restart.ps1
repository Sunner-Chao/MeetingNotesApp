param(
    [string]$Serial = 'emulator-5554',
    [string]$AndroidSdkRoot
)

$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
if ([string]::IsNullOrWhiteSpace($AndroidSdkRoot)) {
    $AndroidSdkRoot = $env:ANDROID_SDK_ROOT
}
if ([string]::IsNullOrWhiteSpace($AndroidSdkRoot)) {
    $AndroidSdkRoot = $env:ANDROID_HOME
}
$adb = if ([string]::IsNullOrWhiteSpace($AndroidSdkRoot)) {
    (Get-Command adb -ErrorAction Stop).Source
} else {
    Join-Path $AndroidSdkRoot 'platform-tools\adb.exe'
}
if (!(Test-Path -LiteralPath $adb)) { throw "ADB executable was not found: $adb" }
$serial = $Serial
$raw = Join-Path $PSScriptRoot 'raw'
$demoDb = Join-Path $projectRoot 'projects\brochure\zhiwuben_core_feature_demo_20260717\assets\meeting_notes_demo.db'

function A([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments) {
    & $adb -s $serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($Arguments -join ' ')" }
}
function Restore-Db {
    A shell am force-stop com.oa.automation | Out-Null
    A push $demoDb /data/local/tmp/meeting_notes_demo.db | Out-Null
    A shell run-as com.oa.automation cp /data/local/tmp/meeting_notes_demo.db databases/meeting_notes.db | Out-Null
    A shell run-as com.oa.automation rm -f databases/meeting_notes.db-wal databases/meeting_notes.db-shm | Out-Null
}
function Wait-ForText([string]$text, [int]$attempts = 10) {
    $localXml = Join-Path $env:TEMP 'zhiwuben_window.xml'
    for ($i = 0; $i -lt $attempts; $i++) {
        A shell uiautomator dump /sdcard/window.xml | Out-Null
        A pull /sdcard/window.xml $localXml | Out-Null
        $xml = [System.IO.File]::ReadAllText($localXml, [System.Text.Encoding]::UTF8)
        if ($xml.Contains($text)) { return }
        Start-Sleep -Seconds 1
    }
    throw "Timed out waiting for UI text: $text"
}
function Launch-Home {
    A shell am start -n com.oa.automation/.ui.MainActivity | Out-Null
    Wait-ForText 'VIP专区' 12
    Start-Sleep -Seconds 1
}
function Open-Report {
    A shell input tap 430 680 | Out-Null
    Wait-ForText '继续录音' 8
    Start-Sleep -Milliseconds 500
}
function Start-Cap([string]$name) {
    $remote = "/sdcard/$name.mp4"
    A shell rm -f $remote | Out-Null
    $p = Start-Process -FilePath $adb -ArgumentList @('-s',$serial,'shell','screenrecord','--bit-rate','10000000',$remote) -WindowStyle Hidden -PassThru
    Start-Sleep -Seconds 1
    return @{Name=$name;Remote=$remote;Process=$p}
}
function Stop-Cap($c) {
    A shell pkill -INT screenrecord | Out-Null
    Start-Sleep -Seconds 2
    Wait-Process -Id $c.Process.Id -Timeout 5 -ErrorAction SilentlyContinue
    A pull $c.Remote (Join-Path $raw ($c.Name + '.mp4')) | Out-Null
    Start-Sleep -Seconds 2
    Write-Output "Re-recorded $($c.Name)"
}

# AI refinement is a standalone shot.
Restore-Db
Launch-Home
Open-Report
$c = Start-Cap '07_refine'
A shell input tap 630 145 | Out-Null
Wait-ForText '输入润色要求' 6
Start-Sleep -Seconds 3
Stop-Cap $c

# Export formats are a separate shot, so no panel-closing gesture can change routes.
Restore-Db
Launch-Home
Open-Report
$c = Start-Cap '07_export'
A shell input tap 1000 145 | Out-Null
Wait-ForText 'MARKDOWN' 6
Start-Sleep -Seconds 3
Stop-Cap $c

# Professional templates start recording only after the VIP page is confirmed.
Restore-Db
Launch-Home
A shell input tap 860 340 | Out-Null
Wait-ForText '专业日志模板' 8
Start-Sleep -Seconds 1
$c = Start-Cap '08_professional_templates'
A shell input tap 500 610 | Out-Null
Start-Sleep -Seconds 2
A shell input tap 500 790 | Out-Null
Start-Sleep -Seconds 4
Stop-Cap $c

Restore-Db
Launch-Home
Write-Output 'Post-restart correction segments recorded.'
