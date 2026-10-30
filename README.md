# Schematic Builder (Fabric 1.21.11, client-side)

Builds the schematic you have **placed with Litematica**, block by block, with smooth camera turns,
random delays, line-of-sight checks, a reach limit and hotbar/inventory swapping.

## Requirements (in-game)
Minecraft 1.21.11, Fabric Loader 0.18+, Java 21, Fabric API, **Litematica 1.21.11** and **MaLiLib 1.21.11**.

## Build
1. JDK 21.
2. Easiest: generate a fresh 1.21.11 template at https://fabricmc.net/develop/template, copy its
   `gradlew`, `gradlew.bat` and `gradle/` folder into this project (so you get a matching Gradle wrapper).
3. If a version in `gradle.properties` doesn't resolve, take the current numbers from https://fabricmc.net/develop.
4. `./gradlew build` -> `build/libs/schembuilder-1.1.0.jar`.
   (No Litematica jar needed to compile - it's accessed via reflection.)

## Use
1. Load + place a schematic in Litematica.
2. Walk next to it. **B** menu, **V** start/stop, **K** emergency stop (rebindable: Options > Controls > Schematic Builder).

## Limitations
* You walk; it builds what is in reach (no pathfinding). It never breaks blocks.
* Handled: logs/pillars axis, slab and stair/trapdoor top/bottom. Not handled: horizontal facing
  (stairs direction, furnaces, doors...), double slabs, beds, doors' second half.

## Use responsibly
Automated placement is banned on many servers. Singleplayer / your own server / where rules allow.
