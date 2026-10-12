package dev.donut.client.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import dev.donut.client.autobuild.AutoBuilderController;
import dev.donut.client.autobuild.MaterialTracker;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.gui.AutoBuilderScreen;
import dev.donut.client.gui.DonutScreen;
import dev.donut.client.input.ModuleKeybinds;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

/** Production popup input, compressed files, ghost rendering and Creative material access. */
public final class AutoBuilderGuiGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        ClientConfig config = new ClientConfig();
        AtomicInteger saves = new AtomicInteger();
        AutoBuilderController builder = AutoBuilderController.get();
        String originalBinding = context.computeOnClient(client -> ModuleKeybinds.mapping(3).saveString());
        int originalScale = context.computeOnClient(client -> client.options.guiScale().get());
        Path directory = context.computeOnClient(client -> client.gameDirectory.toPath().resolve("schematics/donut"));
        String suffix = " - " + Long.toUnsignedString(System.nanoTime()) + ".litematic";
        Path houseFile = directory.resolve("AutoBuilder 01 House" + suffix);
        Path cubeFile = directory.resolve("AutoBuilder 02 Cube" + suffix);
        Path invalidFile = directory.resolve("AutoBuilder 03 Invalid" + suffix);
        try {
            Files.createDirectories(directory);
            NbtIo.writeCompressed(houseFixture(), houseFile);
            NbtIo.writeCompressed(cubeFixture(), cubeFile);
            Files.writeString(invalidFile, "invalid compressed NBT fixture");
        } catch (IOException error) { throw new AssertionError("Cannot create isolated GUI fixtures", error); }
        context.runOnClient(client -> {
            builder.setSchematic(null); builder.setPreviewVisible(false);
            client.options.guiScale().set(1);
            client.resizeDisplay();
        });
        context.getInput().resizeWindow(1280, 800);
        try (var world = context.worldBuilder().create()) {
            world.getServer().runCommand("gamemode creative @a");
            world.getServer().runCommand("time set noon");
            world.getServer().runCommand("fill -10 64 -12 20 64 20 minecraft:smooth_stone");
            world.getServer().runCommand("tp @a 3.5 65 10.5 180 18");
            context.waitTicks(5);
            world.getClientWorld().waitForChunksRender();

            GuiTestInput.openAnimated(context, () -> new DonutScreen(config, saves::incrementAndGet));
            require(GuiTestInput.visible(context, "module0") && GuiTestInput.visible(context, "module1")
                    && GuiTestInput.visible(context, "module2") && !GuiTestInput.visible(context, "module3"),
                    "Visual must contain only its three visual modules");
            GuiTestInput.clickWithFade(context, "tab3", "contentProgress");
            require(GuiTestInput.visible(context, "module3") && GuiTestInput.visible(context, "keybind3"),
                    "AutoBuilder and its key box are missing from Blatant");
            require(!GuiTestInput.visible(context, "module0") && !GuiTestInput.visible(context, "module1")
                    && !GuiTestInput.visible(context, "module2"), "Visual modules leaked into Blatant");
            GuiTestInput.click(context, "search"); GuiTestInput.type(context, "autobuilder");
            require(GuiTestInput.visible(context, "module3"), "Blatant search did not find AutoBuilder");
            GuiTestInput.click(context, "tab3");
            GuiTestInput.click(context, "search"); GuiTestInput.type(context, "stone");
            require(!GuiTestInput.visible(context, "module0") && !GuiTestInput.visible(context, "module3"),
                    "Blatant search must remain restricted to its category");
            GuiTestInput.click(context, "tab3");
            GuiTestInput.click(context, "keybind3");
            context.getInput().pressKey(GLFW.GLFW_KEY_F7);
            context.runOnClient(client -> require(ModuleKeybinds.mapping(3).saveString().equals("key.keyboard.f7"),
                    "AutoBuilder key capture failed"));
            GuiTestInput.click(context, "favorite3");
            GuiTestInput.click(context, "tab1");
            require(config.autoBuilder.favorite && GuiTestInput.visible(context, "module3")
                    && !GuiTestInput.visible(context, "module0"), "AutoBuilder favorites failed");
            GuiTestInput.click(context, "tab0");
            require(!GuiTestInput.visible(context, "module3"), "Favoriting AutoBuilder moved it back into Visual");
            GuiTestInput.click(context, "tab3");
            screenshot(context, "autobuilder-01-blatant-category");
            click(context, "module3");
            context.waitForScreen(AutoBuilderScreen.class);
            settle(context);
            require(visible(context, "folder") && visible(context, "refresh"), "Library folder controls are missing");
            click(context, "refresh"); settle(context);
            screenshot(context, "autobuilder-02-library");

            chooseFile(context, houseFile);
            click(context, "cancelPreview");
            context.runOnClient(client -> {
                require(builder.schematic().name().equals("AutoBuilder House"), "Compressed file metadata did not load");
                require(builder.schematic().regionCount() == 2, "Multiple regions did not load through the popup");
                require(builder.schematic().dimensions().equals(new BlockPos(9, 5, 6)), "Multi-region bounds are wrong");
            });
            // Direction buttons use the sorted library rather than restarting at the first file.
            click(context, "next");
            awaitLoad(context);
            context.runOnClient(client -> require(builder.schematic().name().equals("AutoBuilder Cube"), "Next schematic failed"));
            click(context, "previous");
            awaitLoad(context);
            context.runOnClient(client -> require(builder.schematic().name().equals("AutoBuilder House"), "Previous schematic failed"));
            chooseFile(context, invalidFile);
            context.runOnClient(client -> {
                require(((String) field(client.screen, "message")).startsWith("Invalid schematic:"), "Invalid files need a clear validation error");
                require(builder.schematic().name().equals("AutoBuilder House"), "An invalid file replaced the valid schematic");
            });
            chooseFile(context, houseFile);
            click(context, "cancelPreview");

            // Input must use transformed popup coordinates at both GUI scales.
            enterCoordinate(context, 0, "0");
            enterCoordinate(context, 1, "65");
            enterCoordinate(context, 2, "0");
            context.runOnClient(client -> require(builder.transform().origin().equals(new BlockPos(0, 65, 0)), "Coordinate input failed"));
            click(context, "coordinate0");
            type(context, "2");
            click(context, "rotate");
            context.runOnClient(client -> require(builder.transform().origin().getX() == 2 && builder.transform().quarterTurns() == 1,
                    "Rotate lost the freshly entered coordinates"));
            click(context, "mirror");
            context.runOnClient(client -> require(builder.transform().mirrorX(), "Mirror toggle failed"));
            click(context, "rotate"); click(context, "rotate"); click(context, "rotate"); click(context, "mirror");
            enterCoordinate(context, 0, "0");
            // Invalid coordinates must prevent the requested preview action.
            click(context, "coordinate0"); type(context, "99999999999"); click(context, "preview");
            context.runOnClient(client -> require(client.screen instanceof AutoBuilderScreen && !builder.previewVisible(),
                    "Invalid coordinates started a preview at an unintended origin"));
            enterCoordinate(context, 0, "0");
            context.runOnClient(client -> require(!((String) field(client.screen, "message")).startsWith("Invalid coordinates."),
                    "A corrected coordinate left its validation error visible"));

            context.runOnClient(client -> {
                Screen screen = client.screen;
                Object model = field(screen, "model");
                double yaw = number(model, "yaw"), zoom = number(model, "zoom");
                Bounds bounds = find(screen, "model");
                MouseButtonEvent event = mouse(screen, bounds.x + bounds.w / 2, bounds.y + bounds.h / 2);
                screen.mouseClicked(event, false);
                screen.mouseDragged(event, 40, 12);
                screen.mouseReleased(event);
                screen.mouseScrolled(event.x(), event.y(), 0, 2);
                require(number(model, "yaw") != yaw && number(model, "zoom") > zoom, "3D preview drag or zoom failed");
            });
            screenshot(context, "autobuilder-03-preview-positioning");
            click(context, "resetView");
            int priorSaves = saves.get();
            click(context, "opacityUp");
            require(saves.get() > priorSaves && config.autoBuilder.previewOpacity > .28, "Preview opacity did not save");
            context.runOnClient(client -> { client.options.guiScale().set(2); client.resizeDisplay(); });
            settle(context);
            screenshot(context, "autobuilder-04-preview-scaled");

            // A real world state and real inventory drive all material columns.
            world.getServer().runCommand("setblock 0 65 0 minecraft:oak_planks");
            world.getServer().runCommand("item replace entity @a hotbar.0 with minecraft:oak_planks 8");
            world.getServer().runCommand("item replace entity @a inventory.0 with minecraft:oak_planks 12");
            context.waitTicks(4);
            click(context, "materials"); settle(context);
            context.runOnClient(client -> {
                MaterialTracker.Snapshot snapshot = (MaterialTracker.Snapshot) field(client.screen, "materials");
                MaterialTracker.Row planks = snapshot.rows().stream().filter(row -> row.item() == Items.OAK_PLANKS).findFirst().orElseThrow();
                MaterialTracker.Row slabs = snapshot.rows().stream().filter(row -> row.item() == Items.OAK_SLAB).findFirst().orElseThrow();
                require(planks.correct() == 1 && planks.hotbar() == 8 && planks.inventory() == 12, "Materials stock or correct count is wrong");
                require(slabs.total() == 2, "Double slab requires two items");
            });
            require(visible(context, "getMaterials") && !visible(context, "rotate"), "Materials modal should block the positioned controls");
            click(context, "materialSearch"); type(context, "slab");
            context.runOnClient(client -> require(((String) field(client.screen, "searchQuery")).equals("slab"), "Materials search failed"));
            screenshot(context, "autobuilder-05-materials-search");
            key(context, GLFW.GLFW_KEY_A, GLFW.GLFW_MOD_CONTROL); key(context, GLFW.GLFW_KEY_BACKSPACE, 0);
            screenshot(context, "autobuilder-06-materials-creative");
            click(context, "getMaterials");
            context.waitTicks(4);
            context.runOnClient(client -> require(client.player.getInventory().countItem(Items.OAK_SLAB) >= 2, "Creative materials did not populate inventory"));
            click(context, "closeMaterials"); settle(context);

            world.getServer().runCommand("gamemode survival @a");
            context.waitFor(client -> !client.player.getAbilities().instabuild);
            click(context, "materials"); settle(context);
            require(!visible(context, "getMaterials"), "Get Materials must be disabled in Survival");
            screenshot(context, "autobuilder-07-materials-survival");
            click(context, "closeMaterials"); settle(context);
            enterCoordinate(context, 0, "1000000");
            click(context, "materials"); settle(context);
            context.runOnClient(client -> {
                MaterialTracker.Snapshot snapshot = (MaterialTracker.Snapshot) field(client.screen, "materials");
                require(snapshot.unknownBlocks() == snapshot.totalBlocks(), "Unloaded build positions must remain unknown");
            });
            screenshot(context, "autobuilder-07b-materials-unloaded");
            click(context, "closeMaterials"); settle(context);
            enterCoordinate(context, 0, "0");
            context.runOnClient(client -> { client.options.guiScale().set(1); client.resizeDisplay(); }); settle(context);
            world.getServer().runCommand("tp @a 4.5 66 14.5 180 14");
            click(context, "preview");
            context.waitForScreen(null);
            context.runOnClient(client -> require(builder.previewVisible() && !builder.isRunning(), "Showing the ghost must not start building"));
            world.getClientWorld().waitForChunksRender(false);
            screenshot(context, "autobuilder-08-world-ghost");

            // The assigned production shortcut opens the manager from ordinary gameplay.
            context.waitTicks(2);
            context.getInput().pressKey(GLFW.GLFW_KEY_F7);
            context.waitForScreen(AutoBuilderScreen.class); settle(context);
            click(context, "cancelPreview");
            context.runOnClient(client -> require(!builder.previewVisible(), "Cancel Preview left the ghost active"));
            click(context, "close");
            context.waitForScreen(null);
        } finally {
            context.setScreen(() -> null);
            context.runOnClient(client -> {
                builder.setSchematic(null); builder.setPreviewVisible(false);
                ModuleKeybinds.setKey(client, 3, InputConstants.getKey(originalBinding));
                client.options.guiScale().set(originalScale); client.resizeDisplay();
            });
            for (Path fixture : List.of(houseFile, cubeFile, invalidFile)) {
                try { Files.deleteIfExists(fixture); }
                catch (IOException error) { throw new AssertionError("Cannot remove test fixture " + fixture, error); }
            }
        }
    }

    private static CompoundTag houseFixture() {
        ListTag palette = new ListTag();
        palette.add(state("air")); palette.add(state("oak_planks")); palette.add(state("glass"));
        CompoundTag doubleSlab = state("oak_slab"); CompoundTag properties = new CompoundTag();
        properties.putString("type", "double"); properties.putString("waterlogged", "false"); doubleSlab.put("Properties", properties);
        palette.add(doubleSlab); palette.add(state("oak_log")); palette.add(state("red_wool"));
        int[] cells = new int[6 * 5 * 6];
        for (int y = 0; y < 5; y++) for (int z = 0; z < 6; z++) for (int x = 0; x < 6; x++) {
            int value = y == 0 ? 1 : y == 4 ? 5 : x == 0 || z == 0 || x == 5 || z == 5 ? 1 : 0;
            if (y == 2 && (x == 0 || x == 5) && z > 1 && z < 4) value = 2;
            if ((y == 1 || y == 2) && z == 5 && (x == 2 || x == 3)) value = 0;
            if (y == 1 && x == 1 && z == 1) value = 3;
            cells[y * 36 + z * 6 + x] = value;
        }
        CompoundTag regions = new CompoundTag();
        regions.put("House", region(0, 0, 0, 6, 5, 6, palette, cells));
        ListTag pillarPalette = new ListTag(); pillarPalette.add(state("air")); pillarPalette.add(state("oak_log"));
        regions.put("Pillar", region(8, 0, 0, 1, 2, 1, pillarPalette, new int[]{1, 1}));
        return root("AutoBuilder House", regions);
    }
    private static CompoundTag cubeFixture() {
        ListTag palette = new ListTag(); palette.add(state("air")); palette.add(state("stone"));
        CompoundTag regions = new CompoundTag();
        regions.put("Cube", region(0, 0, 0, 2, 2, 2, palette, new int[]{1,1,1,1,1,1,1,1}));
        return root("AutoBuilder Cube", regions);
    }
    private static CompoundTag root(String name, CompoundTag regions) {
        CompoundTag root = new CompoundTag(); root.putInt("Version", 6);
        root.putInt("MinecraftDataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        CompoundTag metadata = new CompoundTag(); metadata.putString("Name", name);
        root.put("Metadata", metadata); root.put("Regions", regions); return root;
    }
    private static CompoundTag state(String name) { CompoundTag tag = new CompoundTag(); tag.putString("Name", "minecraft:" + name); return tag; }
    private static CompoundTag pos(int x, int y, int z) { CompoundTag tag = new CompoundTag(); tag.putInt("x", x); tag.putInt("y", y); tag.putInt("z", z); return tag; }
    private static CompoundTag region(int x, int y, int z, int sx, int sy, int sz, ListTag palette, int[] cells) {
        CompoundTag tag = new CompoundTag(); tag.put("Position", pos(x,y,z)); tag.put("Size", pos(sx,sy,sz));
        tag.put("BlockStatePalette", palette);
        int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
        long[] packed = new long[(cells.length * bits + 63) / 64];
        for (int i = 0; i < cells.length; i++) {
            int start = i * bits, word = start / 64, shift = start % 64;
            packed[word] |= (long) cells[i] << shift;
            if (shift + bits > 64) packed[word + 1] |= (long) cells[i] >>> (64 - shift);
        }
        tag.putLongArray("BlockStates", packed); return tag;
    }

    private static void enterCoordinate(ClientGameTestContext context, int axis, String value) {
        click(context, "coordinate" + axis); type(context, value); key(context, GLFW.GLFW_KEY_ENTER, 0); settle(context);
    }
    private static void chooseFile(ClientGameTestContext context, Path path) {
        settle(context);
        int index = context.computeOnClient(client -> ((List<?>) field(client.screen, "files")).indexOf(path));
        require(index >= 0, "Fixture not listed by Refresh: " + path);
        context.runOnClient(client -> {
            int maximum = Math.max(0, ((List<?>) field(client.screen, "files")).size() * 31 - 272);
            try { Field field = AutoBuilderScreen.class.getDeclaredField("fileScroll"); field.setAccessible(true); field.set(client.screen, Math.min(maximum, index * 31)); }
            catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        context.waitTick(); click(context, "file" + index); awaitLoad(context);
    }
    private static void awaitLoad(ClientGameTestContext context) {
        context.waitFor(client -> !((Boolean) field(client.screen, "loading"))); settle(context);
    }
    private static void settle(ClientGameTestContext context) {
        context.waitTick();
        context.waitFor(client -> client.screen instanceof AutoBuilderScreen && number(client.screen, "progress") == 1
                && !((Boolean) field(client.screen, "refreshing")) && !((Boolean) field(client.screen, "modalClosing"))
                && (!((Boolean) field(client.screen, "materialsOpen")) || number(client.screen, "modalProgress") == 1));
    }
    private static void click(ClientGameTestContext context, String id) {
        context.runOnClient(client -> {
            Screen screen = client.screen; Bounds bounds = find(screen, id);
            require(bounds != null, "Missing rendered control " + id);
            MouseButtonEvent event = mouse(screen, bounds.x + bounds.w * (id.startsWith("module") ? .2 : .5), bounds.y + bounds.h * .5);
            require(screen.mouseClicked(event, false), "GUI did not consume " + id); screen.mouseReleased(event);
        });
        context.waitTick();
    }
    private static MouseButtonEvent mouse(Screen screen, double x, double y) {
        return new MouseButtonEvent(number(screen, "originX") + x * number(screen, "scale"),
                number(screen, "originY") + y * number(screen, "scale"), new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0));
    }
    private static void type(ClientGameTestContext context, String value) {
        context.runOnClient(client -> value.codePoints().forEach(cp -> client.screen.charTyped(new CharacterEvent(cp, 0)))); context.waitTick();
    }
    private static void key(ClientGameTestContext context, int code, int modifiers) {
        context.runOnClient(client -> client.screen.keyPressed(new KeyEvent(code, 0, modifiers))); context.waitTick();
    }
    private static boolean visible(ClientGameTestContext context, String id) { return context.computeOnClient(client -> find(client.screen, id) != null); }
    private static Bounds find(Screen screen, String id) {
        for (Object hit : (List<?>) field(screen, "hits")) if (id.equals(field(hit, "id")))
            return new Bounds(number(hit, "x"), number(hit, "y"), number(hit, "w"), number(hit, "h"));
        return null;
    }
    private static Object field(Object object, String name) {
        try { Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object); }
        catch (ReflectiveOperationException error) { throw new AssertionError("Cannot inspect " + name, error); }
    }
    private static double number(Object object, String name) { return ((Number) field(object, name)).doubleValue(); }
    private static void screenshot(ClientGameTestContext context, String name) { context.takeScreenshot(TestScreenshotOptions.of(name).withSize(1280, 800)); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private record Bounds(double x, double y, double w, double h) { }
}
