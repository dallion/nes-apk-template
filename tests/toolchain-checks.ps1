$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
. (Join-Path $root 'tools/toolchain.ps1')
$environmentBefore = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Process')
$before = @{}
foreach ($name in @('JAVA_HOME','ANDROID_HOME','ANDROID_SDK_ROOT','PATH')) { $before[$name]=[Environment]::GetEnvironmentVariable($name,'Process') }
$paths = Get-ProjectToolchain
if ($paths.Jdk -ne (Join-Path $root '.toolchains/jdk')) { throw 'JDK must belong to this project' }
if ($paths.Sdk -ne (Join-Path $root '.toolchains/android-sdk')) { throw 'SDK must belong to this project' }
if ($paths.PowerShell -ne (Join-Path $root '.toolchains/pwsh/pwsh.exe')) { throw 'PowerShell must belong to this project' }
$observed = Invoke-ProjectToolchain { $env:JAVA_HOME }
if ($observed -ne $paths.Jdk) { throw 'Child operation did not select project JDK' }
if ([Environment]::GetEnvironmentVariable('JAVA_HOME', 'Process') -ne $environmentBefore) { throw 'Caller JAVA_HOME was not restored' }
try { Invoke-ProjectToolchain { throw 'expected failure' }; throw 'Failure was swallowed' }
catch { if ($_.Exception.Message -ne 'expected failure') { throw } }
if ([Environment]::GetEnvironmentVariable('JAVA_HOME', 'Process') -ne $environmentBefore) { throw 'Failure leaked JAVA_HOME' }
foreach ($name in $before.Keys) { if ([Environment]::GetEnvironmentVariable($name,'Process') -ne $before[$name]) { throw "Environment leaked: $name" } }
$fixture=Join-Path $root ('tmp/' + (Get-Date -Format 'yyyy-MM-dd') + '-toolchain-checks/' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path (Join-Path $fixture 'tools') | Out-Null
foreach ($file in @('toolchain.ps1','setup-toolchain.ps1','toolchain.lock.json')) { Copy-Item -LiteralPath (Join-Path $root "tools/$file") -Destination (Join-Path $fixture "tools/$file") }
$start=[Diagnostics.ProcessStartInfo]::new('powershell.exe',('-NoProfile -ExecutionPolicy Bypass -File "' + (Join-Path $fixture 'tools/setup-toolchain.ps1') + '" -CheckOnly'))
$start.UseShellExecute=$false
$start.RedirectStandardError=$true
$start.RedirectStandardOutput=$true
$process=[Diagnostics.Process]::Start($start)
$result=$process.StandardError.ReadToEnd()+$process.StandardOutput.ReadToEnd()
$process.WaitForExit()
if ($process.ExitCode -eq 0 -or $result -notmatch 'Missing jdk') { throw 'Missing project JDK must fail without falling back to global Java' }
if (Test-Path -LiteralPath (Join-Path $fixture '.toolchains')) { throw 'CheckOnly created an installation' }
$lock=Get-Content (Join-Path $fixture 'tools/toolchain.lock.json') -Raw | ConvertFrom-Json
foreach ($name in @('jdk','pwsh')) {
    $directory=Join-Path $fixture ".toolchains/$name"
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    $lock.$name | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $directory '.project-toolchain.json') -Encoding UTF8
}
$process=[Diagnostics.Process]::Start($start)
$result=$process.StandardError.ReadToEnd()+$process.StandardOutput.ReadToEnd()
$process.WaitForExit()
if ($process.ExitCode -eq 0) { throw 'Installation markers must not hide missing tool binaries' }
$sdkSource=Join-Path $fixture 'sdk-source/35.0.0'
$sdkDestination=Join-Path $fixture '.toolchains/android-sdk/build-tools/35.0.0'
New-Item -ItemType Directory -Force -Path $sdkSource,$sdkDestination | Out-Null
'Pkg.Revision=35.0.0' | Set-Content -LiteralPath (Join-Path $sdkSource 'source.properties')
'binary fixture' | Set-Content -LiteralPath (Join-Path $sdkSource 'aapt2.exe')
Install-ProjectSdkCopy -Source $sdkSource -Destination $sdkDestination -ToolDirectory (Join-Path $fixture '.toolchains') -RequiredFile 'aapt2.exe'
if (-not (Test-Path -LiteralPath (Join-Path $sdkDestination 'aapt2.exe'))) { throw 'SDK recovery nested the copied directory' }
'PASS project tool paths and environment restoration on success and failure'
