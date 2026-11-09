$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$root = Split-Path $PSScriptRoot -Parent
$properties = Get-Content (Join-Path $root 'gradle/wrapper/gradle-wrapper.properties') -Raw | ConvertFrom-StringData
$url = $properties.distributionUrl
$checksum = $properties.distributionSha256Sum
if ($url -notmatch '^https://services\.gradle\.org/distributions/(gradle-(.+)-bin\.zip)$') {
    throw 'Expected a pinned official Gradle binary distribution and SHA-256.'
}
$filename = $Matches[1]
$version = $Matches[2]
if ($checksum -notmatch '^[a-f0-9]{64}$') { throw 'Missing Gradle distribution SHA-256.' }
if ($properties.zipStoreBase -ne 'GRADLE_USER_HOME') { throw 'Unsupported Wrapper cache base.' }
$gradleHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path ([Environment]::GetFolderPath('UserProfile')) '.gradle' }
# Match the checked-in Wrapper's PathAssembler: base-36 of the unsigned URL MD5.
$md5 = [Security.Cryptography.MD5]::Create()
try { $bytes = $md5.ComputeHash([Text.Encoding]::UTF8.GetBytes($url)) } finally { $md5.Dispose() }
[array]::Reverse($bytes)
$number = [Numerics.BigInteger]::new([byte[]]($bytes + [byte]0))
$key = ''
$digits = '0123456789abcdefghijklmnopqrstuvwxyz'
while ($number -gt 0) {
    $key = $digits[[int]($number % 36)] + $key
    $number = [Numerics.BigInteger]::Divide($number, 36)
}
$directory = Join-Path $gradleHome ($properties.zipStorePath + '/' + $filename.Replace('.zip','') + '/' + $key)
New-Item -ItemType Directory -Force -Path $directory | Out-Null
$archive = Join-Path $directory $filename
if (Test-Path -LiteralPath $archive) {
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $checksum) { throw 'Existing Gradle archive checksum mismatch.' }
    return
}
$headers = @{Accept='application/vnd.github+json'}
if ($env:GH_TOKEN) { $headers.Authorization = 'Bearer ' + $env:GH_TOKEN }
$release = Invoke-RestMethod -Uri "https://api.github.com/repos/gradle/gradle-distributions/releases/tags/v$version" -Headers $headers
$asset = @($release.assets | Where-Object name -EQ $filename)
if ($asset.Count -ne 1 -or $asset[0].digest -ne "sha256:$checksum") { throw 'Official Gradle release does not match the Wrapper checksum.' }
if (([uri]$asset[0].url).Host -ne 'api.github.com') { throw 'Unexpected Gradle asset API host.' }
$headers.Accept = 'application/octet-stream'
$temporary = Join-Path $directory ([guid]::NewGuid().ToString('N') + '.partial')
try {
    Invoke-WebRequest -Uri $asset[0].url -Headers $headers -OutFile $temporary
    if ((Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash -ne $checksum) { throw 'Gradle archive checksum mismatch.' }
    [IO.File]::Move($temporary, $archive, $false)
} finally {
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
}
Write-Output "Prepared verified Gradle Wrapper cache: $archive"
