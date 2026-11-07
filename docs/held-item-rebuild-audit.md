# Held-item movement and rebuild audit — rc.23

This audit looks at commit `67d875d40070a429300457e96f32bafb53e3f448` on `fix/held-item-rebuilds`, November 6, 2026. It follows the hitches I noticed while holding different items and moving. This was a check of the existing release, with no changes to production code or release files.

## Findings

### 1. Temporary bloom targets expire much sooner than the cached renderer

Both `BloomRenderer` and `HeldItemRenderer` execute their frame graphs through `ScopedAllocator`, which delegates allocation to Minecraft's frame resource pool. `GameRenderer` constructs `CrossFrameResourcePool(3)`. Inspection of the installed Minecraft 26.3 bytecode confirms that unused entries age at each `endFrame` and are freed when their remaining lifetime reaches zero. Reusing a cached post-processing chain therefore does not necessarily reuse its intermediate textures after a short interruption.

Observed examples:

- Re-equipping a torch after 50 ticks with an empty hand created **five intermediate textures**, despite **zero** held-chain rebuilds or main held-target resizes. This happened in both instrumented runs that counted these targets.
- In the focused test, looking away from the placed lights and back created **five world intermediate textures per return**: four `640 × 337` targets and one `1920 × 1009` target. The same behavior occurred while holding a lantern and with an empty hand. Allocation call stacks identified these as world bloom, independently of the held-item renderer.
- Switching to a soul lantern or copper lantern created five held intermediate targets at the equip transition, while the existing held chain and main targets were reused. No additional target allocations occurred later in those walking phases.

Relevant code: `client/render/ScopedAllocator.java`, `client/render/BloomTargetDescriptor.java`, `BloomRenderer.java:265`, and `client/render/item/HeldItemRenderer.java:663`. The held renderer already keeps eligible inactive main targets and chains warm for four seconds; the world chain cache has a ten-second expiry. Neither cache currently controls the lifetime of these delegated intermediate textures.

**Recommended first change:** keep Fluorescent's intermediate targets for reuse after short inactive periods, matching their exact dimensions and format. Keep the existing graph, shader operations, clear/write rules and source rendering. The cache needs a byte limit, idle expiry, and cleanup on reload, resolution changes, disable and failure. Only change Fluorescent's reuse rules, not the lifetime of Minecraft's entire pool. The tests show avoidable allocations, but don't measure an FPS gain or prove this explains every hitch.

### 2. The held crop can shrink and regrow during walking

`HeldItemRenderer.Group.ensure` shrinks targets after 1.5 seconds when the aligned required area is less than half the allocated area. Growth includes one spare allocation bucket, but shrinking uses the current required size with no equivalent spare room.

After switching from torch to lantern, two separate runs observed:

1. A shrink from `768 × 768` to `576 × 384`, at phase tick 38.
2. Growth to `576 × 768` at tick 105 in one run and tick 112 in the focused repeat, after movement reversed direction.

Each resize recreated the held main targets, rebuilt its post chain, and allocated five intermediate targets. These were chain/target rebuilds; the shader pipeline cache did not miss. After this initial adjustment, the lantern remained stable for the rest of the 600-tick walking phase. A subsequent 400-tick walk covering **84.4 blocks** produced **zero** target allocations or chain rebuilds.

**Recommended second change:** use the space the moving item has needed recently when deciding how far to shrink. Then check both the resize count and the target area being processed. Keeping the largest targets forever could add GPU work every frame. The tests show occasional resizing, not constant rebuilding while moving.

## What stayed stable

- The Refined Torches torch completed three approximately **91.8-block** walking/sprinting phases with no held target resizes, held-chain rebuilds, shader-cache misses, full renderer resets or held baked-geometry rebuilds during those phases. The two runs with intermediate-target instrumentation also recorded zero such allocations during that walk and the following camera-turning phase.
- In the focused run, the soul lantern and copper lantern each covered roughly **50 blocks** with their held groups actively processed. Their allocations were at initial equip only; there were no later chain rebuilds or resizes during walking.
- No probe recorded repeated renderer invalidation or shader-pipeline cache misses in the measured phases. The initial renderer reset when first enabling held bloom is expected.
- Placed-source snapshots updated as the player moved. A changed source snapshot alone is not a new held model, shader or framebuffer. These counters do not establish the cost of all source updates in every world.

An earlier test included a campfire, but it didn't activate held bloom with the copied item settings. That run doesn't count as a successful campfire held-bloom check.

## Test scope and evidence

Four separate OpenGL/Sodium in-game runs completed and exited with code 0:

| Evidence directory under `work/` | Coverage |
| --- | --- |
| `evidence-rc23-held-motion` | Standing, walking, first equip, re-equip, torch walking/sprinting, turning, switching. Main held targets, chains, geometry and shader counters. |
| `evidence-rc23-held-targets` | Same sequence with intermediate-target allocation counters. |
| `evidence-rc23-held-multiple` | Added lantern, soul lantern, campfire, and look-away/back phases. |
| `evidence-rc23-held-detail` | Longer lantern walking, a second warmed walk, soul and copper lanterns, and look-away/back with a lantern and an empty hand. Intermediate allocations distinguished by renderer call stack. |

Each directory contains `manifest.json`, `motion.json`, `result.json`, and `runtime.log`. Manifests record the candidate JAR hash and probe/fixture hashes. All runs used the verified rc.23 JAR, SHA-256 `33641a6bb5d30d5b4632ce89ed872a96ab2b4a9d630385a3af91aa1d0496fdf6`.

The temporary profiles used copies of my current Fluorescent settings, masks and item overrides, with Refined Torches 2.1, Punchy 3.1b, Respackopts, LibJF, Sodium and Fabric API. The generated Punchy configuration matched my main profile byte-for-byte. Settings included pixel size 3, approximately 0.499 smoothness, held bloom on, distance pixels off, 16-chunk render distance, 8-chunk simulation distance, view bobbing on, and a `1920 × 1009` framebuffer. The walking lane was built in a temporary world copy. My main Modrinth instance, installed mod and worlds were left alone.

These are rebuild/allocation checks, not FPS benchmarks. The in-game checks used a 120-FPS cap, a null audio device, and the diagnostic JVM flags `-XX:TieredStopAtLevel=1 -XX:ActiveProcessorCount=2`. Minecraft's AFK limit also affected the later frame timings, so those timings can't show normal gameplay FPS or improvements. The release validation explains why the diagnostic Java settings were used.

The test instance left out unrelated mods and other resource packs. It logged Refined Torches particle-condition errors about `particlesOnThirdPerson`, plus offline-service and Windows performance-counter warnings. No Fluorescent rendering failure was logged. This setup doesn't fully reproduce my main profile or exact location.

This audit didn't change production code or produce a new JAR or source package. The temporary probe was compiled against the game and build being tested, and all four runs reached their completion marker. There was no new Gradle build or unit-test run; the earlier rc.23 results are separate from this audit.

## Remaining verification

At the end of this audit, the tests hadn't fully reproduced the repeated hitch across all the items or confirmed a single cause. The two clearest things to fix were intermediate-texture reuse and the lantern's shrink/regrow sequence. After either fix, repeat the allocation counts, compare framebuffer output, check retained GPU memory and normal-runtime frame times, then test my exact scene with the full mod and resource-pack list. Also check resource reload, resize, item switching, other players' items and Vulkan before releasing a change to resource lifetimes.
