package dev.donut.client.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.ConfigStore;
import dev.donut.client.config.DonutConfigPaths;
import dev.donut.client.config.HotbarSettings;
import dev.donut.client.gui.DonutScreen;
import dev.donut.client.hotbar.HotbarBlur;
import dev.donut.client.input.ModuleKeybinds;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.CameraType;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.component.DataComponents;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import org.lwjgl.glfw.GLFW;

/** Real hotbar pixels, counts, offhand, GUI scaling and streaming controls. */
public final class HotbarGameTest implements FabricClientGameTest {
    private static final int MODULE = 9, TEST_KEY = GLFW.GLFW_KEY_F8;
    private static final int WIDTH = 1280, HEIGHT = 800;

    @Override public void runTest(ClientGameTestContext context) {
        ClientConfig config = new ClientConfig();
        AtomicInteger saves = new AtomicInteger();
        Path fixture = context.computeOnClient(client -> client.gameDirectory.toPath().resolve("config/hotbar-gametest.json"));
        ConfigStore store = new ConfigStore(fixture);
        Runnable save = () -> { saves.incrementAndGet(); store.save(config); };
        String originalBinding = context.computeOnClient(client -> ModuleKeybinds.mapping(MODULE).saveString());
        int originalScale = context.computeOnClient(client -> client.options.guiScale().get());
        boolean originalHideGui = context.computeOnClient(client -> client.options.hideGui);
        context.runOnClient(client -> {
            client.options.guiScale().set(1);
            client.options.hideGui = false;
            client.resizeDisplay();
        });
        context.getInput().resizeWindow(WIDTH, HEIGHT);
        try (var world = context.worldBuilder().create()) {
            world.getServer().runCommand("gamemode creative @a");
            world.getServer().runCommand("time set noon");
            world.getServer().runCommand("weather clear");
            world.getServer().runCommand("fill -20 64 -20 20 64 5 minecraft:light_gray_concrete");
            world.getServer().runCommand("fill -20 64 -12 20 85 -8 minecraft:light_gray_concrete");
            world.getServer().runCommand("tp @a 0.5 65 0.5 180 0");
            context.waitTicks(5);
            world.getClientWorld().waitForChunksRender();
            context.waitFor(client -> client.player != null && client.player.onGround()
                    && Math.abs(client.player.getY() - 65) < .01);
            testSettings(context, config, store, save, saves);
            populateHotbar(world.getServer());
            context.waitFor(client -> client.player.getInventory().getItem(0).getCount() == 64
                    && client.player.getInventory().getItem(7).getCount() == 53
                    && client.player.getOffhandItem().getCount() == 37);
            context.waitTicks(60); // Let the held-item name and inventory pop animation finish.
            testProduction(context, world.getServer());
        } finally {
            context.setScreen(() -> null);
            context.getInput().releaseKey(TEST_KEY);
            context.runOnClient(client -> {
                ModuleKeybinds.setKey(client, MODULE, InputConstants.getKey(originalBinding));
                client.options.guiScale().set(originalScale);
                client.options.hideGui = originalHideGui;
                client.resizeDisplay();
            });
            try { Files.deleteIfExists(fixture); }
            catch (IOException error) { throw new AssertionError("Cannot remove the Hotbar configuration fixture", error); }
        }
    }

    private static void testSettings(ClientGameTestContext context, ClientConfig config, ConfigStore store,
                                     Runnable save, AtomicInteger saves) {
        GuiTestInput.openAnimated(context, () -> new DonutScreen(config, save));
        GuiTestInput.click(context, "tab0");
        GuiTestInput.click(context, "search");
        GuiTestInput.type(context, "hotbar");
        require(!GuiTestInput.visible(context, "module9"), "Hotbar appeared in Visual modules");
        GuiTestInput.clickWithFade(context, "tab3", "contentProgress");
        GuiTestInput.click(context, "search");
        GuiTestInput.type(context, "hotbar");
        require(!GuiTestInput.visible(context, "module9"), "Hotbar appeared in Blatant modules");
        GuiTestInput.clickWithFade(context, "tab4", "contentProgress");
        GuiTestInput.assertModuleRows(context, 7, 8, 9);
        GuiTestInput.assertSelectionAnimation(context, MODULE);
        GuiTestInput.rightClick(context, "module9");
        require(!config.hotbar.enabled && saves.get() == 0, "Opening Hotbar settings activated or saved the module");
        GuiTestInput.click(context, "enable9");
        GuiTestInput.click(context, "m9.hideHeldItems");
        require(config.hotbar.hideHeldItems && store.load().hotbar.hideHeldItems,
                "The held-item privacy setting did not save");
        setBlurSlider(context, 35);
        require(config.hotbar.blurPercent == 35 && store.load().hotbar.blurPercent == 35,
                "The Hotbar blur slider did not select and save 35%");
        setBlurSlider(context, 90);
        require(config.hotbar.blurPercent == 90 && store.load().hotbar.blurPercent == 90,
                "The Hotbar blur slider did not restore its default 90%");
        GuiTestInput.click(context, "favorite9");
        require(config.hotbar.enabled && store.load().hotbar.enabled && store.load().hotbar.favorite,
                "Hotbar activation and favorite settings did not save");
        GuiTestInput.clickWithFade(context, "tab1", "contentProgress");
        require(GuiTestInput.visible(context, "module9") && !GuiTestInput.visible(context, "module7")
                        && !GuiTestInput.visible(context, "module8"), "Hotbar did not appear alone in Favorites");
        GuiTestInput.clickWithFade(context, "tab4", "contentProgress");
        GuiTestInput.click(context, "search");
        GuiTestInput.type(context, "hotbar");
        require(GuiTestInput.visible(context, "module9") && !GuiTestInput.visible(context, "module7")
                        && !GuiTestInput.visible(context, "module8"), "Streaming search did not isolate Hotbar");
        int beforeBinding = saves.get();
        GuiTestInput.click(context, "keybind9");
        context.getInput().pressKey(TEST_KEY);
        context.runOnClient(client -> require(ModuleKeybinds.mapping(MODULE).saveString().equals("key.keyboard.f8"),
                "Hotbar did not capture its shortcut"));
        require(config.hotbar.enabled && saves.get() == beforeBinding, "Shortcut capture toggled or saved Hotbar settings");
        context.runOnClient(client -> { client.options.guiScale().set(2); client.resizeDisplay(); });
        GuiTestInput.settle(context);
        setBlurSlider(context, 65);
        require(config.hotbar.blurPercent == 65 && store.load().hotbar.blurPercent == 65,
                "The scaled Hotbar blur slider did not select and save 65%");
        setBlurSlider(context, 90);
        require(config.hotbar.blurPercent == 90 && store.load().hotbar.blurPercent == 90,
                "The scaled Hotbar blur slider did not restore 90%");
        setBlurSlider(context, 140);
        require(config.hotbar.blurPercent == 140 && store.load().hotbar.blurPercent == 140,
                "The extended Hotbar slider did not select and save 140%");
        GuiTestInput.assertContentClipped(context);
        screenshot(context, "hotbar-01-streaming-settings");
        GuiTestInput.closeAnimated(context);
    }

    private static void populateHotbar(TestServerContext server) {
        String[] items = {"diamond 64", "golden_apple 47", "redstone 32", "tnt 16",
                "enchanted_book[enchantment_glint_override=true] 1", "ender_pearl 15",
                "oak_sapling 7", "cooked_beef 53", "potion 1"};
        for (int index = 0; index < items.length; index++) {
            server.runCommand("item replace entity @a hotbar." + index + " with minecraft:" + items[index]);
        }
        server.runCommand("item replace entity @a weapon.offhand with minecraft:emerald 37");
    }

    private static void testProduction(ClientGameTestContext context, TestServerContext server) {
        KeyMapping open = context.computeOnClient(client -> Arrays.stream(client.options.keyMappings)
                .filter(key -> key.getName().equals("key.donut.open_gui")).findFirst().orElseThrow());
        context.waitTicks(4);
        context.getInput().pressKey(open);
        context.waitForScreen(DonutScreen.class);
        GuiTestInput.settle(context);
        ClientConfig config = context.computeOnClient(client -> (ClientConfig) field(client.screen, "config"));
        ConfigStore store = new ConfigStore(DonutConfigPaths.resolve(FabricLoader.getInstance().getConfigDir()).active());
        boolean enabled = config.hotbar.enabled, showHud = config.showHud, progress = config.progressBar.enabled;
        int originalBlurPercent = config.hotbar.blurPercent;
        try {
            context.setScreen(() -> null);
            context.runOnClient(client -> {
                config.hotbar.enabled = false;
                config.hotbar.blurPercent = 90;
                config.showHud = false;
                config.progressBar.enabled = false;
                client.options.hideGui = false;
            });
            for (int scale = 1; scale <= 2; scale++) {
                int currentScale = scale;
                context.runOnClient(client -> {
                    client.options.guiScale().set(currentScale);
                    client.resizeDisplay();
                });
                context.waitTicks(4);
                Path off = screenshot(context, "hotbar-scale" + scale + "-02-disabled");
                context.getInput().holdKey(TEST_KEY);
                context.waitTicks(5);
                require(config.hotbar.enabled && store.load().hotbar.enabled,
                        "The production shortcut did not enable and save Hotbar");
                context.runOnClient(client -> KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(TEST_KEY)));
                context.waitTicks(2);
                require(config.hotbar.enabled, "A repeated held shortcut toggled Hotbar again");
                context.getInput().releaseKey(TEST_KEY);
                context.waitFor(client -> allocated("hotbar"));
                Path on = screenshot(context, "hotbar-scale" + scale + "-03-blurred");
                assertBlur(off, on, scale);
                assertBlurAmounts(context, config, off, on, scale);

                context.runOnClient(client -> config.hotbar.blurPercent = 90);
                context.getInput().pressKey(TEST_KEY);
                context.waitTicks(3);
                require(!config.hotbar.enabled && !store.load().hotbar.enabled,
                        "The second production shortcut did not disable and save Hotbar");
                assertReleased(context);
                Path restored = screenshot(context, "hotbar-scale" + scale + "-04-restored");
                require(changedPixels(read(off), read(restored), WIDTH / 2 - 120 * scale,
                                HEIGHT - 24 * scale, WIDTH / 2 + 92 * scale, HEIGHT, true, scale) < 80 * scale * scale,
                        "Disabling Hotbar did not restore the original hotbar and count pixels at GUI scale " + scale);
                testItemNames(context, config, server, scale);
            }
            testHeldItems(context, config, server);
            context.runOnClient(client -> { config.hotbar.enabled = true; client.options.hideGui = true; });
            context.waitTicks(3);
            assertReleased(context);
            Path f1Enabled = screenshot(context, "hotbar-05-f1-enabled");
            context.runOnClient(client -> config.hotbar.enabled = false);
            context.waitTicks(2);
            Path f1Disabled = screenshot(context, "hotbar-06-f1-disabled");
            require(changedPixels(read(f1Enabled), read(f1Disabled), 0, HEIGHT - 50, WIDTH, HEIGHT, false, 2) < 20,
                    "Hotbar produced pixels while the HUD was hidden with F1");

            context.runOnClient(client -> { config.hotbar.enabled = true; client.options.hideGui = false; });
            context.setScreen(() -> new Screen(Component.literal("Hotbar overlay order")) {
                @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
                    graphics.fill(width / 2 - 100, height - 25, width / 2 + 100, height, 0xFFFF33CC);
                }
                @Override public boolean isPauseScreen() { return false; }
            });
            context.waitTicks(3);
            BufferedImage menu = read(screenshot(context, "hotbar-07-menu-above-blur"));
            require((menu.getRGB(WIDTH / 2, HEIGHT - 12) & 0xFFFFFF) == 0xFF33CC,
                    "Hotbar blur obscured a menu drawn above the HUD");
        } finally {
            context.setScreen(() -> null);
            context.getInput().releaseKey(TEST_KEY);
            context.runOnClient(client -> {
                config.hotbar.enabled = enabled;
                config.hotbar.blurPercent = originalBlurPercent;
                config.showHud = showHud;
                config.progressBar.enabled = progress;
                client.options.hideGui = false;
                store.save(config);
            });
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void testHeldItems(ClientGameTestContext context, ClientConfig config, TestServerContext server) {
        boolean originalEnabled = config.hotbar.enabled, originalHide = config.hotbar.hideHeldItems;
        int originalBlur = config.hotbar.blurPercent;
        int originalScale = context.computeOnClient(client -> client.options.guiScale().get());
        CameraType originalCamera = context.computeOnClient(client -> client.options.getCameraType());
        try {
            context.runOnClient(client -> {
                client.options.guiScale().set(1);
                client.options.setCameraType(CameraType.FIRST_PERSON);
                client.resizeDisplay();
                config.hotbar.enabled = true;
                config.hotbar.hideHeldItems = false;
                config.hotbar.blurPercent = 140;
            });
            server.runCommand("item replace entity @a hotbar.8 with minecraft:air");
            server.runCommand("item replace entity @a weapon.offhand with minecraft:air");
            context.getInput().pressKey(options -> options.keyHotbarSlots[8]);
            context.waitTicks(16);
            Path empty = screenshot(context, "hands-00-empty-fist-reference");

            server.runCommand("item replace entity @a hotbar.0 with minecraft:diamond_sword");
            server.runCommand("item replace entity @a hotbar.1 with minecraft:bow");
            server.runCommand("item replace entity @a hotbar.2 with minecraft:oak_planks 32");
            server.runCommand("item replace entity @a weapon.offhand with minecraft:shield");
            context.waitFor(client -> client.player.getInventory().getItem(0).is(Items.DIAMOND_SWORD)
                    && client.player.getOffhandItem().is(Items.SHIELD));
            Item[] items = {Items.DIAMOND_SWORD, Items.BOW, Items.OAK_PLANKS};
            for (int slot = 0; slot < items.length; slot++) {
                int selected = slot;
                context.runOnClient(client -> config.hotbar.hideHeldItems = false);
                context.getInput().pressKey(options -> options.keyHotbarSlots[selected]);
                context.waitTicks(14);
                Path visible = screenshot(context, "hands-01-visible-slot" + slot);
                context.runOnClient(client -> {
                    config.hotbar.hideHeldItems = true;
                    config.hotbar.blurPercent = selected == 1 ? 0 : 140;
                });
                context.waitTicks(3);
                Path hidden = screenshot(context, "hands-02-fists-slot" + slot);
                require(changedPixels(read(visible), read(hidden), 0, HEIGHT / 2, WIDTH, HEIGHT - 65, false, 1) > 500,
                        "Held-item privacy did not remove the selected item and shield");
                require(changedPixels(read(empty), read(hidden), 0, HEIGHT / 2, WIDTH, HEIGHT - 65, false, 1) < 150,
                        "Hiding the selected item did not render the normal empty fist");
                context.runOnClient(client -> require(client.player.getMainHandItem().is(items[selected])
                                && client.player.getOffhandItem().is(Items.SHIELD),
                        "Held-item privacy changed the client inventory"));
                server.runOnServer(mc -> {
                    var player = mc.getPlayerList().getPlayers().getFirst();
                    require(player.getMainHandItem().is(items[selected]) && player.getOffhandItem().is(Items.SHIELD),
                            "Held-item privacy changed server equipment");
                });
            }
            context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));
            context.waitTicks(3);
            Path hiddenThird = screenshot(context, "hands-03-third-person-empty");
            context.runOnClient(client -> {
                AvatarRenderer renderer = (AvatarRenderer) client.getEntityRenderDispatcher().getRenderer(client.player);
                AvatarRenderState state = renderer.createRenderState();
                renderer.extractRenderState(client.player, state, 0);
                require(state.rightHandItemState.isEmpty() && state.leftHandItemState.isEmpty()
                                && state.heldOnHead.isEmpty(), "The local avatar still exposes a held item");
                config.hotbar.hideHeldItems = false;
            });
            context.waitTicks(3);
            Path visibleThird = screenshot(context, "hands-04-third-person-visible");
            require(changedPixels(read(hiddenThird), read(visibleThird), 0, HEIGHT / 4, WIDTH, HEIGHT - 65, false, 1) > 100,
                    "The third-person held items did not restore when privacy was disabled");

            context.runOnClient(client -> {
                config.hotbar.hideHeldItems = true;
                client.options.setCameraType(CameraType.FIRST_PERSON);
                client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(new Vec3(1.5, 65, 1.5), Direction.UP, new BlockPos(1, 64, 1), false));
            });
            // The client predicts placement immediately; wait for the actual server block.
            for (int tick = 0; tick < 40 && !server.computeOnServer(mc ->
                    mc.overworld().getBlockState(new BlockPos(1, 65, 1)).is(Blocks.OAK_PLANKS)); tick++) {
                context.waitTick();
            }
            context.waitFor(client -> client.level.getBlockState(new BlockPos(1, 65, 1)).is(Blocks.OAK_PLANKS));
            server.runOnServer(mc -> require(mc.overworld().getBlockState(new BlockPos(1, 65, 1)).is(Blocks.OAK_PLANKS),
                    "Empty-hand privacy prevented normal server-authoritative block placement"));
        } finally {
            populateHotbar(server);
            context.runOnClient(client -> {
                config.hotbar.enabled = originalEnabled;
                config.hotbar.hideHeldItems = originalHide;
                config.hotbar.blurPercent = originalBlur;
                client.options.guiScale().set(originalScale);
                client.options.setCameraType(originalCamera);
                client.resizeDisplay();
            });
            context.getInput().pressKey(options -> options.keyHotbarSlots[0]);
            context.waitTicks(55);
        }
    }

    private static void testItemNames(ClientGameTestContext context, ClientConfig config,
                                      TestServerContext server, int scale) {
        boolean originalEnabled = config.hotbar.enabled;
        int originalAmount = config.hotbar.blurPercent;
        server.runOnServer(mc -> {
            var player = mc.getPlayerList().getPlayers().getFirst();
            var stack = player.getInventory().getItem(6);
            stack.set(DataComponents.CUSTOM_NAME, Component.literal("Private renamed item with a very long secret label")
                    .withStyle(ChatFormatting.BOLD));
            player.inventoryMenu.broadcastChanges();
        });
        context.waitFor(client -> client.player.getInventory().getItem(6).has(DataComponents.CUSTOM_NAME));
        try {
            for (int test = 0; test < 3; test++) {
                boolean survival = test == 2;
                int slot = test == 0 ? 1 : 6;
                String label = "hotbar-name-scale" + scale + "-" + (survival ? "survival" : slot == 1 ? "creative" : "renamed");
                server.runCommand("gamemode " + (survival ? "survival" : "creative") + " @a");
                if (survival) server.runCommand("experience set @a 7 levels");
                context.waitFor(client -> client.gameMode.canHurtPlayer() == survival);
                context.runOnClient(client -> config.hotbar.enabled = true);
                BufferedImage clear = read(captureItemName(context, config, slot, 0, label + "-clear"));
                int[] bounds = context.computeOnClient(client -> {
                    var stack = client.player.getInventory().getItem(slot);
                    int width = client.font.width(stack.getHoverName());
                    return new int[] {(client.getWindow().getGuiScaledWidth() - width) / 2 - 5,
                            client.getWindow().getGuiScaledHeight() - (survival ? 59 : 45) - 2, width + 10, 13};
                });
                BufferedImage normal = read(captureItemName(context, config, slot, 90, label + "-blur90"));
                BufferedImage maximum = read(captureItemName(context, config, slot, 140, label + "-blur140"));
                double clearEdges = regionEdges(clear, bounds, scale);
                double normalEdges = regionEdges(normal, bounds, scale);
                double maximumEdges = regionEdges(maximum, bounds, scale);
                require(normalEdges < clearEdges * .55,
                        "The selected-item name remained sharp at 90%: " + label + ", ratio=" + normalEdges / clearEdges);
                require(maximumEdges < clearEdges * .3 && maximumEdges < normalEdges * .95,
                        "The selected-item name retained readable edges at 140%: " + label + ", ratio=" + maximumEdges / clearEdges);
                require(allocated("itemName"), "Visible selected-item text did not receive its blur region");
                // Vanilla's health/hunger and contextual bar span exactly 182 GUI pixels.
                // The neighboring offhand pickup animation belongs to the blurred hotbar.
                if (survival) require(changedPixels(clear, maximum, WIDTH / 2 - 91 * scale, HEIGHT - 43 * scale,
                                WIDTH / 2 + 91 * scale, HEIGHT - 24 * scale, false, scale) < 4 * scale * scale,
                        "Blurring the selected-item name changed health, hunger or experience pixels");

                context.runOnClient(client -> config.hotbar.enabled = false);
                BufferedImage disabled = read(captureItemName(context, config, slot, 140, label + "-disabled"));
                require(regionEdges(disabled, bounds, scale) > maximumEdges * 2,
                        "Disabling Hotbar did not restore a clear selected-item name");
                assertReleased(context);
            }
            server.runCommand("gamemode creative @a");
            context.runOnClient(client -> { config.hotbar.enabled = true; config.hotbar.blurPercent = 140; });
            context.waitTicks(55);
            context.waitFor(client -> !allocated("itemName"));
            require(context.computeOnClient(client -> allocated("hotbar")),
                    "Expiring the item name also released the active hotbar blur");
        } finally {
            server.runCommand("gamemode creative @a");
            server.runOnServer(mc -> {
                var player = mc.getPlayerList().getPlayers().getFirst();
                player.getInventory().getItem(6).remove(DataComponents.CUSTOM_NAME);
                player.inventoryMenu.broadcastChanges();
            });
            context.runOnClient(client -> {
                config.hotbar.enabled = originalEnabled;
                config.hotbar.blurPercent = originalAmount;
            });
            context.waitTicks(55);
        }
    }

    private static Path captureItemName(ClientGameTestContext context, ClientConfig config,
                                         int slot, int percent, String name) {
        context.runOnClient(client -> config.hotbar.blurPercent = percent);
        context.getInput().pressKey(options -> options.keyHotbarSlots[8]);
        context.waitTicks(2);
        context.getInput().pressKey(options -> options.keyHotbarSlots[slot]);
        context.waitTicks(2);
        context.runOnClient(client -> require(((Number) field(client.gui, "toolHighlightTimer")).intValue() > 20,
                "The selected-item name expired before its screenshot"));
        return screenshot(context, name);
    }

    private static double regionEdges(BufferedImage image, int[] bounds, int scale) {
        double total = 0;
        int right = Math.min(image.getWidth() - 1, (bounds[0] + bounds[2]) * scale);
        int bottom = Math.min(image.getHeight() - 1, (bounds[1] + bounds[3]) * scale);
        for (int y = Math.max(0, bounds[1] * scale); y < bottom; y++) {
            for (int x = Math.max(0, bounds[0] * scale); x < right; x++) {
                total += distance(image.getRGB(x, y), image.getRGB(x + 1, y));
                total += distance(image.getRGB(x, y), image.getRGB(x, y + 1));
            }
        }
        return total;
    }

    /** Move the rendered slider through the same screen input and release/save path as a user. */
    private static void setBlurSlider(ClientGameTestContext context, int percent) {
        GuiTestInput.settle(context);
        GuiTestInput.reveal(context, "m9.blurAmount");
        context.runOnClient(client -> {
            DonutScreen screen = (DonutScreen) client.screen;
            List<?> hits = (List<?>) field(screen, "hits");
            Object hit = hits.stream().filter(value -> field(value, "id").equals("m9.blurAmount"))
                    .findFirst().orElseThrow(() -> new AssertionError("Cannot find the rendered Hotbar blur slider"));
            double localX = number(hit, "x") + number(hit, "w") * Math.min(.999999, percent / (double) HotbarSettings.MAX_BLUR_PERCENT);
            double localY = number(hit, "y") + number(hit, "h") / 2;
            var event = new MouseButtonEvent(number(screen, "originX") + localX * number(screen, "scale"),
                    number(screen, "originY") + localY * number(screen, "scale"),
                    new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0));
            require(screen.mouseClicked(event, false), "The Hotbar blur slider did not consume its click");
            screen.mouseReleased(event);
        });
        GuiTestInput.settle(context);
    }

    private static void assertBlurAmounts(ClientGameTestContext context, ClientConfig config, Path off,
                                           Path ninetyPercent, int scale) {
        context.runOnClient(client -> config.hotbar.blurPercent = 30);
        context.waitTicks(2);
        BufferedImage low = read(screenshot(context, "hotbar-scale" + scale + "-blur30"));
        context.runOnClient(client -> config.hotbar.blurPercent = 100);
        context.waitTicks(2);
        BufferedImage full = read(screenshot(context, "hotbar-scale" + scale + "-blur100"));
        context.runOnClient(client -> config.hotbar.blurPercent = 140);
        context.waitTicks(2);
        BufferedImage maximum = read(screenshot(context, "hotbar-scale" + scale + "-blur140"));
        BufferedImage baseline = read(off), strong = read(ninetyPercent);
        double baselineEdges = edgeEnergy(baseline, scale), lowEdges = edgeEnergy(low, scale);
        double strongEdges = edgeEnergy(strong, scale), fullEdges = edgeEnergy(full, scale);
        require(lowEdges < baselineEdges * .98 && lowEdges > strongEdges * 1.15,
                "30% Hotbar blur did not retain more detail than 90% at GUI scale " + scale);
        require(fullEdges < strongEdges * .99,
                "100% Hotbar blur was not stronger than 90% at GUI scale " + scale);
        require(edgeEnergy(maximum, scale) < fullEdges * .95,
                "140% Hotbar blur did not remove more detail than the previous maximum at GUI scale " + scale);
        require(changedPixels(baseline, low, 0, 0, WIDTH, HEIGHT / 2 - 20, false, scale) < 50
                        && changedPixels(baseline, full, 0, 0, WIDTH, HEIGHT / 2 - 20, false, scale) < 50,
                "Changing the Hotbar blur percentage affected the upper scene");

        context.runOnClient(client -> config.hotbar.blurPercent = 0);
        context.waitTicks(3);
        assertReleased(context);
        BufferedImage zero = read(screenshot(context, "hotbar-scale" + scale + "-blur0"));
        require(config.hotbar.enabled, "Selecting 0% disabled the Hotbar module");
        require(changedPixels(baseline, zero, WIDTH / 2 - 120 * scale, HEIGHT - 24 * scale,
                        WIDTH / 2 + 92 * scale, HEIGHT, true, scale) < 80 * scale * scale,
                "Enabled Hotbar at 0% changed the clear hotbar pixels at GUI scale " + scale);
    }

    private static void assertBlur(Path offPath, Path onPath, int scale) {
        BufferedImage off = read(offPath), on = read(onPath);
        for (int slot = 0; slot < 9; slot++) {
            int left = WIDTH / 2 + (-88 + slot * 20) * scale;
            require(changedPixels(off, on, left, HEIGHT - 19 * scale, left + 16 * scale,
                            HEIGHT - 3 * scale, false, scale) > 35 * scale * scale,
                    "Hotbar did not obscure item slot " + slot + " at GUI scale " + scale);
            if (slot != 4 && slot != 8) {
                require(changedPixels(off, on, left + 7 * scale, HEIGHT - 10 * scale,
                                left + 18 * scale, HEIGHT - scale, false, scale) > 6 * scale * scale,
                        "Hotbar left the item count clear in slot " + slot);
            }
        }
        require(changedPixels(off, on, WIDTH / 2 - 118 * scale, HEIGHT - 20 * scale,
                        WIDTH / 2 - 100 * scale, HEIGHT - 2 * scale, false, scale) > 35 * scale * scale,
                "Hotbar did not obscure the offhand item and count");
        double originalEdges = edgeEnergy(off, scale), blurredEdges = edgeEnergy(on, scale);
        require(blurredEdges < originalEdges * .75,
                "Hotbar pixels retained sharp item/count edges instead of a strong blur: " + blurredEdges / originalEdges);
        // The upper half excludes hand models and animated enchantment glint in the world view.
        require(changedPixels(off, on, 0, 0, WIDTH, HEIGHT / 2 - 20, false, scale) < 50,
                "Hotbar changed scene pixels outside the bottom HUD region");
    }

    private static double edgeEnergy(BufferedImage image, int scale) {
        double total = 0;
        for (int y = HEIGHT - 20 * scale; y < HEIGHT - 2 * scale; y++) {
            for (int x = WIDTH / 2 - 89 * scale; x < WIDTH / 2 + 89 * scale; x++) {
                // Enchantment glint advances between captures; measure the eight static slots.
                if (x >= WIDTH / 2 - 10 * scale && x < WIDTH / 2 + 10 * scale) continue;
                total += distance(image.getRGB(x, y), image.getRGB(x + 1, y));
                total += distance(image.getRGB(x, y), image.getRGB(x, y + 1));
            }
        }
        return total;
    }

    private static boolean allocated(String region) {
        Object target = field(HotbarBlur.class, region);
        return field(target, "horizontal") != null && field(target, "blurred") != null;
    }

    private static void assertReleased(ClientGameTestContext context) {
        context.waitFor(client -> !allocated("hotbar") && !allocated("itemName"));
    }

    private static int changedPixels(BufferedImage before, BufferedImage after, int left, int top,
                                     int right, int bottom, boolean skipGlint, int scale) {
        int changed = 0;
        for (int y = top; y < bottom; y++) for (int x = left; x < right; x++) {
            if (skipGlint && x >= WIDTH / 2 - 10 * scale && x < WIDTH / 2 + 10 * scale) continue;
            if (distance(before.getRGB(x, y), after.getRGB(x, y)) > 25) changed++;
        }
        return changed;
    }

    private static int distance(int first, int second) {
        return Math.abs(((first >> 16) & 255) - ((second >> 16) & 255))
                + Math.abs(((first >> 8) & 255) - ((second >> 8) & 255)) + Math.abs((first & 255) - (second & 255));
    }

    private static BufferedImage read(Path path) {
        try {
            BufferedImage image = ImageIO.read(path.toFile());
            require(image != null, "Cannot decode the hotbar screenshot");
            return image;
        } catch (IOException error) { throw new AssertionError("Cannot read the hotbar screenshot", error); }
    }

    private static Object field(Object owner, String name) {
        try {
            Class<?> type = owner instanceof Class<?> ownerClass ? ownerClass : owner.getClass();
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner instanceof Class<?> ? null : owner);
        } catch (ReflectiveOperationException error) { throw new AssertionError("Cannot inspect Hotbar field " + name, error); }
    }

    private static double number(Object owner, String name) {
        return ((Number) field(owner, name)).doubleValue();
    }

    private static Path screenshot(ClientGameTestContext context, String name) {
        // Inventory commands award recipes and advancements, and their fading notifications
        // otherwise change unrelated top-right/chat pixels between the blur comparisons.
        context.runOnClient(client -> {
            client.getToastManager().clear();
            client.gui.getChat().clearMessages(false);
        });
        context.waitTick();
        return context.takeScreenshot(TestScreenshotOptions.of(name).withSize(WIDTH, HEIGHT));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
