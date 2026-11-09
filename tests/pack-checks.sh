#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/../tools/toolchain.sh"
require_commands jq iconv
scratch="$project_root/tmp/$(date +%F)-pack-checks"
mkdir -p "$scratch"
scratch="$(mktemp -d "$scratch/unix.XXXXXXXX")"
pack="$project_root/pack.sh"
resolver="$project_root/tools/resolve-game.sh"
passed=0

while IFS= read -r fixture; do
  name="$(jq -r '.name' <<< "$fixture")"
  game="$scratch/$name"
  mkdir "$game"
  if jq -e '.invalidUtf8' <<< "$fixture" >/dev/null; then
    printf '\xff\xfe\xff' > "$game/game.properties"
  else
    {
      if jq -e '.bom' <<< "$fixture" >/dev/null; then printf '\xef\xbb\xbf'; fi
      jq -r '"id=\(.id // "test_game")\nversionCode=\(.versionCode // "1")\nversionName=\(.versionName // "1.0")\n\(.config // "")"' <<< "$fixture"
    } > "$game/game.properties"
  fi
  if ! jq -e '.noRom' <<< "$fixture" >/dev/null; then
    if jq -e '.invalidRom' <<< "$fixture" >/dev/null; then
      printf 'invalid' > "$game/测试 游戏.NES"
    else
      printf 'NES\x1a\0\0\0\0\0\0\0\0\0\0\0\0' > "$game/测试 游戏.NES"
    fi
  fi
  if jq -e '.secondRom' <<< "$fixture" >/dev/null; then cp "$game/测试 游戏.NES" "$game/second.nes"; fi
  if jq -e '.nestedRom' <<< "$fixture" >/dev/null; then
    mkdir "$game/nested"
    cp "$game/测试 游戏.NES" "$game/nested/second.nes"
  fi
  if jq -e 'has("cheat")' <<< "$fixture" >/dev/null; then
    cheat_name="$(jq -r '.cheatName // "测试 游戏.CHT"' <<< "$fixture")"
    encoding="$(jq -r '.encoding // "UTF-8"' <<< "$fixture")"
    jq -j '.cheat' <<< "$fixture" | iconv -f UTF-8 -t "$encoding" > "$game/$cheat_name"
  fi
  if jq -e '.secondCheat' <<< "$fixture" >/dev/null; then touch "$game/second.cht"; fi
  if jq -e '.oversizeCheat' <<< "$fixture" >/dev/null; then
    dd if=/dev/zero of="$game/large.cht" bs=131073 count=1 2>/dev/null
  fi

  error="$(jq -r '.error // ""' <<< "$fixture")"
  log="$scratch/$name.log"
  if [[ -n "$error" ]]; then
    if bash "$pack" -Game "$game" -CheckOnly > "$log" 2>&1 || ! grep -Fq "$error" "$log"; then
      cat "$log" >&2
      fail "FAIL $name: expected $error"
    fi
  else
    bash "$pack" -Game "$game" -CheckOnly > "$log" 2>&1 || { cat "$log" >&2; fail "FAIL $name"; }
    bash "$resolver" -Game "$game" | jq -e --argjson fixture "$fixture" '
      . as $actual | ($fixture.expect // {} | to_entries | all($actual[.key] == .value)) and
      ($fixture.filename == null or ($actual.output | split("/") | last) == $fixture.filename) and
      ($fixture.warning == null or any($actual.warnings[]; contains($fixture.warning)))
    ' >/dev/null || fail "FAIL $name: unexpected snapshot"
  fi
  passed=$((passed + 1))
  printf 'PASS %s\n' "$name"
done < <(jq -c '.[]' "$project_root/tests/packaging-cases.json")
((passed > 0)) || fail 'No packaging fixtures ran.'

# Exercise path handling, symlink rejection and existing output protection through pack.sh.
(
  cd "$scratch"
  bash "$pack" -Game ./defaults -CheckOnly
) > "$scratch/relative.log" 2>&1
passed=$((passed + 1))
cp -R "$scratch/defaults" "$scratch/linked"
mv "$scratch/linked/测试 游戏.NES" "$scratch/outside.nes"
if ln -s "$scratch/outside.nes" "$scratch/linked/测试 游戏.NES"; then
  if bash "$pack" -Game "$scratch/linked" -CheckOnly > "$scratch/linked.log" 2>&1 ||
     ! grep -Fq '链接' "$scratch/linked.log"; then
    fail 'FAIL linked ROM'
  fi
  passed=$((passed + 1))
elif [[ "${CI:-}" == true ]]; then
  fail 'Could not create the symbolic-link fixture.'
else
  printf 'SKIP symbolic-link check (creation unavailable)\n'
fi
# Give the sentinel test its own identity so it cannot collide with a personal APK.
game="$scratch/defaults"
unique_id="pack_check_$$_$RANDOM"
printf '%s\n' "id=$unique_id" versionCode=1 versionName=1.0 > "$game/game.properties"
output="$(bash "$resolver" -Game "$game" | jq -r '.output')"
mkdir -p "$project_root/outputs"
mkdir "${output%/*}"
trap 'rm -f "$output"; rmdir "${output%/*}"' EXIT
printf sentinel > "$output"
if bash "$pack" -Game "$game" -CheckOnly > "$scratch/existing.log" 2>&1 ||
   ! grep -Fq Overwrite "$scratch/existing.log"; then
  fail 'FAIL existing output'
fi
bash "$pack" -Game "$game" -CheckOnly -Overwrite > "$scratch/overwrite.log" 2>&1
[[ "$(cat "$output")" == sentinel ]] || fail 'CheckOnly modified the existing APK.'
printf 'All %s packaging input checks passed. Logs: %s\n' "$((passed + 1))" "$scratch"
