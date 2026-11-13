param(
    [Parameter(Mandatory=$true)][string]$Device,
    [Parameter(Mandatory=$true)][string]$Package,
    [ValidateRange(1,4)][int]$RuntimeSpeed = 1
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../tools/toolchain.ps1')
$adb = (Get-ProjectToolchain).Adb
$result = Join-Path $PSScriptRoot ('../tmp/menu-regression/' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $result | Out-Null
function Invoke-Adb {
    $Arguments = $args
    $output = & $adb -s $Device @Arguments
    if($LASTEXITCODE -ne 0) { throw "ADB failed: $Arguments" }
    return $output
}
function Get-Tree {
    Invoke-Adb shell uiautomator dump /data/local/tmp/nes-menu.xml | Out-Null
    Invoke-Adb pull /data/local/tmp/nes-menu.xml (Join-Path $result 'current.xml') | Out-Null
    return [xml](Get-Content -LiteralPath (Join-Path $result 'current.xml') -Raw -Encoding UTF8)
}
function Find-Label([xml]$Tree, [string]$Label) {
    return @($Tree.SelectNodes('//node') | Where-Object { $_.text -eq $Label }) | Select-Object -First 1
}
function Assert-Label([string]$Label) {
    if($null -eq (Find-Label (Get-Tree) $Label)) { throw "Missing menu label: $Label" }
}
function Tap-Label([string]$Label) {
    $node = Find-Label (Get-Tree) $Label
    if($null -eq $node) { throw "Missing menu action: $Label" }
    $bounds = [regex]::Matches($node.bounds,'\d+') | ForEach-Object { [int]$_.Value }
    Invoke-Adb shell input tap ([int](($bounds[0]+$bounds[2])/2)) ([int](($bounds[1]+$bounds[3])/2)) | Out-Null
}

# Use an independent test application: this regression saves and restores its states.
Invoke-Adb shell am force-stop $Package | Out-Null
Invoke-Adb shell am start -W -n "$Package/com.nes.template.MainActivity" | Out-Null
Start-Sleep -Seconds 2
$pidBefore = (Invoke-Adb shell pidof $Package).Trim()
$before = @(Invoke-Adb shell run-as $Package cat files/diagnostics/events.log)
for($attempt = 0; $attempt -lt 10; $attempt++) {
    $tree = Get-Tree
    if($null -ne (Find-Label $tree '继续游戏')) { break }
    if($null -ne (Find-Label $tree '菜单')) { Tap-Label '菜单' }
    Start-Sleep -Seconds 1
}
Assert-Label '继续游戏'
Invoke-Adb shell input keyevent 20 | Out-Null
$focused = @((Get-Tree).SelectNodes('//node') | Where-Object { $_.focused -eq 'true' })
if($focused.Count -ne 1 -or $focused[0].text -eq '菜单' -or [string]::IsNullOrEmpty($focused[0].text)) {
    throw 'Directional navigation did not focus a menu action.'
}
Tap-Label '保存即时存档'
Tap-Label '速度：1×'
Tap-Label '4×'
Assert-Label '速度：4×'
if($RuntimeSpeed -ne 4) {
    Tap-Label '速度：4×'
    Tap-Label "${RuntimeSpeed}×"
}
Tap-Label '声音：开'
Assert-Label '声音：关'
Tap-Label '声音：关'
Tap-Label '金手指'
Tap-Label '添加金手指'
Assert-Label '6800-01-63 / 6800:63 / SXIOPO'
Invoke-Adb shell input keyevent 4 | Out-Null
Invoke-Adb shell input keyevent 4 | Out-Null
Tap-Label '手柄设置'
Tap-Label 'A'
Assert-Label '绑定按键：A'
Invoke-Adb shell input keyevent 4 | Out-Null
Invoke-Adb shell input keyevent 4 | Out-Null
Tap-Label '更多设置'
Tap-Label '重置游戏'
Assert-Label '确认重置'
Invoke-Adb shell input keyevent 4 | Out-Null
Invoke-Adb shell input keyevent 4 | Out-Null

$paused = @(Invoke-Adb shell run-as $Package cat files/diagnostics/events.log) | Select-Object -Skip $before.Count
if(@($paused | Select-String 'game resume begin').Count -ne 0) { throw 'A submenu resumed emulation.' }
if(@($paused | Select-String 'surface holder (created|changed|destroyed)').Count -ne 0) { throw 'A submenu changed the game Surface.' }

Tap-Label '读取即时存档'
Start-Sleep -Seconds 32
Invoke-Adb shell input keyevent 4 | Out-Null
Tap-Label '读取自动存档'
$tree = Get-Tree
$latest = @($tree.SelectNodes('//node') | Where-Object { $_.text -match '最新$' }) | Select-Object -First 1
if($null -eq $latest) { throw 'No automatic recovery point after 32 seconds of gameplay.' }
Tap-Label $latest.text
Invoke-Adb shell input keyevent 4 | Out-Null
Assert-Label '继续游戏'
$pidAfter = (Invoke-Adb shell pidof $Package).Trim()
if($pidBefore -ne $pidAfter) { throw 'App process changed during the regression.' }
$events = @(Invoke-Adb shell run-as $Package cat files/diagnostics/events.log) | Select-Object -Skip $before.Count
$events | Set-Content -LiteralPath (Join-Path $result 'events.log') -Encoding utf8
foreach($expected in @('auto state saved timestamp=','auto state restore end','state restore end')) {
    if(@($events | Select-String -SimpleMatch $expected).Count -eq 0) { throw "Missing success event: $expected" }
}
if(@($events | Select-String 'failed|game error code=|uncaught').Count -gt 0) { throw 'Diagnostics contain a failure.' }
Invoke-Adb shell screencap -p /data/local/tmp/nes-menu.png | Out-Null
Invoke-Adb pull /data/local/tmp/nes-menu.png (Join-Path $result 'menu.png') | Out-Null
Write-Output "Menu regression passed. Evidence: $result"
