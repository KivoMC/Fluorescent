# Fluorescent 2.0.0 for Minecraft 26.3

Fluorescent is a Minecraft mod I've been working on that adds customizable pixelated bloom to the game.

You can choose exactly which parts of a block or item should glow by painting over its texture. Whether it's a torch flame, a lantern, or the eyes on a mob spawner, you get to decide what lights up.

There's also a self-lit option that lets painted areas glow in the dark without changing Minecraft's actual lighting.

## Installation

You'll need:

- Minecraft Java Edition 26.3
- Fabric Loader 0.19.5 or newer
- Fabric API for 26.3
- Java 25

Drop `fluorescent-2.0.0.jar` into your `mods` folder and launch the game. Keep only one Fluorescent or Still Bloom JAR installed.

Sodium is optional.

## The Default Look

I've included a Default look so you can get started without having to paint everything yourself.

It uses 3 px bloom with 33% smoothness, self-lit textures, and first-person held-item glow.

I've also set up 88 blocks and 14 held-item profiles, including custom-painted textures like the spawner eyes. Some related blocks are linked together, so things like torches and wall torches can share their masks.

The Default look is already selected when you first install Fluorescent.

You can always find it again under **F7 → Looks → Built-in → Default**.

If you've made changes, you can preview the Default look using **Try in world** or restore it using **Apply → Apply complete setup**. You can also use **Undo** to bring back your previous setup.

Any looks you've saved yourself will stay in **Saved**.

A few of my masks were made using Refined Torches textures. You don't need that resource pack, but I'd recommend it if you want those masks to line up properly.

## Getting Started

Here's how to make your first block glow.

1. **Open Fluorescent.** Press **F7** while in a world. The first time you open it, a short guide will walk you through the basics. You can skip it or reopen it later.

2. **Pick a block.** Head over to **Blocks**, select a block, and click **Paint mask**. Choose a texture and paint whichever pixels you want to glow. You can also use **Fill** to select everything or **Clear** and **Remove mask** to remove the glow.

3. **Adjust the glow.** Turn on **Live preview** to see your changes as you make them. Under **Glow**, you can change the pixel size, strength, smoothness, and more. **Keep block detail** controls how much of the original texture shows through. **Light response** has options for self-lit textures, uniform emission, and brightness smoothing.

4. **Save your work.** Once you're happy with everything, go to **Looks → Save current**. You can also share your look with other people using a share code. They can import it, preview it, and decide whether they want to use it.

## Controls

| Key | What it does |
|---|---|
| F7 | Open Fluorescent |
| G | Open the block you're looking at in live preview |
| F8 | Toggle bloom on or off |
| F9 | Switch between pixel sizes |
| F10 | Reload settings and masks |

You can change these in Minecraft's Controls menu.

### Pixel Sizes

Fluorescent has three pixel sizes:

- **2 — Fine:** Smaller, more detailed glow.
- **3 — Balanced:** The default size.
- **4 — Chunky:** Bigger, more noticeable pixels.

Pressing **F9** cycles through all three.

There's also **Distance pixels**, which lets the glow use smaller pixels as you move farther away. You can choose between 3→2, 4→3, and 4→2.

Smaller pixels can be a little more demanding to render, so keep that in mind if you're having performance issues.

## Customizing Your Glow

There's quite a bit you can change, but I've tried to keep everything easy to find.

### Blocks

Under **Blocks**, you can customize the glow of individual blocks, including their color, strength, shape, flare, and detail.

You can also use **Link blocks** to make different block variants share settings and compatible masks. Before linking them, you can choose which block's settings you want to use.

### Held Items

You can give items their own glow, whether you're holding them or another player is.

Just hold an item and open **Held Items → Edit items** to work with its textures.

Held items and placed blocks have separate masks, so you don't have to use the same glow for both.

### Glow Settings

Under **Glow**, you can try different bloom shapes, including square, soft, diamond, and starburst.

You can also adjust how the glow changes with distance.

The **Performance** settings let you control how far bloom renders and how it fades away.

### Resource Packs

Fluorescent works with resource packs, but changing textures can sometimes affect where your painted masks line up.

If you switch resource packs, I'd recommend checking your masks to make sure everything still looks right.

The painter doesn't support placed liquids or special geometry without an editable block model.

## Saving and Sharing Looks

You can save your current setup as a look and come back to it whenever you want.

Looks can include your glow settings, painted masks, block links, and held-item settings.

You can also share your looks with other people using share codes.

When importing a look, you'll get the chance to preview it before applying anything.

If you change your mind after applying a look, **Undo** can bring back your previous setup.

## Troubleshooting

If Fluorescent has trouble reading one of your settings or mask files, it won't overwrite the file.

The menu should tell you which file is causing the problem. Once you've fixed or restored it, press **F10** to reload your settings before saving anything.

If something goes wrong while saving a complete preset, Fluorescent will try to restore your previous files.

## Compatibility and Testing

I've tested Fluorescent with Minecraft 26.3's built-in Vulkan renderer and OpenGL.

You can check the [validation report](VALIDATION.md) to see what's been tested and what still needs checking.

## Building from Source

If you want to build Fluorescent yourself, you'll need JDK 25.

On Windows:

```bash
gradlew.bat build
```

On other systems:

```bash
./gradlew build
```

I've also included a [source guide](SOURCE-GUIDE.md) if you want to look through the code and see how everything is organized.

## License

Fluorescent is a project I release under the name **Kivo**.

It's covered by an [All Rights Reserved license](LICENSE).

You're free to use the mod and share your own presets, but redistributing or reusing the mod itself requires my written permission unless the license says otherwise.

