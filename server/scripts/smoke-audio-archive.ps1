[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$BackendBaseUrl,

    [Parameter(Mandatory = $true)]
    [string]$SttBaseUrl,

    [Parameter(Mandatory = $true)]
    [string]$ConfigFile
)

$ErrorActionPreference = "Stop"
$BackendBaseUrl = $BackendBaseUrl.TrimEnd("/")
$SttBaseUrl = $SttBaseUrl.TrimEnd("/")
$ConfigFile = (Resolve-Path -LiteralPath $ConfigFile).Path
$stamp = Get-Date -Format "MMddHHmmss"
$userA = "arca_$stamp"
$userB = "arcb_$stamp"
$password = "Test-$([guid]::NewGuid().ToString('N').Substring(0, 16))"
$meetingId = "archive-smoke-$stamp"
$tempRoot = Join-Path $env:TEMP "meetingnotes-archive-$stamp"
$wav = Join-Path $tempRoot "smoke.wav"
$download = Join-Path $tempRoot "download.wav"
$registeredA = $null
$registeredB = $null

function Get-ConfigValue([string]$Name) {
    $line = Get-Content -LiteralPath $ConfigFile |
        Where-Object { $_ -match ("^" + [regex]::Escape($Name) + "=") } |
        Select-Object -Last 1
    if (-not $line) { return "" }
    return ($line -replace ("^" + [regex]::Escape($Name) + "="), "").Trim()
}

function New-SilentWav([string]$Path) {
    $sampleRate = 16000
    $dataBytes = $sampleRate * 2
    $stream = [IO.File]::Open($Path, [IO.FileMode]::Create)
    $writer = [IO.BinaryWriter]::new($stream)
    try {
        $writer.Write([Text.Encoding]::ASCII.GetBytes("RIFF"))
        $writer.Write([int](36 + $dataBytes))
        $writer.Write([Text.Encoding]::ASCII.GetBytes("WAVEfmt "))
        $writer.Write([int]16)
        $writer.Write([int16]1)
        $writer.Write([int16]1)
        $writer.Write([int]$sampleRate)
        $writer.Write([int]($sampleRate * 2))
        $writer.Write([int16]2)
        $writer.Write([int16]16)
        $writer.Write([Text.Encoding]::ASCII.GetBytes("data"))
        $writer.Write([int]$dataBytes)
        $writer.Write([byte[]]::new($dataBytes))
    } finally {
        $writer.Dispose()
        $stream.Dispose()
    }
}

New-Item -ItemType Directory -Path $tempRoot | Out-Null
New-SilentWav $wav

try {
    $jsonA = @{ username = $userA; password = $password } | ConvertTo-Json
    $jsonB = @{ username = $userB; password = $password } | ConvertTo-Json
    $registeredA = Invoke-RestMethod -SkipCertificateCheck -Method Post `
        -Uri "$BackendBaseUrl/auth/register" -ContentType "application/json" -Body $jsonA
    $registeredB = Invoke-RestMethod -SkipCertificateCheck -Method Post `
        -Uri "$BackendBaseUrl/auth/register" -ContentType "application/json" -Body $jsonB
    $headersA = @{
        Authorization = "Bearer $($registeredA.stt_access_token)"
        "X-Meeting-Id" = $meetingId
    }
    $headersB = @{ Authorization = "Bearer $($registeredB.stt_access_token)" }

    try {
        Invoke-WebRequest -Method Post -Uri "$SttBaseUrl/transcribe" `
            -Headers $headersA -Form @{ file = Get-Item $wav } | Out-Null
    } catch {
        $status = [int]$_.Exception.Response.StatusCode
        if ($status -notin 400, 422, 500) { throw }
    }

    $ownerHeaders = @{ Authorization = $headersA.Authorization }
    $listA = Invoke-RestMethod -Method Get `
        -Uri "$SttBaseUrl/audio-archive?meeting_id=$meetingId" -Headers $ownerHeaders
    $listB = Invoke-RestMethod -Method Get `
        -Uri "$SttBaseUrl/audio-archive?meeting_id=$meetingId" -Headers $headersB
    if ($listA.items.Count -ne 1) {
        throw "Expected one archive for account A, found $($listA.items.Count)"
    }
    if ($listB.items.Count -ne 0) { throw "Account B can see account A archive" }
    $archive = $listA.items[0]

    Invoke-WebRequest -Method Get -Uri "$SttBaseUrl$($archive.download_path)" `
        -Headers $ownerHeaders -OutFile $download
    $sourceHash = (Get-FileHash -Algorithm SHA256 $wav).Hash
    $downloadHash = (Get-FileHash -Algorithm SHA256 $download).Hash
    if ($sourceHash -ne $downloadHash) { throw "Downloaded bytes do not match the upload" }

    $crossStatus = 0
    try {
        Invoke-WebRequest -Method Get -Uri "$SttBaseUrl$($archive.download_path)" `
            -Headers $headersB | Out-Null
    } catch {
        $crossStatus = [int]$_.Exception.Response.StatusCode
    }
    if ($crossStatus -ne 404) {
        throw "Cross-account download returned $crossStatus instead of 404"
    }

    Invoke-RestMethod -Method Delete -Uri "$SttBaseUrl$($archive.download_path)" `
        -Headers $ownerHeaders | Out-Null
    $afterDelete = Invoke-RestMethod -Method Get `
        -Uri "$SttBaseUrl/audio-archive?meeting_id=$meetingId" -Headers $ownerHeaders
    if ($afterDelete.items.Count -ne 0) { throw "Archive delete did not remove the item" }

    [pscustomobject]@{
        release = (Invoke-RestMethod -Uri "$SttBaseUrl/health").release
        owner_list_count = $listA.items.Count
        isolated_list_count = $listB.items.Count
        cross_account_download_status = $crossStatus
        byte_match = ($sourceHash -eq $downloadHash)
        delete_verified = ($afterDelete.items.Count -eq 0)
        duration_sec = $archive.duration_sec
    }
} finally {
    try {
        $adminUser = Get-ConfigValue "ACCOUNT_ADMIN_USERNAME"
        $adminPassword = Get-ConfigValue "ACCOUNT_ADMIN_PASSWORD"
        if ($adminUser -and $adminPassword) {
            $adminLogin = Invoke-RestMethod -SkipCertificateCheck -Method Post `
                -Uri "$BackendBaseUrl/auth/login" -ContentType "application/json" `
                -Body (@{ username = $adminUser; password = $adminPassword } | ConvertTo-Json)
            $adminHeaders = @{ Authorization = "Bearer $($adminLogin.access_token)" }
            foreach ($registered in @($registeredA, $registeredB)) {
                if ($registered -and $registered.user.id) {
                    Invoke-RestMethod -SkipCertificateCheck -Method Delete `
                        -Uri "$BackendBaseUrl/admin/accounts/users/$($registered.user.id)" `
                        -Headers $adminHeaders | Out-Null
                }
            }
        }
    } catch {
        Write-Warning "Smoke-test account cleanup needs review: $($_.Exception.Message)"
    }
    Remove-Item -LiteralPath $wav, $download -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $tempRoot -Force -ErrorAction SilentlyContinue
}
