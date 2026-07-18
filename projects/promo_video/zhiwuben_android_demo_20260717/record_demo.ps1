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
$root = $PSScriptRoot
$raw = Join-Path $root 'raw'
$demoDb = Join-Path $projectRoot 'projects\brochure\zhiwuben_core_feature_demo_20260717\assets\meeting_notes_demo.db'

New-Item -ItemType Directory -Path $raw -Force | Out-Null

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    & $adb -s $serial @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "ADB failed: $($Arguments -join ' ')"
    }
}

function Restore-DemoDatabase {
    Invoke-Adb shell am force-stop com.oa.automation | Out-Null
    Invoke-Adb push $demoDb /data/local/tmp/meeting_notes_demo.db | Out-Null
    Invoke-Adb shell run-as com.oa.automation cp /data/local/tmp/meeting_notes_demo.db databases/meeting_notes.db | Out-Null
    Invoke-Adb shell run-as com.oa.automation rm -f databases/meeting_notes.db-wal databases/meeting_notes.db-shm | Out-Null
}

function Launch-Home {
    Invoke-Adb shell am start -n com.oa.automation/.ui.MainActivity | Out-Null
    Start-Sleep -Seconds 5
}

function Tap([int]$x, [int]$y) {
    Invoke-Adb shell input tap $x $y | Out-Null
}

function Swipe([int]$x1, [int]$y1, [int]$x2, [int]$y2, [int]$duration) {
    Invoke-Adb shell input swipe $x1 $y1 $x2 $y2 $duration | Out-Null
}

function Start-Capture([string]$name) {
    $remote = "/sdcard/$name.mp4"
    Invoke-Adb shell rm -f $remote | Out-Null
    $process = Start-Process -FilePath $adb -ArgumentList @(
        '-s', $serial, 'shell', 'screenrecord', '--bit-rate', '10000000', $remote
    ) -WindowStyle Hidden -PassThru
    Start-Sleep -Seconds 1
    return @{ Name = $name; Remote = $remote; Process = $process }
}

function Stop-Capture($capture) {
    Invoke-Adb shell pkill -INT screenrecord | Out-Null
    Start-Sleep -Seconds 2
    Wait-Process -Id $capture.Process.Id -Timeout 5 -ErrorAction SilentlyContinue
    $local = Join-Path $raw ($capture.Name + '.mp4')
    Invoke-Adb pull $capture.Remote $local | Out-Null
    Write-Output "Recorded $local"
}

function Open-DemoReport {
    Tap 430 680
    Start-Sleep -Seconds 2
}

# 01 - Launch and meeting archive
Restore-DemoDatabase
Invoke-Adb shell input keyevent HOME | Out-Null
Start-Sleep -Seconds 1
$capture = Start-Capture '01_launch_home'
Start-Sleep -Milliseconds 600
Invoke-Adb shell am start -n com.oa.automation/.ui.MainActivity | Out-Null
Start-Sleep -Seconds 6
Stop-Capture $capture

# 02 - Create a meeting and browse the template system
Restore-DemoDatabase
Launch-Home
$capture = Start-Capture '02_create_templates'
Tap 965 2280
Start-Sleep -Seconds 2
Tap 755 1440
Start-Sleep -Seconds 2
Tap 300 280
Start-Sleep -Seconds 2
Swipe 540 1250 540 520 900
Start-Sleep -Seconds 2
Stop-Capture $capture

# 03 - Switch to text, file and image input
Restore-DemoDatabase
Launch-Home
Tap 965 2280
Start-Sleep -Seconds 1
Tap 755 1440
Start-Sleep -Seconds 2
$capture = Start-Capture '03_text_input'
Tap 780 505
Start-Sleep -Seconds 4
Stop-Capture $capture

# 04 - Reopen a meeting and continue from saved transcription
Restore-DemoDatabase
Launch-Home
Open-DemoReport
$capture = Start-Capture '04_recording_context'
Tap 300 925
Start-Sleep -Seconds 5
Stop-Capture $capture

# 05 - Scroll through the structured AI report
Restore-DemoDatabase
Launch-Home
Open-DemoReport
$capture = Start-Capture '05_ai_report'
Start-Sleep -Seconds 1
Swipe 540 1900 540 760 1200
Start-Sleep -Seconds 1
Swipe 540 1900 540 760 1200
Start-Sleep -Seconds 1
Swipe 540 1900 540 760 1200
Start-Sleep -Seconds 2
Stop-Capture $capture

# 06 - Expand and inspect the original transcript
Restore-DemoDatabase
Launch-Home
Open-DemoReport
$capture = Start-Capture '06_transcript_trace'
Tap 870 1180
Start-Sleep -Seconds 2
Swipe 540 1850 540 900 1000
Start-Sleep -Seconds 3
Stop-Capture $capture

# 07 - Open AI refinement, then the export format menu
Restore-DemoDatabase
Launch-Home
Open-DemoReport
$capture = Start-Capture '07_refine_export'
Tap 630 145
Start-Sleep -Seconds 3
Invoke-Adb shell input keyevent 4 | Out-Null
Start-Sleep -Seconds 1
Tap 1000 145
Start-Sleep -Seconds 3
Stop-Capture $capture

# 08 - Professional construction and design templates
Restore-DemoDatabase
Launch-Home
$capture = Start-Capture '08_professional_templates'
Tap 860 340
Start-Sleep -Seconds 2
Tap 500 610
Start-Sleep -Seconds 2
Tap 500 790
Start-Sleep -Seconds 2
Swipe 540 1450 540 850 800
Start-Sleep -Seconds 2
Stop-Capture $capture

# 09 - Safe settings overview without opening endpoints or tokens
Restore-DemoDatabase
Launch-Home
$capture = Start-Capture '09_settings'
Tap 1000 145
Start-Sleep -Seconds 5
Stop-Capture $capture

Restore-DemoDatabase
Launch-Home

Write-Output 'All emulator segments recorded.'
