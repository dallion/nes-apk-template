$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
. (Join-Path $root 'tools/toolchain.ps1')
$paths = Get-ProjectToolchain
$game = Join-Path $root ('tmp/' + (Get-Date -Format 'yyyy-MM-dd') + '-build-checks/' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $game | Out-Null
& node (Join-Path $PSScriptRoot 'native/make-cheat-rom.cjs') (Join-Path $game 'probe.nes')
if ($LASTEXITCODE -ne 0) { throw 'Test ROM generation failed.' }
[IO.File]::WriteAllText((Join-Path $game 'game.properties'), "id=ci_probe`nversionCode=1`nversionName=1.0`n", [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText((Join-Path $game 'probe.cht'), "[Probe]`non=6800,16`n", [Text.UTF8Encoding]::new($false))
Push-Location $root
try {
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        & $paths.Gradle --version
        if ($LASTEXITCODE -eq 0) { break }
        if ($attempt -eq 3) { throw 'Gradle bootstrap failed.' }
        if ($attempt -eq 1) { & (Join-Path $root 'tools/prepare-gradle-cache.ps1') }
        Start-Sleep -Seconds 2
    }
    & $paths.PowerShell -NoProfile -File (Join-Path $PSScriptRoot 'toolchain-checks.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'Toolchain checks failed.' }
    & $paths.PowerShell -NoProfile -File (Join-Path $PSScriptRoot 'pack-checks.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'Packaging input checks failed.' }
    & $paths.PowerShell -NoProfile -File (Join-Path $root 'pack.ps1') -game $game -test -overwrite
    if ($LASTEXITCODE -ne 0) { throw 'Test APK packaging failed.' }
    & $paths.Gradle "-PgameDir=$game" :app:lintDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Lint failed.' }
} finally {
    Pop-Location
}
