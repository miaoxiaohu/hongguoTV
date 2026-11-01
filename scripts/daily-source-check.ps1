<#
.SYNOPSIS
  红果 TV「源解析」每日检测 —— 跑电视 App 本体那条解析链路，异常才发通知。

.DESCRIPTION
  检测跑的是 kotlin-tv/core 里的真实代码（ContentRepository + Signer + RemoteVideo）：
  分类页取剧集 → 详情取分集 → 站方签名接口解析播放地址 → 按 Range 真读一段媒体字节。
  也就是说，探针 FAIL 就等于电视上大概率也播不出来，不是「服务活着但内容不对」。

  正常时静默（退出码 0，不推送）；任何一项失败（探针异常退出 / play FAIL / 全部失败 / 超时）
  才发一条 Bark 通知，并在日志里留档。

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\daily-source-check.ps1

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\daily-source-check.ps1 -NoPush
#>
[CmdletBinding()]
param(
  # 要检查的分类 slug（real-drama=真人剧，comic-drama=漫剧，ai-drama=AI 漫剧）
  [string[]]$Categories = @('real-drama', 'comic-drama'),
  # 单个分类的探针最长运行时间
  [int]$TimeoutMinutes = 25,
  # 只检测不发通知（调试用）
  [switch]$NoPush,
  # 报告与日志目录，默认 <repo>\logs\source-check
  [string]$ReportDir = ''
)

$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent $PSScriptRoot
$probe = Join-Path $repo 'kotlin-tv\core\build\install\core\bin\core.bat'
if (-not (Test-Path $probe)) {
  $probe = Join-Path $repo 'kotlin-tv\core\build\install\core\bin\core'
}
if (-not (Test-Path $probe)) {
  Write-Error "找不到探针：$probe`n先构建一次：cd kotlin-tv; .\gradlew.bat :core:installDist"
  exit 20
}

if (-not $ReportDir) { $ReportDir = Join-Path $repo 'logs\source-check' }
New-Item -ItemType Directory -Force -Path $ReportDir | Out-Null

# 定时任务那个会话不一定带 PATH，探针是 gradle 生成的启动脚本，需要能找到 java。
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
  $jdk = [System.Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine')
  if (-not $jdk) { $jdk = [System.Environment]::GetEnvironmentVariable('JAVA_HOME', 'User') }
  if ($jdk -and (Test-Path (Join-Path $jdk 'bin\java.exe'))) {
    $env:JAVA_HOME = $jdk
    $env:Path = (Join-Path $jdk 'bin') + ';' + $env:Path
  }
}

function Send-Bark {
  param([string]$Title, [string]$Body)
  $key = [System.Environment]::GetEnvironmentVariable('BARK_KEY', 'User')
  if (-not $key) {
    Write-Host "[warn] 没有 BARK_KEY，跳过推送：$Title"
    return $false
  }
  $payload = @{ title = $Title; body = $Body; group = 'HongguoTV源解析'; level = 'active' } | ConvertTo-Json
  # PowerShell 5.1 的 -Body 字符串按默认编码发，中文会花：显式转 UTF-8 字节
  $bytes = [System.Text.Encoding]::UTF8.GetBytes($payload)
  try {
    Invoke-RestMethod -Method Post -Uri "https://api.day.app/$key" -ContentType 'application/json; charset=utf-8' -Body $bytes | Out-Null
    return $true
  } catch {
    Write-Host "[warn] Bark 推送失败：$($_.Exception.Message)"
    return $false
  }
}

$results = @()
foreach ($category in $Categories) {
  $outFile = Join-Path $ReportDir "$category.out.txt"
  $errFile = Join-Path $ReportDir "$category.err.txt"
  $proc = Start-Process -FilePath 'cmd.exe' `
    -ArgumentList '/c', "`"$probe`"", "--play=$category" `
    -WorkingDirectory $ReportDir -NoNewWindow -PassThru `
    -RedirectStandardOutput $outFile -RedirectStandardError $errFile

  if (-not $proc.WaitForExit($TimeoutMinutes * 60 * 1000)) {
    try { $proc.Kill() } catch {}
    $results += [pscustomobject]@{
      category = $category; ok = $false; okCount = 0; failCount = -1
      reason = "探针超时（> $TimeoutMinutes 分钟）"; fails = @('探针超时'); exitCode = $null
    }
    continue
  }
  # Start-Process -PassThru 的 ExitCode 只有在进程完全收尾后才可靠，要再 WaitForExit() 一次。
  $exitCode = $null
  try {
    $proc.WaitForExit()
    $proc.Refresh()
    $exitCode = $proc.ExitCode
  } catch { $exitCode = $null }
  # 探针自己写的报告是 UTF-8；控制台重定向出来的是 GBK（按默认编码读，否则中文全是乱码）。
  $reportFile = Join-Path $ReportDir 'probe-report.txt'
  $text = ''
  if (Test-Path $reportFile) {
    Copy-Item $reportFile (Join-Path $ReportDir "$category.report.txt") -Force
    $text = [string](Get-Content $reportFile -Raw -Encoding UTF8)
  } elseif (Test-Path $outFile) {
    $text = [string](Get-Content $outFile -Raw -Encoding Default)
  }
  if ($null -eq $text) { $text = '' }
  $errText = ''
  if (Test-Path $errFile) { $errText = [string](Get-Content $errFile -Raw -Encoding Default) }
  if ($null -eq $errText) { $errText = '' }

  $okLines = @([regex]::Matches($text, '(?m)^play OK .+$') | ForEach-Object { $_.Value })
  $failLines = @([regex]::Matches($text, '(?m)^play FAIL .+$') | ForEach-Object { $_.Value })
  $summary = [regex]::Match($text, '(?m)^playSummary .+$').Value
  $succeeded = -1; $failed = -1
  if ($summary -match '成功=(\d+)\s+失败=(\d+)') { $succeeded = [int]$Matches[1]; $failed = [int]$Matches[2] }

  $reason = $null
  if ($null -ne $exitCode -and $exitCode -ne 0) {
    $tail = (($errText + "`n" + $text) -split "`r?`n" |
      Where-Object { $_.Trim() -and $_ -notmatch '^streamUrl=' } |
      Select-Object -Last 5) -join ' | '
    if ($tail.Length -gt 300) { $tail = $tail.Substring($tail.Length - 300) }
    $reason = "探针异常退出（exit=$exitCode）：$tail"
  } elseif ($failed -gt 0) {
    $reason = "有 $failed 集解析/播放失败"
  } elseif ($succeeded -lt 0) {
    $reason = "探针没有给出结果汇总（可能是分类页取不到数据）"
  } elseif ($succeeded -le 0) {
    $reason = '没有成功解析出任何一集'
  }

  $results += [pscustomobject]@{
    category = $category
    ok = ($null -eq $reason)
    okCount = $succeeded
    failCount = $failed
    reason = $reason
    fails = @($failLines | ForEach-Object { if ($_.Length -gt 160) { $_.Substring(0, 160) } else { $_ } })
    exitCode = $exitCode
  }
}

$bad = @($results | Where-Object { -not $_.ok })
$stamp = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')

$summaryLines = @("红果TV 源解析检测 $stamp")
foreach ($r in $results) {
  $summaryLines += if ($r.ok) {
    "  [OK] $($r.category)：成功 $($r.okCount) 集"
  } else {
    "  [FAIL] $($r.category)：$($r.reason)"
  }
}
$summaryText = $summaryLines -join "`n"

$logFile = Join-Path $ReportDir ('runs.log')
Add-Content -Path $logFile -Value "==== $stamp ====`n$summaryText`n" -Encoding UTF8

Write-Host $summaryText

if ($bad.Count -eq 0) {
  Write-Host '全部正常，不推送。'
  exit 0
}

$body = @()
foreach ($r in $bad) {
  $body += "[$($r.category)] $($r.reason)"
  foreach ($f in $r.fails | Select-Object -First 3) { $body += "  $f" }
}
$body += "日志：$ReportDir"
$bodyText = ($body -join "`n")
if ($bodyText.Length -gt 500) { $bodyText = $bodyText.Substring(0, 500) }

if (-not $NoPush) {
  Send-Bark -Title "红果TV 源解析异常（$($bad.Count)/$($results.Count) 项失败）" -Body $bodyText | Out-Null
}
Write-Host "异常明细：`n$bodyText"
exit 10
