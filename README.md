# Fluorescent for Minecraft 26.3

I made Fluorescent so you can add pixelated bloom to the parts of block and item textures you choose. Paint a torch flame, the windows of a lantern or the eyes on a spawner. Self-lit paint lets those parts glow in darkness. Minecraft continues to handle the world's light levels.

## Install

You'll need Minecraft Java Edition 26.3, Fabric Loader 0.19.5 or newer, Java 25 and Fabric API for 26.3. Put the Fluorescent JAR in your client's mods folder and restart the game. If you're updating, replace the old Fluorescent or Still Bloom JAR so only one is installed. Sodium is optional.

This package is version `0.30.0-rc.24+mc26.3`.

## The Default look

New installs start with my Default look: soft 3 px bloom, 33% smoothness, self-lit paint and first-person held-item bloom. It covers 88 block IDs, including four linked groups, and 14 held-item profiles. I painted patterns such as the spawner eyes and linked the torch and wall torch.

If you already have Fluorescent installed, go to **F7 → Looks → Built-in → Default**. Choose **Try in world** to preview it or **Apply → Apply complete setup** to replace your current settings and masks. **Undo** brings back your previous setup. Your own looks stay in **Saved**. Some of my masks use Refined Torches textures or Enderscape blocks, so those patterns need the matching assets loaded.

## Make your first light

1. Load a world and press **F7**. The first visit opens a four-page guide. Choose **Next** to read it or **Skip** to start editing. **Guide** in the studio footer opens it again.
2. Open **Blocks**, select a placed block and choose **Paint mask**. Select the model texture, then paint the pixels that should glow. **Fill** selects the whole texture. **Clear** and **Remove mask** turn off that texture's bloom.
3. Choose **Live preview** to see the effect while adjusting it. In **Glow**, set the pixel size, smoothness and strength. **Keep block detail** controls how much glow covers the emitting block itself. Open **Light response** for self-lit paint, uniform emission and brightness smoothing.
4. Open **Looks** and choose **Save current**. Use the saved look's sharing controls to copy its code. A friend can use **Import**, preview it, and choose whether to apply it.

## Controls

| Default key | Action |
| --- | --- |
| F7 | Open the studio |
| G | Open the block under the crosshair in live preview |
| F8 | Toggle bloom for a quick comparison |
| F9 | Cycle pixel size |
| F10 | Reload settings and masks |

Change key bindings under Minecraft's Controls menu.

Pixel-size choices are 2 — Fine, 3 — Balanced (Default) and 4 — Chunky. F9 cycles 2 → 3 → 4. Distance sizing can make the pixels smaller farther away, using 3→2, 4→3 or 4→2. Smaller pixels give you a finer glow but take more work to render.

## Choose what glows

- In **Blocks**, choose a block's color, strength, bloom shape, flare or detail. Use **Link blocks** to share settings and compatible mask patterns between variants. Before applying the link, you can choose which block to copy the settings from.
- In **Held Items**, turn on bloom for items in your hands or other players' hands. Hold an item and use **Edit items** to work with its textures. You can set up placed-block and held-item masks separately.
- In **Glow**, try square, soft, diamond or starburst shapes. **Distance pixels** lets the grid use smaller pixels on distant sources. **Performance** controls bloom range and distance fade.
- Resource packs can change the textures and how they fit the model. After changing a pack, check the texture preview to make sure your paint still lines up. The block painter doesn't support placed liquids or special geometry without an editable block model.

## Updating and saved settings

The mod ID and configuration filenames still use `stillbloom`. Your masks, block links, held-item overrides and setup presets carry over when you update. Whether you've dismissed the guide is saved for that Minecraft instance; it isn't included in shared looks. If you have a backup from the older ungrouped mask format, it's named `stillbloom-textures.json.pre-groups.bak`.

Unsupported pixel sizes, including 6 and 8, load as 4 for both near and distant glow. Supported sizes stay as they are. Other settings, masks, links and item overrides stay intact, and updating won't replace your setup with the Default look. Loading leaves the original file alone; saving or exporting writes the corrected sizes.

If Fluorescent can't read a settings or mask file, it leaves the file alone and keeps the current setup when reloading. The menu tells you which file needs attention. Restore or repair it, then press **F10** to reload before saving changes to it.

Saving a complete preset prepares all changed files together. If a write fails, the previous files are restored. If **Keep preset** fails, you can still retry or cancel the trial.

I test Fluorescent with Minecraft 26.3's built-in Vulkan renderer and with OpenGL. The [validation report](VALIDATION.md) covers what passed and what still needs checking.

## Build and license

Build the source with JDK 25 and `gradlew.bat build` on Windows or `./gradlew build` elsewhere.

Settings, masks, links and saved-data code live under `src/main/java/dev/stillbloom`. Rendering, menus, model inspection, compatibility and Minecraft hooks live under `src/client/java/dev/stillbloom`. Tests follow the same packages under `src/test/java`. The [source guide](SOURCE-GUIDE.md) explains the folders, what belongs in each one, and where classes have moved.

I release Fluorescent as Kivo under an [All Rights Reserved license](LICENSE). You can use the mod and share your own preset data. Reusing or redistributing the mod needs my written permission, apart from the exceptions in the license.

