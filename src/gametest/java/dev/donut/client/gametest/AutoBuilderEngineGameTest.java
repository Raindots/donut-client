package dev.donut.client.gametest;

import dev.donut.client.autobuild.*;
import dev.donut.client.freecam.FreecamController;
import dev.donut.client.mixin.BuilderLocalPlayerAccessor;
import java.util.List;
import java.util.ArrayList;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;

/** Runs the production placement, packets, material swaps, walking and pause lifecycle against a real server. */
public final class AutoBuilderEngineGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        AutoBuilderController builder=AutoBuilderController.get();
        context.runOnClient(client->{ builder.cancel(); FreecamController.getInstance().setEnabled(false); });
        try {
            try (var world=context.worldBuilder().create()) {
                TestServerContext server=world.getServer();
                server.runCommand("gamemode creative @a");
                server.runCommand("time set noon");
                server.runCommand("fill -30 64 -30 30 64 30 minecraft:smooth_stone");
                server.runCommand("tp @a 0.5 65 4.5 180 15");
                context.waitTicks(10);
                world.getClientWorld().waitForChunksRender();
                context.waitFor(client->client.player!=null && client.player.onGround());
                var originalInput=context.computeOnClient(client->client.player.input);
                context.runOnClient(client->AutoBuilderPacketTrace.begin(client.getConnection()));

                List<Schematic.Block> states=List.of(
                        block(0,0,0,Blocks.STONE.defaultBlockState()),
                        block(2,0,0,Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE,SlabType.DOUBLE)),
                        block(4,0,0,Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING,Direction.NORTH)),
                        block(6,0,0,Blocks.REPEATER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.SOUTH)
                                .setValue(BlockStateProperties.DELAY,3)),
                        block(8,0,0,Blocks.SNOW.defaultBlockState().setValue(BlockStateProperties.LAYERS,3)),
                        block(10,0,0,Blocks.REDSTONE_WIRE.defaultBlockState()
                                .setValue(BlockStateProperties.NORTH_REDSTONE,RedstoneSide.SIDE).setValue(BlockStateProperties.SOUTH_REDSTONE,RedstoneSide.SIDE)
                                .setValue(BlockStateProperties.EAST_REDSTONE,RedstoneSide.SIDE).setValue(BlockStateProperties.WEST_REDSTONE,RedstoneSide.SIDE)),
                        block(12,0,0,Blocks.COMPARATOR.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.SOUTH)
                                .setValue(BlockStateProperties.MODE_COMPARATOR,ComparatorMode.SUBTRACT)));
                context.runOnClient(client->{
                    builder.setSchematic(schematic("placement fixture",states));
                    builder.setTransform(new PlacementTransform(new BlockPos(0,65,0),0,false));
                    builder.getMaterials(client);
                    require(client.player.getInventory().contains(new ItemStack(Items.STONE)),"Creative material fixture not granted");
                    builder.start();
                });
                awaitFinished(context,builder,550);
                context.runOnClient(client->{
                    require(builder.status()==AutoBuilderController.Status.COMPLETED,"Nearby build failed: " + builder.reason());
                    require(builder.confirmedBlocks()==states.size(),"Progress omitted exact block states");
                    require(client.player.input==originalInput,"Completing did not restore player movement");
                    AutoBuilderPacketTrace.assertHealthy(10);
                });
                assertServerStates(server,states,new BlockPos(0,65,0));

                // Top halves require a side placement face even when the entire ground floor is solid.
                server.runCommand("give @a minecraft:dirt 8");
                server.runCommand("tp @a -19.5 65 -1.5 180 15");
                context.waitTicks(5);
                var topHalves=List.of(
                        block(0,0,0,Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE,SlabType.TOP)),
                        block(4,0,0,Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING,Direction.NORTH).setValue(StairBlock.HALF,Half.TOP)));
                context.runOnClient(client->{
                    builder.setSchematic(schematic("top half fixture",topHalves));
                    builder.setTransform(new PlacementTransform(new BlockPos(-20,65,-5),0,false));
                    builder.getMaterials(client); builder.start();
                });
                awaitFinished(context,builder,450);
                context.runOnClient(client->{
                    require(builder.status()==AutoBuilderController.Status.COMPLETED,"Top slab or upside-down stair failed: " + builder.reason());
                    require(builder.supportsPlaced()>=2,"Side placement supports were not tracked");
                });
                assertServerStates(server,topHalves,new BlockPos(-20,65,-5));

                // Door, bed and plant secondary cells are created by their one primary placement item.
                server.runCommand("setblock -10 64 -15 minecraft:dirt");
                server.runCommand("tp @a -17.5 65 -11.5 180 15");
                context.waitTicks(5);
                BlockState door=Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.NORTH);
                BlockState bed=Blocks.RED_BED.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.NORTH);
                BlockState plant=Blocks.TALL_GRASS.defaultBlockState();
                var pairs=List.of(block(0,0,0,door),block(0,1,0,door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,DoubleBlockHalf.UPPER)),
                        block(4,0,1,bed),block(4,0,0,bed.setValue(BlockStateProperties.BED_PART,BedPart.HEAD)),
                        block(8,0,1,plant),block(8,1,1,plant.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,DoubleBlockHalf.UPPER)));
                context.runOnClient(client->{
                    builder.setSchematic(schematic("paired block fixture",pairs));
                    builder.setTransform(new PlacementTransform(new BlockPos(-18,65,-16),0,false));
                    builder.getMaterials(client); builder.start();
                });
                awaitFinished(context,builder,400);
                context.runOnClient(client->{
                    require(builder.status()==AutoBuilderController.Status.COMPLETED,"Paired block placement failed: " + builder.reason());
                    require(builder.confirmedBlocks()==6,"Paired secondary cells were omitted from confirmed progress");
                });
                assertServerStates(server,pairs,new BlockPos(-18,65,-16));

                // Cancel/reselect within one client turn exposes prediction before any server callback can run.
                server.runCommand("tp @a 20.5 65 -11.5 180 15");
                context.waitTicks(5);
                context.runOnClient(client->{
                    builder.setSchematic(schematic("confirmation footprint fixture",List.of(block(0,0,0,door),
                            block(0,1,0,door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,DoubleBlockHalf.UPPER)))));
                    builder.setTransform(new PlacementTransform(new BlockPos(20,65,-15),0,false));
                    builder.getMaterials(client); builder.start();
                });
                // A real player tick must transmit aim and sneak before manually dispatching in one client turn.
                context.waitFor(client->{
                    Object prepared=field(builder,"prepared");
                    if (prepared==null) return false;
                    Object target=field(prepared,"target");
                    BlockPos targetPos=(BlockPos)field(target,"pos");
                    BlockState desired=(BlockState)field(target,"state");
                    // The first sneak tick changes eye height; a stored plan can still describe standing aim.
                    var plan=PlacementPlanner.plan(client,targetPos,desired,client.player.getMainHandItem());
                    if (plan==null) return false;
                    boolean sneak=!plan.configure() && client.level.getBlockState(targetPos).getBlock()!=desired.getBlock();
                    var sent=(BuilderLocalPlayerAccessor)client.player;
                    return Math.abs(net.minecraft.util.Mth.wrapDegrees(sent.donut$lastSentYaw()-plan.yaw()))<=.001f
                            && Math.abs(sent.donut$lastSentPitch()-plan.pitch())<=.001f
                            && sent.donut$lastSentInput()!=null && sent.donut$lastSentInput().shift()==sneak;
                });
                context.runOnClient(client->{
                    for (int step=0;step<4 && !client.level.getBlockState(new BlockPos(20,66,-15)).is(Blocks.OAK_DOOR);step++) builder.tick(client);
                    require(client.level.getBlockState(new BlockPos(20,66,-15)).is(Blocks.OAK_DOOR),
                            "The door secondary cell was not predicted for the regression fixture: " + builder.reason());
                    builder.cancel();
                    builder.setSchematic(schematic("secondary prediction fixture",List.of(block(0,0,0,door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,DoubleBlockHalf.UPPER)))));
                    builder.setTransform(new PlacementTransform(new BlockPos(20,66,-15),0,false));
                    builder.start(); builder.tick(client);
                    require(builder.confirmedBlocks()==0 && builder.status()==AutoBuilderController.Status.PAUSED,
                            "An unacknowledged paired secondary cell was counted after cancel and reselection");
                });
                context.waitTicks(5);
                server.runOnServer(mc->require(mc.overworld().getBlockState(new BlockPos(20,66,-15))
                        .equals(door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,DoubleBlockHalf.UPPER)),"The confirmation footprint fixture was not placed on the server"));

                // A full inventory permits topping up one compatible stack and never replaces other items.
                context.runOnClient(client->{
                    List<ItemStack> inventory=new ArrayList<>();
                    for (int slot=0;slot<36;slot++) {
                        inventory.add(client.player.getInventory().getItem(slot).copy());
                        ItemStack replacement=new ItemStack(slot==0?Items.STONE:Items.DIAMOND,slot==0?63:64);
                        client.player.getInventory().setItem(slot,replacement);
                        client.gameMode.handleCreativeModeItemAdd(replacement,slot<9?slot+36:slot);
                    }
                    List<Schematic.Block> many=new ArrayList<>();
                    for (int x=0;x<70;x++) many.add(block(x,0,0,Blocks.STONE.defaultBlockState()));
                    builder.setSchematic(new Schematic("capacity fixture",many,new BlockPos(70,1,1),1,List.of()));
                    builder.setTransform(new PlacementTransform(new BlockPos(-25,65,-25),0,false));
                    builder.getMaterials(client);
                    require(client.player.getInventory().getItem(0).getCount()==64,"Get Materials did not top up a partial stack to its capacity");
                    for (int slot=1;slot<36;slot++) require(client.player.getInventory().getItem(slot).is(Items.DIAMOND)
                            && client.player.getInventory().getItem(slot).getCount()==64,"Get Materials overwrote an occupied inventory slot");
                    require(builder.reason().contains("Inventory full"),"Get Materials did not report excess requirements");
                    for (int slot=0;slot<36;slot++) {
                        client.player.getInventory().setItem(slot,inventory.get(slot));
                        client.gameMode.handleCreativeModeItemAdd(inventory.get(slot),slot<9?slot+36:slot);
                    }
                    builder.setSchematic(schematic("build height fixture",List.of(block(0,0,0,Blocks.STONE.defaultBlockState()))));
                    builder.setTransform(new PlacementTransform(new BlockPos(0,4096,0),0,false)); builder.start();
                });
                awaitFinished(context,builder,20);
                context.runOnClient(client->require(builder.status()==AutoBuilderController.Status.PAUSED
                        && builder.reason().startsWith("Outside world build height"),"Out-of-height schematic did not pause with its real cause"));

                // A tall obstacle forces A* to walk around it; a server teleport forces replanning.
                server.runCommand("fill 7 65 9 7 66 13 minecraft:stone");
                server.runCommand("tp @a 0.5 65 11.5 -90 10");
                context.waitTicks(5);
                var distant=List.of(block(0,0,0,Blocks.COBBLESTONE.defaultBlockState()));
                context.runOnClient(client->{
                    builder.setSchematic(schematic("navigation fixture",distant));
                    builder.setTransform(new PlacementTransform(new BlockPos(18,65,11),0,false));
                    builder.getMaterials(client); builder.start();
                });
                context.waitTicks(20);
                server.runCommand("tp @a 1.5 65 15.5 -90 10");
                awaitFinished(context,builder,450);
                context.runOnClient(client->{
                    require(builder.status()==AutoBuilderController.Status.COMPLETED,"Navigation failed: " + builder.reason());
                    require(client.player.getX()>12,"Builder did not walk into placement range");
                    require(client.player.input==originalInput,"Navigation completion did not restore input");
                });
                assertServerStates(server,distant,new BlockPos(18,65,11));

                // Support columns are also placed by vanilla interactions and remain explicitly tracked.
                server.runCommand("tp @a -12.5 65 0.5 -90 15");
                server.runCommand("give @a minecraft:dirt 8");
                context.waitTicks(5);
                var elevated=List.of(block(0,0,0,Blocks.SAND.defaultBlockState()));
                context.runOnClient(client->{
                    builder.setSchematic(schematic("support fixture",elevated));
                    builder.setTransform(new PlacementTransform(new BlockPos(-10,67,0),0,false));
                    builder.getMaterials(client); builder.start();
                });
                awaitFinished(context,builder,220);
                context.runOnClient(client->{
                    require(builder.status()==AutoBuilderController.Status.COMPLETED,"Support placement failed: " + builder.reason());
                    require(builder.supportsPlaced()>=2,"Required support column was not tracked");
                });
                assertServerStates(server,elevated,new BlockPos(-10,67,0));
                server.runOnServer(mc->{
                    require(mc.overworld().getBlockState(new BlockPos(-10,65,0)).is(Blocks.DIRT)
                            && mc.overworld().getBlockState(new BlockPos(-10,66,0)).is(Blocks.DIRT),
                            "Support column was not authoritative on the server");
                });

                context.runOnClient(client->{
                    builder.setSchematic(schematic("menu fixture",distant));
                    builder.setTransform(new PlacementTransform(new BlockPos(22,65,20),0,false));
                    builder.getMaterials(client); builder.start();
                });
                context.waitTicks(3);
                context.runOnClient(client->client.setScreen(new InventoryScreen(client.player)));
                context.waitTicks(2);
                context.runOnClient(client->{
                    require(builder.status()==AutoBuilderController.Status.PAUSED,"An inventory menu did not pause construction");
                    require(client.player.input==originalInput,"Menu pause left movement overridden");
                    client.setScreen(null); builder.resume();
                });
                context.waitTicks(3);
                context.runOnClient(client->FreecamController.getInstance().setEnabled(true));
                context.waitTicks(2);
                context.runOnClient(client->{
                    require(builder.status()==AutoBuilderController.Status.PAUSED,"Freecam did not pause construction");
                    FreecamController.getInstance().setEnabled(false);
                    require(client.player.input==originalInput,"Freecam restored builder input after pause");
                    builder.cancel();
                });

                // Missing items pause in survival; Creative material provisioning cannot grant anything there.
                server.runCommand("gamemode survival @a"); server.runCommand("clear @a");
                server.runCommand("tp @a 0.5 65 -8.5 180 10");
                context.waitTicks(5);
                var missing=List.of(block(0,0,0,Blocks.QUARTZ_BLOCK.defaultBlockState()));
                context.runOnClient(client->{
                    builder.setSchematic(schematic("missing fixture",missing));
                    builder.setTransform(new PlacementTransform(new BlockPos(0,65,-11),0,false));
                    builder.getMaterials(client);
                    require(client.player.getInventory().isEmpty(),"Get Materials granted items without Creative access");
                    builder.start();
                });
                awaitFinished(context,builder,20);
                context.runOnClient(client->require(builder.status()==AutoBuilderController.Status.PAUSED && builder.reason().startsWith("Missing "),
                        "Missing materials did not pause with a useful reason: " + builder.reason()));

                // Adventure restrictions are server authoritative even when the schematic and inventory match.
                server.runCommand("gamemode adventure @a"); server.runCommand("give @a minecraft:quartz_block 8");
                context.waitTicks(5);
                context.runOnClient(client->builder.resume());
                awaitFinished(context,builder,240);
                context.runOnClient(client->{
                    require(builder.status()==AutoBuilderController.Status.PAUSED,"Server rejection was counted as construction");
                    require(builder.confirmedBlocks()==0,"Rejected block contributed to progress");
                });
                server.runOnServer(mc->require(mc.overworld().getBlockState(new BlockPos(0,65,-11)).isAir(),"Adventure fixture unexpectedly built a block"));

                // Deliberately leave a run active: the real disconnect event must release navigation.
                server.runCommand("gamemode creative @a"); context.waitTicks(3);
                context.runOnClient(client->{
                    AutoBuilderPacketTrace.assertHealthy(20);
                    System.out.println("AutoBuilder packet regression passed: " + AutoBuilderPacketTrace.summary());
                    AutoBuilderPacketTrace.stop();
                    builder.getMaterials(client); builder.resume();
                });
            }
            context.runOnClient(client->require(!builder.isRunning() && !builder.previewVisible(),"Disconnect did not stop AutoBuilder"));
        } finally {
            context.runOnClient(client->{ AutoBuilderPacketTrace.stop(); builder.cancel(); builder.setSchematic(null); FreecamController.getInstance().setEnabled(false); });
        }
    }
    private static void awaitFinished(ClientGameTestContext context,AutoBuilderController builder,int maximumTicks) {
        for (int elapsed=0;elapsed<maximumTicks;elapsed++) {
            if (context.computeOnClient(client->!builder.isRunning())) return;
            context.waitTick();
        }
        throw new AssertionError("AutoBuilder did not settle within " + maximumTicks + " ticks: " + context.computeOnClient(client->
                builder.reason() + " / player=" + client.player.position() + " / target=" + client.level.getBlockState(builder.transform().origin())
                        + " / below=" + client.level.getBlockState(builder.transform().origin().below())
                        + " / foundation=" + client.level.getBlockState(builder.transform().origin().below(2))));
    }
    private static Schematic.Block block(int x,int y,int z,BlockState state) { return new Schematic.Block(new BlockPos(x,y,z),state,null); }
    private static Schematic schematic(String name,List<Schematic.Block> blocks) {
        int x=1,y=1,z=1;
        for (Schematic.Block block:blocks) {
            x=Math.max(x,block.pos().getX()+1);
            y=Math.max(y,block.pos().getY()+1);
            z=Math.max(z,block.pos().getZ()+1);
        }
        return new Schematic(name,blocks,new BlockPos(x,y,z),1,List.of());
    }
    private static void assertServerStates(TestServerContext server,List<Schematic.Block> blocks,BlockPos origin) {
        server.runOnServer(mc->{
            for (var block:blocks) require(mc.overworld().getBlockState(origin.offset(block.pos())).equals(block.state()),
                    "Server block state differs at " + origin.offset(block.pos()) + ": expected " + block.state() + " actual " + mc.overworld().getBlockState(origin.offset(block.pos())));
        });
    }
    private static void require(boolean condition,String message) { if (!condition) throw new AssertionError(message); }
    private static Object field(Object owner,String name) {
        try {
            var field=owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
        } catch (ReflectiveOperationException error) { throw new AssertionError("Could not inspect builder preparation",error); }
    }
}
