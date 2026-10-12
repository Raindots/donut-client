package dev.donut.client.autobuild;

import dev.donut.client.freecam.FreecamController;
import dev.donut.client.mixin.BuilderClientLevelAccessor;
import dev.donut.client.mixin.BuilderLocalPlayerAccessor;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;

/** Vanilla interactions, bounded walking and server-confirmed construction. All access is on the client thread. */
public final class AutoBuilderController {
    public enum Status { IDLE, READY, RUNNING, PAUSED, COMPLETED }
    private static final AutoBuilderController INSTANCE = new AutoBuilderController();
    private final BuilderNavigator navigator = new BuilderNavigator();
    private Schematic schematic;
    private PlacementTransform transform = new PlacementTransform(BlockPos.ZERO, 0, false);
    private boolean previewVisible;
    private Status status = Status.IDLE;
    private String reason = "Choose a schematic.";
    private ClientLevel session;
    private List<Target> targets = List.of();
    private Target[] byIndex = new Target[0];
    private final NavigableSet<Target> work = new TreeSet<>(Comparator.comparingInt((Target t)->t.pos.getY()).thenComparingInt(Target::index));
    private final Map<BlockPos, Integer> indices = new HashMap<>();
    private final BitSet correct = new BitSet();
    private final Map<BlockPos, Integer> failures = new HashMap<>();
    private final Set<BlockPos> deferred = new HashSet<>();
    private final Set<BlockPos> supportBlocks = new HashSet<>();
    private final Map<BlockPos,Integer> unconfirmed = new HashMap<>();
    private ClientLevel predictionLevel;
    private Target current;
    private Prepared prepared;
    private Pending pending;
    private int tick, scanIndex, cooldown, noProgress, revalidateIndex, finalCheckIndex, originalSlot = -1;
    private boolean scanned;
    private String lastFailure = "";

    private record Target(BlockPos pos, BlockState state, int index, boolean support) { }
    private record Prepared(Target target, PlacementPlanner.Plan plan, int readyTick) { }
    private static final class Pending {
        final Target target;
        final BlockState before, predicted;
        final int sentTick, sequence;
        boolean acknowledged;
        BlockState observed;
        Pending(Target target, BlockState before, BlockState predicted, int sentTick, int sequence) {
            this.target=target; this.before=before; this.predicted=predicted; this.sentTick=sentTick; this.sequence=sequence;
        }
    }
    private AutoBuilderController() { }
    public static AutoBuilderController get() { return INSTANCE; }
    public Schematic schematic() { return schematic; }
    public PlacementTransform transform() { return transform; }
    public boolean previewVisible() { return previewVisible; }
    public void setPreviewVisible(boolean value) { previewVisible = value && schematic != null; }
    public Status status() { return status; }
    public String reason() { return reason; }
    public boolean isRunning() { return status == Status.RUNNING; }
    public int confirmedBlocks() { return correct.cardinality(); }
    public double progress() { return targets.isEmpty() ? 0 : (double)correct.cardinality()/targets.size(); }
    public int supportsPlaced() { return supportBlocks.size(); }

    public void setSchematic(Schematic value) {
        cancel(); schematic = value; rebuildTargets(); previewVisible = value != null;
        status = value == null ? Status.IDLE : Status.READY;
        reason = value == null ? "Choose a schematic." : "Position the preview, then Start.";
    }
    public void setTransform(PlacementTransform value) {
        if (transform.equals(value)) return;
        cancel(); transform = Objects.requireNonNull(value); rebuildTargets();
        status = schematic == null ? Status.IDLE : Status.READY; reason = "Position updated.";
    }
    private void rebuildTargets() {
        indices.clear(); correct.clear(); failures.clear(); deferred.clear(); supportBlocks.clear(); scanIndex=0; scanned=false;
        work.clear(); revalidateIndex=0; finalCheckIndex=0;
        if (schematic == null) { targets=List.of(); byIndex=new Target[0]; return; }
        List<Target> values = new ArrayList<>(schematic.blocks().size());
        for (Schematic.Block block : schematic.blocks()) {
            BlockPos pos = transform.apply(block.pos());
            int index=values.size(); values.add(new Target(pos,transform.apply(block.state()),index,false)); indices.put(pos,index);
        }
        // Low structural blocks become supports for later floors, attachments and redstone.
        values.sort(Comparator.comparingInt((Target t) -> t.pos.getY())
                .thenComparingInt(t -> MaterialTracker.isSecondary(t.state) ? 2 : t.state.getBlock() instanceof FallingBlock ? 1 : 0));
        targets=List.copyOf(values); byIndex=new Target[values.size()];
        for (Target target:values) byIndex[target.index]=target;
        work.addAll(values);
    }
    public void start() {
        Minecraft client=Minecraft.getInstance();
        if (schematic == null || targets.isEmpty()) { reason="Load a schematic containing blocks."; return; }
        if (!canRun(client)) return;
        if (predictionLevel!=client.level) { unconfirmed.clear(); predictionLevel=client.level; }
        correct.clear(); failures.clear(); deferred.clear(); scanIndex=0; scanned=false;
        work.clear(); work.addAll(targets); revalidateIndex=0; finalCheckIndex=0;
        session=client.level; tick=0; noProgress=0; lastFailure=""; current=null; pending=null; prepared=null;
        originalSlot=client.player.getInventory().getSelectedSlot(); status=Status.RUNNING; previewVisible=true;
        reason="Checking loaded blocks...";
    }
    public void pause() { pauseWith("Paused by you."); }
    private void pauseWith(String message) {
        if (status != Status.RUNNING && status != Status.PAUSED) { reason=message; return; }
        status=Status.PAUSED; reason=message; prepared=null; navigator.release(); restoreSlot();
    }
    public void resume() {
        if (status != Status.PAUSED) { start(); return; }
        Minecraft client=Minecraft.getInstance();
        if (!canRun(client)) return;
        if (session != client.level) { start(); return; }
        failures.clear(); deferred.clear(); lastFailure=""; noProgress=0; current=null; prepared=null; navigator.reset();
        work.clear(); for (Target target:targets) if (!correct.get(target.index)) work.add(target);
        originalSlot=client.player.getInventory().getSelectedSlot(); status=Status.RUNNING; reason="Resuming...";
    }
    private boolean canRun(Minecraft client) {
        if (client.level == null || client.player == null || client.gameMode == null) { reason="Join a world before starting."; return false; }
        if (FreecamController.enabled()) { reason="Disable Freecam before resuming AutoBuilder."; return false; }
        if (client.gameMode.isSpectator()) { reason="Spectator mode cannot place blocks."; return false; }
        if (client.player.isPassenger()) { reason="Leave the vehicle before starting AutoBuilder."; return false; }
        if (client.screen != null) { reason="Close the open menu before resuming."; return false; }
        return true;
    }
    public void cancel() {
        navigator.reset(); restoreSlot(); current=null; prepared=null; pending=null; session=null;
        failures.clear(); deferred.clear(); cooldown=0;
        status=schematic == null ? Status.IDLE : Status.READY; reason="Build cancelled.";
    }
    public void disconnect() {
        if (isRunning() || status==Status.PAUSED) { pauseWith("Disconnected. Rejoin, check the position, then Resume."); }
        navigator.reset(); restoreSlot(); session=null; pending=null; prepared=null; current=null; correct.clear(); scanIndex=0; scanned=false;
        unconfirmed.clear(); predictionLevel=null;
        previewVisible=false;
    }
    private void restoreSlot() {
        var player=Minecraft.getInstance().player;
        if (player != null && originalSlot >= 0) player.getInventory().setSelectedSlot(originalSlot);
        originalSlot=-1;
    }

    public void tick(Minecraft client) {
        if (!isRunning()) return;
        tick++;
        for (var key:List.of(client.options.keyAttack,client.options.keyUse,client.options.keyDrop,client.options.keySwapOffhand)) {
            key.setDown(false); while (key.consumeClick()) { }
        }
        if (client.level == null || client.player == null || client.gameMode == null || client.level != session) { disconnect(); return; }
        if (FreecamController.enabled()) { pauseWith("Freecam is active. Disable it, then Resume."); return; }
        if (client.screen != null || client.isPaused()) { pauseWith("An open menu paused construction. Close it, then Resume."); return; }
        if (!client.player.isAlive() || client.player.isRemoved()) { pauseWith("Player unavailable. Resume after respawning."); return; }
        if (client.player.isPassenger()) { pauseWith("Leave the vehicle, then Resume."); return; }
        if (client.player.isUsingItem()) { pauseWith("Finish using the held item, then Resume."); return; }
        if (scanned) revalidate(client,256);
        if (pending != null) {
            navigator.hold(client,false);
            if (pending.acknowledged || tick-pending.sentTick > 60) finishPending(client);
            return;
        }
        if (prepared != null) {
            navigator.hold(client,shouldSneak(client,prepared.target,prepared.plan));
            aim(client,prepared.plan);
            if (tick>=prepared.readyTick) sendPrepared(client);
            return;
        }
        if (cooldown>0) { cooldown--; return; }
        if (!scanned) {
            int end=Math.min(targets.size(),scanIndex+2048);
            for (;scanIndex<end;scanIndex++) {
                Target target=targets.get(scanIndex);
                if (client.level.isOutsideBuildHeight(target.pos)) { pauseWith("Outside world build height at " + coordinates(target.pos) + ". Reposition the schematic."); return; }
                if (!client.level.getWorldBorder().isWithinBounds(target.pos)) { pauseWith("Outside the world border at " + coordinates(target.pos) + ". Reposition the schematic."); return; }
                if (!unconfirmed.containsKey(target.pos) && MaterialTracker.isLoaded(client.level,target.pos)
                        && MaterialTracker.matches(target.state,client.level.getBlockState(target.pos))) markCorrect(target);
            }
            if (scanIndex<targets.size()) return;
            scanned=true;
        }
        if (correct.cardinality()==targets.size()) {
            navigator.release(); reason="Verifying the completed structure...";
            int end=Math.min(targets.size(),finalCheckIndex+2048);
            for (;finalCheckIndex<end;finalCheckIndex++) {
                Target target=targets.get(finalCheckIndex);
                if (unconfirmed.containsKey(target.pos) || !MaterialTracker.isLoaded(client.level,target.pos)
                        || !MaterialTracker.matches(target.state,client.level.getBlockState(target.pos))) {
                    correct.clear(target.index); work.add(target); finalCheckIndex=0; return;
                }
            }
            if (finalCheckIndex==targets.size()) complete();
            return;
        }
        finalCheckIndex=0;
        if (current == null) current=chooseTarget(client);
        if (current == null) {
            pauseWith(lastFailure.isEmpty() ? "No placeable blocks remain. Check unloaded areas, supports and occupied positions." : lastFailure);
            return;
        }
        if (client.level.isOutsideBuildHeight(current.pos)) { pauseWith("Outside world build height at " + coordinates(current.pos) + ". Reposition the schematic."); return; }
        if (!client.level.getWorldBorder().isWithinBounds(current.pos)) { pauseWith("Outside the world border at " + coordinates(current.pos) + ". Reposition the schematic."); return; }
        if (!MaterialTracker.isLoaded(client.level,current.pos)) { defer("Area is unloaded at " + coordinates(current.pos) + ". Move closer, then Resume."); return; }
        if (unconfirmed.containsKey(current.pos)) { pauseWith("Still waiting for server confirmation at " + coordinates(current.pos) + ". Resume after the connection recovers."); return; }
        BlockState actual=client.level.getBlockState(current.pos);
        if (MaterialTracker.matches(current.state,actual)) { markCorrect(current); current=null; return; }
        if (MaterialTracker.isSecondary(current.state)) { defer("Paired block is missing at " + coordinates(current.pos) + ". Check its door, bed or plant base."); return; }
        String manual=manualStateReason(current.state,actual);
        if (manual!=null) { defer(manual + " at " + coordinates(current.pos) + ". Complete it manually, then Resume."); return; }
        if (!PlacementPlanner.canAdd(current.state,actual) && !PlacementPlanner.needsConfiguration(current.state,actual)) {
            defer("Occupied or incorrect block at " + coordinates(current.pos) + ". Correct it manually, then Resume."); return;
        }
        var requirement=MaterialTracker.requirement(current.state);
        if (requirement.item()==Items.AIR || !(requirement.item() instanceof BlockItem)) {
            defer("This block requires manual placement: " + current.state.getBlock().getName().getString() + " at " + coordinates(current.pos)); return;
        }
        if (!current.state.canSurvive(client.level,current.pos) || !hasNeighborSupport(client,current.pos)) {
            Target support=necessarySupport(client,current);
            if (support!=null) { current=support; return; }
            // A support belonging to the schematic may still be awaiting construction.
            defer("Missing support at " + coordinates(current.pos) + ". Provide solid supports or access, then Resume."); return;
        }
        boolean configuring=PlacementPlanner.needsConfiguration(current.state,actual);
        int slot=configuring ? client.player.getInventory().getSelectedSlot() : findSlot(client,requirement.item());
        if (slot<0) { pauseWith("Missing " + requirement.item().getName().getString() + ". Add materials, then Resume."); return; }
        if (slot>=9) {
            int selected=client.player.getInventory().getSelectedSlot();
            client.gameMode.handleInventoryMouseClick(client.player.inventoryMenu.containerId,slot,selected,ClickType.SWAP,client.player);
            cooldown=2; reason="Moving material into the hotbar..."; return;
        }
        client.player.getInventory().setSelectedSlot(slot);
        var plan=PlacementPlanner.plan(client,current.pos,current.state,client.player.getMainHandItem());
        if (plan != null) {
            navigator.hold(client,shouldSneak(client,current,plan));
            aim(client,plan);
            prepared=new Prepared(current,plan,tick+2);
            reason=(current.support ? "Placing a temporary support" : plan.configure() ? "Setting block state" : "Placing block") + " at " + coordinates(current.pos);
            noProgress=0; return;
        }
        Target lateral=lateralSupport(client,current);
        if (lateral!=null) { current=lateral; return; }
        Target access=accessSupport(client,current);
        if (access!=null) { current=access; return; }
        BuilderNavigator.Result movement=navigator.moveTo(client,current.pos);
        if (movement==BuilderNavigator.Result.ARRIVED) { navigator.rejectCurrent(client); noProgress++; }
        else if (movement==BuilderNavigator.Result.NO_PATH) { defer("No walking route to " + coordinates(current.pos) + ". Provide a path or stairs, then Resume."); return; }
        else if (movement==BuilderNavigator.Result.STUCK) { noProgress+=30; }
        else noProgress++;
        reason="Walking to " + coordinates(current.pos) + " / " + navigator.routeLength() + " steps";
        if (noProgress>300) defer("Cannot reach a valid placement face or orientation at " + coordinates(current.pos) + ". Provide access, then Resume.");
    }
    private Target chooseTarget(Minecraft client) {
        Target best=null; double bestScore=Double.POSITIVE_INFINITY;
        if (work.isEmpty()) return null;
        int minimumY=work.first().pos.getY(), examined=0;
        for (Target target:work) {
            if (++examined>256 || target.pos.getY()>minimumY+1) break;
            double score=client.player.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(target.pos))
                    + (target.pos.getY()-minimumY)*64 + (MaterialTracker.isSecondary(target.state)?512:0);
            if (score<bestScore) { bestScore=score; best=target; }
        }
        return best;
    }
    private void revalidate(Minecraft client,int budget) {
        for (int checked=0;checked<Math.min(budget,targets.size());checked++) {
            if (revalidateIndex>=targets.size()) revalidateIndex=0;
            Target target=targets.get(revalidateIndex++);
            if (unconfirmed.containsKey(target.pos) || (pending!=null && pending.target.pos.equals(target.pos))) continue;
            boolean matches=MaterialTracker.isLoaded(client.level,target.pos) && MaterialTracker.matches(target.state,client.level.getBlockState(target.pos));
            if (matches) markCorrect(target);
            else if (correct.get(target.index)) { correct.clear(target.index); if (!deferred.contains(target.pos)) work.add(target); }
        }
    }
    private void sendPrepared(Minecraft client) {
        Prepared action=prepared; prepared=null;
        if (!action.target.equals(current)) return;
        // Recompute after walking/input has settled; entities and the world may have moved.
        var plan=PlacementPlanner.plan(client,current.pos,current.state,client.player.getMainHandItem());
        if (plan==null) { navigator.rejectCurrent(client); return; }
        boolean sneak=shouldSneak(client,current,plan);
        navigator.hold(client,sneak);
        aim(client,plan);
        var sent=(BuilderLocalPlayerAccessor)client.player;
        // Let vanilla send rotation/input once per client tick before the interaction. An extra
        // movement packet here duplicates LocalPlayer.sendPosition and violates strict packet ordering.
        if (Math.abs(net.minecraft.util.Mth.wrapDegrees(sent.donut$lastSentYaw()-plan.yaw()))>.001f
                || Math.abs(sent.donut$lastSentPitch()-plan.pitch())>.001f
                || sent.donut$lastSentInput()==null || sent.donut$lastSentInput().shift()!=sneak) {
            prepared=new Prepared(current,plan,tick+1);
            reason="Aligning for placement at " + coordinates(current.pos);
            return;
        }
        BlockState before=client.level.getBlockState(current.pos);
        var result=client.gameMode.useItemOn(client.player,InteractionHand.MAIN_HAND,plan.hit());
        int sequence=((BuilderClientLevelAccessor)client.level).donut$predictionHandler().currentSequence();
        unconfirmed.put(current.pos,sequence);
        BlockPos secondary=secondaryPosition(current);
        if (secondary!=null) unconfirmed.put(secondary,sequence);
        pending=new Pending(current,before,plan.prediction(),tick,sequence);
        if (result.consumesAction()) client.player.swing(InteractionHand.MAIN_HAND);
        reason="Waiting for the server at " + coordinates(current.pos);
    }
    private static void aim(Minecraft client,PlacementPlanner.Plan plan) {
        client.player.setYRot(plan.yaw()); client.player.setXRot(plan.pitch());
    }
    public void serverBlockUpdate(BlockPos pos,BlockState state) {
        unconfirmed.remove(pos);
        if (session==null) return;
        Integer index=indices.get(pos);
        if (index!=null) {
            // Index refers to the original list, not its sorted order.
            BlockState desired=transform.apply(schematic.blocks().get(index).state());
            if (MaterialTracker.matches(desired,state)) { markCorrect(byIndex[index]); deferred.remove(pos); }
            else { correct.clear(index); if (!deferred.contains(pos)) work.add(byIndex[index]); finalCheckIndex=0; }
        }
        if (pending!=null && pending.target.pos.equals(pos)) pending.observed=state;
    }
    public void serverAcknowledged(int sequence) {
        unconfirmed.entrySet().removeIf(entry->entry.getValue()<=sequence);
        if (pending!=null && sequence>=pending.sequence) pending.acknowledged=true;
    }
    public void positionCorrected() { if (isRunning()) { navigator.replan(); prepared=null; reason="Server corrected the position. Replanning..."; } }
    private void finishPending(Minecraft client) {
        Pending action=pending;
        if (!action.acknowledged && action.observed==null) {
            pauseWith("No server confirmation at " + coordinates(action.target.pos) + ". Check the connection, then Resume.");
            return;
        }
        pending=null;
        BlockState actual=action.observed==null ? client.level.getBlockState(action.target.pos) : action.observed;
        boolean changed=action.acknowledged && !actual.equals(action.before);
        if (action.observed!=null && !action.observed.equals(action.before)) changed=true;
        if (MaterialTracker.matches(action.target.state,actual) && changed) {
            markCorrect(action.target); failures.remove(action.target.pos);
            for (BlockPos pos:deferred) { Integer index=indices.get(pos); if (index!=null && !correct.get(index)) work.add(byIndex[index]); }
            deferred.clear(); current=null; noProgress=0; cooldown=2;
        } else if (changed && actual.getBlock()==action.target.state.getBlock()
                && (PlacementPlanner.canAdd(action.target.state,actual) || PlacementPlanner.needsConfiguration(action.target.state,actual))) {
            // Double slabs, stackable decorations and configurable redstone require further interactions.
            failures.remove(action.target.pos); cooldown=2;
        } else {
            int attempts=failures.merge(action.target.pos,1,Integer::sum); navigator.rejectCurrent(client); cooldown=4;
            reason="Server rejected placement at " + coordinates(action.target.pos) + " (" + attempts + "/3). Replanning...";
            if (attempts>=3) pauseWith("Server rejected or changed the requested state at " + coordinates(action.target.pos)
                    + ". Check build permissions, supports and state compatibility, then Resume.");
        }
    }
    private void markCorrect(Target target) {
        if (target.support) supportBlocks.add(target.pos); else { correct.set(target.index); work.remove(target); }
    }
    private void defer(String message) {
        lastFailure=message;
        if (current.support) { pauseWith(message); current=null; return; }
        deferred.add(current.pos); work.remove(current); current=null; noProgress=0; navigator.reset();
    }
    private void complete() {
        status=Status.COMPLETED; navigator.reset(); restoreSlot(); current=null;
        reason="Complete: " + correct.cardinality() + " blocks confirmed." + (supportBlocks.isEmpty()?"":" " + supportBlocks.size() + " temporary supports remain.");
        if (schematic.blocks().stream().anyMatch(block->block.blockEntity()!=null)) reason += " Stored block-entity data needs server support.";
    }
    private static String coordinates(BlockPos pos) { return pos.getX()+", "+pos.getY()+", "+pos.getZ(); }
    private static int findSlot(Minecraft client,Item item) {
        for (int i=0;i<36;i++) if (client.player.getInventory().getItem(i).is(item)) return i;
        return -1;
    }
    private static BlockPos secondaryPosition(Target target) {
        if ((target.state.getBlock() instanceof DoorBlock || target.state.getBlock() instanceof DoublePlantBlock)
                && target.state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)==DoubleBlockHalf.LOWER)
            return target.pos.above();
        if (target.state.getBlock() instanceof BedBlock && target.state.getValue(BedBlock.PART)==BedPart.FOOT)
            return target.pos.relative(target.state.getValue(BedBlock.FACING));
        return null;
    }
    private static boolean shouldSneak(Minecraft client,Target target,PlacementPlanner.Plan plan) {
        // Sneaking prevents opening a support container, but also prevents slab/stack-item merging.
        return !plan.configure() && client.level.getBlockState(target.pos).getBlock()!=target.state.getBlock();
    }
    private static String manualStateReason(BlockState desired,BlockState actual) {
        if (desired.hasProperty(BlockStateProperties.WATERLOGGED) && desired.getValue(BlockStateProperties.WATERLOGGED)
                && (desired.getBlock()!=actual.getBlock() || !actual.getValue(BlockStateProperties.WATERLOGGED)))
            return "Waterlogged blocks require water placement";
        for (var property:desired.getProperties()) {
            if ((property.getName().equals("age") || property.getName().equals("stage"))
                    && desired.getValue(property) instanceof Integer age && age>0)
                return "This grown block state requires growth or server support";
        }
        if (desired.getBlock() instanceof CandleBlock && desired.getValue(BlockStateProperties.LIT))
            return "Lit candles require ignition";
        if (desired.getBlock() instanceof LeavesBlock && !desired.getValue(BlockStateProperties.PERSISTENT))
            return "Natural leaf persistence requires server state support";
        return null;
    }
    private static boolean hasNeighborSupport(Minecraft client,BlockPos pos) {
        BlockState actual=client.level.getBlockState(pos);
        if (!actual.isAir()) return true;
        for (Direction direction:Direction.values()) {
            BlockPos neighbor=pos.relative(direction);
            if (!MaterialTracker.isLoaded(client.level,neighbor)) continue;
            var state=client.level.getBlockState(neighbor);
            if (!state.canBeReplaced() && !state.getCollisionShape(client.level,neighbor).isEmpty()) return true;
        }
        return false;
    }
    private Target necessarySupport(Minecraft client,Target target) {
        BlockPos below=target.pos.below();
        if (indices.containsKey(below)) return null;
        Item material=null;
        for (Item candidate:List.of(Items.DIRT,Items.COBBLESTONE,Items.STONE,Items.NETHERRACK)) if (findSlot(client,candidate)>=0) { material=candidate; break; }
        if (!(material instanceof BlockItem item)) return null;
        // Build a short support column from its existing foundation, never overwrite a schematic cell.
        BlockPos base=below;
        for (int depth=0;depth<12;depth++,base=base.below()) {
            if (!MaterialTracker.isLoaded(client.level,base) || indices.containsKey(base) || !client.level.getBlockState(base).canBeReplaced()) return null;
            BlockPos foundation=base.below();
            if (!MaterialTracker.isLoaded(client.level,foundation)) return null;
            if (client.level.getBlockState(foundation).isCollisionShapeFullBlock(client.level,foundation))
                return new Target(base,item.getBlock().defaultBlockState(),-1,true);
        }
        return null;
    }
    private Target lateralSupport(Minecraft client,Target target) {
        boolean topSlab=target.state.getBlock() instanceof SlabBlock && target.state.getValue(SlabBlock.TYPE)==SlabType.TOP;
        boolean topStair=target.state.getBlock() instanceof StairBlock && target.state.getValue(StairBlock.HALF)==Half.TOP;
        if (target.support || (!topSlab && !topStair)) return null;
        List<Direction> directions=new ArrayList<>();
        if (topStair) {
            Direction facing=target.state.getValue(StairBlock.FACING);
            // A support behind the desired look direction exposes a face with the required stair yaw.
            // The opposite face would force the wrong direction, so it never blocks a suitable new support.
            directions.add(facing); directions.add(facing.getClockWise()); directions.add(facing.getCounterClockWise());
        } else {
            for (Direction direction:Direction.Plane.HORIZONTAL) directions.add(direction);
            // The face of a support beyond the target is visible from the player's current side.
            directions.sort(Comparator.comparingDouble((Direction direction)->
                    -client.player.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(target.pos.relative(direction)))));
        }
        for (Direction direction:directions) {
            BlockPos neighbor=target.pos.relative(direction);
            if (!MaterialTracker.isLoaded(client.level,neighbor)) continue;
            var state=client.level.getBlockState(neighbor);
            var shape=state.getCollisionShape(client.level,neighbor);
            if (!state.canBeReplaced() && !shape.isEmpty() && shape.bounds().maxY>.65) return null;
        }
        Item material=null;
        for (Item candidate:List.of(Items.DIRT,Items.COBBLESTONE,Items.STONE,Items.NETHERRACK))
            if (findSlot(client,candidate)>=0) { material=candidate; break; }
        if (!(material instanceof BlockItem item)) return null;
        for (Direction direction:directions) {
            BlockPos neighbor=target.pos.relative(direction);
            if (indices.containsKey(neighbor) || !MaterialTracker.isLoaded(client.level,neighbor)
                    || !MaterialTracker.isLoaded(client.level,neighbor.below())
                    || !client.level.getBlockState(neighbor).canBeReplaced()
                    || !client.level.getBlockState(neighbor.below()).isCollisionShapeFullBlock(client.level,neighbor.below())) continue;
            return new Target(neighbor,item.getBlock().defaultBlockState(),-1,true);
        }
        return null;
    }
    private Target accessSupport(Minecraft client,Target target) {
        if (target.support || target.pos.getY()-client.player.getY()<1.6) return null;
        Item material=null;
        for (Item candidate:List.of(Items.DIRT,Items.COBBLESTONE,Items.STONE,Items.NETHERRACK))
            if (findSlot(client,candidate)>=0) { material=candidate; break; }
        if (!(material instanceof BlockItem item)) return null;
        Target nearest=null; double distance=Double.POSITIVE_INFINITY;
        // A single adjacent step permits walking onto a two-block support column.
        // Taller access routes remain bounded and must use existing stairs or scaffold terraces.
        for (Direction direction:Direction.Plane.HORIZONTAL) {
            BlockPos step=target.pos.below(2).relative(direction);
            if (MaterialTracker.isLoaded(client.level,step)
                    && client.level.getBlockState(step).isCollisionShapeFullBlock(client.level,step)) return null;
        }
        for (Direction direction:Direction.Plane.HORIZONTAL) {
            BlockPos step=target.pos.below(2).relative(direction);
            if (indices.containsKey(step) || !MaterialTracker.isLoaded(client.level,step)
                    || !client.level.getBlockState(step).canBeReplaced()
                    || !client.level.getBlockState(step.below()).isCollisionShapeFullBlock(client.level,step.below())) continue;
            double candidate=client.player.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(step));
            if (candidate<distance) { distance=candidate; nearest=new Target(step,item.getBlock().defaultBlockState(),-1,true); }
        }
        return nearest;
    }

    /** Creative inventory packets preserve occupied slots and respect each item's stack limit. */
    public void getMaterials(Minecraft client) {
        if (schematic==null || client.player==null || client.level==null || client.gameMode==null) return;
        if (!client.player.getAbilities().instabuild || !client.gameMode.getPlayerMode().isCreative()) { reason="Get Materials requires Creative access."; return; }
        if (client.player.containerMenu!=client.player.inventoryMenu) { reason="Close the container before getting materials."; return; }
        MaterialTracker.Snapshot snapshot=MaterialTracker.snapshot(client,schematic,transform);
        int added=0, omitted=0;
        for (MaterialTracker.Row row:snapshot.rows()) {
            if (row.item()==Items.AIR) continue;
            int need=Math.max(0,row.remaining()-row.hotbar()-row.inventory());
            for (int slot=0;slot<36 && need>0;slot++) {
                ItemStack existing=client.player.getInventory().getItem(slot);
                if (!existing.isEmpty() && !existing.is(row.item())) continue;
                int room=row.item().getDefaultInstance().getMaxStackSize()-(existing.isEmpty()?0:existing.getCount());
                if (room<=0) continue;
                int count=Math.min(room,need); ItemStack stack=existing.isEmpty()?new ItemStack(row.item(),count):existing.copyWithCount(existing.getCount()+count);
                client.player.getInventory().setItem(slot,stack);
                client.gameMode.handleCreativeModeItemAdd(stack,slot<9?slot+36:slot);
                need-=count; added+=count;
            }
            omitted+=need;
        }
        reason="Added " + added + " items." + (omitted>0?" Inventory full: " + omitted + " items still missing.":"");
    }
}
