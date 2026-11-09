#!/usr/bin/env bash
# Standard output is the game input snapshot consumed by Gradle and pack.sh.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/toolchain.sh"
require_commands jq iconv

game=
output_root="$project_root/outputs"
while (($#)); do
  case "$1" in
    -Game) (($# >= 2)) || fail 'Specify -Game <directory>.'; game="$2"; shift 2 ;;
    -OutputRoot) (($# >= 2)) || fail 'Specify -OutputRoot <directory>.'; output_root="$2"; shift 2 ;;
    *) fail "Unknown option: $1" ;;
  esac
done
[[ -n "$game" && -d "$game" ]] || fail "游戏目录不存在：$game"
directory="$(CDPATH= cd -- "$game" && pwd -P)"
[[ "$output_root" == /* ]] || output_root="$PWD/$output_root"
config_file="$directory/game.properties"
[[ -f "$config_file" ]] || fail "缺少 game.properties：$directory"
[[ ! -L "$config_file" ]] || fail '游戏输入文件不允许链接：game.properties'

shopt -s nullglob dotglob
roms=()
cheats=()
for file in "$directory"/*; do
  [[ ! -d "$file" && ( -f "$file" || -L "$file" ) ]] || continue
  case "$file" in
    *.[nN][eE][sS]) roms+=("$file") ;;
    *.[cC][hH][tT]) cheats+=("$file") ;;
  esac
done
rom_count=0
cheat_count=0
if [[ -n "${roms[0]:-}" ]]; then rom_count="${#roms[@]}"; fi
if [[ -n "${cheats[0]:-}" ]]; then cheat_count="${#cheats[@]}"; fi
((rom_count == 1)) || fail "游戏目录必须恰好一个 NES 文件，实际 $rom_count 个：$directory"
((cheat_count <= 1)) || fail "游戏目录最多一个 CHT 文件，实际 $cheat_count 个：$directory"
rom_file="${roms[0]}"
cheat_file="${cheats[0]:-}"
for file in "$rom_file" ${cheats[@]+"${cheats[@]}"}; do
  [[ ! -L "$file" ]] || fail "游戏输入文件不允许链接：${file##*/}"
done

config="$( (iconv -f UTF-8 -t UTF-8 < "$config_file" ||
  fail 'game.properties 必须使用 UTF-8 编码') | jq -Rse '
  def trim: gsub("^[\\s\u0085\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]+|[\\s\u0085\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]+$"; "");
  def allowed: ["id", "appName", "applicationId", "versionCode", "versionName", "cheatsRomSha256"];
  reduce (sub("^\ufeff+"; "") | split("\n")[] | trim | select(length > 0 and (startswith("#") | not))) as $line ({};
    if ($line | contains("=") | not) then error("配置格式错误，需 key=value：" + $line)
    else ($line | index("=")) as $separator
      | ($line[:$separator] | trim) as $key
      | if (allowed | index($key)) == null then error("未知配置项：" + $key)
        elif has($key) then error("配置项重复：" + $key)
        else .[$key] = ($line[$separator + 1:] | trim)
        end
    end)
  | if ([.id, .versionCode, .versionName] | any(. == null or . == ""))
    then error("配置 id、versionCode 和 versionName 必填") else . end
  | if (.id | test("^[a-z][a-z0-9_]{0,49}$") and (test("^(con|prn|aux|nul|com[0-9]|lpt[0-9])$") | not))
    then . else error("id 格式无效或使用了 Windows 保留设备名") end
  | if (.versionCode | test("^[0-9]+$") and (tonumber >= 1 and tonumber <= 2147483647))
    then .versionCode |= tonumber else error("versionCode 必须是正整数，最大2147483647") end
  | if (.versionName | test("[\u0000-\u001f]")) then error("versionName 不能包含控制字符") else . end
  | if has("cheatsRomSha256") and (.cheatsRomSha256 | test("^[0-9a-fA-F]{64}$") | not)
    then error("cheatsRomSha256 必须是64位十六进制摘要") else . end
  ' )"

od -An -tu1 -N16 < "$rom_file" | awk '
  NR == 1 { magic = ($1 == 78 && $2 == 69 && $3 == 83 && $4 == 26) }
  { bytes += NF }
  END { exit !(bytes == 16 && magic) }
' ||
  fail 'NES 必须包含有效的 iNES / NES 2.0 文件头'
rom_hash="$(sha256 < "$rom_file")"
rom_name="${rom_file##*/}"
cheat_name="${cheat_file##*/}"
warnings=()
cheat_hash=
has_codes=false
if [[ -z "$cheat_file" ]]; then
  warnings+=('未提供金手指，将继续打包。')
else
  (($(wc -c < "$cheat_file") <= 128 * 1024)) || fail 'CHT 文件不能超过128 KiB'
  cheat_hash="$(sha256 < "$cheat_file")"
  if [[ "${rom_name%.*}" != "${cheat_name%.*}" ]]; then
    warnings+=("NES 与 CHT 名称不一致，按同目录配对：$rom_name / $cheat_name")
  fi
  binding="$(jq -r '(.cheatsRomSha256 // "") | ascii_downcase' <<< "$config")"
  if [[ -z "$binding" ]]; then
    warnings+=('金手指未绑定经过验证的 ROM 摘要，打包仅检查代码语法。')
  else
    [[ "$binding" == "$rom_hash" ]] || fail "金手指绑定的 ROM 摘要不匹配。实际：$rom_hash；需要重新验证，程序不会自动修改配置。"
  fi
  encoding=UTF-8
  if ! iconv -f UTF-8 -t UTF-8 < "$cheat_file" >/dev/null 2>&1; then encoding=GB18030; fi
  has_codes="$(iconv -f "$encoding" -t UTF-8 < "$cheat_file" | jq -Rs '
    sub("^\ufeff+"; "") | split("\n")
    | any(test("^[\\s\u0085\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]*(;|//|$)") | not)')"
  if [[ "$has_codes" == false ]]; then
    warnings+=('CHT 没有有效代码（空文件或纯注释），不提供内置预设。')
  fi
fi

jq -cn --argjson config "$config" --arg directory "$directory" --arg outputRoot "$output_root" \
  --arg romFile "$rom_file" --arg romSha256 "$rom_hash" --arg defaultName "${rom_name%.*}" \
  --arg cheatFile "$cheat_file" --arg cheatSha256 "$cheat_hash" --argjson hasCheatCodes "$has_codes" '
    def file_part: gsub("[<>:\"/\\\\|?*\u0000-\u001f]"; "_") | sub("[ .]+$"; "") | if . == "" then "_" else . end;
    ($config.appName // $defaultName) as $appName
    | if ($appName | test("^[\\s\u0085\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]*$|[\u0000-\u001f]"))
      then error("appName 不能为空或包含控制字符") else . end
    | ($config.applicationId // ("com.nes.game." + $config.id)) as $applicationId
    | if ($applicationId | test("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$") | not)
      then error("applicationId 必须是有效的 Android 包名") else . end
    | (($appName | file_part) + "-v" + ($config.versionName | file_part) + "-c\($config.versionCode)-debug.apk") as $filename
    | if ($filename | explode | map(if . > 65535 then 2 else 1 end) | add) > 255
      then error("输出文件名超过255字符，请缩短 appName 或 versionName") else . end
    | {directory: $directory, id: $config.id, $appName, $applicationId,
       versionCode: $config.versionCode, versionName: $config.versionName,
       $romFile, $romSha256, $cheatFile, $cheatSha256, $hasCheatCodes,
       output: ($outputRoot + "/" + $config.id + "/" + $filename), warnings: $ARGS.positional}
  ' --args ${warnings[@]+"${warnings[@]}"}
