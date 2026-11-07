# Fluorescent 2.0.0 source guide

Fluorescent is a client-only Fabric mod for Minecraft 26.3, built with Java 25. The source download includes the code, resources, Gradle wrapper and automated tests. You can build it without my local worlds, settings or launch scripts.

## Build

Install JDK 25, set `JAVA_HOME` to it, and open a terminal in the extracted project folder.

Windows:

```text
gradlew.bat build
```

macOS / Linux:

```sh
chmod +x gradlew
./gradlew build
```

Gradle downloads what it needs on the first build. After that, you can use `--offline` as long as the dependencies are cached. The build compiles the common and client code, runs the JUnit tests and puts the mod and source JARs in `build/libs`. Use the JAR without `-sources` in Minecraft. Test results are in `build/reports/tests/test/index.html`.

## Folder map

```text
src/main/java/dev/stillbloom/
  config/         Settings, overrides, resolved styles and range rules
  mask/           Painted masks, block links, shared profiles and edit transactions
  persistence/    Reading JSON and safely saving files
  preset/         Bundled defaults, saved looks, import/export and complete saves

src/client/java/dev/stillbloom/
  StillBloomClient.java    Fabric entrypoint, key bindings and startup/cleanup
  BlockPicker.java         Crosshair block-picking action
  gui/            Screens, search, live preview and shared menu drawing
    layout/       Brush/canvas geometry, scrolling and transitions
  model/          Registry search and resource-pack model inspection
  render/         Pass graphs, execution, history, weather and resource lifetimes
    source/       Placed-source scanning, geometry, bounds and texture uploads
    item/         Held-item capture, graphs and rendering
  compat/         Custom held-item geometry capture for Punchy
  mixin/          Minecraft accessors and hooks, including optional Punchy hooks

src/main/resources/      Shaders, bundled setup, metadata and mixin declarations
src/test/java/           Automated tests following the production packages
gradle/wrapper/          Runs the project's chosen Gradle version
tools/format_java.py     Optional Java formatting helper
```

There are 104 production Java files: 15 common and 89 client. Another 78 files are tests and aren't included in the mod JAR. Of the production files, 30 are Minecraft accessors and integration hooks. Each targets a specific part of Minecraft, so they stay separate.

The [class inventory](docs/source-relocations.md) shows where each original class lives now. The [rebuild investigation](docs/rebuild-fixes-report.md) explains the texture-cache and held-item resize fixes.

## Where things belong

- **Data and saving:** `config`, `mask`, `preset` and `persistence` don't import client rendering code. Keep JSON names and saved-data rules here. `BloomOverride` handles copying and comparing overrides; `SetupPersistence` saves complete setups through `JsonFiles`.
- **Block linking:** `BlockLinks` stays with `TextureMasks` because linked blocks share saved profiles. Model discovery belongs in `model`. The link editor keeps its temporary discovery session in `BlockLinksScreen.Session`.
- **Rendering:** `BloomGraph` builds immutable descriptions of each render pass, including the weather version. `BloomRenderer` manages live chains and their cache. `BloomHistory` manages history targets. `BloomPass.Bounds` describes how far the existing filters reach. Held-item rendering has its own path under `render.item`.
- **Source data:** `SourceMask` coordinates placed-source caches. `TextureGeometry` owns its triangle index and emission correction; `MaskTexture` owns upload-row planning; `SourceVolumePool` owns packed volumes; `SourceBounds` owns clustering. These are static nested helpers, without an implicit reference to their owner.
- **Menus:** Screens handle input and edits that haven't been saved yet. Layout calculations stay in `gui.layout`. The checkerboard renderer is part of `MenuPresentation`. Model discovery for the editor doesn't run during gameplay.
- **Integration:** `StillBloomClient` connects the systems. Keep mixins focused on hooking into Minecraft; the main logic belongs in the system they call. Optional mod support must stay optional.

Some screens and renderers are still large because their state and cleanup rules need to stay together. Split out a class when it has a separate job, not just to make a file shorter. Keep the existing cache, allocation, invalidation and cleanup rules when changing rendering code.

## Names to keep stable

The public name is Fluorescent, but the internal mod ID is still `stillbloom` so existing setups keep working. Keep configuration filenames, JSON fields, preset envelope versions, resource identifiers and the `/dev/stillbloom/defaults/` resource path stable. Moving Java packages doesn't require changing saved configurations.

The Fabric entrypoint is `dev.stillbloom.StillBloomClient`. Mixins are declared under `dev.stillbloom.mixin` in `stillbloom.mixins.json`. If an integration class moves, update these declarations and any reflection references too. Internal helper classes aren't a public compatibility API.

## Formatting and checks

Java uses four spaces and the repository's `.editorconfig`. The optional formatter requires Python 3 and the official [google-java-format 1.37.0 all-deps JAR](https://github.com/google/google-java-format/releases/tag/v1.37.0):

```text
python tools/format_java.py --formatter /path/to/google-java-format-1.37.0-all-deps.jar
python tools/format_java.py --formatter /path/to/google-java-format-1.37.0-all-deps.jar --check
```

Formatting is not a Gradle dependency. Run `gradlew.bat build` after changes. Keep tests in the package that owns the behavior and share test fixtures rather than importing one test class into another.

Rendering changes need in-game checks too. A passing build can't tell you whether the glow looks right. Compare pixel sizes 2/3/4, painted blocks and held items, smoothness, distance sizing, weather, resource reloads and resizing on the supported backends. After changing data or menus, check saved presets and restart the game. The [validation report](VALIDATION.md) lists what passed for this version and what still needs testing.

The full development checkout keeps older comparison and in-game test scripts under `tools`. They need local test files, so I leave them out of the source download. You don't need them to build the mod or run its automated tests.

## Writing

Keep explanations simple and direct, like the README. Use first person when talking about my work and speak to the reader as "you" when giving instructions. Don't invent experiences or reasons for a decision.

Java comments should be short first-person notes about why the code works that way. Leave out comments that just repeat the code. Keep technical identifiers, license notices, credits and any AI disclosures intact.
