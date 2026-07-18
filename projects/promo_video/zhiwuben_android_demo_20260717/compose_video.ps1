$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot
$raw = Join-Path $root 'raw'
$assets = Join-Path $root 'assets'
$work = Join-Path $root 'work'
$exports = Join-Path $root 'exports'
$ffmpeg = (Get-Command ffmpeg).Source
$ffprobe = (Get-Command ffprobe).Source

New-Item -ItemType Directory -Path $assets, $work, $exports -Force | Out-Null

$items = @(
    [PSCustomObject]@{ Name = 'intro'; Cue = '智悟本，把会议现场变成可执行的结果。'; Kind = 'card'; Duration = 4.5 },
    [PSCustomObject]@{ Name = '01_launch_home'; Cue = '历史会议集中管理，每一场会议都能持续沉淀。'; Kind = 'screen'; Duration = 0; MinDuration = 7.0 },
    [PSCustomObject]@{ Name = '02_create_templates'; Cue = '新建会议后，可以先选择纪要模板，再开始采集。'; Kind = 'screen'; Duration = 0; MinDuration = 9.5 },
    [PSCustomObject]@{ Name = '03_text_input'; Cue = '除了录音，文本、文件和图片也能汇入同一场会议。'; Kind = 'screen'; Duration = 0; MinDuration = 6.5 },
    [PSCustomObject]@{ Name = '04_recording_context'; Cue = '录音与转写内容保留在同一个会议上下文中，随时可以继续。'; Kind = 'screen'; Duration = 0; MinDuration = 6.5 },
    [PSCustomObject]@{ Name = '05_ai_report'; Cue = '人工智能按照模板生成概览、要点、决策、任务和行动项。'; Kind = 'screen'; Duration = 0; MinDuration = 10.0 },
    [PSCustomObject]@{ Name = '06_transcript_trace'; Cue = '结构化结论可以随时回到原始转写进行复核。'; Kind = 'screen'; Duration = 0; MinDuration = 7.5 },
    [PSCustomObject]@{ Name = '07_refine'; Cue = '报告生成后，还能围绕当前内容继续追问和润色。'; Kind = 'screen'; Duration = 0; MinDuration = 6.5 },
    [PSCustomObject]@{ Name = '07_export'; Cue = '纪要支持 Markdown、纯文本、Word 和 PDF 多种格式。'; Kind = 'screen'; Duration = 0; MinDuration = 6.5 },
    [PSCustomObject]@{ Name = '08_professional_templates'; Cue = '专业日志模板覆盖施工与建筑设计等行业场景。'; Kind = 'screen'; Duration = 0; MinDuration = 8.0 },
    [PSCustomObject]@{ Name = '09_settings'; Cue = '语音转写与大模型解耦配置，适配不同部署方式。'; Kind = 'screen'; Duration = 0; MinDuration = 6.5 },
    [PSCustomObject]@{ Name = 'outro'; Cue = '智悟本，让每次会议都有清晰结论和下一步。'; Kind = 'card'; Duration = 5.5 }
)

function New-CardImage {
    param(
        [string]$Path,
        [string]$Kicker,
        [string]$Title,
        [string]$Body,
        [bool]$IsOutro
    )

    Add-Type -AssemblyName System.Drawing
    $bitmap = New-Object System.Drawing.Bitmap 1080, 1920
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit

    $navy = [System.Drawing.ColorTranslator]::FromHtml('#0B1F3A')
    $navy2 = [System.Drawing.ColorTranslator]::FromHtml('#132C4F')
    $teal = [System.Drawing.ColorTranslator]::FromHtml('#10B7A3')
    $blue = [System.Drawing.ColorTranslator]::FromHtml('#2878F0')
    $white = [System.Drawing.Color]::White
    $muted = [System.Drawing.ColorTranslator]::FromHtml('#B9C9DC')

    $graphics.Clear($navy)
    $graphics.FillRectangle((New-Object System.Drawing.SolidBrush $teal), 0, 0, 22, 1920)

    $family = New-Object System.Drawing.FontFamily 'Microsoft YaHei'
    $kickerFont = New-Object System.Drawing.Font $family, 42, ([System.Drawing.FontStyle]::Bold), ([System.Drawing.GraphicsUnit]::Pixel)
    $titleFont = New-Object System.Drawing.Font $family, 82, ([System.Drawing.FontStyle]::Bold), ([System.Drawing.GraphicsUnit]::Pixel)
    $bodyFont = New-Object System.Drawing.Font $family, 34, ([System.Drawing.FontStyle]::Regular), ([System.Drawing.GraphicsUnit]::Pixel)
    $labelFont = New-Object System.Drawing.Font $family, 30, ([System.Drawing.FontStyle]::Bold), ([System.Drawing.GraphicsUnit]::Pixel)

    $graphics.DrawString($Kicker, $kickerFont, (New-Object System.Drawing.SolidBrush $teal), 84, 145)
    $titleRect = New-Object System.Drawing.RectangleF 78, 330, 930, 390
    $graphics.DrawString($Title, $titleFont, (New-Object System.Drawing.SolidBrush $white), $titleRect)
    $bodyRect = New-Object System.Drawing.RectangleF 84, 775, 900, 180
    $graphics.DrawString($Body, $bodyFont, (New-Object System.Drawing.SolidBrush $muted), $bodyRect)

    $labels = if ($IsOutro) {
        @('清晰结论', '责任到人', '进度可追', '多格式交付')
    } else {
        @('实时转写', '模板纪要', 'AI 追问', '多格式导出')
    }
    $colors = @($blue, $teal, [System.Drawing.ColorTranslator]::FromHtml('#F7A928'), [System.Drawing.ColorTranslator]::FromHtml('#F06462'))
    for ($i = 0; $i -lt 4; $i++) {
        $row = [Math]::Floor($i / 2)
        $col = $i % 2
        $x = 84 + ($col * 470)
        $y = 1080 + ($row * 170)
        $graphics.FillRectangle((New-Object System.Drawing.SolidBrush $navy2), $x, $y, 430, 126)
        $graphics.FillRectangle((New-Object System.Drawing.SolidBrush $colors[$i]), $x, $y, 9, 126)
        $graphics.DrawString($labels[$i], $labelFont, (New-Object System.Drawing.SolidBrush $white), $x + 34, $y + 39)
    }

    $graphics.FillRectangle((New-Object System.Drawing.SolidBrush $teal), 84, 1534, 190, 6)
    $graphics.DrawString('ANDROID 功能演示', $bodyFont, (New-Object System.Drawing.SolidBrush $muted), 84, 1572)

    $bitmap.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
    $graphics.Dispose()
    $bitmap.Dispose()
    $kickerFont.Dispose()
    $titleFont.Dispose()
    $bodyFont.Dispose()
    $labelFont.Dispose()
    $family.Dispose()
}

function Get-Duration([string]$Path) {
    $value = & $ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 $Path
    if ($LASTEXITCODE -ne 0) { throw "ffprobe failed for $Path" }
    return [double]::Parse($value.Trim(), [System.Globalization.CultureInfo]::InvariantCulture)
}

function Format-SrtTime([double]$Seconds) {
    if ($Seconds -lt 0) { $Seconds = 0 }
    $span = [TimeSpan]::FromSeconds($Seconds)
    return ('{0:00}:{1:00}:{2:00},{3:000}' -f [Math]::Floor($span.TotalHours), $span.Minutes, $span.Seconds, $span.Milliseconds)
}

function New-Narration([string]$Text, [string]$Path) {
    Add-Type -AssemblyName System.Speech
    $synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
    $synth.SelectVoice('Microsoft Huihui Desktop')
    $synth.Rate = 0
    $synth.Volume = 100
    $synth.SetOutputToWaveFile($Path)
    $synth.Speak($Text)
    $synth.SetOutputToNull()
    $synth.Dispose()
}

$introPng = Join-Path $assets 'intro.png'
$outroPng = Join-Path $assets 'outro.png'
New-CardImage -Path $introPng -Kicker '智悟本' -Title "把会议现场，`n变成可执行的结果" -Body '录音、转写、模板、AI 纪要、追问与交付，在一条链路内完成。' -IsOutro $false
New-CardImage -Path $outroPng -Kicker '智悟本' -Title "让每次会议，`n都有清晰结论和下一步" -Body '从现场采集到结构化交付，一条链路完成。' -IsOutro $true

$normalized = New-Object System.Collections.Generic.List[string]
foreach ($item in $items) {
    $voicePath = Join-Path $work ($item.Name + '.wav')
    New-Narration -Text $item.Cue -Path $voicePath

    $outputPath = Join-Path $work ($item.Name + '_norm.mp4')
    if ($item.Kind -eq 'screen') {
        $inputPath = Join-Path $raw ($item.Name + '.mp4')
        if (-not (Test-Path -LiteralPath $inputPath)) { throw "Missing segment: $inputPath" }
        $rawDuration = Get-Duration $inputPath
        $item.Duration = [Math]::Max($rawDuration, $item.MinDuration)
        $padDuration = [Math]::Max(0, $item.Duration - $rawDuration)
        $durationText = $item.Duration.ToString('0.000', [System.Globalization.CultureInfo]::InvariantCulture)
        $padText = $padDuration.ToString('0.000', [System.Globalization.CultureInfo]::InvariantCulture)
        $filter = "[0:v]fps=30,tpad=stop_mode=clone:stop_duration=$padText,scale=756:1680:flags=lanczos,pad=1080:1920:162:40:color=0x0B1F3A,drawbox=x=162:y=1718:w=756:h=4:color=0x10B7A3:t=fill[v];[1:a]adelay=450|450,apad,atrim=duration=$durationText,aresample=48000,pan=stereo|c0=c0|c1=c0[a]"
        & $ffmpeg -y -hide_banner -loglevel error -i $inputPath -i $voicePath -filter_complex $filter -map '[v]' -map '[a]' -t $durationText -c:v libx264 -preset medium -crf 19 -pix_fmt yuv420p -r 30 -c:a aac -b:a 128k -ar 48000 -movflags +faststart $outputPath
    } else {
        $cardPath = if ($item.Name -eq 'intro') { $introPng } else { $outroPng }
        $durationText = $item.Duration.ToString('0.000', [System.Globalization.CultureInfo]::InvariantCulture)
        $audioFilter = "adelay=450|450,apad,atrim=duration=$durationText,aresample=48000,pan=stereo|c0=c0|c1=c0"
        & $ffmpeg -y -hide_banner -loglevel error -loop 1 -framerate 30 -i $cardPath -i $voicePath -t $durationText -map 0:v -map 1:a -vf format=yuv420p -af $audioFilter -c:v libx264 -preset medium -crf 19 -r 30 -c:a aac -b:a 128k -ar 48000 -movflags +faststart $outputPath
    }
    if ($LASTEXITCODE -ne 0) { throw "ffmpeg normalization failed for $($item.Name)" }
    $normalized.Add($outputPath)
    Write-Output "Prepared $($item.Name): $([Math]::Round($item.Duration, 2)) sec"
}

$concatList = Join-Path $work 'concat.txt'
$concatLines = $normalized | ForEach-Object {
    $relativePath = [System.IO.Path]::GetRelativePath($work, $_).Replace('\', '/')
    "file '$($relativePath.Replace("'", "''"))'"
}
[System.IO.File]::WriteAllLines($concatList, $concatLines, (New-Object System.Text.UTF8Encoding $false))

$joined = Join-Path $work 'zhiwuben_demo_joined.mp4'
& $ffmpeg -y -hide_banner -loglevel error -f concat -safe 0 -i $concatList -c copy -movflags +faststart $joined
if ($LASTEXITCODE -ne 0) { throw 'ffmpeg concat failed' }

$srtPath = Join-Path $exports '智悟本_Android功能演示_中文字幕_20260717.srt'
$srtLines = New-Object System.Collections.Generic.List[string]
$cursor = 0.0
for ($i = 0; $i -lt $items.Count; $i++) {
    $actualDuration = Get-Duration $normalized[$i]
    $start = $cursor + 0.25
    $end = $cursor + $actualDuration - 0.25
    $srtLines.Add([string]($i + 1))
    $srtLines.Add("$(Format-SrtTime $start) --> $(Format-SrtTime $end)")
    $srtLines.Add($items[$i].Cue)
    $srtLines.Add('')
    $cursor += $actualDuration
}
[System.IO.File]::WriteAllLines($srtPath, $srtLines, (New-Object System.Text.UTF8Encoding $false))

$final = Join-Path $exports '智悟本_Android功能演示_中文字幕配音_20260717.mp4'
$escapedSrt = $srtPath.Replace('\', '/').Replace(':', '\:')
$subtitleFilter = "subtitles='$escapedSrt':force_style='FontName=Microsoft YaHei,FontSize=9,PrimaryColour=&H00FFFFFF,OutlineColour=&H90000000,BorderStyle=1,Outline=0.8,Shadow=0,Alignment=2,MarginV=8,MarginL=26,MarginR=26'"
& $ffmpeg -y -hide_banner -loglevel error -i $joined -vf $subtitleFilter -c:v libx264 -preset medium -crf 18 -pix_fmt yuv420p -c:a copy -movflags +faststart $final
if ($LASTEXITCODE -ne 0) { throw 'ffmpeg subtitle burn-in failed' }

$duration = Get-Duration $final
Write-Output "Final video: $final"
Write-Output "Duration: $([Math]::Round($duration, 2)) sec"
