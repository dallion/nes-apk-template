# Compatible with Windows PowerShell 5.1 so environment preparation needs no installed PowerShell 7.
function Install-ProjectSdkCopy {
    param([string]$Source, [string]$Destination, [string]$ToolDirectory, [string]$RequiredFile)
    $toolRoot=[IO.Path]::GetFullPath($ToolDirectory)
    $target=[IO.Path]::GetFullPath($Destination)
    if (-not $target.StartsWith($toolRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'SDK destination must remain inside the project tools.' }
    $stage=Join-Path $toolRoot ('staging/' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path $stage | Out-Null
    $copy=Join-Path $stage 'component'
    Copy-Item -LiteralPath $Source -Destination $copy -Recurse
    foreach ($file in @('source.properties',$RequiredFile)) {
        if (-not (Test-Path -LiteralPath (Join-Path $copy $file))) { throw "Incomplete SDK source: $Source ($file)" }
    }
    if (Test-Path -LiteralPath $target) {
        $backup=Join-Path $toolRoot ('incomplete/' + [guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Force -Path (Split-Path $backup -Parent) | Out-Null
        Move-Item -LiteralPath $target -Destination $backup
    }
    New-Item -ItemType Directory -Force -Path (Split-Path $target -Parent) | Out-Null
    Move-Item -LiteralPath $copy -Destination $target
}

function Get-ProjectToolchain {
    $projectRoot = Split-Path $PSScriptRoot -Parent
    $directory = Join-Path $projectRoot '.toolchains'
    $onWindows = $env:OS -eq 'Windows_NT'
    $system = if ($onWindows) { 'windows' } elseif ($IsMacOS) { 'macos' } elseif ($IsLinux) { 'linux' } else { throw 'Unsupported host OS.' }
    $architecture = if ($onWindows) { $env:PROCESSOR_ARCHITECTURE } else { [Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString() }
    $architecture = if ($architecture -in @('AMD64','X64')) { 'x64' } elseif ($architecture -eq 'ARM64') { 'arm64' } else { throw "Unsupported host architecture: $architecture" }
    $platform = "$system-$architecture"
    if ($platform -notin @('windows-x64','linux-x64','macos-x64','macos-arm64')) { throw "Unsupported host platform: $platform" }
    $executable = if ($onWindows) { '.exe' } else { '' }
    [pscustomobject]@{
        Root = $projectRoot
        Directory = $directory
        Platform = $platform
        IsWindows = $onWindows
        Jdk = Join-Path $directory 'jdk'
        Sdk = Join-Path $directory 'android-sdk'
        PowerShell = Join-Path $directory "pwsh/pwsh$executable"
        Java = Join-Path $directory "jdk/bin/java$executable"
        Adb = Join-Path $directory "android-sdk/platform-tools/adb$executable"
        Gradle = Join-Path $projectRoot $(if ($onWindows) { 'gradlew.bat' } else { 'gradlew' })
        NdkHost = if ($onWindows) { 'windows-x86_64' } elseif ($IsMacOS) { 'darwin-x86_64' } else { 'linux-x86_64' }
    }
}

function Invoke-ProjectToolchain {
    param([Parameter(Mandatory=$true)][scriptblock]$Action)
    $paths = Get-ProjectToolchain
    $values = @{
        JAVA_HOME = $paths.Jdk
        ANDROID_HOME = $paths.Sdk
        ANDROID_SDK_ROOT = $paths.Sdk
        PATH = ((Join-Path $paths.Jdk 'bin'), (Split-Path $paths.PowerShell -Parent), (Split-Path $paths.Adb -Parent), $env:PATH) -join [IO.Path]::PathSeparator
    }
    $previous = @{}
    foreach ($name in $values.Keys) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
    try {
        foreach ($name in $values.Keys) { [Environment]::SetEnvironmentVariable($name, $values[$name], 'Process') }
        & $Action
    } finally {
        foreach ($name in $previous.Keys) {
            if ($null -eq $previous[$name]) { Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue }
            else { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
        }
    }
}
