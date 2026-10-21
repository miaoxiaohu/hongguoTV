# Pushes a built APK to the XGIMI TV's local push-install service and asks it to install.
#
# The service lives on the TV itself (192.168.1.26:10000) and holds a key; /api/upload takes the
# APK bytes, /api/install asks for the install. Measured 2026-10-19: with autoInstall on, the
# install goes through without anyone touching the TV - no dialog to tap. The install request still
# has to name the file, so the name uploaded and the name installed must be the same string.
#
# The service is not always up. It went quiet twice in one afternoon while the box was being used
# for other things, so this script probes first and says so plainly instead of hanging on a socket.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\push-to-tv.ps1 -Apk <path\to.apk>
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\push-to-tv.ps1 -Apk x.apk -WaitSeconds 180
param(
  [Parameter(Mandatory = $true)][string]$Apk,
  [string]$Host_ = "192.168.1.26",
  [int]$Port = 10000,
  # The service's key. Deliberately empty: this repository is public, and a key that can
  # push an APK onto the TV without anyone touching it must not live in it. The service
  # reports its own key on /api/info, so the default path needs no secret at all. Pass
  # -Key only for unattended runs that already know it.
  [string]$Key = "",
  [string]$Package = "com.hongguotv.nativeapp",
  # How long to wait for the TV to report the new version. 0 means "do not wait".
  [int]$WaitSeconds = 0
)

$ErrorActionPreference = 'Stop'
$name = [System.IO.Path]::GetFileName($Apk)
if (-not (Test-Path $Apk)) { throw "APK not found: $Apk" }
$size = (Get-Item $Apk).Length
Write-Output "APK: $name  ($([math]::Round($size/1MB,2)) MB)"

$base = "http://$Host_`:$Port"

# 0. The service has to be there before anything else: an upload to a dead socket fails with a
#    connection error that reads like a bad APK.
try {
  $info = Invoke-RestMethod -Uri "$base/api/info" -TimeoutSec 15
  Write-Output "service -> online"
} catch {
  throw "推装服务不在线 ($base/api/info: $($_.Exception.Message)). 先确认电视上那个服务还开着，再重试。"
}

# 0b. The key comes from the service itself when it is not given on the command line. The
#     value is never printed: a key that authorizes a silent install is a secret, and
#     anything written to stdout ends up in whatever collects this run's log.
if (-not $Key) {
  if (-not $info.pin) {
    throw "推装服务没有在 /api/info 里给出 key，无法推装。请用 -Key 显式指定。"
  }
  $Key = [string]$info.pin
  Write-Output "key     -> taken from the service's /api/info"
} else {
  Write-Output "key     -> given on the command line"
}

# Whatever the service sends back is printed as-is, except the key: a response that happens
# to echo it must not put it into the log.
function Format-Safe([string]$text) {
  if ([string]::IsNullOrEmpty($text) -or -not $Key) { return $text }
  return $text -replace [regex]::Escape($Key), '***'
}

# 1. Upload the bytes to the TV.
$up = Invoke-WebRequest -Uri "$base/api/upload?filename=$([uri]::EscapeDataString($name))&k=$Key" `
        -Method Post -InFile $Apk -ContentType 'application/octet-stream' -TimeoutSec 300 -UseBasicParsing
Write-Output ("upload  -> " + $up.StatusCode + " " + (Format-Safe $up.Content))
# The service stores the file under a different name when that name is taken: it answers with the
# name it actually used ("...-1.apk"), and the install has to name THAT file. Asking to install the
# name we sent instead installs the older file that still sits under it, so the push reports
# success while the TV keeps the previous build. Measured 2026-10-21: a re-push of 0.9.49 landed
# the previous 0.9.49 on the box instead of the new one.
$stored = $name
try {
    $answer = $up.Content | ConvertFrom-Json
    if ($answer.name) { $stored = [string]$answer.name }
} catch { }
if ($stored -ne $name) { Write-Output "upload  -> the service stored it as $stored (that name was taken)" }

# 2. Ask the TV to install it.
$body = @{ name = $stored } | ConvertTo-Json -Compress
$ins = Invoke-WebRequest -Uri "$base/api/install?k=$Key" -Method Post -ContentType 'application/json' `
        -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 120 -UseBasicParsing
Write-Output ("install -> " + $ins.StatusCode + " " + (Format-Safe $ins.Content))

# 3. Read the TV's view of the queue and of what is installed.
#    The queue entry carries the version of the file that was just uploaded. That is the version
#    the TV has to end up with, and it is the yardstick for step 4.
Start-Sleep -Seconds 3
$apks = Invoke-RestMethod -Uri "$base/api/apks?k=$Key" -TimeoutSec 60
$mine = $apks.items | Where-Object { $_.name -eq $stored }
if ($mine) { Write-Output ("queued  -> " + (Format-Safe ($mine | ConvertTo-Json -Compress))) } else { Write-Output "queued  -> NOT FOUND in the TV's list" }
$wanted = if ($mine) { $mine.versionCode } else { $null }

$installed = Invoke-RestMethod -Uri "$base/api/installed?k=$Key" -TimeoutSec 60
$app = $installed.items | Where-Object { $_.package -eq $Package }
if ($app) {
  Write-Output ("onTV    -> " + (Format-Safe ($app | ConvertTo-Json -Compress)))
} else {
  # Being absent from the list is the normal state of a fresh install: the package manager only
  # reports a package once it really has it. Treating that as a failure ends the run before the
  # wait below gets its chance, and the push then reads as broken when it actually worked.
  Write-Output "onTV    -> $Package is not installed yet"
}

# 4. Wait for the pushed version to show up on the TV. Installing takes a while on this box, and
#    "pushed" is not "installed" - reading the version back is the only proof that landed.
#    The yardstick is the version that was pushed, not the version that happened to be there
#    before: pushing a rebuild of a version that is already installed never changes versionCode,
#    and a "did the number change?" test would sit there until it timed out on an install that
#    had in fact succeeded.
if ($WaitSeconds -gt 0) {
  Write-Output "waiting -> up to $WaitSeconds s for $Package at version $wanted"
  $deadline = (Get-Date).AddSeconds($WaitSeconds)
  while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 5
    $queued = (Invoke-RestMethod -Uri "$base/api/apks?k=$Key" -TimeoutSec 60).items | Where-Object { $_.name -eq $stored }
    if ($queued -and $queued.error) {
      Write-Output ("onTV    -> the TV reported an install failure: " + (Format-Safe $queued.error))
      exit 1
    }
    $now = (Invoke-RestMethod -Uri "$base/api/installed?k=$Key" -TimeoutSec 60).items | Where-Object { $_.package -eq $Package }
    if ($now -and ($null -eq $wanted -or $now.versionCode -eq $wanted)) {
      Write-Output ("onTV    -> installed: " + (Format-Safe ($now | ConvertTo-Json -Compress)))
      exit 0
    }
  }
  Write-Output "onTV    -> $Package is not at version $wanted after $WaitSeconds s; check the TV's screen"
  exit 1
}

# No waiting was asked for, so there is no proof to give back. Say that plainly instead of
# exiting 0 on the strength of an install request that was merely accepted.
if (-not $app) {
  Write-Output "onTV    -> not confirmed (WaitSeconds=0 means no waiting); re-run with -WaitSeconds to watch the version land"
  exit 1
}
