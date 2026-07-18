param(
    [string]$Message,
    [string]$Version
)

$ErrorActionPreference = 'Stop'

$NativeCommandPreference = Get-Variable -Name PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue
if ($NativeCommandPreference) {
    $OriginalNativeCommandErrorPreference = $NativeCommandPreference.Value
    $PSNativeCommandUseErrorActionPreference = $false
}

$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$OriginalLocation = Get-Location
$ProjectRoot = $OriginalLocation.Path
if ((Split-Path -Leaf $ProjectRoot) -ieq 'git_shell') {
    $ProjectRoot = Split-Path -Parent $ProjectRoot
}

try {
    Set-Location $ProjectRoot

    . (Join-Path $ScriptRoot 'git-script-profile.ps1')
    $ProfileDefaults = Get-GitScriptProfile
    $remoteName = $ProfileDefaults.RemoteName
    Ensure-GitHubOriginInteractive -RemoteName $remoteName | Out-Null

    function Get-CurrentBranch {
        $branchOutput = & git branch --show-current 2>$null
        if (-not $branchOutput) {
            return ''
        }

        return $branchOutput.Trim()
    }

    function Get-StatusLines {
        return @(& git status --short 2>$null)
    }

    function Write-StatusSummary {
        param([string[]]$Lines)

        if (-not $Lines -or $Lines.Count -eq 0) {
            Write-Host "[push-github] 当前工作区干净。" -ForegroundColor Green
            return
        }

        $tracked = @($Lines | Where-Object { $_ -notmatch '^\?\?' }).Count
        $untracked = @($Lines | Where-Object { $_ -match '^\?\?' }).Count
        Write-Host "[push-github] 检测到改动：已跟踪文件 $tracked 个，未跟踪文件 $untracked 个。" -ForegroundColor Yellow
    }

    function Normalize-VersionTag {
        param([string]$InputVersion)

        if (-not $InputVersion) {
            return ''
        }

        $normalized = $InputVersion.Trim()
        if (-not $normalized) {
            return ''
        }

        if ($normalized -notmatch '^v') {
            $normalized = "v$normalized"
        }

        return $normalized
    }

    function Get-LocalVersionTags {
        return @(& git tag --list 'v*' 2>$null | Sort-Object)
    }

    function Get-RemoteVersionTags {
        return @(
            & git ls-remote --tags $remoteName 2>$null | ForEach-Object {
                $line = $_.Trim()
                if (-not $line) { return }
                $parts = $line -split '\s+'
                if ($parts.Length -lt 2) { return }
                $ref = $parts[1]
                if ($ref -match '^refs/tags/(.+?)(\^\{\})?$') {
                    $Matches[1]
                }
            } | Sort-Object -Unique
        )
    }

    function Resolve-CommitMessage {
        if ($Message -and $Message.Trim()) {
            return $Message.Trim()
        }

        $inputMessage = Read-Host "请输入本次 commit 信息（必填）"
        if (-not $inputMessage -or -not $inputMessage.Trim()) {
            throw "commit 信息不能为空。"
        }

        return $inputMessage.Trim()
    }

    function Resolve-PushMode {
        Write-Host "[push-github] 请选择本次推送模式：" -ForegroundColor Cyan
        Write-Host "  1. 全量推（覆盖远端，按本地为准）" -ForegroundColor DarkGray
        Write-Host "  2. 仅推更新内容（默认，尽量保留远端现状）" -ForegroundColor DarkGray
        $choice = Read-Host "请输入 1 或 2（直接回车默认 2）"

        if ($choice -eq '1') {
            return 'full_override'
        }

        return 'update_only'
    }

    function Resolve-ReleaseMode {
        Write-Host "[push-github] 请选择本次发布方式：" -ForegroundColor Cyan
        Write-Host "  1. 默认分支推送（不创建版本 tag）" -ForegroundColor DarkGray
        Write-Host "  2. 版本发布推送（创建并同步版本 tag）" -ForegroundColor DarkGray
        $choice = Read-Host "请输入 1 或 2（直接回车默认 1）"

        if ($choice -eq '2') {
            return 'tag_release'
        }

        return 'branch_only'
    }

    function Resolve-VersionTag {
        param([bool]$UseTagRelease)

        if (-not $UseTagRelease) {
            return ''
        }

        if ($Version) {
            $directTag = Normalize-VersionTag -InputVersion $Version
            if (-not $directTag) {
                return ''
            }
        } else {
            $localTags = Get-LocalVersionTags
            $remoteTags = Get-RemoteVersionTags

            if ($localTags.Count -gt 0) {
                Write-Host "[push-github] 本地版本标签：" -ForegroundColor DarkGray
                Write-Host ("  " + ($localTags -join ', ')) -ForegroundColor DarkGray
            } else {
                Write-Host "[push-github] 当前本地仓库还没有版本标签。" -ForegroundColor DarkGray
            }

            if ($remoteTags.Count -gt 0) {
                Write-Host "[push-github] 远端版本标签：" -ForegroundColor DarkGray
                Write-Host ("  " + ($remoteTags -join ', ')) -ForegroundColor DarkGray
            } else {
                Write-Host "[push-github] 当前远端还没有版本标签。" -ForegroundColor DarkGray
            }
            $directTag = ''
        }

        while ($true) {
            if (-not $directTag) {
                $inputVersion = Read-Host "请输入新版本号（例如 1.0.0 或 v1.0.0）"
                $directTag = Normalize-VersionTag -InputVersion $inputVersion
            }

            if (-not $directTag) {
                Write-Host "[push-github] 未输入有效版本号，请重新输入。" -ForegroundColor Yellow
                continue
            }

            $prevErrorPref = $ErrorActionPreference
            $ErrorActionPreference = 'SilentlyContinue'
            & git rev-parse "refs/tags/$directTag" 1>$null 2>$null
            $tagExists = ($LASTEXITCODE -eq 0)
            $ErrorActionPreference = $prevErrorPref
            if ($tagExists) {
                Write-Host "[push-github] 版本标签 $directTag 已存在，将按当前内容覆盖该标签。" -ForegroundColor Yellow
            }

            return $directTag
        }
    }

    function Ensure-VersionTag {
        param([string]$VersionTag)

        if (-not $VersionTag) {
            return
        }

        $prevErrorPref = $ErrorActionPreference
        $ErrorActionPreference = 'SilentlyContinue'
        & git rev-parse "refs/tags/$VersionTag" 1>$null 2>$null
        $tagExists = ($LASTEXITCODE -eq 0)
        $ErrorActionPreference = $prevErrorPref
        if ($tagExists) {
            Write-Host "[push-github] 本地已存在标签 $VersionTag，正在删除旧标签以便重建..." -ForegroundColor Yellow
            & git tag -d $VersionTag 1>$null 2>$null
            if ($LASTEXITCODE -ne 0) {
                throw "删除本地旧标签 $VersionTag 失败。"
            }
        }

        Write-Host "[push-github] 创建版本标签: $VersionTag" -ForegroundColor Cyan
        & git tag -a $VersionTag -m "release: $VersionTag"
        if ($LASTEXITCODE -ne 0) {
            throw "git tag 创建失败。"
        }
    }

    function Invoke-Push {
        param(
            [string]$Branch,
            [bool]$Force,
            [string]$RemoteName
        )

        if ($Force) {
            Write-Host "[push-github] 使用 --force-with-lease 推送当前分支..." -ForegroundColor Yellow
            & git push --force-with-lease -u $RemoteName $Branch | Out-Host
            $pushExitCode = $LASTEXITCODE
            if ($pushExitCode -ne 0) {
                Write-Host "[push-github] --force-with-lease 失败，尝试使用 --force..." -ForegroundColor Yellow
                & git push --force -u $RemoteName $Branch | Out-Host
                $pushExitCode = $LASTEXITCODE
            }
        } else {
            & git push -u $RemoteName $Branch | Out-Host
            $pushExitCode = $LASTEXITCODE
        }

        return $pushExitCode
    }


    $branch = Get-CurrentBranch
    if (-not $branch) {
        throw "未检测到当前分支，当前可能处于 detached HEAD 状态。请先执行 git switch <branch> 切回分支后再推送。"
    }

    $releaseMode = Resolve-ReleaseMode
    $useTagRelease = ($releaseMode -eq 'tag_release')
    $pushMode = Resolve-PushMode
    $forcePush = ($pushMode -eq 'full_override')

    Write-Host "[push-github] 当前分支: $branch" -ForegroundColor Yellow
    Write-Host "[push-github] 获取远端当前分支信息..." -ForegroundColor Cyan
    $remoteBranchOutput = @(& git ls-remote --exit-code --heads $remoteName "refs/heads/$branch" 2>&1)
    $remoteBranchExitCode = $LASTEXITCODE
    if ($remoteBranchExitCode -eq 0) {
        $remoteBranchExists = $true
    } elseif ($remoteBranchExitCode -eq 2) {
        $remoteBranchExists = $false
    } else {
        $remoteBranchError = ($remoteBranchOutput | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
        throw "查询远端分支失败（git ls-remote 退出码 $remoteBranchExitCode）。请检查远端仓库地址、SSH 配置或网络。$([Environment]::NewLine)$remoteBranchError"
    }

    if ($remoteBranchExists) {
        & git fetch $remoteName ("+refs/heads/${branch}:refs/remotes/${remoteName}/${branch}")
        if ($LASTEXITCODE -ne 0) {
            throw "git fetch 当前分支失败。请先检查远端仓库地址、SSH 配置或网络。"
        }
    }

    if ($remoteBranchExists) {
        $prevErrorPref = $ErrorActionPreference
        $ErrorActionPreference = 'SilentlyContinue'
        $null = & git rev-parse HEAD 2>$null
        $localHasCommits = ($LASTEXITCODE -eq 0)
        $ErrorActionPreference = $prevErrorPref
        if ($localHasCommits) {
            $aheadBehindOutput = (& git rev-list --left-right --count "$branch...refs/remotes/$remoteName/$branch" 2>$null)
            $localAhead = 0
            $remoteAhead = 0
            if ($aheadBehindOutput -and $LASTEXITCODE -eq 0) {
                $parts = $aheadBehindOutput.Trim() -split '\s+'
                if ($parts.Length -ge 2) {
                    $localAhead = [int]$parts[0]
                    $remoteAhead = [int]$parts[1]
                }
            }
        } else {
            $localAhead = 0
            $remoteAhead = 0
        }

        if ($remoteAhead -gt 0 -and -not $forcePush) {
            Write-Host "[push-github] 远端比本地领先 $remoteAhead 个提交。" -ForegroundColor Yellow
            Write-Host "[push-github] 当前为仅推更新内容模式，不会自动覆盖远端。" -ForegroundColor Yellow
            Write-Host "[push-github] 可选操作：" -ForegroundColor Cyan
            Write-Host "  1. 取消推送，稍后先 pull" -ForegroundColor DarkGray
            Write-Host "  2. 继续普通 push（大概率会被拒绝）" -ForegroundColor DarkGray
            Write-Host "  3. 改为全量推（覆盖远端）" -ForegroundColor DarkGray
            $choice = Read-Host "请输入 1 / 2 / 3（默认 1）"
            if (-not $choice -or $choice -eq '1') {
                Write-Host "[push-github] 已取消推送。" -ForegroundColor Yellow
                exit 0
            }
            if ($choice -eq '3') {
                $pushMode = 'full_override'
                $forcePush = $true
            }
        }
    } else {
        Write-Host "[push-github] 远端不存在分支 $branch，后续将创建远端分支。" -ForegroundColor Yellow
    }

    Write-Host "[push-github] 当前推送模式: $(if ($pushMode -eq 'full_override') { '全量推（覆盖远端）' } else { '仅推更新内容' })" -ForegroundColor DarkGray
    $statusLines = Get-StatusLines
    Write-StatusSummary -Lines $statusLines

    if ($statusLines.Count -gt 0) {
        Write-Host "[push-github] 暂存当前仓库的所有本地改动..." -ForegroundColor Cyan
        & git add -A
        if ($LASTEXITCODE -ne 0) {
            throw "git add -A 失败。"
        }

        $stagedStatus = @(& git diff --cached --name-only)
        if ($stagedStatus.Count -gt 0) {
            $Message = Resolve-CommitMessage
            $versionTag = Resolve-VersionTag -UseTagRelease:$useTagRelease
            if ($versionTag) {
                $Message = "$Message [$versionTag]"
            }

            Write-Host "[push-github] 提交信息: $Message" -ForegroundColor Yellow
            & git commit -m $Message
            if ($LASTEXITCODE -ne 0) {
                throw "git commit 失败。"
            }

            Ensure-VersionTag -VersionTag $versionTag
        } else {
            $versionTag = ''
            Write-Host "[push-github] 当前没有可提交的已暂存改动。" -ForegroundColor Yellow
        }
    } else {
        $versionTag = Resolve-VersionTag -UseTagRelease:$useTagRelease
        Write-Host "[push-github] 当前没有本地改动，将直接执行推送。" -ForegroundColor Yellow
        if ($versionTag) {
            Ensure-VersionTag -VersionTag $versionTag
        }
    }

    Write-Host "[push-github] 推送到 GitHub..." -ForegroundColor Cyan
    $pushExitCode = Invoke-Push -Branch $branch -Force:$forcePush -RemoteName $remoteName
    if ($pushExitCode -ne 0) {
        if ($pushMode -eq 'full_override') {
            throw "git push 失败。当前已按全量推模式执行。常见原因：远端分支受保护、权限不足、SSH 配置错误。"
        }
        throw "git push 失败。常见原因：远端领先、权限不足、SSH 配置错误，或当前仍需要先 pull。"
    }

    if ($versionTag) {
        Write-Host "[push-github] 推送版本标签: $versionTag" -ForegroundColor Cyan
        Write-Host "[push-github] 若远端已存在同名标签，将按当前本地版本覆盖..." -ForegroundColor DarkGray
        & git push --force $remoteName "refs/tags/${versionTag}:refs/tags/${versionTag}"
        if ($LASTEXITCODE -ne 0) {
            throw "git push tag 失败。"
        }
    }

    Write-Host "[push-github] 已完成推送。分支: $branch" -ForegroundColor Green
    if ($versionTag) {
        Write-Host "[push-github] 已完成远端版本标签同步: $versionTag" -ForegroundColor Green
    }
}
finally {
    Set-Location $OriginalLocation
    if ($NativeCommandPreference) {
        $PSNativeCommandUseErrorActionPreference = $OriginalNativeCommandErrorPreference
    }
}
