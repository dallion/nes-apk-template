# Bash helpers for the Linux/macOS toolchain.
project_root="$(CDPATH= cd -- "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
tool_directory="$project_root/.toolchains"
project_jdk="$tool_directory/jdk"
project_sdk="$tool_directory/android-sdk"
if [[ "$(uname -s)" == Darwin ]]; then
  # IDEs launched from Finder may omit Homebrew from their inherited PATH.
  export PATH="$PATH:/opt/homebrew/bin:/usr/local/bin"
fi

fail() {
  printf '%s\n' "$*" >&2
  exit 1
}

require_commands() {
  local command
  for command in "$@"; do
    command -v "$command" >/dev/null || fail "Required tool: $command"
  done
}

sha256() {
  local digest
  if command -v sha256sum >/dev/null; then
    digest="$(sha256sum)" || return
  else
    digest="$(shasum -a 256)" || return
  fi
  printf '%s\n' "${digest%% *}"
}

read_property() {
  awk -v key="$2" '
    {
      separator = index($0, "=")
      if (!separator) next
      name = substr($0, 1, separator - 1)
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", name)
      if (name == key) {
        value = substr($0, separator + 1)
        gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
      }
    }
    END { if (value == "") exit 1; print value }
  ' "$1"
}

with_project_toolchain() (
  export JAVA_HOME="$project_jdk" ANDROID_HOME="$project_sdk" ANDROID_SDK_ROOT="$project_sdk"
  export PATH="$project_jdk/bin:$project_sdk/platform-tools:$PATH"
  "$@"
)

github_api() {
  local accept="$1" url="$2"
  shift 2
  [[ "$url" == https://api.github.com/* ]] || fail 'Unexpected GitHub API host.'
  local headers=(-H "Accept: $accept")
  if [[ -n "${GH_TOKEN:-}" ]]; then
    headers+=(-H "Authorization: Bearer $GH_TOKEN")
  fi
  curl --fail --location --retry 3 --silent --show-error "${headers[@]}" "$@" "$url"
}
