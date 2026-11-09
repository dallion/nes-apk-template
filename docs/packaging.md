# Packaging Rules

## Game Directory

- Keep personal game collections under `work/<game-directory>/`, excluded from Git. Retain game-specific backups and evidence in an `archive/` subdirectory; use `tmp/` for disposable test inputs and execution output.
- One directory is one packaging unit. Read direct files only; do not search subdirectories or reference external game files.
- Require exactly one `.nes` and `game.properties`; allow at most one `.cht` and an optional `icon.png`. Match NES and CHT extensions case-insensitively and ignore other files.
- Preserve filenames. Recommend matching NES/CHT basenames; warn on a mismatch and pair by directory.
- Missing CHT is allowed with a notice. Empty or comment-only CHT is allowed with a notice; invalid codes block APK delivery.
- Package assets internally as `game.nes` and optional `game.cht`. Never retain assets from a previous game.
- Use the exact filename `icon.png` for a custom launcher icon. Supply a square PNG, preferably 512 × 512 pixels. Transparent backgrounds are supported. If absent, use the default controller icon. Reject invalid images during the Gradle build and report the source path.

## Configuration and Identity

- Use UTF-8 `key=value`, without escapes or continuation lines. Reject duplicate and unknown keys.
- Allow only `id`, `appName`, `applicationId`, `versionCode`, `versionName`, and `cheatsRomSha256`.
- Require `id`, `versionCode`, and `versionName`. IDs match `[a-z][a-z0-9_]{0,49}` and exclude Windows reserved device names; version codes are positive integers and version names are nonempty.
- Default `appName` to the ROM basename and `applicationId` to `com.nes.game.<id>`. An explicit package name preserves an existing app identity.
- Keep ID, package name, and signing key stable for APK updates; increment `versionCode`. Coexisting games or variants use separate directories, distinct IDs, and distinct package names.
- Renaming directories, files, or display names must not change app identity. Never derive identity from ROM hashes.

## Cheats and ROM Binding

- Use the optional `cheatsRomSha256` to record a verified ROM's SHA-256. With CHT present, warn if unbound and reject a mismatched binding.
- Never automatically create or refresh a verification binding. A matching hash identifies the ROM; it does not prove cheat effects.
- Validate bundled codes using the application's parser before delivery. New bundled presets default to disabled; preserve local choices under the existing ROM-specific storage rules.

## Entry Points and Outputs

- Prepare Windows x64 tools with `powershell -NoProfile -ExecutionPolicy Bypass -File tools/setup-toolchain.ps1`. Linux x64 and Intel / Apple Silicon macOS use `./tools/setup-toolchain.sh` with Bash, `curl`, `tar`, `unzip`, `iconv`, and `jq` available. Both install the JDK and Android SDK under the ignored `.toolchains/`. Windows also installs project PowerShell there. Use `-CheckOnly` to check installed tools. Archive versions and checksums are selected by host platform in `tools/toolchain.lock.json`; Android build component versions belong to root `gradle.properties`.
- SDK setup prompts for licenses by default. `-SdkSource` reuses installed components and merges that SDK's accepted license records; unattended setup may explicitly pass `-AcceptAndroidLicenses`.
- Use the project JDK and SDK for command-line and IDE builds. Setup writes `local.properties` and `.gradle/config.properties`, preserving the explicit game selection; IDE Gradle JDK uses `GRADLE_LOCAL_JAVA_HOME`. Packaging restores the caller's process environment after execution.
- Use `./pack.ps1 -Game <directory>` on Windows and `bash ./pack.sh -Game <directory>` on Linux/macOS. Direct Gradle builds use `-PgameDir=<directory>`. Gradle invokes the host's resolver and consumes the same snapshot format on both platforms.
- Require an explicit game directory for both entry points. Gradle also accepts `gameDir` in the ignored project `local.properties` for IDE sync and builds; `-PgameDir` takes precedence. Shared build and test scripts must not default to a personal game directory, app identity, or device identifier.
- Deliver to `outputs/<id>/<appName>-v<versionName>-c<versionCode>-debug.apk`. Replace Windows-invalid filename characters with `_` and trim trailing spaces and periods; retain the original display name in the app.
- Reject an existing delivery path unless `-Overwrite` is explicit. Failed packaging must preserve existing outputs and deliver no new APK.
- Before delivery, verify APK identity, version, and ROM/CHT content against the selected inputs. Gradle verifies the selected icon hash before generating the launcher resource. Print ID, display name, package, version, selected files, ROM hash, notices, and output path.

## Validation

- For packaging changes, run the host's `tests/pack-checks.ps1` or `bash tests/pack-checks.sh` and the affected Gradle checks. Both entry points verify the fixtures in `tests/packaging-cases.json`.
- Cover missing/multiple NES files, multiple/absent CHT files, invalid configuration and codes, hash mismatches, output protection, and switching between games with and without cheats.
- For identity changes, verify intended coexistence or update behavior and preserve the existing app's data and signature.
