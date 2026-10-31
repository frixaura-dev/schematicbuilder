# SchemBuilder

A Fabric mod for Minecraft **1.21.4** that loads a schematic and builds it for you, with an animated interface.

## Building

Push this folder to a GitHub repo. The workflow in `.github/workflows/build.yml` builds the mod on every push. Open the **Actions** tab, then the latest run, and download the **schembuilder-mod** artifact. It contains `schembuilder-1.0.0.jar`.

The workflow pins Gradle 8.12. Loom 1.10 doesn't support Gradle 9, so `gradle-version: current` can fail.

Put the jar in `.minecraft/mods` together with Fabric API for 1.21.4.

## Using it

1. Drop `.litematic`, `.schem` (Sponge v1–3 / WorldEdit) or `.nbt` (structure block) files into `.minecraft/schematics`.
2. Press **B** to open the interface and click a schematic. It is placed with its corner at your feet, and an outline shows in the world.
3. Use the X/Y/Z − / + buttons to nudge it (shift-click moves it by 10), and **Rotate** to turn it.
4. Stand on a block inside the outline and press **Start build**.
5. Press **N** to pause or resume. You can set a stop key in Controls → SchemBuilder.

## What it does

- **Moves with your normal controls.** It walks with W, jumps with Space and sneaks with Shift. A pathfinder handles walking, stepping up and dropping down. It can also pillar up on a block that belongs at its feet.
- **Gets orientation right.** For each block, it simulates the item's own placement logic across faces, hit points and look directions. It then turns to the rotation that produces the exact state. This covers stairs, slabs, logs, doors and hinges, trapdoors, observers, pistons, buttons, levers, wall torches, and 16-way signs and banners.
- **Fixes mistakes.** If a block comes out wrong, or something else is in the way, it breaks it with the best tool in your hotbar and places it again. It gives up after 3 tries and marks the block in yellow.
- **Right-click properties.** It sets repeater delay, comparator mode, note block pitch, open/closed doors, trapdoors and gates, lever state and daylight sensor mode by clicking.
- **Stackable blocks.** It builds double slabs, candles, sea pickles, turtle eggs, snow layers and pink petals.
- **NBT.**
  - *Survival:* sign text is written from the schematic.
  - *Creative* (with "Creative: exact NBT" on): each block is placed from an item that carries its exact block state and block-entity data. This covers chest contents, banners, signs, skulls, spawners and more. Signs, spawners and command blocks need operator / cheats on.
- **Items.** It pulls items from your main inventory into the hotbar. If you run out of something, it pauses and tells you what's missing.
- **Overlay.** Red boxes mark wrong blocks, blue marks blocks still to place, yellow marks skipped ones, and the current block pulses white.

## Limits

- Fluids aren't placed. Blocks the game places alongside another one (door tops, bed heads, tall plant tops) are handled by placing the bottom part. Properties the game works out from neighbours are not compared: fence and wall connections, stair shape, redstone power, crop age and chest pairing.
- In survival, block entities other than signs (such as chest contents) can't be filled in.
- Schematics should come from 1.21.x. Older palettes aren't upgraded.
- Movement and camera are plain and tool-like. There is no randomisation or disguise. Use it in singleplayer or on servers that allow printers and auto-builders.

## Code map

```
schematic/  readers for .litematic, .schem and .nbt; Placement (origin and rotation)
build/      Builder (state machine), PlacementSolver, StateMatcher, Navigator, Inventory
ui/         BuilderScreen, BuildHud, Toasts, UiSound, Draw helpers, Theme
render/     OverlayRenderer (world outlines)
mixin/      BlockItem invoker, sign editor accessor
```

If a build fails on a Minecraft API name, the error log points to the exact line. Paste it back and I'll fix it.
