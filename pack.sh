#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/tools/toolchain.sh"

game=
check_only=false
test=false
overwrite=false
while (($#)); do
  case "$1" in
    -Game) (($# >= 2)) || fail 'Specify -Game <directory>.'; game="$2"; shift ;;
    -CheckOnly) check_only=true ;;
    -Test) test=true ;;
    -Overwrite) overwrite=true ;;
    *) fail "Unknown option: $1" ;;
  esac
  shift
done
[[ -n "$game" ]] || fail 'Usage: bash ./pack.sh -Game <directory> [-CheckOnly] [-Test] [-Overwrite]'
selected="$(bash "$project_root/tools/resolve-game.sh" -Game "$game")"
jq -r '
  "游戏：\(.appName) / id=\(.id)",
  "包名：\(.applicationId) / 版本：\(.versionName) (\(.versionCode))",
  "ROM：\(.romFile)", "ROM SHA-256：\(.romSha256)",
  "CHT：\(if .cheatFile == "" then "未提供金手指" else .cheatFile end)",
  "图标：\(if .iconFile == "" then "默认手柄图标" else .iconFile end)",
  "APK：\(.output)"
' <<< "$selected"
jq -r '.warnings[] | "警告：\(.)"' <<< "$selected" >&2
output="$(jq -r '.output' <<< "$selected")"
if [[ -e "$output" || -L "$output" ]]; then
  "$overwrite" || fail '输出 APK 已存在。请递增版本号，或明确传入 -Overwrite 替换。'
  [[ -f "$output" && ! -L "$output" ]] || fail "交付路径必须是普通文件：$output"
fi
if "$check_only"; then exit 0; fi

require_commands unzip
[[ -x "$project_jdk/bin/java" ]] || fail 'Project JDK is missing. Run tools/setup-toolchain.sh.'
tasks=(:app:stageDebugApk)
if "$test" || jq -e '.cheatFile != ""' <<< "$selected" >/dev/null; then
  tasks=(:app:testDebugUnitTest :app:stageDebugApk)
fi

snapshot_directory="$project_root/build/package-inputs"
mkdir -p "$snapshot_directory"
snapshot="$(mktemp "$snapshot_directory/inputs.XXXXXXXX")"
built="$snapshot.apk"
delivery_stage=
temporary=
cleanup() {
  rm -f "$snapshot"
  if [[ -d "$built" ]]; then
    rm -f "$built/app-debug.apk" "$built/output-metadata.json"
    rmdir "$built"
  fi
  if [[ -n "$temporary" ]]; then rm -f "$temporary"; fi
  if [[ -n "$delivery_stage" ]]; then rmdir "$delivery_stage"; fi
}
trap cleanup EXIT
printf '%s\n' "$selected" > "$snapshot"
game_directory="$(jq -r '.directory' <<< "$selected")"
cd "$project_root"
with_project_toolchain ./gradlew "${tasks[@]}" "-PgameDir=$game_directory" \
  "-PgameInputsSnapshot=$snapshot" --console=plain || fail '构建失败，未更新交付 APK。'

jq -e --argjson expected "$selected" '
  .applicationId == $expected.applicationId and (.elements | length == 1) and
  .elements[0].versionCode == $expected.versionCode and .elements[0].versionName == $expected.versionName
' "$built/output-metadata.json" >/dev/null || fail '构建产物的包名或版本与本次游戏配置不一致，未输出 APK。'

output_directory="${output%/*}"
mkdir -p "$output_directory"
delivery_stage="$(mktemp -d "$output_directory/.delivery.XXXXXXXX")"
temporary="$delivery_stage/${output##*/}"
cp "$built/app-debug.apk" "$temporary"
entries="$(unzip -Z -1 "$temporary")"
verify_asset() {
  local entry="$1" expected="$2" actual
  if [[ -z "$expected" ]]; then
    if grep -Fxq "$entry" <<< "$entries"; then fail "发现不属于本次游戏的旧资源：$entry"; fi
    return
  fi
  actual="$(unzip -p "$temporary" "$entry" | sha256)" || fail "构建缺少或无法读取资源：$entry"
  [[ "$actual" == "$expected" ]] || fail "构建过程中输入变化：${entry}，未输出 APK，请重新打包。"
}
verify_asset assets/game.nes "$(jq -r '.romSha256' <<< "$selected")"
verify_asset assets/game.cht "$(jq -r '.cheatSha256' <<< "$selected")"

if "$overwrite"; then
  mv -f "$temporary" "$output_directory/"
else
  # Publish atomically without replacing a file created during the build.
  ln "$temporary" "$output_directory/" ||
    fail '交付路径已存在或无法创建，未更新交付 APK。'
fi
printf '打包完成：%s\n' "$output"
