param(
    [switch]$Remove
)

$ErrorActionPreference = "Stop"

$taskName = "MeetingNotesApp-LocalSTT"
$existingTask = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue

if ($Remove) {
    if ($null -ne $existingTask) {
        Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
        Write-Host "[STT] Removed scheduled task: $taskName"
    } else {
        Write-Host "[STT] Scheduled task is not installed: $taskName"
    }
    exit 0
}

$starterPath = (Resolve-Path (Join-Path $PSScriptRoot "start-windows-local.bat")).Path
$currentUser = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$action = New-ScheduledTaskAction `
    -Execute $env:ComSpec `
    -Argument ('/d /c ""{0}""' -f $starterPath) `
    -WorkingDirectory $PSScriptRoot
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $currentUser
$principal = New-ScheduledTaskPrincipal `
    -UserId $currentUser `
    -LogonType Interactive `
    -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -StartWhenAvailable `
    -ExecutionTimeLimit ([TimeSpan]::Zero) `
    -MultipleInstances IgnoreNew
$task = New-ScheduledTask `
    -Action $action `
    -Trigger $trigger `
    -Principal $principal `
    -Settings $settings `
    -Description "Start the MeetingNotesApp local Faster-Whisper service after user logon."

Register-ScheduledTask -TaskName $taskName -InputObject $task -Force | Out-Null
Write-Host "[STT] Scheduled task installed: $taskName"
Write-Host "[STT] User: $currentUser"
Write-Host "[STT] Starter: $starterPath"
