$ErrorActionPreference = 'Stop'
$scriptPath = Join-Path $PSScriptRoot 'v100_funasr_server.py'
Stop-ScheduledTask -TaskName 'MeetingNotesApp-V100-STT'
Get-CimInstance Win32_Process -Filter "Name = 'python.exe'" | Where-Object {
    $_.CommandLine -and $_.CommandLine.Contains($scriptPath)
} | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
Start-ScheduledTask -TaskName 'MeetingNotesApp-V100-STT'
Write-Output 'V100 STT restart requested'
