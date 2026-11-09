# Fixed upstream sources

- LibretroDroid: https://github.com/Swordfish90/LibretroDroid, 8835c3098514390a271e36983957f7bb5f40abf1 (GPL-3.0-or-later).
- LibretroDroid oboe: b15f5e39c01a7ada306d959e5129620b145fb8b4 (Apache-2.0).
- LibretroDroid libretro-common: b0c348ea5543c4d7fb0bc479258aa6988b20c0c9 (upstream per-file licenses).
- FCEUmm: https://github.com/libretro/libretro-fceumm, e0f3086a8104f8fbbe49a2615b10401504a9533f (see upstream COPYING and source headers).

Upstream source trees are vendored under native/ without their Git metadata. Local mapper changes remain ordinary project diffs. Upstream license files remain in the source trees.
Android: compile/target 36, min 23, arm64-v8a and x86_64; NDK 27.2.12479018; CMake 3.22.1; JDK 21; Gradle 8.14.4; AGP 8.13.2; Kotlin 2.2.10.

Project tool archives are pinned by host platform in tools/toolchain.lock.json. Android SDK component versions are defined in gradle.properties.

