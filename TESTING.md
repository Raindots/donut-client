# Validation

Automated configuration tests cover defaults, JSON persistence and replacement, invalid-file recovery/backups, missing and future fields, unknown modes, numeric boundaries, NaN/infinity, and color sanitization.

Run `./gradlew build` with JDK 21. The installable, remapped artifact is `build/libs/donut-client-1.6.1.jar`.

Run `./gradlew runClientGameTest` for isolated Minecraft runtime tests. They create disposable worlds, check finder counts/rendering, Fullbright, Freecam, Fly and module keybinds, and exercise the AutoBuilder loader, popups, materials, placement, and navigation against an integrated server. Test code and fixtures are excluded from the installable jar. This requires a working graphics device.

The multiplayer Fly suite uses a dedicated server bound to localhost and logs a skip unless `build/run/clientGameTest/eula.txt` contains `eula=true`. Loom normally runs `deleteGameTestRunDir` before launch, which deletes that file along with the runtime directory. To opt in manually after reading and accepting the [Minecraft EULA](https://aka.ms/MinecraftEULA), create or edit `build/run/clientGameTest/eula.txt` with `eula=true`, then run `./gradlew runClientGameTest -x deleteGameTestRunDir` from the project root to preserve the flag. EULA acceptance is a separate user decision; the tests never create or change an acceptance flag.

## Verified on 2026-10-10 (1.6.1 multiplayer Fly)

- `build runClientGameTest --offline --no-daemon`: passed with JDK 21. All 36 unit tests and the eleven existing Minecraft runtime suites passed. The dedicated-server multiplayer suite was skipped at the user's request; no EULA acceptance flag was set.
- Removed the singleplayer and unpublished-LAN activation restrictions. Remote sessions use vanilla client abilities and ordinary packets, while hosted worlds still grant flight only to the local owner. A client ability-packet mixin retains the remote server's latest flight flags for restoration. Actual remote movement, server permission updates, and LAN publication remain unverified in this run.
- Regression checks confirm hosted Survival movement, ability restoration across game modes, temporary-flight save protection, death/respawn/disconnect cleanup, and the existing GUI/shortcut/HUD integrations. Disconnect cleanup now matches its connection, runs on the client thread, and avoids sending ability packets after disconnect.
- Visually reviewed the updated Blatant Fly card and saved `docs/screenshots/fly-blatant.png`. Inspected the remapped 1.6.1 JAR: both flight mixins are included and test/game-test classes are excluded. Remote servers may restrict flight or correct movement.

## Verified on 2026-10-09 (1.6.0 Fly)

- `build runClientGameTest --offline --no-daemon`: passed with JDK 21. All 36 unit tests and all eleven Minecraft runtime suites passed, including transient Fly activation, persistent favorites, older/null settings, and saving preferences during active flight.
- Production Fly is in Blatant and supports Favorites, an assignable Controls shortcut, settings expansion, held-key repeat suppression, and the active-module HUD. Visually reviewed the actual Blatant card and Survival flight screenshots, saved as `docs/screenshots/fly-blatant.png` and `fly-survival.png`.
- Disposable Survival-world checks verify both client and integrated-server flight permission, real forward/up/down movement, hovering, ordinary camera attachment, and gravity after disabling. Game mode, movement speeds, and unrelated abilities remain unchanged.
- Runtime checks preserve previously grounded and airborne Creative abilities and Spectator flight, restore the correct baseline after game-mode changes, and clear activation on death, respawn, and disconnect.
- Saved actual player data while Fly was active and read its compressed NBT: Survival flight flags remain disabled in the saved file while live flight stays enabled. The save mixin masks only temporary flight flags; it does not mutate live abilities.
- Inspected the remapped release JAR: version 1.6.0, Fly controller/settings/save mixin and translated Controls label present, and no test/game-test classes. Remote servers, LAN publication, dimension changes, and third-party movement mods were not exercised by this test.

## Verified on 2026-10-09 (1.5.1 Base Finder layers)

- `build runClientGameTest --offline --no-daemon`: passed with JDK 21. All 33 unit tests and all ten Minecraft runtime suites passed.
- Removed the world-height guide added in 1.4.2. The overlay retains a 16x16 chunk footprint but follows only contiguous observed evidence heights, with 0.05-block Y padding, one-pixel outlines, and fill at six percent of the configured alpha. Separate evidence depths do not bridge empty vertical space.
- Loaded-world checks verify a buried Y=-32 layer from the surface and nearby, through-wall GPU submission, exact color/alpha/fill, and two separate layers at Y=-40 and -8 within one chunk. Existing negative-Y detection and growth-stage regressions still pass. Unit tests cover immutable layer snapshots, negative/empty bounds and invalid reversed layers.
- Verified the Base Finder card, `basefinder` search, translated Controls name, settings/filters, favorites, shortcut capture/persistence/repeat suppression, and production HUD. Original JSON and shortcut identifiers are unchanged so existing preferences and keys remain compatible.
- Visually reviewed actual screenshots at GUI scales 1 and 2, the renamed HUD, and compact underground/separated layers. Representative images are saved in `docs/screenshots/basefinder-*.png`.
- The first runtime attempt found an integer teleport-centering mismatch in the new test camera setup. Corrected the test coordinate to an explicit decimal and reran the full suite successfully; production code was unchanged.
- Inspected the remapped release JAR: version 1.5.1, evidence-layer class and Base Finder Controls label present, and no test/game-test classes. Third-party shader packs and alternate renderers remain untested.

## Verified on 2026-10-09 (1.5.0 Fullbright)

- `build runClientGameTest --offline --no-daemon`: passed with JDK 21. All 30 unit tests and all ten Minecraft runtime suites passed.
- Fullbright applies gamma 1000 only to the lightmap's brightness read. The normal brightness option remains unchanged, including while saving/reloading options or editing brightness with Fullbright enabled. Disabling returns to the current normal brightness.
- Runtime checks used the production module, GUI and shortcuts in a disposable, unlit singleplayer room. Screenshot pixel checks confirm the room becomes bright without an all-white frame and returns to its original darkness after disabling. No Night Vision effect was present.
- Verified Visual/Favorites membership, exclusion from Blatant, gamma search, inline settings, key capture/persistence, shortcut activation and held-key repeat suppression, and input clipping at GUI scales 1 and 2. Configuration tests cover missing/older/null settings and enabled/favorite persistence.
- Visually reviewed screenshots at both GUI scales and the off/on/restored room captures. Representative images are saved as `docs/screenshots/fullbright-clickgui.png`, `fullbright-scaled.png`, `fullbright-off.png`, `fullbright-on.png`, and `fullbright-restored.png`.
- Inspected the remapped release JAR: version 1.5.0, Fullbright classes and its lightmap mixin present, and no test/game-test classes. Third-party shader packs and alternate renderers remain untested.

## Verified on 2026-10-09 (1.4.2 underground amethyst)

- Reproduced the old Smart Adjustment bug in an actual loaded singleplayer world: only mature-cluster fixtures at Y=4, -17 and -63 matched, while small, medium, large and mixed-stage fixtures were discarded. Negative-Y section mapping was correct; the mature-cluster ratio filter caused the misses.
- Removed that ratio filter while preserving stage weights and sensitivity thresholds. Maximum sensitivity now also accepts a lone growing bud. Smart Adjustment continues to ignore bare amethyst/budding blocks.
- `build runClientGameTest --offline --no-daemon`: passed with JDK 21. All 28 unit tests and all nine Minecraft runtime suites passed.
- Depth regression checks exact evidence heights at Y=4, -2, -3, -16, -17, -32, -48 and -63, mixed-stage geodes, updates from small buds to mature clusters, lone buds at maximum sensitivity, and bare-block suppression after replacing a detected cluster.
- Added a full-build-height wireframe guide without column fill. The runtime test inspects actual rendered frame data and the GPU line buffer for buried Y=-32 amethyst viewed from Y=65. It verifies chunk bounds, alpha/color, and through-wall rendering. Visually reviewed the surface screenshot saved as `docs/screenshots/chunkfinder-underground-surface.png`.
- Inspected the remapped release JAR: version 1.4.2, Chunk Finder classes present, and no test/game-test classes. The user's specific world was not inspected; all reproductions used disposable worlds with known, loaded fixtures.

## Verified on 2026-10-07 (1.4.1 placement packets)

- `build runClientGameTest --offline --no-daemon`: passed with JDK 21. All 28 unit tests and all eight Minecraft runtime suites passed.
- A regression run with the old placement path reproduced two regular movement packets in one client tick. The corrected production path completed 29 placement interactions across 544 client ticks with at most one regular movement packet per tick and positive, strictly increasing placement sequences. Vanilla teleport responses are excluded from the regular-movement count.
- Placement now waits for vanilla's transmitted rotation and sneak input. Runtime checks passed for directional blocks, configurable redstone, slabs, paired blocks, cancel/reselect before server confirmation, temporary supports, obstacle navigation, server position corrections, menu/Freecam pauses, missing materials, and authoritative rejection.
- Inspected the remapped release JAR: version 1.4.1, the new player-state accessor registered and correctly remapped, AutoBuilder and Chunk Finder classes present, and no test classes or test mixin resources.
- One retry encountered a Fabric network-synchronizer race during world teardown before the engine suite. The unchanged rerun passed with network synchronization enabled. The user's private server and its anti-cheat plugin have not been directly tested.

## Verified on 2026-10-07 (1.4.0 Chunk Finder)

- `build runClientGameTest --offline --no-daemon`: passed with JDK 21. All 28 unit tests and all eight Minecraft runtime suites passed.
- Chunk Finder configuration tests cover defaults, older/null configurations, numeric boundaries, color sanitization, and persistence of every filter and setting.
- Loaded-world fixtures verify all seven indicator types independently, sensitivity thresholds, smart suppression of mixed-stage geodes, simulation-distance bounds, evidence removal, disable/clear/disconnect cleanup, and scan progress while crossing chunk boundaries every tick.
- Production GUI input verifies Visual/Favorites membership, exclusion from Blatant, search, inline expansion, all sliders/filters, saved shortcuts, actual shortcut activation and repeat suppression. Input clipping was checked at GUI scales 1 and 2.
- Visually reviewed actual menu screenshots at both scales and chunk overlays at alpha 80 and 160. Previews are saved in `docs/screenshots/chunkfinder-clickgui.png`, `chunkfinder-filters.png`, `chunkfinder-scaled.png`, and `chunkfinder-overlay.png`.
- Inspected the remapped JAR: version 1.4.0, Chunk Finder classes present, and no test/game-test classes. Detections remain heuristic and can include natural terrain. External servers, shader packs and alternate renderers remain untested.

The Freecam runtime test compares both the actual client player and integrated server player before and after camera movement, checks speed/boost/vertical movement and mouse look, verifies action queues are cleared, and checks that disabling restores normal input without undoing a server teleport. It also checks disconnect cleanup and clicks the Freecam GUI controls. Configuration tests cover persistence of speed/favorites and deliberately non-persistent activation.

## Verified on 2026-10-05 (1.3.1 categories)

- `build runClientGameTest --offline`: passed; all 24 unit tests and all six Minecraft runtime suites passed.
- Verified AutoBuilder appears only under Blatant and its Favorites entry, while Visual contains the finders and Freecam. Category fades, scoped search, keybind capture, and AutoBuilder popup controls remain working.
- Visually reviewed the new sidebar and Blatant card in Minecraft. Updated `docs/screenshots/autobuilder-clickgui.png` and verified the installable JAR reports version 1.3.1 with no test classes.

## Verified on 2026-10-05 (1.3.0 AutoBuilder)

- `build runClientGameTest --offline`: passed on Windows with JDK 21, Minecraft 1.21.11, Fabric Loader 0.18.4, and Fabric API 0.141.4+1.21.11. All 24 unit tests and all six Minecraft runtime suites passed.
- Loader tests cover compressed/uncompressed `.litematic`, multiple signed-size regions, packed entries crossing 64-bit boundaries, block-entity validation, identical/conflicting overlaps including chests with different region origins, malformed palettes/properties/lengths, data-version and volume bounds, and a 65,536-block file.
- Material tests cover exact states, partial double slabs, stacked candles, paired/orphan blocks, hotbar versus inventory, offhand exclusion, and genuinely unloaded chunks. Incremental scans match synchronous results, obey cell budgets, publish only completed snapshots, and cancel on context changes or disconnect.
- GUI tests cover the fourth module and its real keybind, favorites, valid/invalid library selection and Refresh, previous/next, coordinates, rotation/mirroring, drag/zoom, popup fades and modal input, search, Creative access and capacity, and production ghost-preview controls. Visually reviewed actual screenshots at GUI scales 1 and 2; representative images are in `docs/screenshots/`.
- Production placement tests confirm server states for ordinary blocks, double/top slabs, directional and upside-down stairs, snow layers, repeater delay, comparator mode, redstone wire, doors, beds, and tall plants. A cancel/reselect regression verifies predicted secondary cells do not count before acknowledgment.
- Navigation tests walk around an obstacle, replan after a server teleport, place an elevated sand block on a support column/access step, and track lateral supports. Menu/Freecam pauses restore input, missing materials pause clearly, Adventure placements remain rejected with zero progress, and disconnect stops construction.
- Inspected the remapped release JAR: version 1.3.0, Minecraft 1.21.11, AutoBuilder classes present, and no game-test/test classes.
- Multiplayer packet behavior was exercised against Minecraft's integrated server. External servers, third-party renderers, and shader packs remain untested. Placement and navigation limits are documented in `AUTOBUILDER.md`.

## Verified on 2026-10-04 (1.2.1 animations)

- `build runClientGameTest --offline`: passed; all 15 configuration tests and all three Minecraft runtime suites passed.
- Verified intermediate screen/content/color-editor fade states, delayed close removal, smooth reversal of an in-progress settings expansion, and settled card heights.
- Verified the actual Right Shift shortcut closes and immediately reopens the GUI, and module shortcuts remain usable after a fade-out. Screen removal occurs during Minecraft's input tick to preserve input suppression without discarding fresh presses.
- Existing settings, color/filter groups, favorites, search, keybind capture, persistence, Freecam, and finder rendering checks remain passing.
- Visually reviewed the 12px favorite stars aligned with the switches and the smaller 85% keybind text inside outlined boxes at GUI scale 2. Updated screenshots in `docs/screenshots/`.

## Verified on 2026-10-04 (1.2.0 compact GUI)

- `build runClientGameTest --offline`: passed with JDK 21; all 15 configuration tests and all three Minecraft runtime suites completed successfully.
- Verified right-click expands and collapses inline settings without changing activation or saving unchanged configuration. Left-click module headers and switches toggle independently.
- Verified multiple open cards, compact rendering controls, save callbacks, favorites/search, collapsible color/filter groups, and modal color editing.
- Verified scrolled filter controls, viewport input clipping, and opening/collapsing cards at GUI scale 2. Existing keyboard/mouse binding capture, persistence, Freecam, and finder rendering tests also passed.
- Visually reviewed the actual in-game compact overview and expanded settings screenshots, saved in `docs/screenshots/clickgui.png` and `docs/screenshots/clickgui-expanded.png`.
- Confirmed the installable jar reports version 1.2.0 and contains no game-test classes. Third-party renderers and shader packs were not tested.

## Verified on 2026-09-27

- `build`: passed; all 15 configuration tests passed.
- `runClientGameTest`: all three runtime suites passed (finders, Freecam, and module keybinds).
- Assigned keys to all three modules through the production click GUI; verified keyboard/mouse capture, Escape cancellation (including key repeat), Delete/Backspace clearing, `options.txt` persistence, and reloading through Minecraft Options.
- Verified real shortcuts toggle each module, native key repeats do not retoggle a held binding, menu input does not replay after closing, and holding Sprint across menu closure does not block shortcuts.
- Reviewed captured GUI screenshots at GUI scales 1 and 2, including the scaled binding-capture control. Rounded edges and icons use antialiased geometry.
- Reviewed the names-only array list at both scales and confirmed F1 hides it. All-disabled state was also captured.
- Updated representative screenshots in `docs/screenshots/`. Third-party renderers and shader packs were not tested.

## Verified on 2026-09-23

- `build`: passed; all 15 configuration tests passed, including Freecam settings persistence, disabled activation on load, and numeric sanitization.
- `runClientGameTest`: both the finder and Freecam runtime suites passed on Windows with Minecraft 1.21.11, Fabric Loader 0.18.4 and Fabric API 0.141.4+1.21.11.
- GUI module switches, mode selection, opacity input, save callbacks, and Freecam speed/boost sliders passed assertions.
- All seven target types and dropper filtering passed loaded-world assertions.
- Captured all six combinations of render mode and depth policy. Visual review confirmed outlines, fill, and wall occlusion. Representative screenshots are in `docs/screenshots/`.
- Freecam forward, sprint-boosted, and vertical movement passed while both the actual client player and integrated-server player retained their positions and rotations. Real mouse input and direct camera rotation also preserved both player poses.
- Freecam interaction queues were cleared; disabling restored the original player input and camera. A server teleport received during Freecam remained authoritative after disabling, and disconnect cleanup reset activation.
- External multiplayer servers, third-party shader packs, and alternate renderers have not been tested.

## Gameplay checks

Use a separate creative singleplayer world with commands enabled:

1. Open the Donut title-screen button and check the compact two-column module grid, sidebar and typography. Right-click each header to expand/collapse its settings without changing activation; left-click the header or switch to toggle the module. Verify keybinds and stars keep their separate actions. Enter a world and open the GUI with Right Shift.
2. Place a chest, double chest, trapped chest, ender chest, regular and colored shulker boxes, dropper, monster spawner and trial spawner. Dispensers should not match the dropper filter.
3. Enable each finder independently; switch between Outline, Filled and Both. Verify the outlines track the block shapes while walking and turning the camera.
4. Place an opaque wall between the player and targets. Toggle Through walls to compare rendering with and without depth testing.
5. Set each opacity to zero and full, and compare line widths. Change base and family colors using presets, RGB sliders and hex entry.
6. Turn each filter off and confirm only the corresponding blocks disappear. Break a highlighted block; its highlight should disappear immediately.
7. Reduce range and walk across its boundary. Test distance fading, unload chunks, and change dimension/world; previous-world targets should disappear.
8. Expand multiple cards, scroll all settings, use favorites/search, edit the accent and HUD setting, close/reopen, and restart the game to check persistence. Check the compact Colors and Block filters groups. Test another window size and GUI scale, including clicks near the clipped top/bottom of the module viewport.
9. Assign each module's key in the click GUI and confirm the same binding appears in Minecraft Controls. Test keyboard and mouse bindings, Escape to cancel, Delete/Backspace to clear, conflicts, and persistence after restarting. Capturing a binding must not toggle its module; shortcuts must stay inactive in chat and menus.
10. Enable the finder modules and Freecam, then check the array list at the top right: names only, longest first, with soft shadows and no solid box or counters. AutoBuilder appears while construction runs. Disable modules and the Array list appearance option, and check F1 hides the HUD.
11. Inspect rounded corners, favorite/search/module icons, toggle movement, and scrolling at GUI scales 1 and 2 and a smaller window. Test with the intended shader/rendering mods separately if using them.
12. Add ordinary and multi-region `.litematic` files through AutoBuilder's Open Folder action. Test selection, Refresh, previous/next, model rotation/zoom, world coordinates, rotation/mirror, ghost visibility, and Cancel Preview. A malformed file should show its reason and retain the last valid selection.
13. Compare material totals with single/double slabs, beds/doors/plants, hotbar and inventory stacks, partial builds, and unloaded chunks. In Creative, fill occupied inventory slots and verify Get Materials respects remaining capacity. Check the table remains responsive during a large schematic's Updating scan.
14. Build a small structure, directional blocks, and redstone states on a clear floor. Repeat with an obstacle, an elevated target, and top slab/stair side supports. Verify pause/resume for menus, Freecam, missing materials, and rejected placements. Confirm temporary supports remain reported for removal. Test your intended multiplayer server separately.
15. In an unlit room without Night Vision, toggle Fullbright and verify gamma 1000 makes the room visible. Disable it to restore normal brightness. Change the ordinary brightness setting while enabled, save/reload options, and confirm disabling uses that setting without saving gamma 1000 to `options.txt`.

Only checks actually executed are reported in the delivery message; this checklist is not a claim that every renderer/mod combination has been tested.
