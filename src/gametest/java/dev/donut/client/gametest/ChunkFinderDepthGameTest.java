package dev.donut.client.gametest;

import dev.donut.client.chunkfinder.ChunkFinderScanner;
import dev.donut.client.chunkfinder.EvidenceKind;
import dev.donut.client.chunkfinder.FoundChunk;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.finder.FinderScanner;
import dev.donut.client.render.HighlightRenderer;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Actual loaded sections below zero and growth updates at unchanged block coordinates. */
public final class ChunkFinderDepthGameTest implements FabricClientGameTest {
    private static final List<Fixture> DEPTH_FIXTURES = List.of(
            new Fixture(-1, -1, 4, 2, "amethyst_cluster", Blocks.AMETHYST_CLUSTER, 8),
            new Fixture(0, -1, -2, 8, "small_amethyst_bud", Blocks.SMALL_AMETHYST_BUD, 8),
            new Fixture(1, -1, -3, 4, "medium_amethyst_bud", Blocks.MEDIUM_AMETHYST_BUD, 8),
            new Fixture(-1, 0, -16, 3, "large_amethyst_bud", Blocks.LARGE_AMETHYST_BUD, 9),
            new Fixture(0, 0, -17, 2, "amethyst_cluster", Blocks.AMETHYST_CLUSTER, 8),
            new Fixture(1, 0, -32, 8, "small_amethyst_bud", Blocks.SMALL_AMETHYST_BUD, 8),
            new Fixture(-1, 1, -48, 4, "medium_amethyst_bud", Blocks.MEDIUM_AMETHYST_BUD, 8),
            new Fixture(0, 1, -63, 2, "amethyst_cluster", Blocks.AMETHYST_CLUSTER, 8));
    private static final Fixture GROWING = new Fixture(1, 1, -33, 4,
            "small_amethyst_bud", Blocks.SMALL_AMETHYST_BUD, 4);
    private static final Fixture SINGLE = new Fixture(2, 1, -35, 1,
            "small_amethyst_bud", Blocks.SMALL_AMETHYST_BUD, 1);
    private static final int MIXED_CHUNK_X = 2;
    private static final int MIXED_CHUNK_Z = 0;
    private static final int MIXED_Y = -27;
    private static final Fixture SPLIT_LOW = new Fixture(-1, -2, -40, 2,
            "amethyst_cluster", Blocks.AMETHYST_CLUSTER, 8);
    private static final Fixture SPLIT_HIGH = new Fixture(-1, -2, -8, 2,
            "amethyst_cluster", Blocks.AMETHYST_CLUSTER, 8);

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientConfig config = new ClientConfig();
        require(config.chunkFinder.smartAdjustment && config.chunkFinder.sensitivity == 3,
                "Depth regression must exercise default Smart Adjustment and Sensitivity");
        config.chunkFinder.simulationDistance = 4;
        config.chunkFinder.kelp = false;
        config.chunkFinder.caveVines = false;
        config.chunkFinder.vines = false;
        config.chunkFinder.bamboo = false;
        config.chunkFinder.cocoa = false;
        config.chunkFinder.beeNest = false;
        ChunkFinderScanner scanner = new ChunkFinderScanner(config);
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicReference<RenderedFrame> rendered = new AtomicReference<>(new RenderedFrame(Vec3.ZERO, List.of()));
        HighlightRenderer renderer = context.computeOnClient(client -> {
            HighlightRenderer created = new HighlightRenderer(config, new FinderScanner(config), scanner);
            created.register();
            RenderStateDataKey<List<?>> frameKey = frameKey(created);
            WorldRenderEvents.END_MAIN.register(renderContext -> {
                if (!active.get()) return;
                List<?> actualBoxes = ((FabricRenderState) renderContext.worldState()).getData(frameKey);
                Vec3 camera = renderContext.worldState().cameraRenderState.pos;
                List<RenderedBox> boxes = actualBoxes == null ? List.of() : actualBoxes.stream().map(raw ->
                        new RenderedBox(((AABB) field(raw, "bounds")).move(camera),
                                (int) field(raw, "fill"), (int) field(raw, "outline"), (boolean) field(raw, "throughWalls")))
                        .toList();
                rendered.set(new RenderedFrame(camera, boxes));
            });
            ClientTickEvents.END_CLIENT_TICK.register(mc -> {
                if (active.get()) scanner.tick(mc);
            });
            return created;
        });
        context.getInput().resizeWindow(1280, 800);

        try (var world = context.worldBuilder().create()) {
            TestServerContext server = world.getServer();
            createFixtures(server);
            context.waitFor(client -> client.player != null && client.player.onGround()
                    && client.level != null && DEPTH_FIXTURES.stream().allMatch(fixture ->
                        fixtureLoaded(client.level::getBlockState, fixture, fixture.block()))
                    && fixtureLoaded(client.level::getBlockState, GROWING, Blocks.SMALL_AMETHYST_BUD)
                    && fixtureLoaded(client.level::getBlockState, SINGLE, Blocks.SMALL_AMETHYST_BUD)
                    && fixtureLoaded(client.level::getBlockState, SPLIT_LOW, Blocks.AMETHYST_CLUSTER)
                    && fixtureLoaded(client.level::getBlockState, SPLIT_HIGH, Blocks.AMETHYST_CLUSTER)
                    && client.level.getBlockState(new BlockPos(MIXED_CHUNK_X * 16 + 2, MIXED_Y, 2))
                        .is(Blocks.SMALL_AMETHYST_BUD));
            context.runOnClient(client -> {
                require(client.level.getMinY() == -64, "Depth fixture world has an unexpected lower build limit");
                require(scanner.snapshot().isEmpty(), "Disabled depth scanner returned findings");
                config.chunkFinder.enabled = true;
            });
            context.waitTick();
            context.waitFor(client -> scanner.completedPasses() > 0);
            context.runOnClient(client -> {
                Map<String, FoundChunk> results = byChunk(scanner);
                require(results.size() == DEPTH_FIXTURES.size() + 2,
                        "Default settings missed loaded amethyst below zero or a growing geode: " + results.keySet());
                for (Fixture fixture : DEPTH_FIXTURES) assertFixture(results, fixture, fixture.score());
                FoundChunk mixed = results.get(key(MIXED_CHUNK_X, MIXED_CHUNK_Z));
                require(mixed != null && mixed.score() == 10,
                        "Smart Adjustment discarded a mixed geode containing all four growth stages");
                assertAmethystBounds(mixed, MIXED_Y);
                FoundChunk split = results.get(key(SPLIT_LOW.chunkX(), SPLIT_LOW.chunkZ()));
                require(split != null && split.score() == SPLIT_LOW.score() + SPLIT_HIGH.score(),
                        "The same-chunk, separated-height fixtures lost their combined amethyst score");
                require(split.minY() == SPLIT_LOW.y() && split.maxY() == SPLIT_HIGH.y()
                                && split.layers().equals(List.of(new FoundChunk.Layer(SPLIT_LOW.y(), SPLIT_LOW.y()),
                                        new FoundChunk.Layer(SPLIT_HIGH.y(), SPLIT_HIGH.y()))),
                        "Separated amethyst evidence was merged across empty vertical space");
                require(!results.containsKey(key(GROWING.chunkX(), GROWING.chunkZ())),
                        "Four small buds bypassed the default sensitivity threshold");
                require(!results.containsKey(key(SINGLE.chunkX(), SINGLE.chunkZ())),
                        "One small bud bypassed the default sensitivity threshold");
            });
            assertBuriedChunkVisible(context, server, config, renderer, rendered);
            assertSeparateLayersVisible(context, server, config, rendered);

            for (GrowthStage stage : List.of(
                    new GrowthStage("medium_amethyst_bud", Blocks.MEDIUM_AMETHYST_BUD, 8),
                    new GrowthStage("large_amethyst_bud", Blocks.LARGE_AMETHYST_BUD, 12),
                    new GrowthStage("amethyst_cluster", Blocks.AMETHYST_CLUSTER, 16))) {
                replaceAndAwait(context, server, scanner, GROWING, stage.name(), stage.block());
                context.runOnClient(client -> assertFixture(byChunk(scanner), GROWING, stage.score()));
            }

            // Maximum sensitivity must see a lone new bud before it has matured.
            context.runOnClient(client -> config.chunkFinder.sensitivity = 5);
            context.waitTick();
            context.waitFor(client -> scanner.completedPasses() > 0);
            context.runOnClient(client -> assertSingleBud(scanner));
            for (GrowthStage stage : List.of(
                    new GrowthStage("medium_amethyst_bud", Blocks.MEDIUM_AMETHYST_BUD, 2),
                    new GrowthStage("large_amethyst_bud", Blocks.LARGE_AMETHYST_BUD, 3),
                    new GrowthStage("amethyst_cluster", Blocks.AMETHYST_CLUSTER, 4))) {
                replaceAndAwait(context, server, scanner, SINGLE, stage.name(), stage.block());
                context.runOnClient(client -> assertSingleBud(scanner));
            }
            for (GrowthStage ignored : List.of(
                    new GrowthStage("amethyst_block", Blocks.AMETHYST_BLOCK, 0),
                    new GrowthStage("budding_amethyst", Blocks.BUDDING_AMETHYST, 0))) {
                replaceAndAwait(context, server, scanner, SINGLE, ignored.name(), ignored.block());
                context.runOnClient(client -> require(!byChunk(scanner).containsKey(key(SINGLE.chunkX(), SINGLE.chunkZ())),
                        "Maximum sensitivity treated a bare " + ignored.name() + " as a growing bud"));
            }
        } finally {
            active.set(false);
            context.runOnClient(client -> {
                scanner.clear();
                renderer.close();
            });
        }
    }

    private static void assertBuriedChunkVisible(ClientGameTestContext context, TestServerContext server,
                                                ClientConfig config, HighlightRenderer renderer,
                                                AtomicReference<RenderedFrame> rendered) {
        // Keep the Y=-32 buds intact while covering their entire chunk up to the surface.
        server.runCommand("fill 16 -31 0 31 64 15 minecraft:stone");
        server.runCommand("tp @a 0.5 65 8.5 -90 80");
        context.waitFor(client -> client.player != null && client.player.onGround()
                && Math.abs(client.player.getY() - 65) < .01 && Math.abs(client.player.getYRot() + 90) < .01
                && Math.abs(client.player.getXRot() - 80) < .01
                && client.level.getBlockState(new BlockPos(20, 64, 8)).is(Blocks.STONE));
        context.waitTicks(2);
        context.waitFor(client -> {
            RenderedFrame frame = rendered.get();
            return Math.abs(frame.camera().x - .5) < .01 && Math.abs(frame.camera().z - 8.5) < .01
                    && frame.camera().y > 65 && frame.boxes().stream().anyMatch(box ->
                        isLayerBox(box, 1, 0, -32));
        });
        context.runOnClient(client -> {
            List<RenderedBox> boxes = rendered.get().boxes();
            List<RenderedBox> chunkBoxes = boxes.stream().filter(box -> isChunkBox(box, 1, 0)).toList();
            require(chunkBoxes.size() == 1 && isLayerBox(chunkBoxes.getFirst(), 1, 0, -32),
                    "Buried amethyst rendered outside its own one-block evidence layer");
            assertLayerStyle(chunkBoxes.getFirst(), config);
            require(boxes.stream().noneMatch(box -> box.bounds().getYsize() > 16),
                    "Base Finder still submitted a tall column or world-height chunk guide");
            require(field(field(renderer, "lineThrough"), "vertices") != null,
                    "The renderer did not submit the buried layer's through-wall outline to its GPU buffer");
        });
        context.takeScreenshot(TestScreenshotOptions.of("basefinder-04-buried-layer-from-surface").withSize(1280, 800));

        server.runCommand("fill -3 -29 5 3 -29 10 minecraft:smooth_stone");
        server.runCommand("tp @a 0.5 -28 8.5 -90 13");
        context.waitFor(client -> client.player != null && client.player.onGround()
                && Math.abs(client.player.getY() + 28) < .01 && Math.abs(client.player.getXRot() - 13) < .01);
        context.waitTicks(2);
        context.waitFor(client -> rendered.get().camera().y < -26
                && rendered.get().boxes().stream().anyMatch(box -> isLayerBox(box, 1, 0, -32)));
        context.takeScreenshot(TestScreenshotOptions.of("basefinder-05-buried-layer-nearby").withSize(1280, 800));
    }

    private static void assertSeparateLayersVisible(ClientGameTestContext context, TestServerContext server,
                                                    ClientConfig config, AtomicReference<RenderedFrame> rendered) {
        server.runCommand("fill -11 64 -3 -5 64 3 minecraft:smooth_stone");
        server.runCommand("tp @a -8.0 65 -0.5 180 76");
        context.waitFor(client -> client.player != null && client.player.onGround()
                && Math.abs(client.player.getY() - 65) < .01 && Math.abs(client.player.getXRot() - 76) < .01);
        context.waitTicks(2);
        context.waitFor(client -> {
            RenderedFrame frame = rendered.get();
            return Math.abs(frame.camera().x + 8) < .01 && Math.abs(frame.camera().z + .5) < .01
                    && frame.boxes().stream().filter(box -> isChunkBox(box, SPLIT_LOW.chunkX(), SPLIT_LOW.chunkZ()))
                        .count() == 2;
        });
        context.runOnClient(client -> {
            List<RenderedBox> layers = rendered.get().boxes().stream()
                    .filter(box -> isChunkBox(box, SPLIT_LOW.chunkX(), SPLIT_LOW.chunkZ())).toList();
            require(layers.stream().anyMatch(box -> isLayerBox(box, SPLIT_LOW.chunkX(), SPLIT_LOW.chunkZ(), SPLIT_LOW.y()))
                            && layers.stream().anyMatch(box -> isLayerBox(box, SPLIT_HIGH.chunkX(), SPLIT_HIGH.chunkZ(), SPLIT_HIGH.y())),
                    "Two amethyst heights in one chunk were rendered as a single tall box");
            for (RenderedBox layer : layers) assertLayerStyle(layer, config);
            require(layers.stream().noneMatch(box -> box.bounds().minY < -20 && box.bounds().maxY > -20),
                    "A layer box bridged the empty space between separated amethyst fixtures");
        });
        context.takeScreenshot(TestScreenshotOptions.of("basefinder-06-separated-layers").withSize(1280, 800));
    }

    private static void assertLayerStyle(RenderedBox box, ClientConfig config) {
        require(box.throughWalls(), "The evidence layer cannot be seen through terrain");
        require((box.outline() & 0xFFFFFF) == config.chunkFinder.color
                        && (box.outline() >>> 24) == config.chunkFinder.alpha,
                "The evidence layer did not use the configured color and outline alpha");
        require((box.fill() >>> 24) == (int) Math.round(config.chunkFinder.alpha * .06),
                "The evidence layer did not use a discreet six-percent fill alpha");
    }

    private static boolean isChunkBox(RenderedBox box, int chunkX, int chunkZ) {
        AABB bounds = box.bounds();
        return Math.abs(bounds.minX - chunkX * 16) < .001 && Math.abs(bounds.maxX - (chunkX + 1) * 16) < .001
                && Math.abs(bounds.minZ - chunkZ * 16) < .001 && Math.abs(bounds.maxZ - (chunkZ + 1) * 16) < .001;
    }

    private static boolean isLayerBox(RenderedBox box, int chunkX, int chunkZ, int y) {
        return isChunkBox(box, chunkX, chunkZ) && box.throughWalls() && (box.outline() >>> 24) > 0
                && Math.abs(box.bounds().minY - (y - .05)) < .001
                && Math.abs(box.bounds().maxY - (y + 1.05)) < .001;
    }

    @SuppressWarnings("unchecked")
    private static RenderStateDataKey<List<?>> frameKey(HighlightRenderer renderer) {
        return (RenderStateDataKey<List<?>>) field(renderer, "frame");
    }

    private static Object field(Object owner, String name) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Cannot inspect the rendered overlay field " + name, error);
        }
    }

    private static void createFixtures(TestServerContext server) {
        server.runCommand("gamemode creative @a");
        server.runCommand("gamerule minecraft:random_tick_speed 0");
        server.runCommand("fill -3 64 5 3 64 10 minecraft:smooth_stone");
        for (Fixture fixture : DEPTH_FIXTURES) placeFixture(server, fixture);
        placeFixture(server, GROWING);
        placeFixture(server, SINGLE);
        placeFixture(server, SPLIT_LOW);
        placeFixture(server, SPLIT_HIGH);
        server.runCommand("fill 34 " + (MIXED_Y - 1) + " 2 37 " + (MIXED_Y - 1) + " 2 minecraft:budding_amethyst");
        String[] stages = {"small_amethyst_bud", "medium_amethyst_bud", "large_amethyst_bud", "amethyst_cluster"};
        for (int index = 0; index < stages.length; index++) {
            server.runCommand("setblock " + (34 + index) + " " + MIXED_Y
                    + " 2 minecraft:" + stages[index] + "[facing=up]");
        }
        server.runCommand("tp @a 0.5 65 8.5");
    }

    private static void placeFixture(TestServerContext server, Fixture fixture) {
        int x = fixture.chunkX() * 16 + 2;
        int z = fixture.chunkZ() * 16 + 2;
        int endX = x + fixture.count() - 1;
        server.runCommand("fill " + x + " " + (fixture.y() - 1) + " " + z + " " + endX
                + " " + (fixture.y() - 1) + " " + z + " minecraft:smooth_stone");
        server.runCommand("fill " + x + " " + fixture.y() + " " + z + " " + endX
                + " " + fixture.y() + " " + z + " minecraft:" + fixture.name() + "[facing=up]");
    }

    private static void replaceAndAwait(ClientGameTestContext context, TestServerContext server,
                                        ChunkFinderScanner scanner, Fixture fixture, String name, Block block) {
        int x = fixture.chunkX() * 16 + 2;
        int z = fixture.chunkZ() * 16 + 2;
        String properties = name.endsWith("_bud") || name.equals("amethyst_cluster") ? "[facing=up]" : "";
        server.runCommand("fill " + x + " " + fixture.y() + " " + z + " " + (x + fixture.count() - 1)
                + " " + fixture.y() + " " + z + " minecraft:" + name + properties);
        context.waitFor(client -> client.level != null
                && fixtureLoaded(client.level::getBlockState, fixture, block));
        long before = context.computeOnClient(client -> scanner.completedPasses());
        // The first completion may belong to a pass already in progress when the update arrived.
        context.waitFor(client -> scanner.completedPasses() >= before + 2);
    }

    private static boolean fixtureLoaded(Function<BlockPos, BlockState> blocks,
                                         Fixture fixture, Block block) {
        for (int index = 0; index < fixture.count(); index++) {
            if (!blocks.apply(new BlockPos(fixture.chunkX() * 16 + 2 + index, fixture.y(), fixture.chunkZ() * 16 + 2))
                    .is(block)) return false;
        }
        return true;
    }

    private static Map<String, FoundChunk> byChunk(ChunkFinderScanner scanner) {
        return scanner.snapshot().stream().collect(Collectors.toMap(found -> key(found.chunkX(), found.chunkZ()), found -> found));
    }

    private static void assertFixture(Map<String, FoundChunk> results, Fixture fixture, int score) {
        FoundChunk found = results.get(key(fixture.chunkX(), fixture.chunkZ()));
        require(found != null, "Loaded amethyst fixture was missed at Y=" + fixture.y());
        require(found.score() == score, "Amethyst score at Y=" + fixture.y() + " was " + found.score() + "; expected " + score);
        assertAmethystBounds(found, fixture.y());
    }

    private static void assertSingleBud(ChunkFinderScanner scanner) {
        FoundChunk found = byChunk(scanner).get(key(SINGLE.chunkX(), SINGLE.chunkZ()));
        require(found != null && found.score() >= ChunkFinderScanner.thresholdForSensitivity(5),
                "Maximum sensitivity missed a single growing amethyst bud below zero");
        assertAmethystBounds(found, SINGLE.y());
    }

    private static void assertAmethystBounds(FoundChunk found, int y) {
        require(found.minY() == y && found.maxY() == y,
                "Amethyst bounds reported " + found.minY() + ".." + found.maxY() + " instead of Y=" + y);
        require(found.layers().equals(List.of(new FoundChunk.Layer(y, y))),
                "A one-block-high amethyst fixture did not remain a single tight evidence layer");
        require(found.kinds().equals(Set.of(EvidenceKind.AMETHYST)), "Amethyst fixture included an unrelated evidence kind");
    }

    private static String key(int x, int z) { return x + "," + z; }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Fixture(int chunkX, int chunkZ, int y, int count, String name, Block block, int score) { }
    private record GrowthStage(String name, Block block, int score) { }
    private record RenderedFrame(Vec3 camera, List<RenderedBox> boxes) { }
    private record RenderedBox(AABB bounds, int fill, int outline, boolean throughWalls) { }
}
