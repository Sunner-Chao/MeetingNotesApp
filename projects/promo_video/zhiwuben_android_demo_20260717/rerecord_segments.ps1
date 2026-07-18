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
function Launch-Home { A shell am start -n com.oa.automation/.ui.MainActivity | Out-Null; Start-Sleep -Seconds 5 }
function Tap([int]$x, [int]$y) { A shell input tap $x $y | Out-Null }
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
    Write-Output "Re-recorded $($c.Name)"
}

# AI refinement: close via the toolbar toggle, then open export formats.
Restore-Db
Launch-Home
Tap 430 680
Start-Sleep -Seconds 2
$c = Start-Cap '07_refine_export'
Tap 630 145
Start-Sleep -Seconds 3
Tap 630 145
Start-Sleep -Seconds 1
Tap 1000 145
Start-Sleep -Seconds 4
Stop-Cap $c

# Professional templates: enter the page before capture, then switch and expand.
Restore-Db
Launch-Home
Tap 860 340
Start-Sleep -Seconds 3
$c = Start-Cap '08_professional_templates'
Tap 500 610
Start-Sleep -Seconds 2
Tap 500 790
Start-Sleep -Seconds 3
Stop-Cap $c

Restore-Db
Launch-Home
Write-Output 'Correction segments recorded.'
