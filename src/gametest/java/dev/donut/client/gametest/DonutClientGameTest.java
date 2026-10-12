package dev.donut.client.gametest;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.RenderMode;
import dev.donut.client.finder.FinderScanner;
import dev.donut.client.finder.FoundBlock;
import dev.donut.client.finder.TargetKind;
import dev.donut.client.gui.DonutScreen;
import dev.donut.client.render.HighlightRenderer;
import java.util.EnumSet;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;

/** Exercises real GUI input, loaded block entities, and every overlay pipeline in a disposable flat world. */
public final class DonutClientGameTest implements FabricClientGameTest {
    private static final EnumSet<TargetKind> ALL_KINDS = EnumSet.allOf(TargetKind.class);

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientConfig config = new ClientConfig();
        config.storage.distanceFade = false;
        config.spawner.distanceFade = false;
        FinderScanner scanner = new FinderScanner(config);
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicInteger saves = new AtomicInteger();
        HighlightRenderer renderer = context.computeOnClient(client -> {
            client.options.guiScale().set(1);
            HighlightRenderer created = new HighlightRenderer(config, scanner);
            created.register();
            ClientTickEvents.END_CLIENT_TICK.register(mc -> {
                if (active.get()) scanner.tick(mc);
            });
            return created;
        });
        context.getInput().resizeWindow(1280, 800);

        try (var world = context.worldBuilder().create()) {
            createFixtures(world.getServer());
            context.waitTicks(5);
            world.getClientWorld().waitForChunksRender();
            context.waitFor(client -> client.player != null && Math.abs(client.player.getY() - 65) < 0.1);
            context.runOnClient(client -> require(scanner.snapshot().isEmpty(), "Disabled modules must not find targets"));
            screenshot(context, "donut-00-fixtures-disabled");

            // Use the production screen and mouse event path; all changes stay in the test's config.
            GuiTestInput.openAnimated(context, () -> new DonutScreen(config, saves::incrementAndGet));
            require(!GuiTestInput.visible(context, "m0.modeOUTLINE"), "Module settings must start collapsed");
            GuiTestInput.assertExpansionReverses(context, 0);
            GuiTestInput.rightClick(context, "module0");
            require(GuiTestInput.visible(context, "m0.modeOUTLINE"), "Right-click did not open inline settings");
            context.runOnClient(client -> require(!config.storage.enabled,
                    "Opening settings must not activate the module"));
            require(saves.get() == 0, "Opening settings must not save an unchanged config");
            GuiTestInput.rightClick(context, "module0");
            require(!GuiTestInput.visible(context, "m0.modeOUTLINE"), "Repeated right-click did not collapse settings");
            context.runOnClient(client -> require(!config.storage.enabled,
                    "Collapsing settings must not activate the module"));
            GuiTestInput.click(context, "module0"); // Clicking the card name toggles its module.
            GuiTestInput.click(context, "enable1"); // The explicit switch works independently.
            require(!GuiTestInput.visible(context, "m0.modeOUTLINE"), "Left-clicking a card must keep settings collapsed");
            context.runOnClient(client -> require(config.storage.enabled && config.spawner.enabled,
                    "Module toggles did not update the supplied config"));
            require(saves.get() >= 2, "Changing module toggles must invoke the save callback");
            awaitKinds(context, scanner, ALL_KINDS);
            screenshot(context, "donut-01-clickgui-modules");

            GuiTestInput.rightClick(context, "module0");
            GuiTestInput.rightClick(context, "module1");
            require(GuiTestInput.visible(context, "m0.modeOUTLINE")
                            && GuiTestInput.visible(context, "m1.modeOUTLINE"),
                    "Opening a second card must preserve the first card's inline settings");
            int savesBeforeSettings = saves.get();
            GuiTestInput.click(context, "m0.modeOUTLINE");
            context.runOnClient(client -> require(config.storage.mode == RenderMode.OUTLINE,
                    "Render mode button did not select Outline"));
            GuiTestInput.click(context, "m0.outlineOpacity"); // Slider midpoint is 50%.
            context.runOnClient(client -> require(Math.abs(config.storage.outlineOpacity - 0.5) < 0.02,
                    "Opacity slider did not update the setting"));
            require(saves.get() >= savesBeforeSettings + 2, "Changing inline settings must invoke the save callback");
            screenshot(context, "donut-02-clickgui-storage-settings");
            GuiTestInput.rightClick(context, "module1");

            // Favorites and search still operate on compact cards without changing module activation.
            GuiTestInput.click(context, "favorite0");
            GuiTestInput.clickWithFade(context, "tab1", "contentProgress");
            context.runOnClient(client -> require(config.storage.favorite, "The card star did not update favorites"));
            require(GuiTestInput.visible(context, "module0") && !GuiTestInput.visible(context, "module1"),
                    "Favorites must display only starred modules");
            GuiTestInput.click(context, "tab0");
            GuiTestInput.click(context, "search");
            GuiTestInput.type(context, "camera");
            require(GuiTestInput.visible(context, "module2") && !GuiTestInput.visible(context, "module0")
                            && !GuiTestInput.visible(context, "module1"),
                    "Search must filter modules by their descriptive keywords");
            GuiTestInput.click(context, "tab0"); // Return to the complete module list and clear search.

            // Small GUI scaling must preserve clipping and reach settings below the viewport.
            context.runOnClient(client -> {
                client.options.guiScale().set(2);
                client.resizeDisplay();
            });
            GuiTestInput.settle(context);
            // Tab changes may preserve open cards; only open this one if it is collapsed.
            if (!GuiTestInput.visible(context, "m0.modeOUTLINE")) GuiTestInput.rightClick(context, "module0");
            GuiTestInput.assertContentClipped(context);
            int savesBeforeOutsideClick = saves.get();
            GuiTestInput.clickBelowContent(context);
            require(saves.get() == savesBeforeOutsideClick, "Clicking below the content clip changed a setting");
            GuiTestInput.click(context, "m0.groupColors");
            GuiTestInput.clickWithFade(context, "m0.colorBase highlight", "modalProgress");
            require(!GuiTestInput.visible(context, "module0"), "The color editor must block underlying cards");
            int savesBeforeColor = saves.get();
            GuiTestInput.click(context, "swatch2");
            context.runOnClient(client -> require(config.storage.color == 0x6DDAC5,
                    "The compact color control did not apply its palette selection"));
            require(saves.get() > savesBeforeColor, "Color changes must invoke the save callback");
            GuiTestInput.click(context, "colorDone");
            GuiTestInput.click(context, "m0.groupColors");
            GuiTestInput.scrollTop(context);
            GuiTestInput.click(context, "m0.groupFilters");
            require(!GuiTestInput.visible(context, "m0.droppers"), "The long settings card did not overflow the viewport");
            GuiTestInput.click(context, "m0.droppers");
            context.runOnClient(client -> require(!config.storage.droppers,
                    "A scrolled setting did not accept input at GUI scale 2"));
            GuiTestInput.assertContentClipped(context);
            GuiTestInput.click(context, "m0.droppers");
            GuiTestInput.click(context, "m0.groupFilters");
            GuiTestInput.scrollTop(context);
            screenshot(context, "donut-02b-clickgui-expanded-scaled");
            GuiTestInput.rightClick(context, "module0");
            if (GuiTestInput.visible(context, "m1.modeOUTLINE")) GuiTestInput.rightClick(context, "module1");
            require(!GuiTestInput.visible(context, "m0.modeOUTLINE"), "Scaled header did not collapse its card");
            screenshot(context, "donut-02c-clickgui-collapsed-scaled");
            context.runOnClient(client -> {
                client.options.guiScale().set(1);
                client.resizeDisplay();
            });
            GuiTestInput.settle(context);
            GuiTestInput.closeAnimated(context);

            // Confirm a user filter changes the actual loaded-entity results.
            context.runOnClient(client -> config.storage.droppers = false);
            EnumSet<TargetKind> withoutDropper = EnumSet.copyOf(ALL_KINDS);
            withoutDropper.remove(TargetKind.DROPPER);
            awaitKinds(context, scanner, withoutDropper);
            context.runOnClient(client -> {
                config.storage.droppers = true;
                config.storage.outlineOpacity = 0.95;
                config.spawner.outlineOpacity = 0.95;
                config.storage.fillOpacity = 0.28;
                config.spawner.fillOpacity = 0.28;
                config.storage.lineWidth = 3;
                config.spawner.lineWidth = 3;
            });
            awaitKinds(context, scanner, ALL_KINDS);
            screenshot(context, "donut-03-fixtures-highlighted");

            // An opaque wall hides the right-hand fixtures, distinguishing both depth policies visually.
            world.getServer().runCommand("fill 1 65 4 8 68 4 minecraft:stone_bricks");
            context.waitTicks(3);
            world.getClientWorld().waitForChunksRender(false);
            for (RenderMode mode : RenderMode.values()) {
                for (boolean throughWalls : new boolean[] {false, true}) {
                    context.runOnClient(client -> {
                        config.storage.mode = mode;
                        config.spawner.mode = mode;
                        config.storage.throughWalls = throughWalls;
                        config.spawner.throughWalls = throughWalls;
                    });
                    context.waitTicks(2);
                    screenshot(context, "donut-04-" + mode.name().toLowerCase(Locale.ROOT)
                            + (throughWalls ? "-through-walls" : "-visible-only"));
                }
            }

            context.runOnClient(client -> {
                config.storage.enabled = false;
                config.spawner.enabled = false;
            });
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
        server.runCommand("fill -10 64 -2 10 64 16 minecraft:smooth_stone");
        server.runCommand("setblock -6 65 0 minecraft:chest");
        server.runCommand("setblock -4 65 0 minecraft:trapped_chest");
        server.runCommand("setblock -2 65 0 minecraft:ender_chest");
        server.runCommand("setblock 0 65 0 minecraft:purple_shulker_box");
        server.runCommand("setblock 2 65 0 minecraft:dropper");
        server.runCommand("setblock 4 65 0 minecraft:spawner");
        server.runCommand("setblock 6 65 0 minecraft:trial_spawner");
        server.runCommand("tp @a 0.5 65 12.5 180 12");
    }

    private static void awaitKinds(ClientGameTestContext context, FinderScanner scanner, EnumSet<TargetKind> expected) {
        context.waitFor(client -> scanner.snapshot().size() == expected.size()
                && scanner.snapshot().stream().map(FoundBlock::kind).collect(Collectors.toSet()).equals(expected));
        context.runOnClient(client -> {
            require(scanner.storageCount() == expected.stream().filter(kind -> !kind.isSpawner()).count(),
                    "Storage counter did not match loaded fixtures");
            require(scanner.spawnerCount() == expected.stream().filter(TargetKind::isSpawner).count(),
                    "Spawner counter did not match loaded fixtures");
        });
    }

    private static void screenshot(ClientGameTestContext context, String name) {
        context.takeScreenshot(TestScreenshotOptions.of(name).withSize(1280, 800));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
