# Shared by pack.ps1 and Gradle. Standard output is JSON only.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Game,
    [string]$OutputRoot = (Join-Path (Split-Path $PSScriptRoot -Parent) 'outputs')
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
if(-not (Test-Path -LiteralPath $Game -PathType Container)){throw "游戏目录不存在：$Game"}
$directory=(Resolve-Path -LiteralPath $Game).ProviderPath
$entries=@(Get-ChildItem -LiteralPath $directory -Force)
$files=@($entries | Where-Object { -not $_.PSIsContainer })
$icon=$entries | Where-Object Name -ceq 'icon.png'
$roms=@($files | Where-Object Extension -ieq '.nes')
$cheats=@($files | Where-Object Extension -ieq '.cht')
if($roms.Count -ne 1){throw "游戏目录必须恰好一个 NES 文件，实际 $($roms.Count) 个：$directory"}
if($cheats.Count -gt 1){throw "游戏目录最多一个 CHT 文件，实际 $($cheats.Count) 个：$directory"}
$configFile=Join-Path $directory 'game.properties'
if(-not (Test-Path -LiteralPath $configFile -PathType Leaf)){throw "缺少 game.properties：$directory"}
$selected=@((Get-Item -LiteralPath $configFile),$roms[0])+$cheats
if($icon){
    if($icon.PSIsContainer){throw "图标必须是普通文件：$($icon.FullName)"}
    $selected+=@($icon)
}
foreach($file in $selected){
    if($file.Attributes -band [IO.FileAttributes]::ReparsePoint){throw "游戏输入文件不允许链接或重解析点：$($file.Name)"}
}
$allowed=@('id','appName','applicationId','versionCode','versionName','cheatsRomSha256')
$config=[Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
try {$configText=[Text.UTF8Encoding]::new($false,$true).GetString([IO.File]::ReadAllBytes($configFile)).TrimStart([char]0xFEFF)}
catch [Text.DecoderFallbackException] {throw 'game.properties 必须使用 UTF-8 编码'}
foreach($line in ($configText -split '\r?\n')){
    $line=$line.Trim()
    if(-not $line -or $line.StartsWith('#')){continue}
    $parts=$line.Split('=',2)
    if($parts.Count -ne 2){throw "配置格式错误，需 key=value：$line"}
    $key=$parts[0].Trim()
    if($allowed -cnotcontains $key){throw "未知配置项：$key"}
    if($config.ContainsKey($key)){throw "配置项重复：$key"}
    $config[$key]=$parts[1].Trim()
}
foreach($key in @('id','versionCode','versionName')){
    if(-not $config.ContainsKey($key) -or [string]::IsNullOrWhiteSpace($config[$key])){throw "配置 $key 必填"}
}
$id=$config['id']
if($id -cnotmatch '^[a-z][a-z0-9_]{0,49}$' -or $id -match '^(con|prn|aux|nul|com[0-9]|lpt[0-9])$'){
    throw 'id 需以小写字母开头，仅允许小写字母、数字、下划线，最多50字符，不能使用 Windows 保留设备名'
}
$versionCode=0
if(-not [int]::TryParse($config['versionCode'],[Globalization.NumberStyles]::None,[Globalization.CultureInfo]::InvariantCulture,[ref]$versionCode) -or $versionCode -lt 1){throw 'versionCode 必须是正整数，最大2147483647'}
$versionName=$config['versionName']
if($versionName -match '[\x00-\x1F]'){throw 'versionName 不能包含控制字符'}
$appName=if($config.ContainsKey('appName')){$config['appName']}else{$roms[0].BaseName}
if([string]::IsNullOrWhiteSpace($appName) -or $appName -match '[\x00-\x1F]'){throw 'appName 不能为空或包含控制字符'}
$applicationId=if($config.ContainsKey('applicationId')){$config['applicationId']}else{"com.nes.game.$id"}
if($applicationId -cnotmatch '^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$'){throw 'applicationId 必须是有效的 Android 包名'}
if($config.ContainsKey('cheatsRomSha256') -and $config['cheatsRomSha256'] -notmatch '^[0-9a-fA-F]{64}$'){throw 'cheatsRomSha256 必须是64位十六进制摘要'}
$bytes=[IO.File]::ReadAllBytes($roms[0].FullName)
if($bytes.Length -lt 16 -or $bytes[0] -ne 78 -or $bytes[1] -ne 69 -or $bytes[2] -ne 83 -or $bytes[3] -ne 26){throw 'NES 必须包含有效的 iNES / NES 2.0 文件头'}
$romHash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
$warnings=[Collections.Generic.List[string]]::new()
$cheatFile=''; $cheatHash=''; $hasCodes=$false
if($cheats.Count -eq 0){$warnings.Add('未提供金手指，将继续打包。')}
else {
    $cheatFile=$cheats[0].FullName
    if($cheats[0].Length -gt 128*1024){throw 'CHT 文件不能超过128 KiB'}
    $bytes=[IO.File]::ReadAllBytes($cheatFile)
    $cheatHash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
    if($roms[0].BaseName -cne $cheats[0].BaseName){$warnings.Add("NES 与 CHT 名称不一致，按同目录配对：$($roms[0].Name) / $($cheats[0].Name)")}
    if($config.ContainsKey('cheatsRomSha256')){
        if($config['cheatsRomSha256'] -ine $romHash){throw "金手指绑定的 ROM 摘要不匹配。实际：$romHash；需要重新验证，程序不会自动修改配置。"}
    } else {$warnings.Add('金手指未绑定经过验证的 ROM 摘要，打包仅检查代码语法。')}
    try {$text=[Text.UTF8Encoding]::new($false,$true).GetString($bytes)}
    catch [Text.DecoderFallbackException] {
        $encoding=[Text.Encoding]::GetEncoding('GB18030',[Text.EncoderFallback]::ExceptionFallback,[Text.DecoderFallback]::ExceptionFallback)
        $text=$encoding.GetString($bytes)
    }
    $lines=@($text.TrimStart([char]0xFEFF) -split '\r?\n' | Where-Object {$t=$_.Trim(); $t -and -not $t.StartsWith(';') -and -not $t.StartsWith('//')})
    $hasCodes=$lines.Count -gt 0
    if(-not $hasCodes){$warnings.Add('CHT 没有有效代码（空文件或纯注释），不提供内置预设。')}
}
function FilePart([string]$value){
    $clean=($value -replace '[<>:"/\\|?*\x00-\x1F]','_').TrimEnd([char[]]' .')
    if(-not $clean){return '_'}
    return $clean
}
$outputName="$(FilePart $appName)-v$(FilePart $versionName)-c$versionCode-debug.apk"
if($outputName.Length -gt 255){throw '输出文件名超过255字符，请缩短 appName 或 versionName'}
$output=Join-Path ([IO.Path]::GetFullPath($OutputRoot)) "$id/$outputName"
$iconHash=if($icon){(Get-FileHash -LiteralPath $icon.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}else{''}
[ordered]@{
    directory=$directory; id=$id; appName=$appName; applicationId=$applicationId
    versionCode=$versionCode; versionName=$versionName
    romFile=$roms[0].FullName; romSha256=$romHash
    cheatFile=$cheatFile; cheatSha256=$cheatHash; hasCheatCodes=$hasCodes
    iconFile=([string]$icon.FullName); iconSha256=$iconHash
    output=$output; warnings=@($warnings)
} | ConvertTo-Json -Compress
