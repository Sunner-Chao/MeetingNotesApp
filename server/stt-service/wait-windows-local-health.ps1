param(
    [string]$Uri = "http://127.0.0.1:8888/health",
    [int]$TimeoutSeconds = 180,
    [int]$PollIntervalSeconds = 2
)

$ErrorActionPreference = "Stop"
$deadline = [DateTimeOffset]::UtcNow.AddSeconds([Math]::Max(1, $TimeoutSeconds))
$lastError = $null

Add-Type -AssemblyName System.Net.Http
$handler = [System.Net.Http.HttpClientHandler]::new()
$handler.UseProxy = $false
$client = [System.Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds(5)

try {
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        try {
            $json = $client.GetStringAsync($Uri).GetAwaiter().GetResult()
            $health = $json | ConvertFrom-Json
            if ($health.status -eq "ok" -and $health.model_loaded -eq $true) {
                Write-Host "[STT] Health OK - Engine: $($health.engine) Model: $($health.model) Device: $($health.device)"
                exit 0
            }
            $lastError = "service responded before the model was ready"
        } catch {
            $lastError = $_.Exception.Message
        }
        Start-Sleep -Seconds ([Math]::Max(1, $PollIntervalSeconds))
    }
} finally {
    $client.Dispose()
    $handler.Dispose()
}

Write-Error "[STT] Health check timed out after $TimeoutSeconds seconds: $lastError"
exit 1
