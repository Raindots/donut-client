package dev.donut.client.gametest;

import dev.donut.client.amethyst.AmethystScanner;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.ConfigStore;
import dev.donut.client.config.DonutConfigPaths;
import dev.donut.client.gui.DonutScreen;
import dev.donut.client.input.ModuleKeybinds;
import dev.donut.client.render.HighlightRenderer;
import com.mojang.blaze3d.platform.InputConstants;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/** Received palettes and light, all source modes, detail limit, tracer and real shortcut wiring. */
public final class AmethystGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        var config = new ClientConfig();
        var scanner = new AmethystScanner(config);
        var active = new AtomicBoolean(true);
        int originalScale = context.computeOnClient(client -> client.options.guiScale().get());
        String originalKey = context.computeOnClient(client -> ModuleKeybinds.mapping(10).saveString());
        var renderer = context.computeOnClient(client -> {
            client.options.guiScale().set(1); client.resizeDisplay();
            var result = new HighlightRenderer(config,null,scanner);
            result.register();
            ClientTickEvents.END_CLIENT_TICK.register(mc -> { if(active.get()) scanner.tick(mc); });
            return result;
        });
        context.getInput().resizeWindow(1280,800);
        try (var world = context.worldBuilder().create()) {
            var server = world.getServer();
            server.runCommand("gamemode creative @a");
            server.runCommand("gamerule minecraft:random_tick_speed 0");
            server.runCommand("fill -4 -59 -8 4 -59 0 minecraft:stone");
            server.runCommand("tp @a 0.5 -58 -5.5 0 0");
            server.runCommand("setblock 3 -51 10 minecraft:budding_amethyst");
            server.runCommand("setblock 3 -50 10 minecraft:small_amethyst_bud[facing=up]");
            server.runCommand("setblock 18 -51 10 minecraft:budding_amethyst");
            server.runCommand("setblock 18 -50 10 minecraft:medium_amethyst_bud[facing=up]");
            server.runCommand("setblock 5 -50 8 minecraft:amethyst_block");
            context.waitTicks(5);
            world.getClientWorld().waitForChunksRender();
            context.waitFor(client -> client.player.onGround() && Math.abs(client.player.getY()+58)<.01);
            require(scanner.snapshot().isEmpty(),"Disabled amethyst scanner produced markers");
            context.runOnClient(client -> config.amethyst.enabled=true);
            context.waitFor(client -> scanner.snapshot().size()==2);
            context.runOnClient(client -> require(scanner.snapshot().stream().map(pos->ChunkPos.asLong(pos.getX()>>4,pos.getZ()>>4)).distinct().count()==2,
                    "Bypass did not emit one palette marker per matching chunk"));
            aim(context,Vec3.atCenterOf(scanner.snapshot().getFirst()));
            Path without=screenshot(context,"amethyst-01-palette-bypass");
            context.runOnClient(client -> config.amethyst.tracer=true);
            context.waitTicks(4);
            Path with=screenshot(context,"amethyst-02-tracers");
            try {
                var a=ImageIO.read(without.toFile()); var b=ImageIO.read(with.toFile()); int changed=0;
                for(int y=0;y<700;y++)for(int x=0;x<1280;x++) {
                    int first=a.getRGB(x,y),second=b.getRGB(x,y);
                    int delta=Math.abs((first>>16&255)-(second>>16&255))+Math.abs((first>>8&255)-(second>>8&255))+Math.abs((first&255)-(second&255));
                    if(delta>60)changed++;
                }
                require(changed>30,"Tracer setting did not add rendered lines");
            } catch(Exception error) { throw new AssertionError(error); }
            context.runOnClient(client -> { config.amethyst.tracer=false; config.amethyst.method="Light"; });
            light(server,5,false);
            context.waitFor(client -> scanner.snapshot().contains(new BlockPos(4,-50,8)));
            require(scanner.snapshot().size()==1,"Light method included an unrelated non-maximum cell");
            screenshot(context,"amethyst-03-hidden-bud-light");
            context.runOnClient(client -> config.amethyst.method="ANBS+ Scan");
            light(server,4,false);
            // The solid amethyst block at (5,-50,8) excludes one of the 18 air-edge cells.
            context.waitFor(client -> scanner.snapshot().size()==17);
            screenshot(context,"amethyst-04-anbs");
            context.runOnClient(client -> config.amethyst.minCells=18);
            context.waitFor(client -> scanner.snapshot().isEmpty());
            context.runOnClient(client -> config.amethyst.minCells=12);
            context.waitFor(client -> scanner.snapshot().size()==17);
            light(server,4,true);
            context.waitFor(client -> scanner.snapshot().size()==200);
            light(server,0,false);
            context.waitFor(client -> scanner.snapshot().isEmpty());
            context.runOnClient(client -> config.amethyst.enabled=false);
            context.waitTicks(2);
            require(scanner.snapshot().isEmpty(),"Disable retained amethyst findings");
            testGui(context,config);
            context.runOnClient(client -> { config.amethyst.enabled=true; config.amethyst.method="Bypass"; });
            context.waitFor(client -> scanner.snapshot().size()==2);
            server.runCommand("tp @a 600.5 -58 600.5");
            context.waitTicks(100);
            context.waitFor(client -> scanner.snapshot().isEmpty());
        } finally {
            context.setScreen(()->null);
            active.set(false);
            context.runOnClient(client -> {
                config.amethyst.enabled=false; scanner.clear(); renderer.close();
                ModuleKeybinds.setKey(client,10,InputConstants.getKey(originalKey));
                client.options.guiScale().set(originalScale); client.resizeDisplay();
            });
        }
    }

    private static void testGui(ClientGameTestContext context,ClientConfig config) {
        Path fixture=FabricLoader.getInstance().getConfigDir().resolve("amethyst-gametest.json");
        var store=new ConfigStore(fixture);
        try {
            GuiTestInput.openAnimated(context,()->new DonutScreen(config,()->store.save(config)));
            GuiTestInput.click(context,"tab0");
            GuiTestInput.click(context,"search"); GuiTestInput.type(context,"amethyst");
            require(GuiTestInput.visible(context,"module10"),"Amethyst scanner missing from Visual search");
            GuiTestInput.click(context,"module10");
            GuiTestInput.click(context,"m10.method2");
            require(config.amethyst.method.equals("ANBS+ Scan"),"ANBS method did not select");
            GuiTestInput.click(context,"m10.minCells");
            require(config.amethyst.minCells==51,"Min Cells slider did not select its midpoint");
            GuiTestInput.click(context,"m10.method1");
            require(config.amethyst.method.equals("Light"),"Light method did not select");
            GuiTestInput.click(context,"m10.method0");
            require(config.amethyst.method.equals("Bypass"),"Bypass method did not select");
            GuiTestInput.click(context,"m10.tracer");
            GuiTestInput.click(context,"favorite10");
            require(store.load().amethyst.favorite && store.load().amethyst.tracer,"Amethyst preferences did not save");
            GuiTestInput.clickWithFade(context,"tab1","contentProgress");
            require(GuiTestInput.visible(context,"module10"),"Amethyst missing from Favorites");
            GuiTestInput.click(context,"keybind10"); context.getInput().pressKey(GLFW.GLFW_KEY_F10);
            screenshot(context,"amethyst-05-settings");
            GuiTestInput.closeAnimated(context);
            context.runOnClient(client -> config.amethyst.enabled=false);
            var productionStore=new ConfigStore(DonutConfigPaths.resolve(FabricLoader.getInstance().getConfigDir()).active());
            boolean before=productionStore.load().amethyst.enabled;
            context.getInput().pressKey(GLFW.GLFW_KEY_F10);
            context.waitTicks(5);
            require(productionStore.load().amethyst.enabled!=before,"Amethyst production shortcut did not activate");
            screenshot(context,"amethyst-06-production-hud");
            context.getInput().pressKey(GLFW.GLFW_KEY_F10);
            context.waitTicks(3);
            require(productionStore.load().amethyst.enabled==before,"Amethyst production shortcut did not restore state");
        } finally {
            context.setScreen(()->null);
            try { Files.deleteIfExists(fixture); } catch(Exception error) { throw new AssertionError(error); }
        }
    }

    private static void light(TestServerContext server,int strength,boolean dense) {
        server.runOnServer(mc -> {
            var engine=mc.overworld().getChunkSource().getLightEngine();
            var packet=new ClientboundLightUpdatePacket(new ChunkPos(0,0),engine,null,null);
            var data=packet.getLightData();
            data.getBlockYMask().clear(); data.getEmptyBlockYMask().clear(); data.getBlockUpdates().clear();
            int section=-4-engine.getMinLightSection();
            if(strength>0) {
                var layer=new DataLayer();
                if(dense) for(int x:new int[]{2,5,8,11,14})for(int z:new int[]{2,5,8,11,14})for(int y:new int[]{-56,-53,-50})layer.set(x,y&15,z,strength);
                else for(int x:strength==5?new int[]{4}:new int[]{4,8,12})layer.set(x,-50&15,8,strength);
                data.getBlockYMask().set(section);data.getBlockUpdates().add(layer.getData());
            } else data.getEmptyBlockYMask().set(section);
            mc.getPlayerList().getPlayers().getFirst().connection.send(packet);
        });
    }
    private static void aim(ClientGameTestContext context,Vec3 target) {
        context.runOnClient(client -> {
            Vec3 d=target.subtract(client.player.getEyePosition());
            float yaw=(float)Math.toDegrees(Math.atan2(d.z,d.x))-90,pitch=(float)-Math.toDegrees(Math.atan2(d.y,Math.hypot(d.x,d.z)));
            client.player.setYRot(yaw);client.player.yRotO=yaw;client.player.setXRot(pitch);client.player.xRotO=pitch;
        });
        context.waitTicks(4);
    }
    private static Path screenshot(ClientGameTestContext context,String name) {
        context.runOnClient(client->{client.gui.getChat().clearMessages(false);client.getToastManager().clear();});
        context.waitTick();
        return context.takeScreenshot(TestScreenshotOptions.of(name).withSize(1280,800));
    }
    private static void require(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
}
