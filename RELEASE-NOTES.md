# Fluorescent 0.30.0-rc.24 for Minecraft 26.3

This update fixes two cases where bloom textures were being rebuilt unnecessarily. I also cleaned up the comments and removed an extra folder layer from the source.

- Temporary bloom textures can now be reused when you briefly look away or switch items. The cache has a memory limit; anything beyond it still uses Minecraft's normal pool.
- Held-item render targets keep enough room for the walking animation, so they don't shrink too tightly and immediately need to grow again.
- The extra package-level `client` folder is gone. Gradle's source folders and the existing groups stay where they are.
- Java comments are shorter first-person notes. Comments that only repeated the code are gone.
- Eight new regression tests bring the total to 381. All pass.

Shaders, lighting calculations, defaults, masks, saved data and controls are unchanged. You don't need to reset or migrate your settings. Replace the previous Fluorescent JAR to try this version. The checks show fewer texture allocations, but I haven't measured an FPS improvement.

OpenGL and Vulkan passed the rendering tests with diagnostic Java flags. A normal launch of an intermediate build crashed in `jvm.dll` during resource reload. The cause is still unknown, so normal-launch playtesting needs to pass before release. See the [validation report](VALIDATION.md) and [change report](docs/rebuild-fixes-report.md).
