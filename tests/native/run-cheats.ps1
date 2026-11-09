param(
    [Parameter(Mandatory=$true)][string]$Device,
    [ValidateSet('arm64-v8a','x86_64')][string]$Abi = 'x86_64'
)
$ErrorActionPreference='Stop'
$root=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
. (Join-Path $root 'tools/toolchain.ps1')
$SdkPath=(Get-ProjectToolchain).Sdk
$buildProperties=Get-Content -LiteralPath (Join-Path $root 'gradle.properties') -Raw | ConvertFrom-StringData
$ndkVersion=$buildProperties.androidNdkVersion
$apiLevel=$buildProperties.androidMinSdk
if(-not $ndkVersion -or -not $apiLevel){throw 'gradle.properties 缺少 androidNdkVersion 或 androidMinSdk'}
$pattern='[\\/]local[\\/]' + [regex]::Escape($Abi) + '[\\/]libretro\.so$'
$core=Get-ChildItem (Join-Path $root 'app/build/intermediates/cxx/Debug') -Recurse -Filter libretro.so |
    Where-Object FullName -Match $pattern | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if(-not $core){throw "请先构建 $Abi 内核"}
$work=Join-Path $root 'work'
New-Item -ItemType Directory -Force -Path $work | Out-Null
$triple=if($Abi -eq 'arm64-v8a'){'aarch64'}else{'x86_64'}
$compiler=Join-Path $SdkPath "ndk/$ndkVersion/toolchains/llvm/prebuilt/windows-x86_64/bin/$triple-linux-android$apiLevel-clang.cmd"
$binary=Join-Path $work "cheat-probe-$Abi"
& $compiler (Join-Path $PSScriptRoot 'cheat-probe.c') -I (Join-Path $root 'native/fceumm/src/drivers/libretro/libretro-common/include') -ldl -o $binary
if($LASTEXITCODE -ne 0){throw '探针编译失败'}
$adb=Join-Path $SdkPath 'platform-tools/adb.exe'
function Invoke-Adb([string[]]$Arguments) {
    & $adb -s $Device @Arguments
    if($LASTEXITCODE -ne 0){throw "ADB 失败：$Arguments"}
}
$remote='/data/local/tmp/nes-cheat-probe'
Invoke-Adb @('shell','mkdir','-p',$remote)
Invoke-Adb @('push',$binary,"$remote/cheat-probe")
Invoke-Adb @('push',$core.FullName,"$remote/libretro.so")
Invoke-Adb @('shell','chmod','700',"$remote/cheat-probe")
Invoke-Adb @('shell',"$remote/cheat-probe","$remote/libretro.so")
