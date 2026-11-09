# Third-party components

This project vendors the following upstream source distributions. Their license and copyright notices remain in the source trees; consult those files and per-file headers for the applicable terms.

| Component | Source | License reference |
|---|---|---|
| LibretroDroid | https://github.com/Swordfish90/LibretroDroid | native/libretrodroid/LICENSE; GPL-3.0-or-later source headers |
| FCEUmm | https://github.com/libretro/libretro-fceumm | native/fceumm/Copying; per-file source headers |
| Oboe | https://github.com/google/oboe | native/libretrodroid/libretrodroid/src/main/cpp/oboe/LICENSE |
| libretro-common | https://github.com/libretro/libretro-common | Copyright and license headers in both vendored copies |
| AndroidX, Kotlin and kotlinx.coroutines | Maven dependencies declared in Gradle files | Upstream notices and packaged META-INF license files |

Pinned commits are listed in DEPENDENCIES.md. Preserve notices and provide corresponding source and build instructions as required when distributing derived binaries. Game ROM rights are separate from emulator source licenses.

Local upstream changes:

- FCEUmm Android.mk: fixed version string instead of querying the enclosing Git repository.
- FCEUmm Android.mk: use source paths relative to the module directory to avoid duplicating absolute paths in NDK object filenames.
- LibretroDroid rumble.h and javautils.h: explicitly include the standard functional header for NDK 27.
- LibretroDroid CMakeLists.txt: explicit C/C++ project declaration.
- LibretroDroid AndroidManifest.xml: remove obsolete package declaration; namespace is configured by the local frontend module.

- FCEUmm libretro cheat adapter: add strict `RAM:AAAA:VV` periodic RAM-write codes and reset token type for each code. Existing index/enable limitations are handled by the Android layer rebuilding the active set.
- FCEUmm Mapper 246: correct cheat RAM registration to CPU address 0x6800, matching its actual SRAM mapping. CPU/PPU execution and Mapper bank switching are unchanged.
