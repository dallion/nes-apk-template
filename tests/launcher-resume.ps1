param(
    [Parameter(Mandatory=$true)][string]$Device,
    [Parameter(Mandatory=$true)][string]$Package,
    [int]$Rounds = 3
)
$ErrorActionPreference = 'Stop'
. (Join-Path (Split-Path $PSScriptRoot -Parent) 'tools/toolchain.ps1')
$AdbPath=(Get-ProjectToolchain).Adb
function Invoke-Adb([string[]]$Arguments) {
    $result = & $AdbPath -s $Device @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $Arguments" }
    return $result
}
function Get-GameActivity {
    $dump = Invoke-Adb @('shell','dumpsys','activity','activities')
    $pattern = 'Hist\s+#\d+:\s+ActivityRecord\{([^ ]+) .*' + [regex]::Escape("$Package/")
    $instances = @($dump | ForEach-Object { if ($_ -match $pattern) { $Matches[1] } })
    if ($instances.Count -ne 1) { throw "Expected one game Activity; found $($instances.Count)" }
    return $instances[0]
}
# The app must already be running. This test neither clears data nor starts a new game.
$initial = Get-GameActivity
$initialPid = (Invoke-Adb @('shell','pidof',$Package)) -join ''
for($i=1; $i -le $Rounds; $i++) {
    Invoke-Adb @('shell','input','keyevent','KEYCODE_HOME') | Out-Null
    Invoke-Adb @('shell','dumpsys','activity','activities') | Out-Null
    Invoke-Adb @('shell','am','start','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER','-f','0x10600000','-n',"$Package/com.nes.template.MainActivity") | Out-Null
    $current = Get-GameActivity
    $currentPid = (Invoke-Adb @('shell','pidof',$Package)) -join ''
    if($current -ne $initial -or $currentPid -ne $initialPid) { throw 'Returning from launcher replaced the game session' }
    Write-Output "PASS launcher return $i : Activity=$current PID=$currentPid"
}
