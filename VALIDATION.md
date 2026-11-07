# Fluorescent 0.30.0-rc.24 validation

This update keeps temporary bloom textures around briefly for reuse and stops held-item targets from shrinking too tightly. It also removes an extra package layer and cleans up comments. The comparison starts from rc.23; checkpoint `19d8d25` records the rebuild audit on `fix/held-item-rebuilds`. All 250 checked files in the separate 26.2 project are unchanged. The in-game tests used temporary copies, leaving my main Modrinth instance alone.

Candidate SHA-256: `e2669a4473e842a6828794f1149d14abbd00023e2a832353227aba44bf7ddb6f`.

## Build and source download

- Compilation and the tests for each fix passed. The full clean Gradle build passed after the package and comment changes. Another full build passed after changing how the texture cache handles overflow.
- All **381 tests** pass, with no failures, errors or skips. Eight regression tests were added to existing test classes; there are still 104 production Java files and 78 test Java files.
- The source ZIP includes the source folders, resources, Gradle wrapper, build files, tests and current docs. It leaves out local worlds, build outputs and in-game test launchers. A fresh folder extracted from the ZIP passed a normal Gradle clean build and all **381 tests**, using JDK 25 and cached dependencies. All **305 non-directory JAR entries** match the tested build byte-for-byte. The archive contents and links between docs were also checked.

## Scope and behavior checks

- **99 of 104 production Java bodies are unchanged** after removing comments and normalizing package references. The five focused changes are `ScopedAllocator`, `BloomTargetDescriptor`, `BloomRenderer`, `HeldItemBounds` and `HeldItemRenderer`.
- The package/comment-only compiled comparison matched **4,012 entries**, with no unexplained differences. After the native-pool overflow refinement, **4,009 entries match and five allocator entries are explicitly reviewed**, with zero unexpected changes. Comparisons cover instructions, signatures, annotations and fields, excluding debug and nest metadata.
- **20,736 world, rain and held-item graph descriptions** match rc.23 across 6,912 settings combinations.
- All **83 resource files** checked against rc.23 match, including **78 shaders** and all three bundled setup JSON files. Only the version, entrypoint/package and generated client-only metadata change.
- All **30 mixins** and the Fabric entrypoint resolve. Loom's generated client-only list matches all **182 packaged client classes**. The old `dev/stillbloom/client/` class path is absent, and test classes are not packaged.
- The comment rewrite preserves executable tokens and string literals. It reviews 355 comment groups, rewrites 327 and removes 28 redundant descriptions. License and attribution are preserved.

## Measured rebuild counts

The before-and-after runs, `rc23-held-detail` and `rc24-final-detail`, used copies of my current settings with Refined Torches, Punchy and Sodium: 1920×1009, render distance 16, simulation distance 8, pixel size 3 and view bobbing. These checks counted texture allocations. They weren't FPS benchmarks. Minecraft's inactivity limiter also affected frame rate, so I haven't used those timings to claim a performance gain.

| Check | rc.23 | rc.24 |
| --- | --- | --- |
| World intermediate allocations in lantern look-away/back sequence | 10 | 0 |
| World intermediate allocations in empty-hand look-away/back sequence | 15 | 0 |
| Crop resizes during the first 126.6-block lantern walk | 2 | 1 |
| Held intermediate allocations during that walk | 15 | 5 |
| Intermediate allocations when switching to soul / copper lantern | 5 / 5 | 0 / 0 |
| Target allocations or resizes during the following warmed 84.4-block walk | 0 | 0 |

The lantern now settles at `768 × 576`. Previously, it shrank to `576 × 384` and then grew to `576 × 768`. The old and new final sizes contain the same number of pixels. Equipping an item for the first time, or after the four-second cache expiry, still needs textures to be allocated. Tests also cover effects that exceed the cache budget: extra textures return to Minecraft's pool so they can be reused on the next frame.

The cache keeps at most 32 MiB for world bloom and 16 MiB for held bloom, with up to 64 entries in each. These limits don't include active targets or Minecraft's own short-lived pool. Cached resources are released when they expire, when the renderer is disabled or closed, during resource reload, or after a rendering failure.

## In-game rendering and input

The final build passed both `rc24-final-gl-diagnostic` and `rc24-final-vk`: all **16 cases** completed and **114 shader variants** compiled on each backend. The separate test instance used Fabric Loader 0.20.1, Fabric API 0.165.2+26.3, Sodium 0.9.3-beta.1+mc26.3, Modrinth Java 25.0.2 and a null audio device.

The cases cover pixel sizes 2/3/4, pixelated and smooth glow, diamond/starburst, painted sources, held items, distance sizing, classic and improved-transparency rain, resource reload, odd resolutions and sideways movement. Menu clicks and typed searches worked on both backends. Weather-color comparisons changed zero samples on both; Vulkan depth changed zero samples. OpenGL depth readback is skipped because of the known readback-state issue in the test. The final OpenGL held-item and pixel-size-3 rain screenshots were also checked visually.

The completed runs and movement checks use `-XX:TieredStopAtLevel=1 -XX:ActiveProcessorCount=2`. These diagnostic flags are only in the temporary test launcher. They aren't added to the mod or my main instance. The tests don't cover every resource pack, model or way of using the menus.

## What still needs checking

The normal OpenGL run `rc24-final-gl` used an intermediate build from before the cache overflow fix (SHA-256 `85861b35f3812c524420b28ef03ccc1b47b831da9bf2b33f430a30a6df7992d7`). It reached the world, menu and weather cases, then crashed with **0xC0000005** during resource reload. Windows named `jvm.dll` as the faulting module for that Java process. No JVM error report was written, and the cause is still unknown.

Earlier rc.23 tests also had JVM crashes with older Fluorescent builds and with Fabric + Sodium without Fluorescent installed. That doesn't prove this reload crash has the same cause. The final rc.24 JAR hasn't completed the full test run with normal Java settings.

This stays a release candidate until repeated normal Modrinth launches and longer play sessions pass with the final mods and resource packs. Check walking with several held items, looking away and back, re-equipping items, resource reload, menu editing and saved masks. The results here don't establish an FPS gain or show that every movement hitch is fixed.

See the [source guide](SOURCE-GUIDE.md), [change report](docs/rebuild-fixes-report.md) and [recorded validation data](docs/rebuild-fixes-validation.json).
