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
  [string]$Key = "100829",
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
  Write-Output ("service -> online" + $(if ($info.pin) { " (pin=$($info.pin))" } else { "" }))
} catch {
  throw "推装服务不在线 ($base/api/info: $($_.Exception.Message)). 先确认电视上那个服务还开着，再重试。"
}

# 1. Upload the bytes to the TV.
$up = Invoke-WebRequest -Uri "$base/api/upload?filename=$([uri]::EscapeDataString($name))&k=$Key" `
        -Method Post -InFile $Apk -ContentType 'application/octet-stream' -TimeoutSec 300 -UseBasicParsing
Write-Output ("upload  -> " + $up.StatusCode + " " + $up.Content)

# 2. Ask the TV to install it.
$body = @{ name = $name } | ConvertTo-Json -Compress
$ins = Invoke-WebRequest -Uri "$base/api/install?k=$Key" -Method Post -ContentType 'application/json' `
        -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 120 -UseBasicParsing
Write-Output ("install -> " + $ins.StatusCode + " " + $ins.Content)

# 3. Read the TV's view of the queue and the installed version.
Start-Sleep -Seconds 3
$apks = Invoke-RestMethod -Uri "$base/api/apks?k=$Key" -TimeoutSec 60
$mine = $apks.items | Where-Object { $_.name -eq $name }
if ($mine) { Write-Output ("queued  -> " + ($mine | ConvertTo-Json -Compress)) } else { Write-Output "queued  -> NOT FOUND in the TV's list" }

$installed = Invoke-RestMethod -Uri "$base/api/installed?k=$Key" -TimeoutSec 60
$app = $installed.items | Where-Object { $_.package -eq $Package }
if (-not $app) {
  Write-Output "onTV    -> $Package not in the installed list"
  exit 1
}
Write-Output ("onTV    -> " + ($app | ConvertTo-Json -Compress))

# 4. Optionally wait for the new version to show up. Installing takes a while on this box, and
#    "pushed" is not "installed" - reading the version back is the only proof that landed.
if ($WaitSeconds -gt 0) {
  $before = $app.versionCode
  Write-Output "waiting -> up to $WaitSeconds s for the version to change from $before"
  $deadline = (Get-Date).AddSeconds($WaitSeconds)
  while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 5
    $now = (Invoke-RestMethod -Uri "$base/api/installed?k=$Key" -TimeoutSec 60).items | Where-Object { $_.package -eq $Package }
    if ($now -and $now.versionCode -ne $before) {
      Write-Output ("onTV    -> installed: " + ($now | ConvertTo-Json -Compress))
      exit 0
    }
  }
  Write-Output "onTV    -> still the old version after $WaitSeconds s; check the TV's screen"
  exit 1
}
