# Talks to the phone on this desk over adb: the Palm Phone (PVG100, codename "Pepito").
#
# Why the phone is in the loop at all. The XGIMI TV has no adb over network - 192.168.1.26:5555
# refuses the connection - so a crash on the TV leaves no stack trace anywhere on it. The phone
# runs Android 8.1 against the TV's Android 8.0, close enough to the same environment that the
# same APK reproduces the same failure, and this phone does have adb. So the deal is: run the TV
# build on the phone, read logcat here, and let the phone stand in for the box.
#
# The phone talks adb over TCP 5555, permanently. A Magisk module on the phone ("adb_tcp") sets
# service.adb.tcp.port=5555 and restarts adbd at every boot, so the USB serial never comes back
# and "adb tcpip" is no longer needed - it would fail anyway, there is no USB transport to run it
# on. This script therefore finds the phone by its Wi-Fi MAC in the neighbour table and connects
# over the network. Its address comes from DHCP and can change between reboots; the MAC cannot.
#
# Which adb client runs matters. Two copies exist on this machine:
#   D:\Programs\android-sdk\platform-tools\adb.exe       37.0.1   <- the one to use
#   D:\Programs\AndroidSideloader\platform-tools\adb.exe 35.0.0   <- version mismatch trap
# adb will not talk to a server started by a different version ("adb server version (41) doesn't
# match this client (40)") and it settles that by killing the other server. This script resolves
# the SDK copy first so the mismatch never happens by accident.
#
# The phone is a small 3.3" screen, so the mirror is not a luxury: pointing at the TV app on it
# is painful without one.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\adb-phone.ps1 status
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\adb-phone.ps1 devices
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\adb-phone.ps1 install path\to.apk
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\adb-phone.ps1 logcat
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\adb-phone.ps1 logcat -Seconds 20
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\adb-phone.ps1 shell getprop ro.build.version.release
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\adb-phone.ps1 reverse 8081
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\adb-phone.ps1 wireless
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\adb-phone.ps1 wireless-off
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\adb-phone.ps1 screen
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\adb-phone.ps1 raw install -r x.apk
param(
  # What to do. Anything not listed here falls through to "raw" with the remaining arguments.
  [Parameter(Position = 0)]
  [string]$Command = "status",

  # Everything after the command word. For "install" it is the APK path, for "shell" the command
  # line to run on the phone, for "raw" the arguments for adb itself.
  [Parameter(Position = 1, ValueFromRemainingArguments = $true)]
  [string[]]$Rest,

  # Pin a specific device. Empty means "work it out": prefer a wireless device over the USB one,
  # because after "adb tcpip" the USB serial still shows up but as "offline".
  [string]$Serial = "",

  # logcat: start from a clean buffer, and/or watch for a while instead of forever.
  [switch]$Clear,
  [string]$Grep = "",
  [int]$Seconds = 0,

  # install: put the package away again.
  [switch]$Uninstall,

  # The app this repository builds.
  [string]$Package = "com.hongguotv.nativeapp"
)

$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------------------------------------
# Resolve the adb client.
#
# The SDK copy is preferred over whatever is on PATH, and the AndroidSideloader copy is skipped
# outright: it is older, and two adb versions fighting over one server is a confusing failure to
# debug. The folder name is checked because that copy is not on PATH today but the Sideloader
# app can put it there.
# ---------------------------------------------------------------------------------------------
function Resolve-Adb {
  $candidates = New-Object System.Collections.Generic.List[string]
  $candidates.Add("D:\Programs\android-sdk\platform-tools\adb.exe")
  $onPath = Get-Command adb -ErrorAction SilentlyContinue
  if ($onPath) { $candidates.Add($onPath.Source) }
  foreach ($c in $candidates) {
    if (-not $c) { continue }
    if ($c -like "*AndroidSideloader*") { continue }
    if (Test-Path $c) { return $c }
  }
  throw "找不到 adb.exe。装 platform-tools，或把它放到 PATH 上再试。"
}

$Adb = Resolve-Adb

# ---------------------------------------------------------------------------------------------
# Device rows.
#
# "adb devices -l" prints one line per device: serial, then state. The states that matter here are
# "device" (ready), "unauthorized" (the RSA prompt on the phone was never accepted) and "offline"
# (a USB device that has been handed over to a wireless session).
# ---------------------------------------------------------------------------------------------
function Get-AdbOutput {
  # Run adb and return its output as plain strings.
  #
  # The stderr handling is the point of this helper. The script runs with
  # $ErrorActionPreference = "Stop", and in Windows PowerShell 5.1 a native command that writes
  # to stderr turns that into a terminating error - which is exactly what happens on a cold
  # start: the adb server is not running yet, so "adb devices" prints "* daemon not running;
  # starting now at tcp:5037" on stderr and the script dies on the spot. With the phone in
  # permanent TCP mode that cold start is the normal case (the server starts with the PC and
  # the phone has to be connected over the network), so this has to be survivable.
  param([Parameter(Mandatory = $true)][string[]]$Arguments)
  $prev = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    $out = & $Adb @Arguments 2>&1
  } finally {
    $ErrorActionPreference = $prev
  }
  $lines = @()
  foreach ($item in @($out)) {
    if ($item -is [System.Management.Automation.ErrorRecord]) { $lines += $item.ToString() }
    else { $lines += [string]$item }
  }
  return $lines
}

function Get-DeviceRow {
  $rows = @()
  $lines = Get-AdbOutput @("devices", "-l")
  foreach ($line in $lines) {
    if ($line -match "^\s*(\S+)\s+(device|unauthorized|offline|recovery|bootloader|sideload|host)\b") {
      $rows += [pscustomobject]@{ Serial = $Matches[1]; State = $Matches[2] }
    } elseif ($line -match "^\s*(\S+)\s+no permissions") {
      $rows += [pscustomobject]@{ Serial = $Matches[1]; State = "no permissions" }
    }
  }
  return $rows
}

# ---------------------------------------------------------------------------------------------
# Finding the phone on the network.
#
# The phone runs adbd in permanent TCP mode - a Magisk module ("adb_tcp") sets
# service.adb.tcp.port=5555 and restarts adbd at every boot - so there is no USB serial left to
# talk to. The USB transport is gone as far as adb is concerned. What is left to identify the
# phone is its Wi-Fi MAC address in the neighbour table. The address itself comes from DHCP and
# can change between reboots; the MAC cannot.
#
# Measured 2026-10-24 on this Palm Phone: MAC b4-69-5f-93-71-cf, address 192.168.1.30 at the
# time of writing.
# ---------------------------------------------------------------------------------------------
$PhoneWifiMac = "b4-69-5f-93-71-cf"

function Get-PhoneIpFromArp {
  # "arp -a" prints one "<ip> <mac> dynamic" line per neighbour, MAC dash separated.
  $want = ($PhoneWifiMac -replace "-", "").ToLower()
  foreach ($line in (& arp -a 2>$null)) {
    if ($line -notmatch "^\s*(\d+\.\d+\.\d+\.\d+)\s+([0-9a-fA-F\-]{17})\s") { continue }
    if ((($Matches[2]) -replace "-", "").ToLower() -eq $want) { return $Matches[1] }
  }
  return $null
}

function Connect-PhoneOverTcp {
  # Connect to the phone over TCP 5555 and report whether that worked. The messages go to the
  # host, not into the pipeline, so the boolean this returns stays clean.
  $ip = Get-PhoneIpFromArp
  if (-not $ip) {
    Write-Host "wireless -> MAC $PhoneWifiMac 不在邻居表里"
    return $false
  }
  $out = (Get-AdbOutput @("connect", "$ip`:5555")) -join " "
  if ($out -match "connected to") {
    Write-Host "wireless -> connected to $ip`:5555"
    return $true
  }
  Write-Host "wireless -> 连不上 $ip`:5555 ($out)"
  return $false
}

function Get-Target {
  $rows = Get-DeviceRow
  if (-not $rows) {
    # Nothing in the list at all. Before assuming the cable is loose, try the network: the
    # phone's adbd is in permanent TCP mode, so after a reboot of either side the USB serial
    # is simply gone and the phone is only reachable at its address on port 5555.
    if (Connect-PhoneOverTcp) { $rows = Get-DeviceRow }
  }
  if (-not $rows) {
    # Measured 2026-10-24: after "adb reconnect" the phone fell off the list entirely and stayed
    # off until the cable was pulled and pushed again. An empty list therefore means "the cable
    # needs a wiggle", not "the phone is broken".
    throw "没看到任何设备。已按 MAC（$PhoneWifiMac）在邻居表里找过并试连 5555，没找到。`n拔一下 USB 线再插回去（adb reconnect 会把设备从列表里甩掉），或确认手机连着 Wi-Fi。"
  }

  if ($Serial) {
    $row = $rows | Where-Object { $_.Serial -eq $Serial } | Select-Object -First 1
    if (-not $row) {
      throw "指定的设备 $Serial 不在 adb 列表里。现在是：$(($rows | ForEach-Object { $_.Serial + ' (' + $_.State + ')' }) -join ', ')"
    }
  } else {
    # A ready device, and among those the wireless one: after "adb tcpip 5555" the USB serial
    # stays in the list but drops to "offline" while the phone talks over the network.
    $row = $rows |
      Where-Object { $_.State -eq "device" } |
      Sort-Object { if ($_.Serial -match ":") { 0 } else { 1 } } |
      Select-Object -First 1
  }

  if (-not $row) {
    $bad = $rows | Where-Object { $_.State -eq "unauthorized" } | Select-Object -First 1
    if ($bad) {
      throw @"
手机还没授权本机的 adb 密钥。解锁手机，在「允许 USB 调试吗?」的对话框里勾上「始终允许」再确定。
3.3 寸的屏对话框很小，弹不出来就把「开发者选项 -> USB 调试」关掉再开，或者「撤销 USB 调试授权」后重插线。
"@
    }
    $states = ($rows | ForEach-Object { $_.Serial + " (" + $_.State + ")" }) -join ", "
    if ($rows | Where-Object { $_.State -eq "offline" }) {
      throw "没有可用的设备。当前状态：$states`nUSB 那头是 offline 通常意味着手机刚跑过 adb tcpip / adb usb —— 拔一下线再插回去。"
    }
    throw "没有可用的设备。当前状态：$states"
  }

  if ($row.State -ne "device") {
    throw "设备 $($row.Serial) 的状态是 $($row.State)，还不能用。"
  }
  return $row
}

function Invoke-Adb {
  param([Parameter(Mandatory = $true)][string[]]$Arguments)
  # stderr is relaxed here for the same reason as in Get-AdbOutput: under
  # $ErrorActionPreference = "Stop" a native command writing to stderr would terminate the run.
  $prev = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try { & $Adb @Arguments } finally { $ErrorActionPreference = $prev }
  return $LASTEXITCODE
}

# ---------------------------------------------------------------------------------------------
# Commands.
# ---------------------------------------------------------------------------------------------
switch ($Command.ToLowerInvariant()) {

  "devices" {
    Get-AdbOutput @("devices", "-l") | ForEach-Object { Write-Output $_ }
    break
  }

  "status" {
    $row = Get-Target
    Write-Output "adb      -> $Adb"
    Write-Output "device   -> $($row.Serial)"
    $model = (& $Adb -s $row.Serial shell getprop ro.product.model 2>$null) -join " "
    $release = (& $Adb -s $row.Serial shell getprop ro.build.version.release 2>$null) -join " "
    $sdk = (& $Adb -s $row.Serial shell getprop ro.build.version.sdk 2>$null) -join " "
    $abi = (& $Adb -s $row.Serial shell getprop ro.product.cpu.abi 2>$null) -join " "
    $size = (& $Adb -s $row.Serial shell wm size 2>$null) -join " "
    Write-Output "model    -> $model"
    Write-Output "android  -> $release (API $sdk), abi $abi"
    Write-Output "screen   -> $size"
    $installed = (& $Adb -s $row.Serial shell pm list packages $Package 2>$null) -join ""
    if ($installed -match [regex]::Escape($Package)) {
      $ver = (& $Adb -s $row.Serial shell dumpsys package $Package 2>$null |
              Select-String -Pattern "versionName=" | Select-Object -First 1)
      Write-Output "app      -> $Package is installed $($ver -replace '\s+', ' ')"
    } else {
      Write-Output "app      -> $Package is not installed on the phone"
    }
    break
  }

  "install" {
    if (-not $Rest -or -not $Rest[0]) { throw "用法：adb-phone.ps1 install <path\to.apk>" }
    $apk = $Rest[0]
    if (-not (Test-Path $apk)) { throw "APK 不存在：$apk" }
    $row = Get-Target
    $name = [System.IO.Path]::GetFileName($apk)
    $mb = [math]::Round((Get-Item $apk).Length / 1MB, 2)
    Write-Output "install  -> $name ($mb MB) -> $($row.Serial)"
    & $Adb -s $row.Serial install -r $apk
    if ($LASTEXITCODE -ne 0) { throw "adb install 失败，退出码 $LASTEXITCODE" }
    # Read the version back off the phone: the install call only says the package manager
    # accepted the file, and a version that did not land is the failure worth knowing about.
    $ver = (& $Adb -s $row.Serial shell dumpsys package $Package 2>$null |
            Select-String -Pattern "versionName=" | Select-Object -First 1)
    Write-Output "onPhone  -> $($ver -replace '\s+', ' ')"
    break
  }

  "uninstall" {
    $row = Get-Target
    & $Adb -s $row.Serial uninstall $Package
    break
  }

  "logcat" {
    $row = Get-Target
    if ($Clear) { & $Adb -s $row.Serial logcat -c }
    Write-Output "logcat   -> $($row.Serial). Ctrl-C 结束。"
    if ($Seconds -gt 0) {
      # Run it as a job so it can be cut off on time; without this the stream never returns.
      $job = Start-Job -ScriptBlock {
        param($exe, $dev)
        & $exe -s $dev logcat -v time
      } -ArgumentList $Adb, $row.Serial
      if ($Grep) {
        $job | Wait-Job -Timeout $Seconds | Receive-Job | Select-String -Pattern $Grep
      } else {
        $job | Wait-Job -Timeout $Seconds | Receive-Job
      }
      Stop-Job $job -ErrorAction SilentlyContinue
      Remove-Job $job -Force -ErrorAction SilentlyContinue
    } elseif ($Grep) {
      & $Adb -s $row.Serial logcat -v time | Select-String -Pattern $Grep
    } else {
      & $Adb -s $row.Serial logcat -v time
    }
    break
  }

  "shell" {
    $row = Get-Target
    if (-not $Rest) { throw "用法：adb-phone.ps1 shell <command>" }
    & $Adb -s $row.Serial shell @Rest
    break
  }

  "reverse" {
    # The React Native project in android/ talks to a metro server on this machine over 8081.
    # "adb reverse" points the phone's 8081 back at the PC, so the phone loads the bundle from
    # here instead of from a copy baked into the APK.
    $row = Get-Target
    $ports = if ($Rest) { $Rest } else { @("8081") }
    foreach ($p in $ports) {
      & $Adb -s $row.Serial reverse "tcp:$p" "tcp:$p"
      Write-Output "reverse  -> tcp:$p -> this machine"
    }
    break
  }

  "forward" {
    $row = Get-Target
    if ($Rest.Count -lt 2) { throw "用法：adb-phone.ps1 forward <remote> <local>" }
    & $Adb -s $row.Serial forward $Rest[0] $Rest[1]
    break
  }

  "wireless" {
    # Two situations, and which one applies decides the whole flow:
    #
    # 1. The phone still speaks USB adb (it was never switched over, or somebody ran "adb usb").
    #    Then the address is read over USB first, because "adb tcpip" restarts adbd in TCP mode
    #    and the USB transport disappears with it - afterwards there is no USB shell left to ask.
    # 2. The phone is already in permanent TCP mode (Magisk module adb_tcp). Then there is no
    #    USB serial at all, and the phone is found by its MAC in the neighbour table.
    #
    # The phone and this machine share the 192.168.1.x segment.
    $rows = Get-DeviceRow
    $net = $rows | Where-Object { $_.State -eq "device" -and $_.Serial -match ":" } | Select-Object -First 1
    if ($net) {
      Write-Output "wireless -> already on $($net.Serial)"
      break
    }

    $usb = $rows | Where-Object { $_.State -eq "device" -and $_.Serial -notmatch ":" } | Select-Object -First 1
    if (-not $usb) {
      # No USB transport: the phone is in permanent TCP mode. Find it by MAC and connect.
      if (-not (Connect-PhoneOverTcp)) {
        throw "找不到手机。邻居表里没有 MAC $PhoneWifiMac —— 手机得连着 Wi-Fi（连的是 MIFI）。`n刚开机的话等几十秒，Wi-Fi 起来后地址才会进邻居表。"
      }
      Get-AdbOutput @("devices", "-l") | ForEach-Object { Write-Output $_ }
      break
    }

    $row = $usb

    # The phone's own address is read from the "src" field of its wlan0 route, not from the
    # address in front of "dev wlan0": that one is the subnet (192.168.1.0/24), not the host.
    # "ip -f inet addr show wlan0" is the fallback for a phone whose route table has no src.
    $ip = $null
    $route = (& $Adb -s $row.Serial shell ip route 2>$null) -join "`n"
    foreach ($line in ($route -split "`n")) {
      if ($line -notmatch "wlan0") { continue }
      $m = [regex]::Match($line, "src\s+(\d+\.\d+\.\d+\.\d+)")
      if ($m.Success) { $ip = $m.Groups[1].Value; break }
    }
    if (-not $ip) {
      $addr = (& $Adb -s $row.Serial shell "ip -f inet addr show wlan0" 2>$null) -join "`n"
      $m = [regex]::Match($addr, "inet\s+(\d+\.\d+\.\d+\.\d+)")
      if ($m.Success) { $ip = $m.Groups[1].Value }
    }
    if (-not $ip) {
      throw "读不到手机在 wlan0 上的地址。手机得连着 Wi-Fi。"
    }

    & $Adb -s $row.Serial tcpip 5555
    Start-Sleep -Seconds 3
    Write-Output "wireless -> connecting $ip`:5555"
    & $Adb connect "$ip`:5555"
    Start-Sleep -Seconds 1
    Get-AdbOutput @("devices", "-l") | ForEach-Object { Write-Output $_ }
    Write-Output "提示：现在可以拔掉 USB 线了。想切回 USB：adb-phone.ps1 wireless-off"
    Write-Output "（注意：这台手机平时是开机自动进 TCP 模式的，wireless-off 只对这一次有效，重启手机就自动回来了）"
    break
  }

  "wireless-off" {
    # "adb tcpip" leaves the phone listening on TCP only. "adb usb" asks it to go back to USB.
    # Measured 2026-10-24 on this Palm Phone: that call does NOT bring the USB transport back
    # by itself. The phone's adbd came up in USB mode while the USB gadget had already dropped
    # out of enumeration, so the PC saw neither a USB serial nor a TCP port - the phone had to
    # have its cable pulled and pushed again (or a reboot) before adb came back. So this
    # command checks afterwards and says what to do instead of leaving a dead end.
    #
    # Note for the permanent mode: "adb usb" also clears service.adb.tcp.port, so the phone
    # stays in USB mode until the next reboot - and then the Magisk module "adb_tcp" sets it
    # back to 5555 all by itself. Rebooting the phone is therefore the clean way out.
    $rows = Get-DeviceRow
    $net = $rows | Where-Object { $_.State -eq "device" -and $_.Serial -match ":" } | Select-Object -First 1
    if (-not $net) {
      Write-Output "usb      -> no wireless device found; the phone may already be on USB"
      Get-AdbOutput @("devices", "-l") | ForEach-Object { Write-Output $_ }
      break
    }
    & $Adb -s $net.Serial usb
    Write-Output "usb      -> asked the phone to go back to USB mode ($($net.Serial))"

    $back = $false
    for ($i = 0; $i -lt 6; $i++) {
      Start-Sleep -Seconds 2
      $rows = Get-DeviceRow
      if ($rows | Where-Object { $_.State -eq "device" -and $_.Serial -notmatch ":" }) { $back = $true; break }
    }
    if (-not $back) {
      Write-Output @"
usb      -> 没有自己回来。现在手机的 adbd 是 USB 模式，所以无线 5555 也连不上 —— 两条路同时断了。
请把 USB 线拔掉再插回去（或者重启手机），USB 通道就会回来。
"@
    }
    Get-AdbOutput @("devices", "-l") | ForEach-Object { Write-Output $_ }
    break
  }

  "screen" {
    $row = Get-Target
    $scrcpy = $null
    $onPath = Get-Command scrcpy -ErrorAction SilentlyContinue
    if ($onPath) { $scrcpy = $onPath.Source }
    if (-not $scrcpy) {
      # winget's portable install of scrcpy failed on this machine with 0x80070057, so it was
      # unpacked by hand into D:\Programs\scrcpy instead. Both places are checked.
      foreach ($guess in @("$env:LOCALAPPDATA\Microsoft\WinGet\Links\scrcpy.exe", "D:\Programs\scrcpy\scrcpy.exe")) {
        if (-not $scrcpy -and (Test-Path $guess)) { $scrcpy = $guess }
      }
    }
    if (-not $scrcpy) { throw "没找到 scrcpy。先装上（winget install Genymobile.scrcpy，或解压到 D:\Programs\scrcpy）。" }
    # Audio on a USB-connected phone over scrcpy on Windows tends to stutter or fail outright,
    # and nothing here needs it. The phone's screen goes off and stays awake so it does not drop
    # out mid-session.
    Write-Output "screen   -> scrcpy on $($row.Serial)"
    & $scrcpy -s $row.Serial --no-audio --turn-screen-off --stay-awake
    break
  }

  "raw" {
    if (-not $Rest) { throw "用法：adb-phone.ps1 raw <adb 参数...>" }
    $row = Get-Target
    & $Adb -s $row.Serial @Rest
    break
  }

  default {
    throw "不认识的命令：$Command。可以用：devices / status / install / uninstall / logcat / shell / reverse / forward / wireless / wireless-off / screen / raw"
  }
}
