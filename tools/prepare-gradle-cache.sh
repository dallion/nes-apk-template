#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/toolchain.sh"
require_commands curl jq node

properties="$project_root/gradle/wrapper/gradle-wrapper.properties"
url="$(read_property "$properties" distributionUrl)"
url="${url//\\:/:}"
checksum="$(read_property "$properties" distributionSha256Sum)"
[[ "$checksum" =~ ^[a-f0-9]{64}$ && "$url" =~ ^https://services\.gradle\.org/distributions/(gradle-([^/]+)-bin\.zip)$ ]] ||
  fail 'Expected a pinned official Gradle binary distribution and SHA-256.'
filename="${BASH_REMATCH[1]}"
version="${BASH_REMATCH[2]}"
[[ "$(read_property "$properties" zipStoreBase)" == GRADLE_USER_HOME ]] || fail 'Unsupported Wrapper cache base.'
# Match the Wrapper's base-36 unsigned URL MD5.
key="$(node -e 'console.log(BigInt("0x" + require("node:crypto").createHash("md5").update(process.argv[1]).digest("hex")).toString(36))' "$url")"
directory="${GRADLE_USER_HOME:-$HOME/.gradle}/$(read_property "$properties" zipStorePath)/${filename%.zip}/$key"
archive="$directory/$filename"
if [[ -f "$archive" ]]; then
  [[ "$(sha256 < "$archive")" == "$checksum" ]] || fail 'Existing Gradle archive checksum mismatch.'
  exit 0
fi
release="$(github_api application/vnd.github+json "https://api.github.com/repos/gradle/gradle-distributions/releases/tags/v$version")"
asset="$(jq -er --arg name "$filename" --arg digest "sha256:$checksum" '
  [.assets[] | select(.name == $name)] | select(length == 1 and .[0].digest == $digest) | .[0].url
' <<< "$release")" || fail 'Official Gradle release does not match the Wrapper checksum.'
mkdir -p "$directory"
temporary="$(mktemp "$directory/.gradle.XXXXXXXX")"
trap 'rm -f "$temporary"' EXIT
github_api application/octet-stream "$asset" --output "$temporary"
[[ "$(sha256 < "$temporary")" == "$checksum" ]] || fail 'Gradle archive checksum mismatch.'
mv "$temporary" "$archive"
printf 'Prepared verified Gradle Wrapper cache: %s\n' "$archive"
