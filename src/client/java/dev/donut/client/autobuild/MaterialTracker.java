package dev.donut.client.autobuild;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CandleCakeBlock;
import net.minecraft.world.level.block.CauldronBlock;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.LavaCauldronBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Counts vanilla placement items and compares only loaded world cells. All calls run on the client thread. */
public final class MaterialTracker {
    public record Requirement(Item item, int count) { }
    public record Row(Item item, String name, int total, int correct, int remaining, int hotbar,
                      int inventory, int missing, int unknown) { }
    public record Snapshot(List<Row> rows, int totalBlocks, int correctBlocks, int remainingBlocks,
                           int unknownBlocks, int conflicts) {
        public Snapshot { rows = List.copyOf(rows); }
    }
    private static final IntegerProperty[] COUNTS = {
            BlockStateProperties.LAYERS, BlockStateProperties.CANDLES, BlockStateProperties.PICKLES,
            BlockStateProperties.EGGS, BlockStateProperties.FLOWER_AMOUNT, BlockStateProperties.SEGMENT_AMOUNT
    };
    private MaterialTracker() { }

    public static boolean matches(BlockState desired, BlockState actual) { return desired.equals(actual); }

    /** ClientLevel.hasChunk returns true even for unloaded chunks; request the actual cache without creating a fallback. */
    public static boolean isLoaded(ClientLevel level, BlockPos pos) {
        return level != null && level.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false) != null;
    }

    /** A door, bed, or two-block plant creates its secondary block with its primary item's placement. */
    public static boolean isSecondary(BlockState state) {
        Block block = state.getBlock();
        return ((block instanceof DoorBlock || block instanceof DoublePlantBlock)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER)
                || (block instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.HEAD);
    }

    public static Requirement requirement(BlockState state) {
        Item item = placementItem(state);
        int count = isSecondary(state) || state.isAir() ? 0 : 1;
        if (count != 0) {
            if (state.getBlock() instanceof SlabBlock && state.getValue(SlabBlock.TYPE) == SlabType.DOUBLE) count = 2;
            for (IntegerProperty property : COUNTS) if (state.hasProperty(property)) count = state.getValue(property);
        }
        return new Requirement(item, count);
    }

    /** Already present slab halves and stackable items reduce the number still needed. */
    public static Requirement remaining(BlockState desired, BlockState actual) {
        Requirement all = requirement(desired);
        if (matches(desired, actual)) return new Requirement(all.item(), 0);
        if (all.item() == Items.CAULDRON && (actual.getBlock() instanceof CauldronBlock
                || actual.getBlock() instanceof LayeredCauldronBlock || actual.getBlock() instanceof LavaCauldronBlock))
            return new Requirement(all.item(), 0);
        if (desired.getBlock() instanceof CandleCakeBlock && (actual.getBlock() instanceof CandleCakeBlock
                || actual.is(Blocks.CAKE) && actual.getValue(BlockStateProperties.BITES) == 0))
            return new Requirement(all.item(), 0);
        if (desired.getBlock() instanceof FlowerPotBlock && actual.getBlock() instanceof FlowerPotBlock)
            return new Requirement(all.item(), 0);
        if (desired.getBlock() != actual.getBlock()) return all;
        boolean onlyConfiguration = true;
        String blockName = BuiltInRegistries.BLOCK.getKey(desired.getBlock()).getPath();
        for (Property<?> property : desired.getProperties()) {
            if (desired.getValue(property).equals(actual.getValue(property))) continue;
            String name = property.getName();
            if (!((blockName.equals("repeater") && name.equals("delay"))
                    || (blockName.equals("comparator") && name.equals("mode"))
                    || (blockName.equals("note_block") && name.equals("note"))
                    || property == BlockStateProperties.WATERLOGGED)) onlyConfiguration = false;
        }
        if (onlyConfiguration) return new Requirement(all.item(), 0);
        if (desired.getBlock() instanceof SlabBlock && desired.getValue(SlabBlock.TYPE) == SlabType.DOUBLE
                && actual.getValue(SlabBlock.TYPE) != SlabType.DOUBLE) return new Requirement(all.item(), 1);
        for (IntegerProperty count : COUNTS) {
            if (!desired.hasProperty(count)) continue;
            boolean compatible = true;
            for (Property<?> property : desired.getProperties()) {
                if (property != count && !desired.getValue(property).equals(actual.getValue(property))) compatible = false;
            }
            if (compatible && actual.getValue(count) < desired.getValue(count))
                return new Requirement(all.item(), desired.getValue(count) - actual.getValue(count));
        }
        return all;
    }

    public static Snapshot snapshot(Minecraft client, Schematic schematic, PlacementTransform transform) {
        Scan scan = beginSnapshot(client, schematic, transform);
        while (!scan.advance(client, Integer.MAX_VALUE, Long.MAX_VALUE)) {
            if (scan.cancelled()) throw new IllegalStateException("World changed during material scan");
        }
        return scan.result();
    }

    public static Scan beginSnapshot(Minecraft client, Schematic schematic, PlacementTransform transform) {
        return new Scan(client, schematic, transform);
    }

    /** A two-pass client-thread scan. Both paired-cell indexing and world reads obey the caller's budget. */
    public static final class Scan {
        private final Schematic schematic;
        private final PlacementTransform transform;
        private final ClientLevel world;
        private final LocalPlayer player;
        private Map<Item, Totals> totals = new HashMap<>();
        private Map<BlockPos, Schematic.Block> byPosition = new HashMap<>();
        private int indexPosition, blockPosition, correct, remaining, unknown, conflicts;
        private boolean cancelled;
        private Snapshot result;

        private Scan(Minecraft client, Schematic schematic, PlacementTransform transform) {
            this.schematic = schematic; this.transform = transform;
            world = client.level; player = client.player;
            if (schematic == null) result = new Snapshot(List.of(), 0, 0, 0, 0, 0);
        }
        public Snapshot result() { return result; }
        public boolean cancelled() { return cancelled; }
        public int processedCells() { return indexPosition + blockPosition; }
        public double progress() {
            return schematic == null || schematic.blocks().isEmpty() ? 1
                    : (double) processedCells() / (2L * schematic.blocks().size());
        }
        public boolean matches(Minecraft client, Schematic value, PlacementTransform placement) {
            return !cancelled && world == client.level && player == client.player
                    && schematic == value && transform.equals(placement);
        }
        public void cancel() {
            cancelled = true; result = null;
            // Dropping the references keeps cancellation independent of the number of indexed cells.
            byPosition = Map.of(); totals = Map.of();
        }
        public boolean advance(Minecraft client, int maxCells, long maxNanos) {
            if (maxCells < 1 || maxNanos < 1) throw new IllegalArgumentException("Material scan budget must be positive");
            if (cancelled) return false;
            if (world != client.level || player != client.player) { cancel(); return false; }
            if (result != null) return true;
            long started = System.nanoTime();
            int visited = 0, size = schematic.blocks().size();
            while (visited < maxCells && System.nanoTime() - started < maxNanos) {
                if (indexPosition < size) {
                    Schematic.Block block = schematic.blocks().get(indexPosition++);
                    if (isPaired(block.state())) byPosition.put(block.pos(), block);
                } else if (blockPosition < size) {
                    accumulate(client, schematic.blocks().get(blockPosition++));
                } else break;
                visited++;
            }
            if (indexPosition == size && blockPosition == size) {
                result = finish(client);
                byPosition = Map.of(); totals = Map.of();
            }
            return result != null;
        }

        private void accumulate(Minecraft client, Schematic.Block block) {
            BlockPos pos = transform.apply(block.pos());
            BlockState desired = transform.apply(block.state());
            boolean loaded = isLoaded(client.level, pos);
            BlockState actual = loaded ? client.level.getBlockState(pos) : null;
            if (!loaded) unknown++;
            else if (MaterialTracker.matches(desired, actual)) correct++;
            else {
                remaining++;
                if (!actual.canBeReplaced() && !canCompleteInPlace(desired, actual)) conflicts++;
            }
            Requirement required = requirement(desired);
            boolean orphanSecondary = false;
            if (required.count() == 0) {
                if (!isSecondary(block.state())) return;
                BlockPos primaryPos = primaryPosition(block.pos(), block.state());
                Schematic.Block primary = byPosition.get(primaryPos);
                if (primary != null && primary.state().getBlock() == block.state().getBlock()
                        && !isSecondary(primary.state()) && block.pos().equals(pairedPosition(primary.pos(), primary.state()))) return;
                // A selection containing only the upper/head still needs the item's primary placement.
                required = new Requirement(required.item(), 1);
                orphanSecondary = true;
            }

            // A matching lower door/foot is only complete when its matching second half is present too.
            BlockPos otherLocal = pairedPosition(block.pos(), block.state());
            boolean pairMatches = true;
            if (otherLocal != null && byPosition.containsKey(otherLocal)) {
                BlockPos otherWorld = transform.apply(otherLocal);
                boolean otherLoaded = isLoaded(client.level, otherWorld);
                loaded &= otherLoaded;
                pairMatches = otherLoaded && MaterialTracker.matches(transform.apply(byPosition.get(otherLocal).state()),
                        client.level.getBlockState(otherWorld));
            }
            Totals row = totals.computeIfAbsent(required.item(), item -> new Totals(item, displayName(item, desired)));
            row.total += required.count();
            if (!loaded) row.unknown += required.count();
            else {
                int need = orphanSecondary ? MaterialTracker.matches(desired, actual) ? 0 : 1
                        : pairMatches ? remaining(desired, actual).count() : required.count();
                row.remaining += need;
                row.correct += required.count() - need;
            }
            // These states require two different items rather than the block's pick-block item alone.
            for (Requirement extra : supplemental(desired)) {
                Totals extraRow = totals.computeIfAbsent(extra.item(), item -> new Totals(item, item.getName().getString()));
                extraRow.total += extra.count();
                if (!loaded) extraRow.unknown += extra.count();
                else if (hasSupplement(desired, actual, extra.item())) extraRow.correct += extra.count();
                else extraRow.remaining += extra.count();
            }
        }

        private Snapshot finish(Minecraft client) {
            if (client.player != null) {
                var inventory = client.player.getInventory();
                for (int slot = 0; slot < 36; slot++) {
                    ItemStack stack = inventory.getItem(slot);
                    Totals row = totals.get(stack.getItem());
                    if (row == null || stack.isEmpty()) continue;
                    if (slot < 9) row.hotbar += stack.getCount(); else row.inventory += stack.getCount();
                }
            }
            List<Row> rows = new ArrayList<>();
            for (Totals row : totals.values()) rows.add(new Row(row.item, row.name, row.total, row.correct, row.remaining,
                    row.hotbar, row.inventory, Math.max(0, row.remaining - row.hotbar - row.inventory), row.unknown));
            rows.sort(Comparator.comparing(Row::name, String.CASE_INSENSITIVE_ORDER));
            return new Snapshot(rows, schematic.blocks().size(), correct, remaining, unknown, conflicts);
        }
    }

    private static boolean canCompleteInPlace(BlockState desired, BlockState actual) {
        return remaining(desired, actual).count() < requirement(desired).count();
    }

    private static BlockPos pairedPosition(BlockPos pos, BlockState state) {
        if (isSecondary(state)) return null;
        if ((state.getBlock() instanceof DoorBlock || state.getBlock() instanceof DoublePlantBlock)
                && state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) return pos.above();
        if (state.getBlock() instanceof BedBlock) return pos.relative(state.getValue(BedBlock.FACING));
        return null;
    }

    public static boolean isPaired(BlockState state) {
        return state.getBlock() instanceof DoorBlock || state.getBlock() instanceof DoublePlantBlock
                || state.getBlock() instanceof BedBlock;
    }

    public static BlockPos primaryPosition(BlockPos pos, BlockState secondary) {
        if (secondary.getBlock() instanceof BedBlock) return pos.relative(secondary.getValue(BedBlock.FACING).getOpposite());
        return pos.below();
    }

    private static Item placementItem(BlockState state) {
        Block block = state.getBlock();
        String name = BuiltInRegistries.BLOCK.getKey(block).getPath();
        return switch (name) {
            case "redstone_wire" -> Items.REDSTONE;
            case "tripwire" -> Items.STRING;
            case "water", "bubble_column" -> Items.WATER_BUCKET;
            case "lava" -> Items.LAVA_BUCKET;
            case "powder_snow" -> Items.POWDER_SNOW_BUCKET;
            case "water_cauldron", "lava_cauldron", "powder_snow_cauldron" -> Items.CAULDRON;
            case "wheat" -> Items.WHEAT_SEEDS;
            case "carrots" -> Items.CARROT;
            case "potatoes" -> Items.POTATO;
            case "beetroots" -> Items.BEETROOT_SEEDS;
            case "cocoa" -> Items.COCOA_BEANS;
            case "sweet_berry_bush" -> Items.SWEET_BERRIES;
            case "cave_vines", "cave_vines_plant" -> Items.GLOW_BERRIES;
            case "kelp_plant" -> Items.KELP;
            case "bamboo_sapling" -> Items.BAMBOO;
            case "pumpkin_stem", "attached_pumpkin_stem" -> Items.PUMPKIN_SEEDS;
            case "melon_stem", "attached_melon_stem" -> Items.MELON_SEEDS;
            case "pitcher_crop" -> Items.PITCHER_POD;
            case "torchflower_crop" -> Items.TORCHFLOWER_SEEDS;
            default -> block instanceof CandleCakeBlock ? Items.CAKE
                    : block instanceof FlowerPotBlock ? Items.FLOWER_POT : block.asItem();
        };
    }

    private static String displayName(Item item, BlockState desired) {
        return item == Items.AIR ? "Blocks with no placement item" : item.getName().getString();
    }

    private static List<Requirement> supplemental(BlockState state) {
        String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        if (state.getBlock() instanceof CandleCakeBlock) {
            String candle = name.substring(0, name.length() - "_cake".length());
            return List.of(new Requirement(BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(candle)), 1));
        }
        if (state.getBlock() instanceof FlowerPotBlock pot && !pot.getPotted().defaultBlockState().isAir())
            return List.of(requirement(pot.getPotted().defaultBlockState()));
        if (name.equals("water_cauldron") || state.hasProperty(BlockStateProperties.WATERLOGGED)
                && state.getValue(BlockStateProperties.WATERLOGGED)) return List.of(new Requirement(Items.WATER_BUCKET, 1));
        if (name.equals("lava_cauldron")) return List.of(new Requirement(Items.LAVA_BUCKET, 1));
        if (name.equals("powder_snow_cauldron")) return List.of(new Requirement(Items.POWDER_SNOW_BUCKET, 1));
        return List.of();
    }

    private static boolean hasSupplement(BlockState desired, BlockState actual, Item item) {
        if (matches(desired, actual)) return true;
        if (item == Items.WATER_BUCKET && actual.hasProperty(BlockStateProperties.WATERLOGGED))
            return actual.getBlock() == desired.getBlock() && actual.getValue(BlockStateProperties.WATERLOGGED);
        return actual.getBlock() == desired.getBlock() && (desired.getBlock() instanceof CandleCakeBlock
                || desired.getBlock() instanceof FlowerPotBlock);
    }

    private static final class Totals {
        final Item item; final String name;
        int total, correct, remaining, hotbar, inventory, unknown;
        Totals(Item item, String name) { this.item = item; this.name = name; }
    }
}
