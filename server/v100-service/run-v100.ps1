param(
    [Parameter(Mandatory = $true)][string]$PythonExe,
    [Parameter(Mandatory = $true)][string]$EnvironmentFile
)
$ErrorActionPreference = 'Stop'
$config = Get-Content -LiteralPath $EnvironmentFile -Raw | ConvertFrom-Json
foreach ($property in $config.PSObject.Properties) {
    [Environment]::SetEnvironmentVariable($property.Name, [string]$property.Value, 'Process')
}
$logDirectory = Join-Path $PSScriptRoot 'logs'
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$process = Start-Process -FilePath $PythonExe -ArgumentList @('-u', ('"' + (Join-Path $PSScriptRoot 'v100_funasr_server.py') + '"')) -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -PassThru -Wait -RedirectStandardOutput (Join-Path $logDirectory "$stamp.stdout.log") -RedirectStandardError (Join-Path $logDirectory "$stamp.stderr.log")
exit $process.ExitCode
