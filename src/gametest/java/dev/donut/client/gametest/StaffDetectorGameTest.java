package dev.donut.client.gametest;

import com.google.gson.Gson;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import dev.donut.client.config.DonutConfigPaths;
import dev.donut.client.config.StaffDetectorSettings;
import dev.donut.client.staff.StaffDetectorController;
import dev.donut.client.staff.StaffDetectorEngine;
import java.lang.reflect.Modifier;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/** Real integrated-server transport through production mixins, with synthetic staff profiles/entities. */
public final class StaffDetectorGameTest implements FabricClientGameTest {
    private static final UUID STAFF = UUID.fromString("c458951b-707e-486b-a35a-d46ddf1411da");
    private static final int ENTITY_ID = 2_000_000_001;
    @Override public void runTest(ClientGameTestContext context) {
        var controller = StaffDetectorController.get(); var settings = controller.settings();
        var gson = new Gson(); var original = gson.fromJson(gson.toJson(settings), StaffDetectorSettings.class);
        int originalScale = context.computeOnClient(mc -> mc.options.guiScale().get());
        context.getInput().resizeWindow(1280, 800);
        context.runOnClient(mc -> { mc.options.guiScale().set(1); mc.resizeDisplay(); settings.enabled = false; controller.disconnect(); });
        try (var world = context.worldBuilder().create()) {
            var server = world.getServer();
            context.runOnClient(mc -> {
                settings.rosters.clear(); settings.add("singleplayer", "FixtureStaff");
                settings.chatAlerts = false; settings.completionChecks = settings.rosterAlerts = false;
                settings.enabled = settings.showHud = true;
            });
            context.waitTicks(2);
            sendInfo(context, server, GameType.SPECTATOR);
            context.waitFor(mc -> controller.views().size() == 1 && controller.views().getFirst().spectator());
            context.runOnClient(mc -> {
                var view = controller.views().getFirst();
                require(settings.roster("singleplayer").getFirst().id().equals(STAFF), "Received staff profile did not bind UUID");
                require(view.online() && !view.nearby() && Double.isNaN(view.distance()), "Spectator incorrectly implied proximity");
                require(controller.history().stream().anyMatch(event -> event.kind() == StaffDetectorEngine.Kind.SPECTATOR), "Production game-mode packet hook missed spectator");
            });
            server.runOnServer(mc -> {
                var target = mc.getPlayerList().getPlayers().getFirst();
                target.connection.send(new ClientboundAddEntityPacket(ENTITY_ID, STAFF, target.getX() + 8, target.getY(), target.getZ(),
                        0, 0, EntityType.PLAYER, 0, Vec3.ZERO, 0));
                target.connection.send(new ClientboundSetEntityDataPacket(ENTITY_ID,
                        List.of(new SynchedEntityData.DataValue<>(0, EntityDataSerializers.BYTE, (byte) 0x20))));
            });
            context.waitFor(mc -> controller.views().getFirst().invisible() && controller.views().getFirst().nearby());
            context.takeScreenshot(TestScreenshotOptions.of("staff-detector-01-presence").withSize(1280, 800));
            context.runOnClient(mc -> {
                long before = controller.droppedSnapshots();
                for (int i = 0; i < StaffDetectorController.MAX_PENDING + 10; i++) StaffDetectorController.rosterApplied(mc.getConnection());
                require(controller.pendingSnapshots() == StaffDetectorController.MAX_PENDING, "Roster queue exceeded its bound");
                require(controller.droppedSnapshots() >= before + 10, "Queue overflow was not counted");
            });
            context.waitTicks(2);
            sendInfo(context, server, GameType.SURVIVAL);
            server.runOnServer(mc -> mc.getPlayerList().getPlayers().getFirst().connection.send(new ClientboundRemoveEntitiesPacket(ENTITY_ID)));
            context.waitFor(mc -> !controller.views().getFirst().spectator() && !controller.views().getFirst().nearby());
            server.runOnServer(mc -> mc.getPlayerList().getPlayers().getFirst().connection.send(new ClientboundPlayerInfoRemovePacket(List.of(STAFF))));
            context.waitFor(mc -> !controller.views().getFirst().online());
            context.runOnClient(mc -> { settings.completionChecks = true; set(controller, "nextQuery", System.nanoTime() + 60_000_000_000L); });
            // Unmatched responses must never turn into evidence, including ordinary chat UI completions.
            for (int i = 0; i < 4; i++) {
                server.runOnServer(mc -> mc.getPlayerList().getPlayers().getFirst().connection.send(suggestions(42)));
                context.waitTicks(2);
            }
            require(context.computeOnClient(mc -> !controller.views().getFirst().possibleHidden()), "Unmatched suggestions generated a hidden-player hint");
            // Synthetic responses still travel over the real connection and exercise ID matching in the mixin.
            for (int i = 0; i < settings.requiredSamples; i++) {
                int request = -2000 - i;
                context.runOnClient(mc -> { set(controller, "pendingRequest", request); set(controller, "queryDeadline", System.nanoTime() + 3_000_000_000L); });
                server.runOnServer(mc -> mc.getPlayerList().getPlayers().getFirst().connection.send(suggestions(request)));
                context.waitTicks(2);
            }
            require(context.computeOnClient(mc -> controller.views().getFirst().possibleHidden()), "Matched repeated discrepancy was not recorded");
            context.runOnClient(mc -> { settings.completionChecks = false; }); context.waitTicks(2);
            require(context.computeOnClient(mc -> !controller.views().getFirst().possibleHidden()), "Disabling completion checks retained a hint");
            context.runOnClient(mc -> { settings.enabled = false; }); context.waitTicks(2);
            require(context.computeOnClient(mc -> controller.views().isEmpty() && controller.history().isEmpty() && controller.pendingSnapshots() == 0), "Disabling retained runtime evidence");
            context.runOnClient(mc -> { settings.enabled = true; }); context.waitTicks(2);
            sendInfo(context, server, GameType.SPECTATOR);
            context.waitFor(mc -> !controller.views().isEmpty() && controller.views().getFirst().spectator());

            // A bundle forces the mode change and vanish removal into the same client dispatch.
            server.runOnServer(mc -> mc.getPlayerList().getPlayers().getFirst().connection.send(new ClientboundPlayerInfoRemovePacket(List.of(STAFF))));
            context.waitFor(mc -> mc.getConnection().getPlayerInfo(STAFF) == null);
            context.runOnClient(mc -> {
                controller.disconnect(); settings.chatAlerts = settings.spectatorAlerts = settings.vanishAlerts = true;
            });
            context.waitTicks(2);
            server.runOnServer(mc -> mc.getPlayerList().getPlayers().getFirst().connection.send(new ClientboundBundlePacket(List.of(
                    infoPacket(mc, GameType.SPECTATOR, EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED)),
                    new ClientboundPlayerInfoRemovePacket(List.of(STAFF))))));
            context.waitFor(mc -> controller.views().getFirst().recentSpectator() && ((Long) get(controller, "lastChat")) >= 0);
            context.runOnClient(mc -> {
                var view = controller.views().getFirst();
                require(!view.online() && !view.spectator() && !view.nearby(), "A recent report fabricated current presence");
                require(controller.history().stream().anyMatch(event -> event.kind() == StaffDetectorEngine.Kind.SPECTATOR), "Bundled vanish erased spectator history");
            });
            context.takeScreenshot(TestScreenshotOptions.of("staff-detector-06-vanish-transition").withSize(1280, 800));

            // Vanilla ignores this mode-only update because the profile was already removed.
            context.runOnClient(mc -> { controller.disconnect(); settings.chatAlerts = false; }); context.waitTicks(2);
            server.runOnServer(mc -> mc.getPlayerList().getPlayers().getFirst().connection.send(infoPacket(mc, GameType.SPECTATOR,
                    EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE))));
            context.waitFor(mc -> controller.views().getFirst().recentSpectator());
            context.runOnClient(mc -> {
                require(mc.getConnection().getPlayerInfo(STAFF) == null && !controller.views().getFirst().online(), "Orphan mode report fabricated player info");
                require(controller.diagnostics().contains("Spectator reports: 1"), "Raw spectator report missing from diagnostics");
            });

            context.runOnClient(mc -> controller.disconnect()); context.waitTicks(2);
            sendInfo(context, server, GameType.SURVIVAL);
            server.runOnServer(mc -> mc.getPlayerList().getPlayers().getFirst().connection.send(listingPacket(mc, false)));
            context.waitFor(mc -> controller.views().getFirst().online() && !controller.views().getFirst().listed());
            context.runOnClient(mc -> {
                require(!controller.views().getFirst().spectator() && !controller.views().getFirst().recentSpectator(), "Unlisting fabricated spectator mode");
                require(controller.history().stream().anyMatch(event -> event.kind() == StaffDetectorEngine.Kind.TAB_HIDDEN && event.possible()), "No possible-vanish evidence for tab-hidden staff");
            });
            context.takeScreenshot(TestScreenshotOptions.of("staff-detector-07-tab-hidden").withSize(1280, 800));
            server.runOnServer(mc -> mc.getPlayerList().getPlayers().getFirst().connection.send(listingPacket(mc, true)));
            context.waitFor(mc -> controller.views().getFirst().listed());

            // Record configured identity even while disabled; reuse historical profiles for identity only.
            context.runOnClient(mc -> {
                settings.enabled = false; controller.tick(mc); settings.rosters.clear(); settings.add("singleplayer", "FixtureStaff");
            });
            sendInfo(context, server, GameType.SPECTATOR);
            var cached = context.computeOnClient(mc -> mc.getConnection().getPlayerInfo(STAFF));
            require(context.computeOnClient(mc -> STAFF.equals(settings.roster("singleplayer").getFirst().id())
                    && controller.views().isEmpty() && controller.history().isEmpty()), "Disabled detector did not bind identity quietly");
            server.runOnServer(mc -> mc.getPlayerList().getPlayers().getFirst().connection.send(new ClientboundPlayerInfoRemovePacket(List.of(STAFF))));
            context.waitFor(mc -> mc.getConnection().getPlayerInfo(STAFF) == null);
            context.runOnClient(mc -> {
                settings.rosters.clear(); settings.add("singleplayer", "FixtureStaff");
                mc.getConnection().getSeenPlayers().put(STAFF, cached); settings.enabled = true;
            });
            context.waitFor(mc -> STAFF.equals(settings.roster("singleplayer").getFirst().id()));
            context.runOnClient(mc -> {
                var view = controller.views().getFirst();
                require(!view.online() && !view.spectator() && !view.recentSpectator() && controller.history().isEmpty(), "Historical identity leaked stale mode or online status");
                mc.getConnection().getSeenPlayers().remove(STAFF);
            });
        } finally {
            context.runOnClient(mc -> {
                controller.disconnect(); restore(settings, original);
                var store = new dev.donut.client.config.ConfigStore(DonutConfigPaths.resolve(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()).active());
                var saved = store.load(); saved.staffDetector = original; store.save(saved);
                mc.options.guiScale().set(originalScale); mc.resizeDisplay();
            });
        }
        require(controller.views().isEmpty() && controller.history().isEmpty(), "Disconnect retained staff evidence");
    }
    private static void sendInfo(ClientGameTestContext context, net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext server, GameType mode) {
        server.runOnServer(mc -> mc.getPlayerList().getPlayers().getFirst().connection.send(infoPacket(mc, mode,
                EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED))));
        context.waitTicks(2);
    }
    private static ClientboundPlayerInfoUpdatePacket infoPacket(net.minecraft.server.MinecraftServer mc, GameType mode,
                                                               EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions) {
        var fixture = new ServerPlayer(mc, mc.overworld(), new GameProfile(STAFF, "FixtureStaff"), ClientInformation.createDefault()) {
            @Override public GameType gameMode() { return mode; }
        };
        fixture.connection = mc.getPlayerList().getPlayers().getFirst().connection;
        return new ClientboundPlayerInfoUpdatePacket(actions, List.of(fixture));
    }
    private static ClientboundPlayerInfoUpdatePacket listingPacket(net.minecraft.server.MinecraftServer mc, boolean listed) {
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), mc.registryAccess());
        try {
            buffer.writeEnumSet(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED), ClientboundPlayerInfoUpdatePacket.Action.class);
            buffer.writeVarInt(1); buffer.writeUUID(STAFF); buffer.writeBoolean(listed);
            return ClientboundPlayerInfoUpdatePacket.STREAM_CODEC.decode(buffer);
        } finally { buffer.release(); }
    }
    private static ClientboundCommandSuggestionsPacket suggestions(int id) {
        return new ClientboundCommandSuggestionsPacket(id, new Suggestions(StringRange.at(5), List.of(new Suggestion(StringRange.at(5), "FixtureStaff"))));
    }
    private static void set(Object object, String name, Object value) {
        try { var field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static Object get(Object object, String name) {
        try { var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static void restore(StaffDetectorSettings target, StaffDetectorSettings source) {
        try { for (var field : StaffDetectorSettings.class.getFields()) if (!Modifier.isStatic(field.getModifiers())) field.set(target, field.get(source)); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
