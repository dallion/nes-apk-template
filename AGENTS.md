# AGENTS.md

## Purpose

Maintain a simple, reusable single-game FC/NES Android template. Replace the game files and configuration, build an APK, and launch directly into the game.

## Structure

- `app/`: Kotlin UI, input, and game management.
- `frontend/`: Build integration for LibretroDroid's Android interfaces and native host.
- `native/`: LibretroDroid and FCEUmm source, including custom Mapper support.
- `tests/`: Packaging checks, device regression scripts, and native core probes. Kotlin unit tests are under `app/src/test/`.
- `work/`: Ignored, long-lived personal game collection. Each game directory contains its ROM, optional CHT, and `game.properties`.
- `tools/`: Reusable project tools, including the Windows and Unix game input validators.
- `tmp/`: Ignored temporary scripts and disposable intermediate files.
- `README.md`: Usage.
- `docs/`: Design, packaging rules, and verification guides.

## Shared Constraints

### Architecture

- Reuse existing interfaces.
- Keep UI and input handling in the Android layer; implement Mapper adaptations in the core.

### Files and Dependencies

- Do not commit commercial ROMs, signing keys, local configuration, or build outputs.
- Preserve third-party licenses and source provenance.
- Place reusable project scripts in `tools/`, verification scripts in `tests/`, and one-off task scripts in `tmp/`. Keep root-level scripts limited to project entry points and toolchain-required files.  
- Preserve `work/` as personal data across cleanup tasks. Keep game-specific retained evidence and backups under the corresponding game directory. Store disposable test inputs and execution output in `tmp/`; retain existing project history under `work/_archive/`. Do not add new test runs there.
- Use the root `pack.ps1` on Windows and `bash ./pack.sh` on Linux/macOS for every game. Keep game identity, version, and ROM binding in `game.properties`; do not copy packaging logic or require per-game scripts. Follow `work/AGENTS.md` when organizing the collection.
- Keep Windows and Unix tool preparation, input validation, and APK delivery independent. Share the Android build, configuration and snapshot formats, and test data.

### Validation

- Select checks based on the affected behavior. Use the relevant Kotlin unit tests, packaging checks, native probes, device regressions, and ROM smoke tests described in `docs/verification.md`.
- ROM smoke tests cover startup, visible game output, and basic input; full playthroughs are unnecessary.
- After changing behavior, interfaces, configuration, or build entry points, review the affected documentation and update it where needed. Apply `docs/AGENTS.md` when editing documents in `docs/`.

## Rule Selection

Read applicable rules before analysis, review, or changes. Select by both task intent and affected components; combine rules when needed.

| Task or affected area | Required reading |
|---|---|
| Personal game collections, retained game evidence, or organization of `work/` | [Collection constraints](work/AGENTS.md) and [Packaging rules](docs/packaging.md) |
| Module responsibilities, core integration, input, lifecycle, or storage | [Architecture and design](docs/architecture.md) |
| Cheat parsing, grouping, persistence, bundled presets, or core cheat handling | [Cheat design](docs/cheats.md) |
| Packaging, game configuration, app identity, coexistence, or APK updates; changes or reviews involving `pack.ps1`, `pack.sh`, the game input validators, or Gradle packaging logic | [Packaging rules](docs/packaging.md) |
| Planning or running checks for builds, packaging, or runtime behavior | [Verification guide](docs/verification.md) |
| Creating, editing, moving, renaming, or deleting documents in `docs/` | [Documentation constraints](docs/AGENTS.md) |

Keep each rule in one owning document. Update its routing entry when its scope changes.
