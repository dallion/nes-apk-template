$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$pack = Join-Path $root 'pack.ps1'
$scratch = Join-Path $root ("tmp/"+(Get-Date -Format 'yyyy-MM-dd')+"-pack-checks/"+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $scratch | Out-Null
. (Join-Path $root 'tools/toolchain.ps1')
$shell = (Get-ProjectToolchain).PowerShell
$good = "id=test_game`nversionCode=1`nversionName=1.0`n"
$rom = [byte[]](78,69,83,26,0,0,0,0,0,0,0,0,0,0,0,0)
$passed = 0
function Case($name,$config=$good) {
    $dir=Join-Path $scratch $name
    New-Item -ItemType Directory -Force $dir | Out-Null
    [IO.File]::WriteAllText((Join-Path $dir 'game.properties'),$config,[Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllBytes((Join-Path $dir '测试游戏.nes'),$rom)
    return $dir
}
function Check($name,$dir,$success,$message) {
    $log=Join-Path $scratch "$name.log"
    & $shell -NoProfile -File $pack -Game $dir -CheckOnly *> $log
    $actual=$LASTEXITCODE -eq 0
    $text=Get-Content -LiteralPath $log -Raw
    if($actual -ne $success -or $text -notmatch $message){throw "FAIL $name expected=$success / $message`n$text"}
    $script:passed++
    Write-Output "PASS $name"
}
$d=Case 'valid'; Check 'valid' $d $true 'com.nes.game.test_game'
Check 'missing-cheats' $d $true '未提供金手指'
Check 'default-name' $d $true '测试游戏'
$d=Case 'uppercase'; Move-Item -LiteralPath (Join-Path $d '测试游戏.nes') -Destination (Join-Path $d '测试游戏.NES'); [IO.File]::WriteAllText((Join-Path $d '测试游戏.CHT'),"[生命]`non=534,FA"); Check 'uppercase' $d $true 'CHT|金手指'
$d=Case 'no-nes'; Remove-Item -LiteralPath (Join-Path $d '测试游戏.nes'); Check 'no-nes' $d $false '恰好一个.*NES'
$d=Case 'two-nes'; Copy-Item (Join-Path $d '测试游戏.nes') (Join-Path $d '第二个.nes'); Check 'two-nes' $d $false '恰好一个.*NES'
$d=Case 'two-cht'; [IO.File]::WriteAllText((Join-Path $d 'a.cht'),''); [IO.File]::WriteAllText((Join-Path $d 'b.CHT'),''); Check 'two-cht' $d $false '最多一个.*CHT'
$d=Case 'different-name'; [IO.File]::WriteAllText((Join-Path $d '别名.cht'),'534-01-FA'); Check 'different-name' $d $true '名称不一致'
$d=Case 'empty-cht'; [IO.File]::WriteAllText((Join-Path $d '测试游戏.cht'),''); Check 'empty-cht' $d $true '没有有效代码'
$d=Case 'comments-cht'; [IO.File]::WriteAllText((Join-Path $d '测试游戏.cht'),"; 注释`n// 注释`n"); Check 'comments-cht' $d $true '没有有效代码'
$d=Case 'no-config'; Remove-Item -LiteralPath (Join-Path $d 'game.properties'); Check 'no-config' $d $false 'game.properties'
$d=Case 'unknown-key' ($good+"rom=outside.nes`n"); Check 'unknown-key' $d $false '未知配置项'
$d=Case 'duplicate' ($good+"id=again`n"); Check 'duplicate' $d $false '配置项重复'
$d=Case 'case-key' ($good+"AppName=错误`n"); Check 'case-key' $d $false '未知配置项'
foreach($id in @('1bad','Bad','bad-id',('a'*51),'con')){ $d=Case ("id-"+[guid]::NewGuid().ToString('N')) ($good.Replace('test_game',$id)); Check "id-$id" $d $false 'id' }
foreach($v in @('0','-1','abc','2147483648')){$d=Case ("v-"+[guid]::NewGuid().ToString('N')) ($good.Replace('versionCode=1',"versionCode=$v")); Check "version-$v" $d $false 'versionCode'}
$d=Case 'missing-id' ($good.Replace("id=test_game`n",'')); Check 'missing-id' $d $false 'id'
$d=Case 'empty-version' ($good.Replace('versionName=1.0','versionName=')); Check 'empty-version' $d $false 'versionName'
$d=Case 'empty-appname' ($good+"appName=`n"); Check 'empty-appname' $d $false 'appName'
$d=Case 'bad-package' ($good+"applicationId=bad-id`n"); Check 'bad-package' $d $false 'applicationId'
$d=Case 'override' ($good+"appName=自定名称`napplicationId=com.example.identity_override`n"); Check 'override' $d $true 'com.example.identity_override'
$d=Case 'bad-rom'; [IO.File]::WriteAllText((Join-Path $d '测试游戏.nes'),'invalid'); Check 'bad-rom' $d $false 'iNES'
$d=Case 'nested'; New-Item -ItemType Directory (Join-Path $d 'nested') | Out-Null; [IO.File]::WriteAllBytes((Join-Path $d 'nested/ignore.nes'),$rom); Check 'nested' $d $true 'com.nes.game.test_game'
$hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($rom)).ToLower()
$d=Case 'bound' ($good+"cheatsRomSha256=$hash`n"); [IO.File]::WriteAllText((Join-Path $d '测试游戏.cht'),'534-01-FA'); Check 'bound' $d $true $hash
$d=Case 'unbound'; [IO.File]::WriteAllText((Join-Path $d '测试游戏.cht'),'534-01-FA'); Check 'unbound' $d $true '未绑定'
$d=Case 'wrong-hash' ($good+"cheatsRomSha256=$('0'*64)`n"); [IO.File]::WriteAllText((Join-Path $d '测试游戏.cht'),'534-01-FA'); Check 'wrong-hash' $d $false '摘要不匹配'
$d=Case 'bad-hash' ($good+"cheatsRomSha256=invalid`n"); Check 'bad-hash' $d $false 'cheatsRomSha256'
$d=Case 'sanitize' ($good+"appName=游戏:测试?`n"); Check 'sanitize' $d $true '游戏_测试_-v1.0-c1-debug.apk'
$d=Case 'output-exists'; $out=Join-Path $root 'outputs/test_game/测试游戏-v1.0-c1-debug.apk'; New-Item -ItemType Directory -Force (Split-Path $out) | Out-Null
$createdByTest=$false
try {
    if(Test-Path -LiteralPath $out){throw 'Test output unexpectedly exists; refusing to overwrite'}
    [IO.File]::WriteAllText($out,'sentinel')
    $createdByTest=$true
    Check 'output-exists' $d $false 'Overwrite'
    & $shell -NoProfile -File $pack -Game $d -CheckOnly -Overwrite *> (Join-Path $scratch 'overwrite.log')
    if($LASTEXITCODE -ne 0 -or [IO.File]::ReadAllText($out) -ne 'sentinel'){throw 'Overwrite check changed artifact'}
    $passed++; Write-Output 'PASS overwrite-check-only'
} finally { if($createdByTest -and (Test-Path -LiteralPath $out) -and [IO.File]::ReadAllText($out) -eq 'sentinel'){Remove-Item -LiteralPath $out} }
$d=Case 'utf16'; [IO.File]::WriteAllText((Join-Path $d 'game.properties'),$good,[Text.Encoding]::Unicode); Check 'utf16' $d $false 'UTF-8'
$d=Case 'invalid-utf8'; [IO.File]::WriteAllBytes((Join-Path $d 'game.properties'),[byte[]](255,254,255)); Check 'invalid-utf8' $d $false 'UTF-8'
$d=Case 'utf8-bom'; [IO.File]::WriteAllText((Join-Path $d 'game.properties'),$good,[Text.UTF8Encoding]::new($true)); Check 'utf8-bom' $d $true 'com.nes.game.test_game'
$d=Case 'oversize-cht'; [IO.File]::WriteAllBytes((Join-Path $d '测试游戏.cht'),[byte[]]::new(128*1024+1)); Check 'oversize-cht' $d $false '128 KiB'
$d=Case 'nested-only'; New-Item -ItemType Directory (Join-Path $d 'nested') | Out-Null; Move-Item -LiteralPath (Join-Path $d '测试游戏.nes') -Destination (Join-Path $d 'nested/测试游戏.nes'); Check 'nested-only' $d $false '恰好一个.*NES'
$d=Case 'punctuation' ($good.Replace('versionName=1.0','versionName=1:0?')+"appName=游戏...`n"); Check 'punctuation' $d $true '游戏-v1_0_-c1-debug.apk'
$d=Case 'renamed-folder'; Move-Item -LiteralPath (Join-Path $d '测试游戏.nes') -Destination (Join-Path $d '另一名称.nes'); $a=& (Join-Path $root 'tools/resolve-game.ps1') -Game $d | ConvertFrom-Json
if($a.id -ne 'test_game' -or $a.applicationId -ne 'com.nes.game.test_game' -or $a.appName -ne '另一名称'){throw 'Renaming changed identity'}
$passed++; Write-Output 'PASS renamed-folder-and-rom-stable-identity'
$invoke=Join-Path $scratch 'relative.ps1'
@'
param($Directory,$Pack)
Set-Location -LiteralPath $Directory
& $Pack -Game './valid' -CheckOnly
'@ | Set-Content -LiteralPath $invoke
& $shell -NoProfile -File $invoke -Directory $scratch -Pack $pack *> (Join-Path $scratch 'relative.log')
if($LASTEXITCODE -ne 0){throw 'Relative game path failed from other working directory'}
$passed++; Write-Output 'PASS relative-path-from-other-directory'
$d=Case 'linked-rom'; $source=Join-Path $scratch 'outside.nes'; [IO.File]::WriteAllBytes($source,$rom); Remove-Item -LiteralPath (Join-Path $d '测试游戏.nes')
$linkCreated=$false
try { New-Item -ItemType SymbolicLink -Path (Join-Path $d '测试游戏.nes') -Target $source -ErrorAction Stop | Out-Null; $linkCreated=$true }
catch { Write-Output "SKIP symbolic-link check (creation unavailable): $($_.Exception.Message)" }
if($linkCreated){Check 'linked-rom' $d $false '链接|重解析点'}
Write-Output "All $passed packaging input checks passed. Logs: $scratch"
