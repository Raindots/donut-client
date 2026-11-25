# Donut Client

Client-side Fabric mod for Minecraft Java **1.21.11**.

## Install

- Install Fabric Loader and Fabric API 0.141.4 or newer for Minecraft 1.21.11.
- Put `donut-client-4.0.jar` in your Minecraft `mods` folder. [Download 4.0](https://github.com/Raindots/donut-client/releases/tag/v4.0)
- Press **Right Shift** to open the menu. Select a module to edit its settings and keybind.

## Features

- **Crystal Aura** repeats crystal placement and breaking where you aim while holding **Use Item**. Placement and break delays are adjustable.
- **Auto Totem** refills your offhand from inventory or hotbar. **Anchor Macro** runs one or two anchor cycles with optional cover.
- **Triggerbot** has cooldown, shield, and friend controls. **Aim Assist** has adjustable targeting and strength.
- **Base Finder**, **Amethyst Scanner**, **Light Debug**, and **BlockESP** highlight activity clues, amethyst, underground light, and predicted ores.
- **Sound Detector** alerts you to nearby activity. **Staff Detector** shows signals for players on your roster. [Staff Detector guide](docs/staff-detector.md)
- **Freecam**, **Fly**, **Fullbright**, **Player ESP**, and **Time Changer** control camera, movement, and visuals.
- **AutoBuilder** previews and builds schematics. [AutoBuilder guide](AUTOBUILDER.md)
- **Progress Bar** adds milestones and timers. **Name Hider** and **Hotbar** offer privacy controls for streams.

## Configs

- Save named profiles with descriptions and colors. Profiles capture module settings, HUD, appearance, and module keybinds.
- Use **Load** to apply a profile and **Update config** to save your current setup to it.
- Share a profile with **Export**. Use **Import** or drag a shared JSON file onto the Configs screen.
- **Open folder** opens `config/donut-client/` in your Minecraft instance.

## Build and credits

- Build with JDK 21 using `./gradlew build`. Find the JAR in `build/libs/`.
- Original code by Donut Client contributors. [MIT notice](LICENSE)
- Spectre by 4ldenz. Adapted components use GPL-3.0-only. [Attribution and bundled notices](src/main/resources/licenses/)
- Geist and Inter fonts (SIL OFL 1.1) and Name Hider (CC0 1.0). [Geist and Name Hider notices](src/main/resources/licenses/) · [Inter notice](src/main/resources/assets/donut/font/ofl.txt)
