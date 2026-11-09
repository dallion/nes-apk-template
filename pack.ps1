[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Game,
    [switch]$CheckOnly,
    [switch]$Test,
    [switch]$Overwrite
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'tools/toolchain.ps1')
if (-not (Get-ProjectToolchain).IsWindows) { throw 'Use bash ./pack.sh on Linux or macOS.' }
if ($PSVersionTable.PSVersion.Major -lt 7) {
    $arguments=@('-NoProfile','-File',$PSCommandPath,'-Game',$Game)
    if($CheckOnly){$arguments+='-CheckOnly'}
    if($Test){$arguments+='-Test'}
    if($Overwrite){$arguments+='-Overwrite'}
    & (Get-ProjectToolchain).PowerShell @arguments
    if($LASTEXITCODE -ne 0){throw "Packaging failed: $LASTEXITCODE"}
    return
}
Invoke-ProjectToolchain {
$selected=& (Join-Path $PSScriptRoot 'tools/resolve-game.ps1') -Game $Game | ConvertFrom-Json
Write-Output "游戏：$($selected.appName) / id=$($selected.id)"
Write-Output "包名：$($selected.applicationId) / 版本：$($selected.versionName) ($($selected.versionCode))"
Write-Output "ROM：$($selected.romFile)"
Write-Output "ROM SHA-256：$($selected.romSha256)"
Write-Output "CHT：$(if($selected.cheatFile){$selected.cheatFile}else{'未提供金手指'})"
Write-Output "APK：$($selected.output)"
foreach($warning in $selected.warnings){Write-Warning $warning}
if((Test-Path -LiteralPath $selected.output) -and -not $Overwrite){throw '输出 APK 已存在。请递增版本号，或明确传入 -Overwrite 替换。'}
if($CheckOnly){return}
if(-not (Test-Path -LiteralPath (Get-ProjectToolchain).Java)){throw 'Project JDK is missing. Run tools/setup-toolchain.ps1.'}
$tasks=@(':app:stageDebugApk')
if($Test -or $selected.cheatFile){$tasks=@(':app:testDebugUnitTest',':app:stageDebugApk')}
$snapshotDirectory=Join-Path $PSScriptRoot 'build/package-inputs'
New-Item -ItemType Directory -Force $snapshotDirectory | Out-Null
$snapshot=Join-Path $snapshotDirectory ([guid]::NewGuid().ToString('N')+'.json')
$staged="$snapshot.apk"
[IO.File]::WriteAllText($snapshot,($selected | ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
Push-Location $PSScriptRoot
try {
    & (Get-ProjectToolchain).Gradle @tasks "-PgameDir=$($selected.directory)" "-PgameInputsSnapshot=$snapshot" --console=plain
    if($LASTEXITCODE -ne 0){throw "构建失败，退出码 $LASTEXITCODE，未更新交付 APK。"}
    $metadata=Get-Content -LiteralPath (Join-Path $staged 'output-metadata.json') -Raw | ConvertFrom-Json
    if($metadata.applicationId -cne $selected.applicationId -or @($metadata.elements).Count -ne 1 -or
        $metadata.elements[0].versionCode -ne $selected.versionCode -or $metadata.elements[0].versionName -cne $selected.versionName){
        throw '构建产物的包名或版本与本次游戏配置不一致，未输出 APK。'
    }
    # Check the built inputs before publishing; a source changed during the build must not be mislabeled.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $built=Join-Path $staged 'app-debug.apk'
    $zip=[IO.Compression.ZipFile]::OpenRead($built)
    try {
        foreach($item in @(@{Name='assets/game.nes';Hash=$selected.romSha256},@{Name='assets/game.cht';Hash=$selected.cheatSha256})){
            $entry=$zip.GetEntry($item.Name)
            if(-not $item.Hash){if($entry){throw "发现不属于本次游戏的旧资源：$($item.Name)"}; continue}
            if(-not $entry){throw "构建缺少资源：$($item.Name)"}
            $stream=$entry.Open()
            try {$actual=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)).ToLowerInvariant()}
            finally {$stream.Dispose()}
            if($actual -ne $item.Hash){throw "构建过程中输入变化：$($item.Name)，未输出 APK，请重新打包。"}
        }
    } finally {$zip.Dispose()}
    $outputDirectory=Split-Path $selected.output -Parent
    New-Item -ItemType Directory -Force $outputDirectory | Out-Null
    $temporary=Join-Path $outputDirectory ('.'+[guid]::NewGuid().ToString('N')+'.tmp')
    try {
        Copy-Item -LiteralPath $built -Destination $temporary
        [IO.File]::Move($temporary,$selected.output,[bool]$Overwrite)
    } finally {if(Test-Path -LiteralPath $temporary){Remove-Item -LiteralPath $temporary}}
    Write-Output "打包完成：$($selected.output)"
} finally {
    Pop-Location
    if(Test-Path -LiteralPath $snapshot){Remove-Item -LiteralPath $snapshot}
    if(Test-Path -LiteralPath $staged){
        foreach($name in @('app-debug.apk','output-metadata.json')){
            $file=Join-Path $staged $name
            if(Test-Path -LiteralPath $file){Remove-Item -LiteralPath $file}
        }
        [IO.Directory]::Delete($staged)
    }
}
}
