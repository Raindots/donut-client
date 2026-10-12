# Donut Client

A client-side Fabric mod for Minecraft Java 1.21.11 with storage and spawner highlights, Base Finder, Fullbright, Freecam, Fly, AutoBuilder, module keybinds, and a minimal HUD.

## Install

1. Install Fabric Loader for Minecraft 1.21.11.
2. Install Fabric API 0.141.4 or newer for Minecraft 1.21.11.
3. Copy `donut-client-1.6.1.jar` into your Minecraft `mods` folder, replacing the previous Donut Client jar.
4. Start Minecraft with the Fabric profile.

Press **Right Shift** to open or close the click GUI with a short fade. Escape and the close button also fade out the menu. Freecam defaults to **F6**. Other module keys can be assigned in the click GUI or under Minecraft's keybind settings.

The compact click GUI arranges modules in two columns. **Right-click a module header** to slide its settings open or closed inside the card. Color/filter groups also expand smoothly; tabs, search results, and the color editor fade in. **Left-click the header or its switch** to enable or disable the module. Use the small outlined key box to assign a shortcut and the star to add a favorite. Settings save automatically.

**Blatant** contains **Fly** and **AutoBuilder**. The Visual category contains the finders, Fullbright and Freecam; Favorites can contain modules from either category. AutoBuilder opens a schematic manager; building requires its explicit **Start** action. Assign its shortcut in the outlined key box or Minecraft Controls. Put `.litematic` files in the active Minecraft instance's `schematics/donut/` folder using **Open Folder**, then **Refresh**. Select a file, inspect its rotatable/zoomable model, set world coordinates/rotation/mirroring, and check **Materials** before starting. **Show in world** displays a ghost preview; **Cancel Preview** hides it. Open the manager to pause, resume, or cancel a build. **Get Materials** is available in Creative and fills only available inventory capacity.

**Fly** can be enabled in singleplayer, LAN, and multiplayer worlds. In singleplayer and LAN worlds you host, it grants your actual player vanilla flight, including Survival without commands enabled. On remote servers it uses ordinary vanilla flight movement; server permissions and movement checks may restrict flight or correct your position. Use your normal movement keys, Jump to rise, Sneak to descend, and Sprint for vanilla flight sprinting. Speed, collision, and game mode remain vanilla. Disabling Fly restores your previous vanilla flight abilities in hosted worlds, or the latest flight permissions received from a remote server while Fly was active. Gravity resumes when the restored state is not flying. Fly supports Favorites, the HUD, and an assignable shortcut. Flight starts disabled each session and turns off on death, changing dimension, or disconnect. Temporary flight permission in hosted worlds is excluded from player saves.

AutoBuilder walks and jumps along bounded routes, selects inventory materials, uses ordinary placement interactions, and waits for server updates. Menus, Freecam, missing materials, disconnects, blocked routes, and rejected placements pause building with a reason. File notices explain unsupported block-entity data and older data versions. Exact states that cannot be produced by ordinary placement require manual correction or server support; see [Litematica's server requirements](https://github.com/maruohon/litematica/wiki/Easy-Place). Material counts distinguish unloaded positions as unknown.

See [the AutoBuilder guide](AUTOBUILDER.md) for placement controls, support blocks, navigation bounds, and file limits, and [validation results](TESTING.md) for the checks performed.

Search filters modules, and the Appearance page controls the accent color and HUD. Fresh configurations use a purple accent; existing configurations retain their saved color.

**Fullbright** is in Visual and applies gamma 1000 while enabled. Disable it to return to your normal brightness. It supports favorites, an assignable shortcut, and the active-module HUD.

**Base Finder** (formerly Chunk Finder) is in Visual. Its Simulation distance setting is the scan radius in chunks (2-16, default 6); it does not change the server's simulation distance or load extra chunks. Sensitivity runs from 1 (stricter) to 5 (more candidates). All four amethyst growth stages contribute to detection; sensitivity 5 also detects a single small, medium or large bud. Smart adjustment suppresses bare amethyst/budding blocks and some common naturally generated plant states, while keeping mixed-stage growing geodes. Toggle Kelp, Cave vines, Vines, Amethyst, Bamboo, Cocoa and Bee nest independently. Alpha controls overlay opacity (0-255), and the highlight color is editable. Thin outlines and faint fill mark the chunk footprint only at the detected evidence layers, including through walls. Separate depths remain separate; there are no world-height columns. Existing settings and shortcuts are preserved.

Base Finder checks growth states and geode-related blocks already received by the client. These are heuristics for possible activity, not proof of a player or a base: naturally generated structures and vegetation can match too. Scanning is incremental, so larger distances take longer to refresh; moving to another chunk, changing detection settings, leaving a world, or unloading chunks removes outdated results.

## Build

Install JDK 21, then run:

```powershell
.\gradlew.bat build
```

The finished mod is written to `build/libs/donut-client-1.6.1.jar`.

## License

MIT. The bundled Inter font uses the SIL Open Font License.
