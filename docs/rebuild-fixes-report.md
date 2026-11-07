# Rebuild fixes included in Fluorescent 2.0.0

This report explains the two fixes from the [held-item rebuild investigation](held-item-rebuild-audit.md). I also removed an extra package layer and cleaned up the Java comments. The measurements below were taken during development, before the 2.0.0 version change. They identify the before and after builds by their hashes in the [recorded results](rebuild-fixes-validation.json); they are not new 2.0.0 performance measurements.

## Rebuild fixes

**Temporary bloom textures:** Minecraft discards unused targets from its shared pool after only a few frames. Fluorescent could still have the matching post-processing chain cached, but looking away and back or briefly switching items meant creating the textures again.

`ScopedAllocator` now keeps Fluorescent's own compatible intermediate targets for up to four idle seconds. The cache allows 32 MiB for world bloom and 16 MiB for held bloom, with up to 64 entries each. Reuse requires matching dimensions and format. It stores the textures themselves, not temporary frame-graph views, and releases them on expiry, renderer close/reload or failure. Disabling bloom clears that renderer's cached resources too. Anything beyond the cache limits still uses Minecraft's normal pool, so larger effects don't have to create fresh textures every frame. Active render targets and Minecraft's own short-lived pool aren't counted in these limits.

**Held-item resizing:** The old code waited before shrinking, then used the crop size at that moment. It left none of the spare room used when growing a target. A lantern's target would shrink and then need to grow again as the walking animation moved it. The new code remembers the largest width and height during that wait and keeps one aligned bucket of spare room. It still gives back unused space instead of keeping every item at its largest-ever size. Crop origin, pixel-grid alignment, glow padding and source rendering are unchanged.

The before and after builds ran the same test with copied settings:

| Check | Before fixes | After fixes |
| --- | --- | --- |
| World intermediate allocations when looking away/back with a lantern | 10 | 0 |
| World intermediate allocations during the empty-hand look-away sequence | 15 | 0 |
| Lantern crop resizes during the first 126.6-block walk | 2 | 1 |
| Held intermediate allocations in that lantern walk | 15 | 5 |
| Intermediate allocations when switching to soul lantern / copper lantern | 5 / 5 | 0 / 0 |
| Resizes or target allocations during the following warmed 84.4-block walk | 0 | 0 |

The lantern now settles at `768 × 576` instead of shrinking to `576 × 384` and growing back to `576 × 768`. The two final sizes contain the same number of pixels. The first equip and re-equipping after the four-second expiry still need textures to be allocated. These counts don't measure an FPS gain or explain every possible movement hitch.

## Folder changes

Removed the final package-level `client` directory beneath the client source root:

```text
src/client/java/dev/stillbloom/
  StillBloomClient.java
  BlockPicker.java
  gui/          screens and shared controls
    layout/     canvas, scrolling and layout calculations
  model/        registry and resource-pack model inspection
  render/       bloom graphs, execution and resource lifetimes
    item/       held-item rendering
    source/     placed-light scanning, geometry and uploads
  compat/       custom item capture
  mixin/        Minecraft and optional integration hooks
```

All 89 client Java files moved up one package layer, along with 55 matching test files. Imports, qualified references, the Fabric entrypoint, all 30 mixins, current test tools and links to source files were updated. I kept the functional subfolders because each groups a separate system.

`dev.stillbloom` is unchanged. The current Gradle/Fabric Loom setup uses `src/main/java`, `src/client/java`, the resource directories and `src/test/java`, so those stay. The extra package-level `client` wasn't needed. Java packages still match their file paths. Common data code stays in the main source set, and Loom still marks compiled client classes as client-only.

I kept the test folder. Its 78 source files now run 381 checks, including eight new regression tests. They cover configuration recovery, masks and links, controls, render graphs, bounds and resource lifetimes. Tests aren't included in the mod JAR.

## Comments and scope

The comment review covered `src` and the tracked development checks: 355 comment groups in total. It rewrote 327 as short first-person notes and removed 28 descriptions that repeated the code. Source code accounts for 320 rewrites and all 28 removals; the other seven rewrites are in development check tools. Unneeded Javadoc is gone, while useful ownership and sampling notes and the historical reference artifact hash stay. There were no Java license or attribution notices to remove. The project license is byte-identical.

The comment edit itself was checked to preserve every executable token and string literal. Package/comment-only compiled comparison matched 4,012 entries with no unexplained differences. The later overflow-to-Minecraft refinement changes five reviewed allocator entries; 4,009 entries still match the pre-cleanup fix build. Only five production Java bodies differ from the build before these fixes after normalizing imports, package names and comments: `ScopedAllocator`, `BloomTargetDescriptor`, `BloomRenderer`, `HeldItemBounds` and `HeldItemRenderer`. Both new private helpers stay nested in existing files; the production file count remains 104.

## Verification and limits

The clean Gradle build and full test suite pass: **381 tests**, zero failures, errors or skips. New checks cover idle expiry, memory/entry limits, reuse of overflow, simultaneous target separation, cleanup after failures, format-aware byte accounting, and the lantern animation's resize case. All 78 shader files and bundled setup data are unchanged. The world, weather and held-item graphs match the build before these fixes across 6,912 combinations (20,736 descriptions). Entrypoints, mixins, package paths and the generated client-only manifest are checked against the final JAR.

The [validation report](../VALIDATION.md) separates the current build checks from those earlier in-game tests. Tests used temporary worlds and copied settings, leaving my main Modrinth instance alone. Diagnostic Java flags and an inactivity cap mean the rebuild run can't be used as an FPS benchmark. Normal gameplay with the full mod/pack list, multiplayer held items and longer sessions still needs checking before release.
