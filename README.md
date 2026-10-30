# Schematic Builder (Fabric 1.21.11, client-side)

Builds the schematic you have **placed with Litematica**. It walks around the build by itself,
crouches at ledges, and places blocks with smooth camera turns and random delays.

## Requirements (in-game)
Minecraft 1.21.11, Fabric Loader 0.18+, Java 21, Fabric API, **Litematica 1.21.11** and **MaLiLib 1.21.11**.

## Build
Push to GitHub - the included workflow (`.github/workflows/build.yml`) compiles it and attaches
`schembuilder-1.1.0.jar` as an artifact on the Actions tab. Or locally: JDK 21, `gradle build`.

## Use
1. Litematica: press **M** > Load Schematics, load the file, place it in the world.
2. Stand near the build. **B** opens the menu, **V** starts/stops, **K** is the emergency stop
   (rebind in Options > Controls > Schematic Builder).
3. Menu tabs: **Overview** (status, schematic info), **Materials** (search, only-missing filter, need/have),
   **Settings** (delays, reach, turn speed, search radius, walking and safety options).

## Layer mode (Settings > Layer)
* **Feet level** (default): builds the layer your feet are in - stand on the finished layer below and it fills
  the next one, walking along it without stepping up or down.
* **Floor you stand on**: builds the layer under your feet instead.
* **Whole build, bottom-up / nearest**: ignores your level and works through the whole schematic.
When a layer is finished it waits ("move up to the next layer") and carries on by itself when you change level.

## Natural behaviour (Settings > Human-ness, Take short breaks)
Uneven delays, short pauses, a rare longer break, small camera sway, slightly different aim speed per block,
a short wait when switching blocks, wobble and the odd hesitation while walking, and clicks only where the
crosshair really points. This makes it behave like a relaxed player; it does **not** make it undetectable and
it is not a way around server rules.

## Walking and safety
* Auto-walk: finds a route (A*) to a spot from which the next blocks are in reach, then presses
  forward/jump and turns the camera. It avoids lava, fire, cacti, berry bushes, cobwebs, magma and
  powder snow, prefers routes away from edges, and won't stand where a block still has to go.
* Crouch at edges: holds sneak whenever there is a 3+ block drop within ~0.7 blocks of you (while
  placing and while walking), so you can't slip off.
* "Allow drops" lets the route step down up to 3 blocks (off by default).
* If it gets stuck it re-plans, then skips that spot for a while. Opening any menu pauses it.
* When nothing is left it stops and tells you "Build complete!".

## Limitations
* It never breaks blocks and does not bridge or pillar - it needs a solid neighbour to click against,
  so on a floating build start from the bottom layer / ground.
* Handled orientation: logs/pillars axis, slab and stair/trapdoor top/bottom. Not handled: horizontal
  facing (stair direction, furnaces, doors...), double slabs, beds, doors' second half.
* Walking only goes over loaded chunks it can see; very large builds are scanned within the search radius.

## Use responsibly
Automated movement and placement is banned on many servers. Singleplayer / your own server / where rules allow.
