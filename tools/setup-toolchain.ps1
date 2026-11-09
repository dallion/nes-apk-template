[CmdletBinding()]
param([switch]$CheckOnly, [string]$ArchiveDirectory, [string]$SdkSource)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'toolchain.ps1')
$paths = Get-ProjectToolchain
$lock = Get-Content (Join-Path $PSScriptRoot 'toolchain.lock.json') -Raw | ConvertFrom-Json
if (-not [Environment]::Is64BitOperatingSystem -or $env:OS -ne 'Windows_NT') { throw 'This toolchain supports Windows x64.' }
if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { throw 'This toolchain supports Windows x64, not ARM64.' }
function Install-Archive($name, $spec, $destination, $innerDirectory) {
    $marker = Join-Path $destination '.project-toolchain.json'
    if (Test-Path -LiteralPath $marker) {
        $installed = Get-Content -LiteralPath $marker -Raw | ConvertFrom-Json
        if ($installed.sha256 -eq $spec.sha256) { return }
        throw "Tool version differs: $destination. Keep the existing installation until an explicit replacement."
    }
    if ($CheckOnly) { throw "Missing $name. Run tools/setup-toolchain.ps1." }
    if (Test-Path -LiteralPath $destination) { throw "Unmanaged installation: $destination" }
    $cache = Join-Path $paths.Directory 'downloads'
    New-Item -ItemType Directory -Force -Path $cache | Out-Null
    $archive = Join-Path $cache "$name-$($spec.version).zip"
    if (-not (Test-Path -LiteralPath $archive)) {
        $supplied = if ($ArchiveDirectory) { Join-Path $ArchiveDirectory ([IO.Path]::GetFileName(([uri]$spec.url).AbsolutePath)) } else { $null }
        if ($supplied -and (Test-Path -LiteralPath $supplied)) { Copy-Item -LiteralPath $supplied -Destination $archive }
        else {
            $partial = $archive + '.partial'
            [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
            Invoke-WebRequest -Uri $spec.url -OutFile $partial -UseBasicParsing
            if ((Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash -ne $spec.sha256) { throw "Archive checksum mismatch: $partial" }
            Move-Item -LiteralPath $partial -Destination $archive
        }
    }
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $spec.sha256) { throw "Archive checksum mismatch: $archive" }
    $stage = Join-Path $paths.Directory ('staging/' + [guid]::NewGuid().ToString('N'))
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [IO.Compression.ZipFile]::ExtractToDirectory($archive, $stage)
    $source = if ($innerDirectory) { Join-Path $stage $innerDirectory } else { $stage }
    if (-not (Test-Path -LiteralPath $source)) { throw "Unexpected archive layout: $archive" }
    New-Item -ItemType Directory -Force -Path (Split-Path $destination -Parent) | Out-Null
    $spec | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $source '.project-toolchain.json') -Encoding UTF8
    Move-Item -LiteralPath $source -Destination $destination
}
Install-Archive 'jdk' $lock.jdk $paths.Jdk "jdk-$($lock.jdk.version)"
Install-Archive 'pwsh' $lock.pwsh (Split-Path $paths.PowerShell -Parent) ''
if ($PSVersionTable.PSVersion.Major -lt 7) {
    # Android archives contain paths beyond Windows PowerShell/.NET Framework's path limit.
    $arguments = @('-NoProfile', '-File', $PSCommandPath)
    if ($CheckOnly) { $arguments += '-CheckOnly' }
    if ($ArchiveDirectory) { $arguments += @('-ArchiveDirectory', $ArchiveDirectory) }
    if ($SdkSource) { $arguments += @('-SdkSource', $SdkSource) }
    & $paths.PowerShell @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Project toolchain preparation failed.' }
    return
}
Install-Archive 'android-command-line-tools' $lock.androidCommandLineTools (Join-Path $paths.Sdk "cmdline-tools/$($lock.androidCommandLineTools.version)") 'cmdline-tools'
$jdkRelease = Get-Content -LiteralPath (Join-Path $paths.Jdk 'release') -Raw
if ($jdkRelease -notmatch ('(?m)^JAVA_RUNTIME_VERSION="' + [regex]::Escape($lock.jdk.version) + '-LTS"')) { throw 'Project JDK version does not match the lock file.' }
Invoke-ProjectToolchain {
    & (Join-Path $paths.Jdk 'bin/java.exe') -version
    if ($LASTEXITCODE -ne 0) { throw 'Project Java cannot start.' }
    $version = & $paths.PowerShell -NoProfile -Command '$PSVersionTable.PSVersion.ToString()'
    if ($LASTEXITCODE -ne 0) { throw 'Project PowerShell cannot start.' }
    if ($version -ne $lock.pwsh.version) { throw 'Project PowerShell version does not match the lock file.' }
    Write-Output "PowerShell $version"
}

$properties = Get-Content (Join-Path $paths.Root 'gradle.properties') -Raw | ConvertFrom-StringData
$components = @(
    @{Package="platforms;android-$($properties.androidCompileSdk)";Directory="platforms/android-$($properties.androidCompileSdk)";Required='android.jar'},
    @{Package="build-tools;$($properties.androidBuildToolsVersion)";Directory="build-tools/$($properties.androidBuildToolsVersion)";Required='aapt2.exe'},
    @{Package="ndk;$($properties.androidNdkVersion)";Directory="ndk/$($properties.androidNdkVersion)";Required='ndk-build.cmd'},
    @{Package="cmake;$($properties.androidCmakeVersion)";Directory="cmake/$($properties.androidCmakeVersion)";Required='bin/cmake.exe'},
    @{Package='platform-tools';Directory='platform-tools';Required='adb.exe'}
)
$missing = @()
foreach ($component in $components) {
    $destination = Join-Path $paths.Sdk $component.Directory
    if (-not (Test-Path -LiteralPath (Join-Path $destination 'source.properties')) -or -not (Test-Path -LiteralPath (Join-Path $destination $component.Required))) {
        if ($CheckOnly) { throw "Missing SDK component: $($component.Package)" }
        $source = if ($SdkSource) { Join-Path $SdkSource $component.Directory } else { $null }
        if ($source -and (Test-Path -LiteralPath (Join-Path $source 'source.properties'))) {
            Install-ProjectSdkCopy -Source $source -Destination $destination -ToolDirectory $paths.Directory -RequiredFile $component.Required
        } else {
            if (Test-Path -LiteralPath $destination) {
                $absolute=[IO.Path]::GetFullPath($destination)
                if (-not $absolute.StartsWith([IO.Path]::GetFullPath($paths.Directory)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'SDK destination escapes the project tools.' }
                $backup=Join-Path $paths.Directory ('incomplete/' + [guid]::NewGuid().ToString('N'))
                New-Item -ItemType Directory -Force -Path (Split-Path $backup -Parent) | Out-Null
                Move-Item -LiteralPath $destination -Destination $backup
            }
            $missing += $component.Package
        }
    }
}
if ($missing.Count) {
    Invoke-ProjectToolchain {
        $manager = Join-Path $paths.Sdk "cmdline-tools/$($lock.androidCommandLineTools.version)/bin/sdkmanager.bat"
        & $manager "--sdk_root=$($paths.Sdk)" @missing
        if ($LASTEXITCODE -ne 0) { throw 'SDK installation failed. Review SDK license prompts and retry setup.' }
    }
}
foreach ($component in $components) {
    $metadata = Join-Path (Join-Path $paths.Sdk $component.Directory) 'source.properties'
    if (-not (Test-Path -LiteralPath $metadata)) { throw "Missing SDK metadata: $($component.Package)" }
    $installed = Get-Content -LiteralPath $metadata -Raw | ConvertFrom-StringData
    if ($component.Package -match '^(ndk|cmake|build-tools);(.+)$' -and $installed.'Pkg.Revision' -ne $Matches[2]) { throw "SDK component version mismatch: $($component.Package)" }
    if ($component.Package -like 'platforms;*' -and $installed.'AndroidVersion.ApiLevel' -ne $properties.androidCompileSdk) { throw 'SDK platform API mismatch.' }
}
foreach ($relative in @('platform-tools/adb.exe',"platforms/android-$($properties.androidCompileSdk)/android.jar","build-tools/$($properties.androidBuildToolsVersion)/aapt2.exe","ndk/$($properties.androidNdkVersion)/ndk-build.cmd","cmake/$($properties.androidCmakeVersion)/bin/cmake.exe")) {
    if (-not (Test-Path -LiteralPath (Join-Path $paths.Sdk $relative))) { throw "SDK installation is incomplete: $relative" }
}
if (-not $CheckOnly) {
    # Change only managed keys; keep the developer's explicit game selection and other local properties.
    $localFile = Join-Path $paths.Root 'local.properties'
    $lines = @(if (Test-Path -LiteralPath $localFile) { Get-Content -LiteralPath $localFile | Where-Object {$_ -notmatch '^\s*(sdk\.dir|pwsh\.path)\s*='} })
    $lines += 'sdk.dir=' + $paths.Sdk.Replace('\','/').Replace(':','\:')
    [IO.File]::WriteAllLines($localFile, [string[]]$lines, [Text.UTF8Encoding]::new($false))
    $configDirectory = Join-Path $paths.Root '.gradle'
    New-Item -ItemType Directory -Force -Path $configDirectory | Out-Null
    $configFile = Join-Path $configDirectory 'config.properties'
    $config = @(if (Test-Path -LiteralPath $configFile) { Get-Content -LiteralPath $configFile | Where-Object {$_ -notmatch '^\s*java\.home\s*='} })
    $config += 'java.home=' + $paths.Jdk.Replace('\','/').Replace(':','\:')
    [IO.File]::WriteAllLines($configFile, [string[]]$config, [Text.UTF8Encoding]::new($false))
    $ideaDirectory = Join-Path $paths.Root '.idea'
    New-Item -ItemType Directory -Force -Path $ideaDirectory | Out-Null
    $gradleFile = Join-Path $ideaDirectory 'gradle.xml'
    [xml]$idea = if (Test-Path -LiteralPath $gradleFile) { Get-Content -LiteralPath $gradleFile -Raw } else { '<project version="4" />' }
    $settings = $idea.SelectSingleNode('/project/component[@name="GradleSettings"]')
    if (-not $settings) { $settings=$idea.CreateElement('component'); $settings.SetAttribute('name','GradleSettings'); [void]$idea.project.AppendChild($settings) }
    $linked = $settings.SelectSingleNode('option[@name="linkedExternalProjectsSettings"]')
    if (-not $linked) { $linked=$idea.CreateElement('option'); $linked.SetAttribute('name','linkedExternalProjectsSettings'); [void]$settings.AppendChild($linked) }
    $project = $linked.SelectSingleNode('GradleProjectSettings[option[@name="externalProjectPath" and @value="$PROJECT_DIR$"]]')
    if (-not $project) {
        $project=$idea.CreateElement('GradleProjectSettings'); [void]$linked.AppendChild($project)
        $location=$idea.CreateElement('option'); $location.SetAttribute('name','externalProjectPath'); $location.SetAttribute('value','$PROJECT_DIR$'); [void]$project.AppendChild($location)
    }
    $jvm = $project.SelectSingleNode('option[@name="gradleJvm"]')
    if (-not $jvm) { $jvm=$idea.CreateElement('option'); $jvm.SetAttribute('name','gradleJvm'); [void]$project.AppendChild($jvm) }
    $jvm.SetAttribute('value','#GRADLE_LOCAL_JAVA_HOME')
    $idea.Save($gradleFile)
}
Write-Output "Project tools ready: $($paths.Directory)"
