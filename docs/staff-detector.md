# Staff Detector

Staff Detector is a Visual module for Minecraft Java 1.21.11. It starts disabled, supports Favorites and the active-module list, and has an unbound **Toggle Staff Detector** shortcut in Minecraft Controls and the module inspector.

## Setup

1. Join the server, open the Donut GUI with Right Shift, search **Staff Detector**, and select its tile.
2. Open **Staff list / evidence**. Add each staff member's Minecraft username or full dashed UUID. Lists are saved separately for each server address; singleplayer has its own list. Select a row and choose **Remove selected** to delete it.
3. Enable the module. A username remains **identity unverified** until the client receives a matching player profile. Configured names bind when their profiles arrive even while detection is disabled. On enabling, vanilla's retained profile history can also establish identity, but never current presence or mode. The module saves the observed UUID. An established UUID is never reassigned because someone else uses the old name. Your own UUID is excluded from observations.
4. Optionally enable **Check /msg completions**. This sends completion requests, never messages. By default it checks every 10 seconds after an eight-second session warmup, allows one outstanding request, waits up to three seconds, and needs three consecutive matching responses. Unsupported `/msg` commands are skipped. Use the saved server address consistently: different ports or host aliases have independent lists.

No staff directory, external profile service or remote logging endpoint is contacted. There is no automatic inference that a player is staff merely because they are invisible or in spectator mode.

## What observations mean

| Observation | Meaning |
| --- | --- |
| Player info present | This UUID is in the player information the server supplied. It does not establish proximity. |
| Reported spectator | The server reports this known staff member in spectator mode. It does not identify the staff member's location or camera target. |
| Recent spectator report | A spectator report was received recently, but the profile has since disappeared or its reported mode changed. The hint lasts for the configured lifetime; it does not establish their current mode, online state or proximity. |
| Nearby / Invisible nearby | A received player entity with the known UUID is currently within the configured distance of your actual player. Invisibility does not establish vanish or moderation activity. |
| Possible vanish: profile present; unlisted in tab | The profile remains known to the client but is omitted from the tab list. This differs from a player-info removal. **Vanish / tab alerts** default on and notify on this transition or first observation. It does not establish spectator mode or prove vanish. |
| Possible hidden presence | Repeated matching `/msg` responses include a known staff name while tab omits it. Plugins, stale suggestions and proxy behavior can explain this. It does not establish location, vanish or who is watching you. |
| Roster removed; presence unknown | A previously observed UUID disappeared from player info and remained absent for eight seconds. Logout, transfer and vanish are indistinguishable. Chat alerts for this weak clue default off. |
| Presence unknown | No current direct observation or unexpired hint exists. Fully filtered hidden staff can still be present. |

The top-left HUD always states **Hidden presence is unknown**. It shows up to four staff rows by default, prioritizing nearby invisible entities, reported spectators and completion discrepancies. It respects the global HUD setting, F1 and open menus. Chat notifications respect open menus and F1, have per-person/per-signal cooldowns, and send at most one message every two seconds. Name Hider redacts staff names from the HUD, chat, roster display and evidence history.

The **Evidence** view records up to 128 observations with their reason and age, independently of whether chat alerts are enabled. Completion hints expire after 30 seconds without qualifying evidence by default. An empty response, timeout, restored tab entry, disabling checks or lost queue continuity clears completion evidence. Direct spectator/entity observations update as the received state changes. Repeated observations do not repeatedly notify. History is historical evidence, not a claim that its state is still current.

## Troubleshooting vanish and spectator mode

The detector captures explicit game-mode fields from player-info packets as well as vanilla's applied player list. Vanilla ignores mode-only updates for UUIDs whose profiles have already been removed; the detector retains these reports for configured staff. A spectator update followed immediately by removal, including both in one packet bundle, remains a **recent** report in the HUD and can still produce its queued notification. Mode reports expire instead of turning into a permanent claim of online presence.

Under the module settings, **Copy detection diagnostics** copies counts for unresolved staff names, received spectator/mode updates, tab hiding and profile removals, plus completion-request status. It contains no staff names or UUIDs. These counters cover the current active world/session; mode counters describe received packet fields, not old cached profiles.

- Confirm **Staff Detector**, **Spectator alerts** and **Vanish / tab alerts** are enabled. The staff list must belong to the server address actually used to connect.
- Resolve unverified names by observing the staff profile or entering a known UUID. Hidden names that have never been supplied cannot be resolved from completion text alone.
- A positive spectator-report count means the detector received a spectator field for configured staff. A tab-hidden alert only establishes an omitted tab entry. Neither identifies who is watching you.
- If no current player profile or entity is available and no spectator reports arrive during the event, the server may be withholding every usable signal. Optional completion checks can only help if the server leaks matching suggestions. Profile removal by itself still cannot distinguish a logout from vanish.

There is no universal client-side bypass for complete vanish filtering. Some plugins also suppress tab-completion clues or simulate quit messages; see [SuperVanish's configuration](https://github.com/LeonMangler/SuperVanish/blob/master/src/main/resources/config.yml). The module does not treat those messages as authoritative departures.

## Lifecycle and bounds

Packet hooks capture immutable roster, explicit mode and completion observations after vanilla's client-thread handoff. One ordered queue holds at most 64 observations and drains on the client tick. Overflow discards oldest work and breaks completion continuity. Nearby entities are sampled every five ticks, and player info is also reconciled every ten ticks so enabling after a staff member joined works. Session, dimension and settings-object changes clear runtime observations; disable and disconnect clear pending requests, notifications and history. Saved staff identities and settings remain available. While disabled, matching incoming profiles can bind configured identities without producing detection history or alerts.

Preferences allow 64 saved server lists with up to 128 identities each. Nearby range is 8–256 blocks; increasing it only filters received entities and never requests additional entities or chunks. Completion intervals are 6–120 seconds with 2–10 required samples. Hint lifetime is 10–180 seconds and notification cooldown is 10–300 seconds. Requests use a separate negative ID range and unrelated or expired completion replies are ignored.

Sounds and particles are not used to identify staff in this version. The existing Sound Detector can inspect such activity with its own attribution and uncertainty. No client-only detector can recover staff presence when the server sends no distinguishable evidence.

## Validation

Unit tests cover identity requirements, reported mode versus proximity, listed versus retained player info, range and visibility, repeated exact-name discrepancies, missing replies/expiry, ambiguous removals, reappearance, cooldowns and storage bounds. Regressions cover recent reports surviving removal, orphan mode packets, tab hiding, alert eligibility and expiry. Native game tests use synthetic staff profiles, entity packets and completion replies sent through a disposable integrated-server connection to exercise production mixins, including a same-bundle spectator/removal transition with actual chat delivery. Historical profiles resolve identity without reviving stale spectator state. GUI checks cover native entry validation/removal, persistence, settings, shortcuts and GUI scales 1 and 2.

Run the focused suite with JDK 21:

```powershell
.\gradlew.bat build runClientGameTest -PstaffDetectorTestsOnly=true --offline --no-daemon
```

These fixtures do not establish detection rates on an external server or against a particular vanish plugin. Precision, missed detections and false alerts per hour still require server-specific ground truth.
