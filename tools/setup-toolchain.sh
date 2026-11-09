#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
case "$(uname -s):$(uname -m)" in
  Linux:x86_64) platform=linux-x64 ;;
  Darwin:x86_64) platform=macos-x64 ;;
  Darwin:arm64) platform=macos-arm64 ;;
  *) echo "Unsupported host: $(uname -s):$(uname -m)" >&2; exit 1 ;;
esac
for command in curl tar jq; do
  command -v "$command" >/dev/null || { echo "Required bootstrap tool: $command" >&2; exit 1; }
done
pwsh="$root/.toolchains/pwsh/pwsh"
if [[ ! -x "$pwsh" ]]; then
  if [[ -e "$root/.toolchains/pwsh" ]]; then
    echo 'Incomplete project PowerShell installation; inspect .toolchains/pwsh before retrying.' >&2
    exit 1
  fi
  lock="$root/tools/toolchain.lock.json"
  spec="$(jq -ce --arg key "$platform" '.platforms[$key].pwsh' "$lock")"
  version="$(jq -r '.version' <<< "$spec")"
  url="$(jq -r '.url' <<< "$spec")"
  expected="$(jq -r '.sha256' <<< "$spec")"
  cache="$root/.toolchains/downloads"
  mkdir -p "$cache" "$root/.toolchains/staging"
  archive="$cache/pwsh-$version.tar.gz"
  if [[ ! -f "$archive" ]]; then
    curl --fail --location --retry 3 --output "$archive.partial" "$url"
    if [[ "$platform" == linux-* ]]; then
      actual="$(sha256sum "$archive.partial" | cut -d ' ' -f 1)"
    else
      actual="$(shasum -a 256 "$archive.partial" | cut -d ' ' -f 1)"
    fi
    [[ "$actual" == "$expected" ]] || { echo 'PowerShell checksum mismatch' >&2; exit 1; }
    mv "$archive.partial" "$archive"
  fi
  if [[ "$platform" == linux-* ]]; then
    actual="$(sha256sum "$archive" | cut -d ' ' -f 1)"
  else
    actual="$(shasum -a 256 "$archive" | cut -d ' ' -f 1)"
  fi
  [[ "$actual" == "$expected" ]] || { echo 'PowerShell checksum mismatch' >&2; exit 1; }
  stage="$(mktemp -d "$root/.toolchains/staging/pwsh.XXXXXXXX")"
  tar -xzf "$archive" -C "$stage"
  chmod +x "$stage/pwsh"
  printf '%s\n' "$spec" > "$stage/.project-toolchain.json"
  mv "$stage" "$root/.toolchains/pwsh"
fi
exec "$pwsh" -NoProfile -File "$root/tools/setup-toolchain.ps1" "$@"
