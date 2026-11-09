#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/../tools/toolchain.sh"
require_commands node
cd "$project_root"

scratch="$project_root/tmp/$(date +%F)-build-checks"
mkdir -p "$scratch"
game="$(mktemp -d "$scratch/game.XXXXXXXX")"
node tests/native/make-cheat-rom.cjs "$game/probe.nes"
printf '%s\n' id=ci_probe versionCode=1 versionName=1.0 > "$game/game.properties"
printf '%s\n' '[Probe]' 'on=6800,16' > "$game/probe.cht"

for attempt in 1 2 3; do
  if with_project_toolchain ./gradlew --version; then break; fi
  [[ "$attempt" != 3 ]] || fail 'Gradle bootstrap failed.'
  if [[ "$attempt" == 1 ]]; then bash tools/prepare-gradle-cache.sh; fi
  sleep 2
done
./tools/setup-toolchain.sh -CheckOnly
mkdir "$game/tools"
cp tools/setup-toolchain.sh tools/toolchain.sh tools/toolchain.lock.json "$game/tools/"
if bash "$game/tools/setup-toolchain.sh" -CheckOnly > "$game/clean-check.log" 2>&1 ||
   [[ -e "$game/.toolchains" ]] || ! grep -Fq 'Missing jdk' "$game/clean-check.log"; then
  cat "$game/clean-check.log" >&2
  fail 'CheckOnly must reject a missing project JDK without installing tools.'
fi
bash tests/pack-checks.sh
bash ./pack.sh -Game "$game" -Test -Overwrite
with_project_toolchain ./gradlew "-PgameDir=$game" :app:lintDebug --console=plain
