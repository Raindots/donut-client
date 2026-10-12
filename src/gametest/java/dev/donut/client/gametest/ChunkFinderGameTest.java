package dev.donut.client.gametest;

import dev.donut.client.chunkfinder.ChunkFinderScanner;
import dev.donut.client.chunkfinder.EvidenceKind;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.ChunkFinderSettings;
import dev.donut.client.finder.FinderScanner;
import dev.donut.client.render.HighlightRenderer;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;

/** Loaded terrain fixtures exercise thresholds, natural geodes, filters and radius changes. */
public final class ChunkFinderGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        ClientConfig config = new ClientConfig();
        config.chunkFinder.simulationDistance = 4;
        config.chunkFinder.sensitivity = 1;
        ChunkFinderScanner scanner = new ChunkFinderScanner(config);
        AtomicBoolean active = new AtomicBoolean(true);
        HighlightRenderer renderer = context.computeOnClient(client -> {
            HighlightRenderer created = new HighlightRenderer(config, new FinderScanner(config), scanner);
            created.register();
            ClientTickEvents.END_CLIENT_TICK.register(mc -> {
                if (active.get()) scanner.tick(mc);
            });
            return created;
        });
        context.getInput().resizeWindow(1280, 800);

        try {
            try (var world = context.worldBuilder().create()) {
                createFixtures(world.getServer());
                context.waitTicks(5);
                world.getClientWorld().waitForChunksRender();
                context.waitFor(client -> client.player != null && client.player.onGround());
                context.runOnClient(client -> require(scanner.snapshot().isEmpty(),
                        "Disabled Chunk Finder scanned loaded fixtures"));

                context.runOnClient(client -> config.chunkFinder.enabled = true);
                awaitChunks(context, scanner, Set.of("1,0", "3,0"));
                context.runOnClient(client -> scanner.snapshot().forEach(found -> {
                    require(found.score() >= 12, "Low sensitivity accepted insufficient evidence");
                    require(found.minY() <= 65 && found.maxY() >= 65,
                            "Detected chunk bounds omitted the evidence height");
                }));

                context.runOnClient(client -> config.chunkFinder.sensitivity = 5);
                awaitChunks(context, scanner, Set.of("0,0", "1,0", "3,0"));
                context.runOnClient(client -> require(scanner.snapshot().stream()
                                .filter(found -> found.chunkX() == 0 && found.chunkZ() == 0)
                                .allMatch(found -> found.score() == 4),
                        "A single amethyst cluster had the wrong activity score"));
                world.getServer().runCommand("tp @a 24.5 65 28.5 180 10");
                context.waitFor(client -> client.player != null && client.player.getZ() > 28);
                awaitChunks(context, scanner, Set.of("0,0", "1,0", "3,0"));
                screenshot(context, "chunkfinder-04-world-alpha80");
                context.runOnClient(client -> config.chunkFinder.alpha = 160);
                context.waitTicks(2);
                screenshot(context, "chunkfinder-05-world-alpha160");
                context.runOnClient(client -> config.chunkFinder.alpha = 80);
                world.getServer().runCommand("tp @a 0.5 65 8.5 180 15");
                context.waitFor(client -> client.player != null && client.player.getX() < 1);

                context.runOnClient(client -> config.chunkFinder.amethyst = false);
                awaitChunks(context, scanner, Set.of());
                context.runOnClient(client -> {
                    config.chunkFinder.amethyst = true;
                    config.chunkFinder.simulationDistance = 2;
                });
                awaitChunks(context, scanner, Set.of("0,0", "1,0"));

                // Smart Adjustment suppresses bare geode blocks; turning it off includes that signal.
                context.runOnClient(client -> config.chunkFinder.smartAdjustment = false);
                awaitChunks(context, scanner, Set.of("0,0", "1,0", "2,0"));
                context.runOnClient(client -> config.chunkFinder.smartAdjustment = true);
                awaitChunks(context, scanner, Set.of("0,0", "1,0"));

                // A loaded chunk must lose its highlight after its evidence is removed.
                world.getServer().runCommand("setblock 1 65 1 minecraft:air");
                context.waitTicks(3);
                awaitChunks(context, scanner, Set.of("1,0"));
                context.runOnClient(client -> {
                    scanner.clear();
                    require(scanner.snapshot().isEmpty(), "Explicit clear retained stale chunk findings");
                });
                awaitChunks(context, scanner, Set.of("1,0"));
                context.runOnClient(client -> config.chunkFinder.enabled = false);
                context.waitTick();
                context.runOnClient(client -> require(scanner.snapshot().isEmpty() && scanner.scannedChunkCount() == 0,
                        "Disabling Chunk Finder did not clear its scan state"));
                context.runOnClient(client -> config.chunkFinder.enabled = true);
                awaitChunks(context, scanner, Set.of("1,0"));

                world.getServer().runCommand("tp @a 112.5 65 8.5");
                context.waitFor(client -> client.player != null && client.player.getX() > 110);
                awaitChunks(context, scanner, Set.of());
                world.getServer().runCommand("tp @a 0.5 65 8.5");
                context.waitFor(client -> client.player != null && client.player.getX() < 1);
                awaitChunks(context, scanner, Set.of("1,0"));
                testEveryIndicator(context, world.getServer(), config, scanner);
                context.runOnClient(client -> scanner.clear());
                for (int tick = 0; tick < 120; tick++) {
                    double x = (tick & 1) == 0 ? 15.5 : 16.5;
                    context.runOnClient(client -> client.player.setPos(x, 65, 8.5));
                    context.waitTick();
                }
                context.runOnClient(client -> require(scanner.snapshot().size() == 1 && scanner.snapshot().getFirst().chunkX() == 1,
                        "Crossing a chunk boundary each tick prevented incremental scan progress"));
                awaitChunks(context, scanner, Set.of("1,0"));
            }
            context.waitFor(client -> scanner.snapshot().isEmpty());
        } finally {
            active.set(false);
            context.runOnClient(client -> {
                scanner.clear();
                renderer.close();
            });
        }
    }

    private static void createFixtures(TestServerContext server) {
        server.runCommand("gamemode creative @a");
        server.runCommand("time set noon");
        server.runCommand("gamerule minecraft:random_tick_speed 0");
        server.runCommand("fill 0 64 0 52 64 36 minecraft:smooth_stone");
        for (int x : new int[] {1, 17, 18, 19, 49, 50, 51}) {
            server.runCommand("setblock " + x + " 65 1 minecraft:amethyst_cluster[facing=up]");
        }
        server.runCommand("fill 33 65 1 35 65 3 minecraft:amethyst_block");
        server.runCommand("tp @a 0.5 65 8.5 180 15");
    }

    private static void awaitChunks(ClientGameTestContext context, ChunkFinderScanner scanner, Set<String> expected) {
        context.waitTick();
        context.waitFor(client -> scanner.completedPasses() > 0 && scanner.snapshot().stream()
                .map(found -> found.chunkX() + "," + found.chunkZ()).collect(Collectors.toSet()).equals(expected));
        context.runOnClient(client -> require(scanner.snapshot().size() == expected.size(),
                "Chunk Finder returned duplicate findings for one chunk"));
    }

    private static void testEveryIndicator(ClientGameTestContext context, TestServerContext server,
                                          ClientConfig config, ChunkFinderScanner scanner) {
        server.runCommand("fill 0 65 0 52 70 36 minecraft:air");
        server.runCommand("setblock 17 65 1 minecraft:amethyst_cluster[facing=up]");
        server.runCommand("setblock 18 65 1 minecraft:amethyst_cluster[facing=up]");
        server.runCommand("fill 16 65 2 19 66 4 minecraft:glass");
        server.runCommand("fill 17 65 3 18 66 3 minecraft:water");
        server.runCommand("setblock 17 65 3 minecraft:kelp[age=0]");
        server.runCommand("setblock 18 65 3 minecraft:kelp[age=0]");
        server.runCommand("fill 17 69 5 18 69 5 minecraft:stone");
        server.runCommand("setblock 17 68 5 minecraft:cave_vines[age=0,berries=true]");
        server.runCommand("setblock 18 68 5 minecraft:cave_vines[age=0,berries=true]");
        server.runCommand("fill 17 65 7 17 66 7 minecraft:stone_bricks");
        server.runCommand("setblock 18 65 7 minecraft:vine[west=true]");
        server.runCommand("setblock 18 66 7 minecraft:vine[west=true]");
        server.runCommand("fill 17 64 9 18 64 9 minecraft:dirt");
        server.runCommand("setblock 17 65 9 minecraft:bamboo[age=0,leaves=none,stage=0]");
        server.runCommand("setblock 18 65 9 minecraft:bamboo[age=0,leaves=none,stage=0]");
        server.runCommand("fill 17 65 11 17 66 11 minecraft:jungle_log");
        server.runCommand("setblock 18 65 11 minecraft:cocoa[age=2,facing=west]");
        server.runCommand("setblock 18 66 11 minecraft:cocoa[age=2,facing=west]");
        server.runCommand("setblock 20 65 13 minecraft:bee_nest[honey_level=5]");
        context.waitTicks(5);
        context.runOnClient(client -> {
            config.chunkFinder.simulationDistance = 2;
            config.chunkFinder.sensitivity = 5;
            selectIndicator(config.chunkFinder, null);
            scanner.clear();
        });
        awaitChunks(context, scanner, Set.of("1,0"));
        context.runOnClient(client -> require(scanner.snapshot().getFirst().kinds().equals(EnumSet.allOf(EvidenceKind.class)),
                "The mixed chunk fixture did not detect all seven indicator kinds"));

        for (EvidenceKind kind : EvidenceKind.values()) {
            context.runOnClient(client -> selectIndicator(config.chunkFinder, kind));
            awaitChunks(context, scanner, Set.of("1,0"));
            context.runOnClient(client -> require(scanner.snapshot().getFirst().kinds().equals(Set.of(kind)),
                    "Selecting " + kind + " did not exclude other indicator kinds"));
            context.runOnClient(client -> disableIndicators(config.chunkFinder));
            context.waitTicks(2);
            context.runOnClient(client -> require(scanner.snapshot().isEmpty() && scanner.scannedChunkCount() == 0,
                    "Disabling the final " + kind + " filter retained findings or continued scanning"));
        }
        context.runOnClient(client -> selectIndicator(config.chunkFinder, null));
        awaitChunks(context, scanner, Set.of("1,0"));
    }

    private static void selectIndicator(ChunkFinderSettings settings, EvidenceKind kind) {
        settings.kelp = kind == null || kind == EvidenceKind.KELP;
        settings.caveVines = kind == null || kind == EvidenceKind.CAVE_VINES;
        settings.vines = kind == null || kind == EvidenceKind.VINES;
        settings.amethyst = kind == null || kind == EvidenceKind.AMETHYST;
        settings.bamboo = kind == null || kind == EvidenceKind.BAMBOO;
        settings.cocoa = kind == null || kind == EvidenceKind.COCOA;
        settings.beeNest = kind == null || kind == EvidenceKind.BEE_NEST;
    }

    private static void disableIndicators(ChunkFinderSettings settings) {
        settings.kelp = settings.caveVines = settings.vines = settings.amethyst = false;
        settings.bamboo = settings.cocoa = settings.beeNest = false;
    }

    private static void screenshot(ClientGameTestContext context, String name) {
        context.takeScreenshot(TestScreenshotOptions.of(name).withSize(1280, 800));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
