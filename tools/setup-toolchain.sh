#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/toolchain.sh"

case "$(uname -s):$(uname -m)" in
  Linux:x86_64) platform=linux-x64 ;;
  Darwin:x86_64) platform=macos-x64 ;;
  Darwin:arm64) platform=macos-arm64 ;;
  *) fail "Unsupported host: $(uname -s):$(uname -m)" ;;
esac
require_commands jq iconv
check_only=false
archive_directory=
sdk_source=
accept_licenses=false
while (($#)); do
  case "$1" in
    -CheckOnly) check_only=true; shift ;;
    -ArchiveDirectory) (($# >= 2)) || fail 'Specify an archive directory.'; archive_directory="$2"; shift 2 ;;
    -SdkSource) (($# >= 2)) || fail 'Specify an SDK source directory.'; sdk_source="$2"; shift 2 ;;
    -AcceptAndroidLicenses) accept_licenses=true; shift ;;
    *) fail "Unknown option: $1" ;;
  esac
done
if ! "$check_only"; then require_commands curl tar unzip; fi
lock="$(jq -ce --arg platform "$platform" '.platforms[$platform]' "$project_root/tools/toolchain.lock.json")"
stage=
setup_lock=
cleanup() {
  if [[ -n "$stage" ]]; then rm -rf -- "$stage"; fi
  if [[ -n "$setup_lock" ]]; then rmdir "$setup_lock"; fi
}
trap cleanup EXIT
if ! "$check_only"; then
  mkdir -p "$tool_directory"
  lock_directory="$tool_directory/.setup-lock"
  mkdir "$lock_directory" || fail "Toolchain setup is already locked: $lock_directory"
  setup_lock="$lock_directory"
fi

install_archive() {
  local name="$1" spec="$2" destination="$3" inner="$4"
  local marker="$destination/.project-toolchain.json" version url expected archive extension api_url
  version="$(jq -er '.version' <<< "$spec")"
  url="$(jq -er '.url' <<< "$spec")"
  expected="$(jq -er '.sha256' <<< "$spec")"
  if [[ -f "$marker" ]]; then
    [[ "$(jq -er '.sha256' "$marker")" == "$expected" ]] || fail "Tool version differs: $destination"
    return
  fi
  ! "$check_only" || fail "Missing $name. Run tools/setup-toolchain.sh."
  [[ ! -e "$destination" && ! -L "$destination" ]] || fail "Unmanaged installation: $destination"
  printf 'Preparing %s %s\n' "$name" "$version"
  mkdir -p "$tool_directory/downloads" "$tool_directory/staging"
  stage="$(mktemp -d "$tool_directory/staging/archive.XXXXXXXX")"
  extension=zip
  if [[ "$url" == *.tar.gz ]]; then extension=tar.gz; fi
  archive="$tool_directory/downloads/$name-$version.$extension"
  if [[ ! -f "$archive" ]]; then
    if [[ -n "$archive_directory" && -f "$archive_directory/${url##*/}" ]]; then
      cp "$archive_directory/${url##*/}" "$stage/archive"
    elif ! curl --fail --location --retry 3 --output "$stage/archive" "$url"; then
      api_url="$(jq -er '.apiUrl' <<< "$spec")" || fail "Download failed: $url"
      github_api application/octet-stream "$api_url" --output "$stage/archive"
    fi
    [[ "$(sha256 < "$stage/archive")" == "$expected" ]] || fail "Archive checksum mismatch: $name"
    mv "$stage/archive" "$archive"
  fi
  [[ "$(sha256 < "$archive")" == "$expected" ]] || fail "Archive checksum mismatch: $archive"
  if [[ "$extension" == tar.gz ]]; then
    tar -xzf "$archive" -C "$stage"
  else
    unzip -q "$archive" -d "$stage"
    chmod +x "$stage/$inner/bin/"*
  fi
  [[ -d "$stage/$inner" ]] || fail "Unexpected archive layout: $archive"
  printf '%s\n' "$spec" > "$stage/$inner/.project-toolchain.json"
  mkdir -p "${destination%/*}"
  mv "$stage/$inner" "$destination"
  rm -rf -- "$stage"
  stage=
}

jdk="$(jq -c '.jdk' <<< "$lock")"
jdk_version="$(jq -er '.version' <<< "$jdk")"
jdk_inner="jdk-$jdk_version"
if [[ "$platform" == macos-* ]]; then jdk_inner+=/Contents/Home; fi
install_archive jdk "$jdk" "$project_jdk" "$jdk_inner"
[[ -x "$project_jdk/bin/java" ]] || fail 'Missing project Java executable.'
grep -Fxq "JAVA_RUNTIME_VERSION=\"$jdk_version-LTS\"" "$project_jdk/release" || fail 'Project JDK version does not match the lock file.'
with_project_toolchain "$project_jdk/bin/java" -version

command_line_tools="$(jq -c '.androidCommandLineTools' <<< "$lock")"
manager_directory="$project_sdk/cmdline-tools/$(jq -er '.version' <<< "$command_line_tools")"
install_archive android-command-line-tools "$command_line_tools" "$manager_directory" cmdline-tools
manager="$manager_directory/bin/sdkmanager"
[[ -x "$manager" ]] || fail 'Missing project sdkmanager executable.'

properties="$project_root/gradle.properties"
api="$(read_property "$properties" androidCompileSdk)"
build_tools="$(read_property "$properties" androidBuildToolsVersion)"
ndk="$(read_property "$properties" androidNdkVersion)"
cmake="$(read_property "$properties" androidCmakeVersion)"
components=("platforms;android-$api" "build-tools;$build_tools" "ndk;$ndk" "cmake;$cmake" platform-tools)
directories=("platforms/android-$api" "build-tools/$build_tools" "ndk/$ndk" "cmake/$cmake" platform-tools)
required_files=(android.jar aapt2 ndk-build bin/cmake adb)
missing=()

if ! "$check_only" && [[ -n "$sdk_source" && -d "$sdk_source/licenses" ]]; then
  mkdir -p "$project_sdk/licenses"
  for license in "$sdk_source"/licenses/*; do
    [[ -f "$license" ]] || continue
    target="$project_sdk/licenses/${license##*/}"
    if [[ -f "$target" ]]; then
      merged="$(mktemp "$project_sdk/licenses/.merge.XXXXXXXX")"
      LC_ALL=C sort -u "$license" "$target" > "$merged"
      mv "$merged" "$target"
    else
      cp "$license" "$target"
    fi
  done
fi

backup_incomplete() {
  local destination="$1" backup
  if [[ -e "$destination" || -L "$destination" ]]; then
    mkdir -p "$tool_directory/incomplete"
    backup="$(mktemp -d "$tool_directory/incomplete/sdk.XXXXXXXX")"
    mv "$destination" "$backup/"
  fi
}

for index in "${!components[@]}"; do
  relative="${directories[$index]}"
  destination="$project_sdk/$relative"
  required="${required_files[$index]}"
  if [[ -f "$destination/source.properties" && -f "$destination/$required" ]]; then continue; fi
  ! "$check_only" || fail "Missing SDK component: ${components[$index]}"
  printf 'Preparing SDK component %s\n' "${components[$index]}"
  if [[ -n "$sdk_source" && -f "$sdk_source/$relative/source.properties" ]]; then
    [[ -f "$sdk_source/$relative/$required" ]] || fail "Incomplete SDK source: $sdk_source/$relative ($required)"
    mkdir -p "$tool_directory/staging" "${destination%/*}"
    stage="$(mktemp -d "$tool_directory/staging/sdk.XXXXXXXX")"
    cp -pR "$sdk_source/$relative/." "$stage/component"
    backup_incomplete "$destination"
    mv "$stage/component" "$destination"
    rmdir "$stage"
    stage=
  else
    backup_incomplete "$destination"
    missing+=("${components[$index]}")
  fi
done
if [[ -n "${missing[0]:-}" ]]; then
  if "$accept_licenses"; then
    # Feed a finite set of answers so early sdkmanager exit cannot fail the pipeline via SIGPIPE.
    with_project_toolchain "$manager" "--sdk_root=$project_sdk" --licenses < <(printf 'y\n%.0s' {1..100})
  fi
  with_project_toolchain "$manager" "--sdk_root=$project_sdk" "${missing[@]}"
fi
for index in "${!components[@]}"; do
  component="${components[$index]}"
  destination="$project_sdk/${directories[$index]}"
  metadata="$destination/source.properties"
  [[ -f "$metadata" && -f "$destination/${required_files[$index]}" ]] ||
    fail "SDK installation is incomplete: $component"
  if [[ "${required_files[$index]}" != android.jar && ! -x "$destination/${required_files[$index]}" ]]; then
    fail "SDK component is not executable: $component"
  fi
  case "$component" in
    platforms\;*)
      [[ "$(read_property "$metadata" AndroidVersion.ApiLevel)" == "$api" ]] || fail 'SDK platform API mismatch.' ;;
    ndk\;*|cmake\;*|build-tools\;*)
      [[ "$(read_property "$metadata" Pkg.Revision)" == "${component#*;}" ]] || fail "SDK component version mismatch: $component" ;;
  esac
done

write_property() {
  local file="$1" key="$2" value="$3" pattern="$4" escaped
  # ASCII Java properties escapes also preserve paths containing non-ASCII characters.
  escaped="$(jq -an --arg path "$value" '$path')"
  {
    if [[ -f "$file" ]]; then awk -v pattern="$pattern" '$0 !~ pattern' "$file"; fi
    printf '%s=%s\n' "$key" "${escaped:1:${#escaped}-2}"
  } > "$stage/property"
  mv "$stage/property" "$file"
}
if ! "$check_only"; then
  mkdir -p "$project_root/.gradle" "$tool_directory/staging"
  stage="$(mktemp -d "$tool_directory/staging/properties.XXXXXXXX")"
  write_property "$project_root/local.properties" sdk.dir "$project_sdk" '^[[:space:]]*(sdk[.]dir|pwsh[.]path)[[:space:]]*='
  write_property "$project_root/.gradle/config.properties" java.home "$project_jdk" '^[[:space:]]*java[.]home[[:space:]]*='
fi
printf 'Project tools ready: %s\n' "$tool_directory"
