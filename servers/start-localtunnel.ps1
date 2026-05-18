param(
    [int]$Port,
    [string]$Label,
    [string]$OutputFile
)

$nodeExe = "D:\node-v24.15.0-win-x64\node.exe"
$ltJs = "D:\Program Files\nodejs\node_global\node_modules\localtunnel\bin\lt.js"

# Kill ALL existing localtunnel node processes
Get-Process -Name "node" -ErrorAction SilentlyContinue | ForEach-Object {
    try {
        $cmdLine = (Get-CimInstance Win32_Process -Filter "ProcessId=$($_.Id)").CommandLine
        if ($cmdLine -like "*localtunnel*" -or $cmdLine -like "*lt.js*") {
            Write-Host "[$Label] Killing old localtunnel PID: $($_.Id)"
            Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue
        }
    } catch {}
}

Start-Sleep 1

Remove-Item -Path $OutputFile -ErrorAction SilentlyContinue
$logFile = "$OutputFile.log"
$errFile = "$OutputFile.err"
Remove-Item -Path $logFile -ErrorAction SilentlyContinue
Remove-Item -Path $errFile -ErrorAction SilentlyContinue

Write-Host "[$Label] Starting localtunnel on port $Port..."
Write-Host "[$Label] node: $nodeExe"
Write-Host "[$Label] lt.js: $ltJs"

$proc = Start-Process -FilePath $nodeExe `
    -ArgumentList "`"$ltJs`"","--port","$Port" `
    -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput $logFile -RedirectStandardError $errFile `
    -WorkingDirectory "D:\ngrok"

Write-Host "[$Label] PID: $($proc.Id), waiting for tunnel URL..."

$maxWait = 40
for ($i = 1; $i -le $maxWait; $i++) {
    Start-Sleep -Milliseconds 500
    if ($proc.HasExited) {
        Write-Host "[$Label] Process exited (code: $($proc.ExitCode))"
        if (Test-Path $logFile) {
            $out = Get-Content $logFile -Raw -ErrorAction SilentlyContinue
            Write-Host "[$Label] stdout: $out"
        }
        if (Test-Path $errFile) {
            $err = Get-Content $errFile -Raw -ErrorAction SilentlyContinue
            Write-Host "[$Label] stderr: $err"
        }
        break
    }
    foreach ($f in @($logFile, $errFile)) {
        if (Test-Path $f) {
            try {
                $content = Get-Content $f -Raw -ErrorAction SilentlyContinue
                if ($content -match '(https://[^\s]+\.loca\.lt)') {
                    $url = $matches[1]
                    Write-Host "[$Label] TUNNEL READY: $url"
                    $url | Set-Content -Path $OutputFile -Encoding ASCII
                    exit 0
                }
            } catch {}
        }
    }
}

Write-Host "[$Label] FAILED to get tunnel URL"
if (Test-Path $logFile) {
    Write-Host "[$Label] stdout:"
    Get-Content $logFile -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "[$Label]   $_" }
}
if (Test-Path $errFile) {
    Write-Host "[$Label] stderr:"
    Get-Content $errFile -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "[$Label]   $_" }
}
"FAILED" | Set-Content -Path $OutputFile -Encoding ASCII
exit 1