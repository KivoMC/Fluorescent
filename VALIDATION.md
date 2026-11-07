# Fluorescent 2.0.0 validation

This build uses version **2.0.0** in the mod, settings menu and download filename. The current checks cover the version change and documentation. The rendering measurements below come from earlier development tests; I haven't rerun those tests for this rename.

## Build and package checks

- The clean Gradle build passed with JDK 25 and cached dependencies.
- All **381 automated tests** passed, with no failures, errors or skips.
- The mod contains 305 files. **304 match the previous tested build byte-for-byte**; the only changed file is `fabric.mod.json`, and its only change is the version field. All 219 compiled classes, shaders, bundled masks, defaults and the license are unchanged.
- The Fabric entrypoint and all 30 mixins resolve. The menu reads the version from Fabric's metadata, so it shows 2.0.0 too.
- The source download contains the same source and build files used for these checks. It includes the tests and Gradle wrapper, without local worlds, settings, logs or build outputs. Its file contents and documentation links were checked.
- All 250 checked files in the separate Minecraft 26.2 project are unchanged.

The hashes and detailed results are in the [recorded checks](docs/rebuild-fixes-validation.json). This version change doesn't need a settings reset or migration.

## Earlier rendering checks

The reference build passed 16 rendering cases and compiled 114 shader variants on both OpenGL and Minecraft 26.3's built-in Vulkan renderer. The cases covered pixel sizes 2, 3 and 4; smoothness; distance sizing; diamond and starburst shapes; painted blocks; held items; menus; rain; resource reloads; odd screen sizes; and movement.

Those tests used Fabric Loader 0.20.1, Fabric API 0.165.2+26.3, Sodium 0.9.3-beta.1+mc26.3 and Java 25.0.2 in temporary instances. Both weather-color comparisons passed with no changed samples. Vulkan's depth comparison passed too. The OpenGL test skipped depth readback because of a readback-state problem in the test tool.

The completed runs used `-XX:TieredStopAtLevel=1 -XX:ActiveProcessorCount=2`. These were diagnostic settings in the temporary launcher, not requirements added to Fluorescent or my main instance. The version-only comparison shows that the rendering code and resources haven't changed; it isn't a new in-game test of the 2.0.0 package.

The [rebuild report](docs/rebuild-fixes-report.md) explains the texture reuse and held-item resize fixes. Its before-and-after results count texture allocations, not FPS. Minecraft's inactivity limiter affected those runs, so I haven't used their frame times to claim a performance improvement.

## What still needs checking

An intermediate build crashed during resource reload with normal Java settings. Windows reported `0xC0000005` in `jvm.dll`, and the cause is still unknown. Earlier tests also crashed without Fluorescent installed, but that doesn't prove the reload crash had the same cause.

The final rendering code hasn't completed the full in-game test sequence with normal Java settings. Renaming the version doesn't resolve that gap. Repeated launches and longer Modrinth sessions with the full mod and resource-pack list still need checking, especially walking with different held items, switching items, resource reloads, menu editing and saved masks. Other players' held items also need broader testing.

These results don't establish an FPS gain or prove that every movement hitch is fixed.
