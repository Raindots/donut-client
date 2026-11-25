package dev.donut.client.gametest;

import dev.donut.client.config.*;
import dev.donut.client.gui.DonutScreen;
import dev.donut.client.input.ModuleKeybinds;
import dev.donut.client.light.LightDebugScanner;
import dev.donut.client.render.HighlightRenderer;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.DataLayer;
import org.lwjgl.glfw.GLFW;

/** Received underground block light, denoise, settings invalidation, native drawing and production binding. */
public final class LightDebugGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        var config=new ClientConfig();config.lightDebug.radius=1;
        var scanner=new LightDebugScanner(config);var active=new AtomicBoolean(true);
        int scale=context.computeOnClient(mc->mc.options.guiScale().get());
        String binding=context.computeOnClient(mc->ModuleKeybinds.mapping(11).saveString());
        Path fixture=FabricLoader.getInstance().getConfigDir().resolve("light-debug-gametest.json");var store=new ConfigStore(fixture);
        var renderer=context.computeOnClient(mc->{
            mc.options.guiScale().set(1);mc.resizeDisplay();
            var r=new HighlightRenderer(config,null,null,scanner);r.register();
            ClientTickEvents.END_CLIENT_TICK.register(client->{if(active.get())scanner.tick(client);});return r;
        });
        context.getInput().resizeWindow(1280,800);
        try(var world=context.worldBuilder().create()) {
            var server=world.getServer();server.runCommand("gamemode creative @a");
            server.runCommand("fill -8 -59 -8 24 -59 24 minecraft:stone");
            server.runCommand("fill -8 -45 -8 24 -45 24 minecraft:stone");
            server.runCommand("tp @a 8.5 -58 -1.5 0 -30");context.waitTicks(10);world.getClientWorld().waitForChunksRender();
            light(server,7,0);context.waitTicks(5);require(scanner.snapshot().isEmpty(),"Disabled Light Debug emitted geometry");
            context.runOnClient(mc->config.lightDebug.enabled=true);context.waitTicks(35);
            require(scanner.snapshot().isEmpty(),"Denoise retained an isolated weak source");
            context.runOnClient(mc->config.lightDebug.denoise=false);context.waitFor(mc->count(scanner)==3);
            context.waitTicks(5);context.takeScreenshot(TestScreenshotOptions.of("light-debug-01-underground").withSize(1280,800));
            context.runOnClient(mc->config.lightDebug.minLight=8);context.waitFor(mc->scanner.snapshot().isEmpty());
            light(server,14,0);context.waitFor(mc->count(scanner)==3);
            light(server,14,15);context.waitFor(mc->scanner.snapshot().isEmpty());
            context.runOnClient(mc->config.lightDebug.enabled=false);context.waitTicks(2);require(scanner.snapshot().isEmpty(),"Disable did not clear geometry");
            GuiTestInput.openAnimated(context,()->new DonutScreen(config,()->store.save(config)));
            GuiTestInput.click(context,"search");GuiTestInput.type(context,"light debug");
            require(GuiTestInput.visible(context,"module11"),"Light Debug missing from Visual");
            GuiTestInput.click(context,"module11");GuiTestInput.click(context,"m11.denoise");
            GuiTestInput.click(context,"m11.radius");GuiTestInput.click(context,"m11.minLight");GuiTestInput.click(context,"favorite11");
            require(store.load().lightDebug.favorite&&store.load().lightDebug.radius==9&&store.load().lightDebug.minLight==8,"Light preferences did not persist");
            GuiTestInput.click(context,"keybind11");context.getInput().pressKey(GLFW.GLFW_KEY_F9);
            context.takeScreenshot(TestScreenshotOptions.of("light-debug-02-settings").withSize(1280,800));
            GuiTestInput.closeAnimated(context);
            var production=new ConfigStore(DonutConfigPaths.resolve(FabricLoader.getInstance().getConfigDir()).active());boolean before=production.load().lightDebug.enabled;
            context.getInput().pressKey(GLFW.GLFW_KEY_F9);context.waitTicks(4);require(production.load().lightDebug.enabled!=before,"Light shortcut did not toggle production module");
            context.getInput().pressKey(GLFW.GLFW_KEY_F9);context.waitTicks(4);require(production.load().lightDebug.enabled==before,"Light shortcut failed to restore state");
        } finally {
            active.set(false);context.runOnClient(mc->{scanner.close();renderer.close();ModuleKeybinds.setKey(mc,11,com.mojang.blaze3d.platform.InputConstants.getKey(binding));mc.options.guiScale().set(scale);mc.resizeDisplay();});
            try{Files.deleteIfExists(fixture);}catch(Exception error){throw new AssertionError(error);}
        }
        require(scanner.snapshot().isEmpty(),"World disconnect left light geometry");
    }
    private static int count(LightDebugScanner scanner){return scanner.snapshot().values().stream().mapToInt(java.util.List::size).sum();}
    private static void light(TestServerContext server,int strength,int skylight){
        server.runOnServer(mc->{
            var engine=mc.overworld().getChunkSource().getLightEngine();var packet=new ClientboundLightUpdatePacket(new ChunkPos(0,0),engine,null,null);var data=packet.getLightData();
            data.getBlockYMask().clear();data.getEmptyBlockYMask().clear();data.getBlockUpdates().clear();data.getSkyYMask().clear();data.getEmptySkyYMask().clear();data.getSkyUpdates().clear();
            int section=-4-engine.getMinLightSection();var block=new DataLayer();
            block.set(8,14,8,strength);block.set(9,14,8,Math.max(0,strength-1));block.set(10,14,8,Math.max(0,strength-2));
            data.getBlockYMask().set(section);data.getBlockUpdates().add(block.getData());
            if(skylight==0)data.getEmptySkyYMask().set(section);else{var sky=new DataLayer();sky.fill(skylight);data.getSkyYMask().set(section);data.getSkyUpdates().add(sky.getData());}
            mc.getPlayerList().getPlayers().getFirst().connection.send(packet);
        });
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
