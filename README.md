# Donut Client

A modern client-side Fabric mod for Minecraft Java 1.21.11 with advanced storage and spawner visualization, Hopper detection, 3D world labels, Freecam, extensible module architecture, and an antialiased click GUI.

## Features

- **Storage ESP & Spawner Finder**:
  - Highlights Chests, Trapped Chests, Ender Chests, Shulker Boxes, Hoppers, Droppers, Spawners, and Trial Spawners.
  - Directional, orientation-aware block voxel shape outlines and fills.
  - Customizable rendering: Outline, Filled, or Both.
  - Visible-only, through-walls, and distance-fading support.
  - Per-family color customization.
- **3D World Labels & Distance**:
  - In-world floating billboard labels indicating container family and exact distance in meters.
- **Client-Side Safety & Server Conformance**:
  - Reads strictly from block entities already present in loaded client chunks.
  - Never requests chunks from the server; avoids packet manipulation or server probing.
  - Suitable for single-player, testing worlds, and servers permitting client overlays.
- **Modular Architecture**:
  - Extensible `Module`, `ToggleModule`, and `ModuleManager` system.
- **Freecam**:
  - Detached camera navigation with zero movement packet transmission and spectator clipping prevention.

## Install

1. Install Fabric Loader for Minecraft 1.21.11.
2. Install Fabric API 0.141.4 or newer for Minecraft 1.21.11.
3. Copy `donut-client-1.1.0.jar` into your Minecraft `mods` folder.
4. Start Minecraft with the Fabric profile.

Press **Right Shift** to open the click GUI. Freecam defaults to **F6**. Module keys can also be reassigned in the click GUI or Minecraft's keybind menu.

## Build & Test

Install JDK 21, then run:

```powershell
.\gradlew.bat test
.\gradlew.bat build
```

The finished mod is written to `build/libs/donut-client-1.1.0.jar`.

## License

MIT. The bundled Inter font uses the SIL Open Font License.
