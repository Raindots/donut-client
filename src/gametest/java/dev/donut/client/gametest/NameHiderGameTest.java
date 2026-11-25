package dev.donut.client.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.ConfigStore;
import dev.donut.client.config.DonutConfigPaths;
import dev.donut.client.gui.DonutScreen;
import dev.donut.client.input.ModuleKeybinds;
import dev.donut.client.namehider.NameHiderController;
import dev.donut.client.namehider.ScoreboardBlur;
import dev.donut.client.namehider.SidebarTextSanitizer;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.numbers.FixedFormat;
import net.minecraft.network.chat.numbers.StyledFormat;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.lwjgl.glfw.GLFW;

/** Real sidebar title, team names, custom display/value components, input and non-mutating privacy. */
public final class NameHiderGameTest implements FabricClientGameTest {
    private static final int MODULE = 8, TEST_KEY = GLFW.GLFW_KEY_F9;
    private static final String OBJECTIVE = "name_hider_test";

    @Override public void runTest(ClientGameTestContext context) {
        context.runOnClient(client -> assertSanitizer());
        ClientConfig config = new ClientConfig();
        AtomicInteger saves = new AtomicInteger();
        Path fixture = context.computeOnClient(client -> client.gameDirectory.toPath().resolve("config/name-hider-gametest.json"));
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
        context.getInput().resizeWindow(1280, 800);
        try (var world = context.worldBuilder().create()) {
            world.getServer().runCommand("gamemode creative @a");
            world.getServer().runCommand("time set noon");
            world.getServer().runCommand("fill -20 64 -20 20 64 5 minecraft:light_gray_concrete");
            world.getServer().runCommand("fill -20 64 -12 20 80 -8 minecraft:light_gray_concrete");
            world.getServer().runCommand("tp @a 0.5 65 0.5 180 0");
            context.waitTicks(5);
            world.getClientWorld().waitForChunksRender();
            context.waitFor(client -> client.player != null && client.player.onGround()
                    && Math.abs(client.player.getY() - 65) < .01);
            testSettings(context, config, store, save, saves);
            testSidebar(context, world.getServer());
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
            catch (IOException error) { throw new AssertionError("Cannot remove the Name Hider configuration fixture", error); }
        }
    }

    private static void testSettings(ClientGameTestContext context, ClientConfig config, ConfigStore store,
                                     Runnable save, AtomicInteger saves) {
        GuiTestInput.openAnimated(context, () -> new DonutScreen(config, save));
        GuiTestInput.click(context, "tab0");
        GuiTestInput.click(context, "search");
        GuiTestInput.type(context, "namehider");
        require(!GuiTestInput.visible(context, "module8"), "Name Hider appeared in Visual modules");
        GuiTestInput.clickWithFade(context, "tab3", "contentProgress");
        GuiTestInput.click(context, "search");
        GuiTestInput.type(context, "privacy");
        require(!GuiTestInput.visible(context, "module8"), "Name Hider appeared in Blatant modules");
        GuiTestInput.clickWithFade(context, "tab4", "contentProgress");
        GuiTestInput.assertModuleRows(context, 7, 8);
        GuiTestInput.assertSelectionAnimation(context, MODULE);
        GuiTestInput.rightClick(context, "module8");
        require(!config.nameHider.enabled && saves.get() == 0, "Opening Name Hider settings changed its activation state");
        GuiTestInput.click(context, "enable8");
        GuiTestInput.click(context, "m8.blurScoreboard");
        require(config.nameHider.blurScoreboard && store.load().nameHider.blurScoreboard,
                "The full-scoreboard privacy preference did not save");
        GuiTestInput.click(context, "m8.blurScoreboard");
        GuiTestInput.click(context, "favorite8");
        require(config.nameHider.enabled && store.load().nameHider.enabled && store.load().nameHider.favorite,
                "Name Hider's activation or favorite setting did not save");
        GuiTestInput.clickWithFade(context, "tab1", "contentProgress");
        require(GuiTestInput.visible(context, "module8") && !GuiTestInput.visible(context, "module7"),
                "Name Hider did not appear alone in Favorites");
        GuiTestInput.clickWithFade(context, "tab4", "contentProgress");
        GuiTestInput.click(context, "search");
        GuiTestInput.type(context, "privacy");
        require(GuiTestInput.visible(context, "module8") && !GuiTestInput.visible(context, "module7"),
                "Streaming privacy search did not find Name Hider");
        int beforeBinding = saves.get();
        GuiTestInput.click(context, "keybind8");
        context.getInput().pressKey(TEST_KEY);
        context.runOnClient(client -> require(ModuleKeybinds.mapping(MODULE).saveString().equals("key.keyboard.f9"),
                "Name Hider did not capture its shortcut"));
        require(config.nameHider.enabled && saves.get() == beforeBinding,
                "Capturing the Name Hider shortcut changed its module settings");
        context.runOnClient(client -> { client.options.guiScale().set(2); client.resizeDisplay(); });
        GuiTestInput.settle(context);
        GuiTestInput.click(context, "m8.blurScoreboard");
        require(store.load().nameHider.blurScoreboard, "The scaled scoreboard blur setting did not save");
        GuiTestInput.assertContentClipped(context);
        screenshot(context, "namehider-01-streaming-settings");
        GuiTestInput.closeAnimated(context);
    }

    private static void testSidebar(ClientGameTestContext context, TestServerContext server) {
        KeyMapping open = context.computeOnClient(client -> Arrays.stream(client.options.keyMappings)
                .filter(key -> key.getName().equals("key.donut.open_gui")).findFirst().orElseThrow());
        context.waitTicks(4);
        context.getInput().pressKey(open);
        context.waitForScreen(DonutScreen.class);
        GuiTestInput.settle(context);
        ClientConfig production = context.computeOnClient(client -> screenConfig((DonutScreen) client.screen));
        ConfigStore productionStore = new ConfigStore(DonutConfigPaths.resolve(FabricLoader.getInstance().getConfigDir()).active());
        boolean enabled = production.nameHider.enabled, showHud = production.showHud, progress = production.progressBar.enabled;
        boolean originalBlur = production.nameHider.blurScoreboard;
        String username = context.computeOnClient(client -> client.player.getScoreboardName());
        createSidebar(server, username);
        context.waitFor(client -> client.level.getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR) != null
                && client.level.getScoreboard().listPlayerScores(client.level.getScoreboard().getObjective(OBJECTIVE)).size() == 3);
        BoardSnapshot serverBefore = server.computeOnServer(mc -> snapshot(mc.getScoreboard()));
        BoardSnapshot clientBefore = context.computeOnClient(client -> snapshot(client.level.getScoreboard()));
        try {
            context.setScreen(() -> null);
            context.runOnClient(client -> {
                client.options.guiScale().set(1);
                client.options.hideGui = true;
                client.resizeDisplay();
                production.nameHider.enabled = false;
                production.nameHider.blurScoreboard = false;
                production.showHud = false;
                production.progressBar.enabled = false;
            });
            context.waitTicks(3);
            Path scene = screenshot(context, "namehider-02-sidebar-scene");
            context.runOnClient(client -> client.options.hideGui = false);
            context.waitTicks(2);
            Path off = screenshot(context, "namehider-03-sidebar-visible");
            int ordinaryWidth = context.computeOnClient(client -> sidebarWidth(client.font, client.level.getScoreboard(), username, false));
            assertPanelWidth(scene, off, ordinaryWidth);

            context.getInput().holdKey(TEST_KEY);
            context.waitTicks(6);
            require(NameHiderController.isEnabled() && productionStore.load().nameHider.enabled,
                    "The production Name Hider shortcut did not enable and save the module");
            context.runOnClient(client -> KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(TEST_KEY)));
            context.waitTicks(2);
            require(NameHiderController.isEnabled(), "A repeated held key toggled Name Hider again");
            context.getInput().releaseKey(TEST_KEY);
            context.runOnClient(client -> {
                var board = client.level.getScoreboard();
                var title = board.getObjective(OBJECTIVE).getDisplayName();
                require(NameHiderController.sanitize(title).getString().equals("STREAM  / "),
                        "The enabled controller did not remove repeated case-insensitive account names");
            });
            context.waitTicks(2);
            Path hidden = screenshot(context, "namehider-04-sidebar-hidden");
            int hiddenWidth = context.computeOnClient(client -> sidebarWidth(client.font, client.level.getScoreboard(), username, true));
            require(hiddenWidth < ordinaryWidth - 30, "The sidebar fixture did not exercise width removal");
            assertPanelWidth(scene, hidden, hiddenWidth);
            require(changedPixels(off, hidden, 600, 360, 1280, 415) > 300,
                    "Enabling Name Hider did not change actual sidebar pixels");
            assertUnchanged(context, server, serverBefore, clientBefore);

            context.getInput().pressKey(TEST_KEY);
            context.waitTicks(3);
            require(!NameHiderController.isEnabled() && !productionStore.load().nameHider.enabled,
                    "The second Name Hider shortcut did not disable the module");
            Path restored = screenshot(context, "namehider-05-sidebar-restored");
            assertPanelWidth(scene, restored, ordinaryWidth);
            require(changedPixels(off, restored, 600, 360, 1280, 415) < 30,
                    "Disabling Name Hider did not restore the original sidebar pixels");
            assertUnchanged(context, server, serverBefore, clientBefore);
            testFullBlur(context, production, server, username, serverBefore, clientBefore);
        } finally {
            context.getInput().releaseKey(TEST_KEY);
            context.runOnClient(client -> {
                production.nameHider.enabled = enabled;
                production.nameHider.blurScoreboard = originalBlur;
                production.showHud = showHud;
                production.progressBar.enabled = progress;
                client.options.hideGui = false;
                productionStore.save(production);
            });
        }
    }

    private static void testFullBlur(ClientGameTestContext context, ClientConfig config, TestServerContext server,
                                     String username, BoardSnapshot serverBefore, BoardSnapshot clientBefore) {
        for (int scale = 1; scale <= 2; scale++) {
            int guiScale = scale;
            context.runOnClient(client -> {
                client.options.guiScale().set(guiScale);
                client.resizeDisplay();
                config.nameHider.enabled = false;
                config.nameHider.blurScoreboard = true;
            });
            context.waitTicks(3);
            Path clear = screenshot(context, "scoreboard-full-scale" + scale + "-clear");
            int[] bounds = context.computeOnClient(client -> {
                int width = sidebarWidth(client.font, client.level.getScoreboard(), username, false);
                return new int[] {(client.getWindow().getGuiScaledWidth() - width - 5) * guiScale,
                        (client.getWindow().getGuiScaledHeight() / 2 - 28) * guiScale,
                        (client.getWindow().getGuiScaledWidth() - 1) * guiScale,
                        (client.getWindow().getGuiScaledHeight() / 2 + 9) * guiScale};
            });
            context.runOnClient(client -> config.nameHider.enabled = true);
            context.waitTicks(3);
            Path blurred = screenshot(context, "scoreboard-full-scale" + scale + "-blurred");
            require(changedPixels(clear, blurred, bounds[0], bounds[1], bounds[2], bounds[3]) > 150 * scale * scale,
                    "Full scoreboard blur did not change the rendered sidebar");
            require(edgeEnergy(read(blurred), bounds) < edgeEnergy(read(clear), bounds) * .45,
                    "Full scoreboard blur left readable text edges");
            require(changedPixels(clear, blurred, 0, 0, Math.max(0, bounds[0] - 3), 700) < 50,
                    "Scoreboard privacy changed pixels outside the sidebar");
            require(context.computeOnClient(client -> NameHiderController.sanitize(
                            client.level.getScoreboard().getObjective(OBJECTIVE).getDisplayName()).getString().contains(username)),
                    "Full blur changed the source text instead of obscuring the rendered scoreboard");
            assertUnchanged(context, server, serverBefore, clientBefore);
            context.runOnClient(client -> config.nameHider.enabled = false);
            context.waitTicks(3);
            Path restored = screenshot(context, "scoreboard-full-scale" + scale + "-restored");
            require(changedPixels(clear, restored, bounds[0], bounds[1], bounds[2], bounds[3]) < 30,
                    "Disabling Name Hider did not restore the complete scoreboard");
            context.runOnClient(client -> {
                Object region = field(ScoreboardBlur.class, "region");
                require(field(region, "horizontal") == null && field(region, "blurred") == null,
                        "Disabling full scoreboard blur retained its GPU textures");
            });
        }
    }

    private static double edgeEnergy(BufferedImage image, int[] b) {
        double sum = 0;
        for (int y = b[1] + 1; y < b[3] - 1; y++) for (int x = b[0] + 1; x < b[2] - 1; x++) {
            sum += distance(image.getRGB(x, y), image.getRGB(x + 1, y));
            sum += distance(image.getRGB(x, y), image.getRGB(x, y + 1));
        }
        return sum;
    }

    private static void createSidebar(TestServerContext server, String name) {
        server.runOnServer(mc -> {
            var board = mc.getScoreboard();
            var title = Component.literal("STREAM ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(name).withStyle(ChatFormatting.AQUA))
                    .append(" / ").append(Component.literal(name.toUpperCase(Locale.ROOT)).withStyle(ChatFormatting.LIGHT_PURPLE));
            var objective = board.addObjective(OBJECTIVE, ObjectiveCriteria.DUMMY, title,
                    ObjectiveCriteria.RenderType.INTEGER, false, null);
            var team = board.addPlayerTeam("privacy_test");
            team.setPlayerPrefix(Component.literal("[VIP] " + name + " ").withStyle(ChatFormatting.GREEN));
            team.setPlayerSuffix(Component.literal(" / " + name.toUpperCase(Locale.ROOT)).withStyle(ChatFormatting.YELLOW));
            board.addPlayerToTeam(name, team);
            var own = board.getOrCreatePlayerScore(ScoreHolder.forNameOnly(name), objective);
            own.set(30);
            own.numberFormatOverride(new FixedFormat(Component.literal(name + " points").withStyle(ChatFormatting.RED)));
            var custom = board.getOrCreatePlayerScore(ScoreHolder.forNameOnly("custom_display"), objective);
            custom.set(20);
            int split = Math.max(1, name.length() / 2);
            custom.display(Component.literal("Player: ").withStyle(ChatFormatting.WHITE)
                    .append(Component.literal(name.substring(0, split)).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(name.substring(split)).withStyle(ChatFormatting.LIGHT_PURPLE))
                    .append(Component.literal(" online").withStyle(ChatFormatting.GREEN)));
            custom.numberFormatOverride(new FixedFormat(Component.literal(name.toLowerCase(Locale.ROOT))));
            var other = board.getOrCreatePlayerScore(ScoreHolder.forNameOnly("Other_User"), objective);
            other.set(10);
            other.display(Component.literal("Other_User stays visible").withStyle(ChatFormatting.GRAY));
            board.setDisplayObjective(DisplaySlot.SIDEBAR, objective);
        });
    }

    private static void assertSanitizer() {
        Component boundaries = Component.literal("[Alice] ALICE/aLiCe Alice_ _Alice Alice1 1Alice xAlice Alicex éAliceé");
        require(SidebarTextSanitizer.replaceUsername(boundaries, "Alice").getString()
                        .equals("[] / Alice_ _Alice Alice1 1Alice xAlice Alicex éé"),
                "Username removal did not honor repeated/case-insensitive matches and ASCII token boundaries");
        Component styled = Component.literal("safe ").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
                .append(Component.literal("Al").withStyle(ChatFormatting.RED))
                .append(Component.literal("IcE").withStyle(ChatFormatting.BLUE))
                .append(Component.literal(" tail").withStyle(ChatFormatting.GREEN, ChatFormatting.ITALIC));
        List<Glyph> original = glyphs(styled);
        var expected = new ArrayList<>(original.subList(0, 5));
        expected.addAll(original.subList(10, original.size()));
        Component removed = SidebarTextSanitizer.replaceUsername(styled, "Alice");
        require(removed.getString().equals("safe  tail") && glyphs(removed).equals(expected),
                "A username split across styles was not removed while preserving the surrounding styles");
        require(glyphs(styled).equals(original) && styled.getString().equals("safe AlIcE tail"),
                "Sanitizing a styled component mutated the source text");
        require(SidebarTextSanitizer.replaceUsername(styled, "Nobody") == styled
                        && SidebarTextSanitizer.replaceUsername(styled, "") == styled,
                "A no-match or empty account name replaced an unchanged component");
    }

    private static int sidebarWidth(Font font, Scoreboard board, String username, boolean hide) {
        var objective = board.getObjective(OBJECTIVE);
        int width = font.width(filtered(objective.getDisplayName(), username, hide));
        for (PlayerScoreEntry entry : board.listPlayerScores(objective)) {
            Component name = PlayerTeam.formatNameForTeam(board.getPlayersTeam(entry.owner()), entry.ownerName());
            int valueWidth = font.width(filtered(entry.formatValue(objective.numberFormatOrDefault(StyledFormat.SIDEBAR_DEFAULT)), username, hide));
            width = Math.max(width, font.width(filtered(name, username, hide)) + (valueWidth > 0 ? font.width(": ") + valueWidth : 0));
        }
        return width;
    }

    private static Component filtered(Component component, String username, boolean hide) {
        return hide ? SidebarTextSanitizer.replaceUsername(component, username) : component;
    }

    private static BoardSnapshot snapshot(Scoreboard board) {
        var objective = board.getObjective(OBJECTIVE);
        var rows = board.listPlayerScores(objective).stream().sorted(Comparator.comparing(PlayerScoreEntry::owner))
                .map(entry -> new RowSnapshot(entry.owner(), entry.value(), glyphs(entry.ownerName()),
                        glyphs(PlayerTeam.formatNameForTeam(board.getPlayersTeam(entry.owner()), entry.ownerName())),
                        glyphs(entry.formatValue(objective.numberFormatOrDefault(StyledFormat.SIDEBAR_DEFAULT)))))
                .toList();
        return new BoardSnapshot(glyphs(objective.getDisplayName()), rows);
    }

    private static List<Glyph> glyphs(Component component) {
        List<Glyph> result = new ArrayList<>();
        component.visit((style, text) -> {
            text.codePoints().forEach(code -> result.add(new Glyph(code, style)));
            return Optional.<Void>empty();
        }, Style.EMPTY);
        return List.copyOf(result);
    }

    private static void assertUnchanged(ClientGameTestContext context, TestServerContext server,
                                         BoardSnapshot serverBefore, BoardSnapshot clientBefore) {
        require(server.computeOnServer(mc -> snapshot(mc.getScoreboard())).equals(serverBefore),
                "Name Hider changed authoritative server scoreboard data or styles");
        require(context.computeOnClient(client -> snapshot(client.level.getScoreboard())).equals(clientBefore),
                "Name Hider changed the client scoreboard source components or styles");
    }

    private static void assertPanelWidth(Path scene, Path sidebar, int width) {
        BufferedImage baseline = read(scene), rendered = read(sidebar);
        int headerTop = rendered.getHeight() / 2 + 9 - 27 - 10;
        int left = -1;
        for (int x = rendered.getWidth() / 2 + 1; x < rendered.getWidth(); x++) {
            if (distance(baseline.getRGB(x, headerTop), rendered.getRGB(x, headerTop)) > 25) { left = x; break; }
        }
        require(Math.abs(left - (rendered.getWidth() - width - 5)) <= 1,
                "Sidebar background width does not follow sanitized title, team names and custom score values: expected="
                        + width + ", left=" + left);
    }

    private static int changedPixels(Path first, Path second, int left, int top, int right, int bottom) {
        BufferedImage before = read(first), after = read(second);
        int changed = 0;
        for (int y = top; y < bottom; y++) for (int x = left; x < right; x++) {
            if (distance(before.getRGB(x, y), after.getRGB(x, y)) > 25) changed++;
        }
        return changed;
    }

    private static int distance(int a, int b) {
        return Math.abs(((a >> 16) & 255) - ((b >> 16) & 255))
                + Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)) + Math.abs((a & 255) - (b & 255));
    }

    private static BufferedImage read(Path path) {
        try {
            BufferedImage image = ImageIO.read(path.toFile());
            require(image != null, "Cannot decode the scoreboard screenshot");
            return image;
        } catch (IOException error) { throw new AssertionError("Cannot read the scoreboard screenshot", error); }
    }

    private static ClientConfig screenConfig(DonutScreen screen) {
        try {
            Field field = DonutScreen.class.getDeclaredField("config");
            field.setAccessible(true);
            return (ClientConfig) field.get(screen);
        } catch (ReflectiveOperationException error) { throw new AssertionError("Cannot inspect production Name Hider config", error); }
    }

    private static Path screenshot(ClientGameTestContext context, String name) {
        context.runOnClient(client -> {
            client.gui.getChat().clearMessages(false);
            client.getToastManager().clear();
        });
        return context.takeScreenshot(TestScreenshotOptions.of(name).withSize(1280, 800));
    }

    private static Object field(Object owner, String name) {
        try {
            Field field = (owner instanceof Class<?> type ? type : owner.getClass()).getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner instanceof Class<?> ? null : owner);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Glyph(int codepoint, Style style) { }
    private record RowSnapshot(String owner, int value, List<Glyph> display, List<Glyph> teamName, List<Glyph> score) { }
    private record BoardSnapshot(List<Glyph> title, List<RowSnapshot> rows) { }
}
