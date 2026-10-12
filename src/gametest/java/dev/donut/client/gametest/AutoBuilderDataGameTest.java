package dev.donut.client.gametest;

import dev.donut.client.autobuild.LitematicLoader;
import dev.donut.client.autobuild.MaterialTracker;
import dev.donut.client.autobuild.PlacementTransform;
import dev.donut.client.autobuild.Schematic;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;

/** Real registry, file-format and loaded-world tests that cannot run before Minecraft bootstraps. */
public final class AutoBuilderDataGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        context.runOnClient(client -> {
            try {
                validateFormat(client.gameDirectory.toPath().resolve("autobuilder-data-fixture.litematic"));
                validateItemRules();
            } catch (IOException exception) { throw new AssertionError("Schematic fixture failed", exception); }
        });
        AtomicReference<MaterialTracker.Scan> worldScan = new AtomicReference<>();
        try (var world = context.worldBuilder().create()) {
            var server = world.getServer();
            server.runCommand("gamemode creative @a");
            server.runCommand("fill -4 64 -4 14 64 14 minecraft:smooth_stone");
            server.runCommand("fill -4 65 -4 14 67 14 minecraft:air");
            server.runCommand("setblock 0 65 0 minecraft:stone");
            server.runCommand("setblock 1 65 0 minecraft:stone_slab[type=bottom]");
            server.runCommand("setblock 4 65 0 minecraft:red_bed[facing=south,part=foot]");
            server.runCommand("setblock 4 65 1 minecraft:red_bed[facing=south,part=head]");
            server.runCommand("setblock 8 65 0 minecraft:candle[candles=2]");
            server.runCommand("setblock 9 65 0 minecraft:glass");
            server.runCommand("item replace entity @a hotbar.0 with minecraft:stone 2");
            server.runCommand("item replace entity @a inventory.0 with minecraft:stone 5");
            server.runCommand("item replace entity @a inventory.1 with minecraft:stone_slab 1");
            server.runCommand("item replace entity @a weapon.offhand with minecraft:stone 64");
            server.runCommand("tp @a 0.5 65 8.5 180 15");
            context.waitTicks(8);
            context.waitFor(client -> client.player != null && client.level != null
                    && client.level.getBlockState(new BlockPos(9, 65, 0)).is(Blocks.GLASS));
            context.runOnClient(client -> {
                Schematic schematic = materialsFixture();
                MaterialTracker.Snapshot materials = MaterialTracker.snapshot(client, schematic,
                        new PlacementTransform(new BlockPos(0, 65, 0), 0, false));
                PlacementTransform placement = new PlacementTransform(new BlockPos(0, 65, 0), 0, false);
                MaterialTracker.Scan incremental = MaterialTracker.beginSnapshot(client, schematic, placement);
                require(incremental.result() == null && !incremental.advance(client, 3, Long.MAX_VALUE)
                        && incremental.processedCells() == 3 && incremental.result() == null,
                        "Paired-cell indexing must honor the cell budget and never publish a partial result");
                boolean finished = false;
                while (!finished) {
                    int before = incremental.processedCells();
                    finished = incremental.advance(client, 3, Long.MAX_VALUE);
                    require(incremental.processedCells() - before <= 3, "Material scan exceeded its cell budget");
                }
                require(materials.equals(incremental.result()), "Incremental counts must equal the synchronous snapshot");
                require(!incremental.matches(client, schematic, new PlacementTransform(placement.origin(), 1, false)),
                        "A rotated placement must invalidate its old scan context");
                MaterialTracker.Scan cancelled = MaterialTracker.beginSnapshot(client, schematic, placement);
                cancelled.advance(client, 1, Long.MAX_VALUE); cancelled.cancel();
                require(cancelled.cancelled() && cancelled.result() == null && !cancelled.advance(client, 8, Long.MAX_VALUE),
                        "A cancelled scan must not resume or publish stale rows");
                MaterialTracker.Scan unloaded = MaterialTracker.beginSnapshot(client, schematic,
                        new PlacementTransform(new BlockPos(1_000_000, 65, 0), 0, false));
                while (!unloaded.advance(client, 4, Long.MAX_VALUE)) { }
                require(unloaded.result().unknownBlocks() == schematic.blocks().size()
                        && unloaded.result().remainingBlocks() == 0, "Incremental scans must preserve unloaded cells as unknown");
                require(materials.totalBlocks() == 11, "World fixture total blocks");
                require(materials.correctBlocks() == 3, "Exact state progress must count the stone and both bed halves");
                require(materials.remainingBlocks() == 7 && materials.unknownBlocks() == 1,
                        "Unloaded chunks must be unknown rather than air or missing: " + materials);
                require(materials.conflicts() == 1, "The existing glass is a conflict; partial slabs and candles are not");
                var stone = row(materials, Items.STONE);
                require(stone.total() == 3 && stone.correct() == 1 && stone.remaining() == 1 && stone.unknown() == 1,
                        "Unknown stone must remain separate from known remaining stone");
                require(stone.hotbar() == 2 && stone.inventory() == 5 && stone.missing() == 0,
                        "Inventory stocks must be separated without double counting hotbar or offhand");
                var slab = row(materials, Items.STONE_SLAB);
                require(slab.total() == 2 && slab.correct() == 1 && slab.remaining() == 1 && slab.missing() == 0,
                        "A partially built double slab only needs its second slab");
                var candle = row(materials, Items.CANDLE);
                require(candle.total() == 4 && candle.correct() == 2 && candle.remaining() == 2 && candle.missing() == 2,
                        "Stacked candles must be counted as actual items");
                require(row(materials, Items.OAK_DOOR).total() == 1 && row(materials, Items.SUNFLOWER).total() == 1,
                        "Door and plant secondary halves must not count twice");
                require(row(materials, Items.RED_BED).total() == 1 && row(materials, Items.RED_BED).correct() == 1,
                        "A completed two-block bed counts one item");
                Schematic orphan = new Schematic("Upper door selection", List.of(new Schematic.Block(BlockPos.ZERO,
                        Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), null)),
                        new BlockPos(1, 1, 1), 1, List.of());
                var orphanMaterials = MaterialTracker.snapshot(client, orphan,
                        new PlacementTransform(new BlockPos(10, 66, 0), 0, false));
                require(row(orphanMaterials, Items.OAK_DOOR).total() == 1 && row(orphanMaterials, Items.OAK_DOOR).remaining() == 1,
                        "An orphan secondary selection must not incorrectly claim zero placement items");
                MaterialTracker.Scan disconnected = MaterialTracker.beginSnapshot(client, schematic, placement);
                disconnected.advance(client, 1, Long.MAX_VALUE); worldScan.set(disconnected);
            });
        }
        context.runOnClient(client -> {
            MaterialTracker.Scan disconnected = worldScan.get();
            require(!disconnected.advance(client, 4, Long.MAX_VALUE) && disconnected.cancelled()
                    && disconnected.result() == null, "World changes must cancel in-flight material scans");
        });
    }

    private static void validateFormat(Path file) throws IOException {
        CompoundTag fixture = formatFixture();
        try {
            NbtIo.writeCompressed(fixture, file);
            Schematic schematic = LitematicLoader.load(file);
            require(schematic.blocks().size() == 4 && schematic.regionCount() == 2, "Multiple region block count");
            require(schematic.dimensions().equals(new BlockPos(7, 3, 8)), "Signed region bounds");
            require(blockAt(schematic, new BlockPos(4, 2, 3)).state().getValue(SlabBlock.TYPE) == SlabType.DOUBLE,
                    "A three-bit palette entry at bit 63 must cross the long boundary");
            CompoundTag storedEntity = blockAt(schematic, new BlockPos(5, 1, 0)).blockEntity();
            require(storedEntity.getStringOr("CustomName", "").equals("fixture") && storedEntity.getIntOr("x", -1) == 5
                    && storedEntity.getIntOr("y", -1) == 1 && storedEntity.getIntOr("z", -1) == 0,
                    "Region-local block entities must be retained in normalized schematic coordinates");
            require(schematic.warnings().stream().anyMatch(text -> text.contains("block-entity")), "Block entity limitation must be visible");
            NbtIo.write(fixture, file);
            require(LitematicLoader.load(file).blocks().size() == 4, "Uncompressed NBT format fallback");

            CompoundTag malformed = fixture.copy();
            region(malformed, "negative").putLongArray("BlockStates", new long[0]);
            rejected(malformed, "packed BlockStates length");
            malformed = fixture.copy();
            long[] packed = region(malformed, "negative").getLongArray("BlockStates").orElseThrow();
            packed[0] = (packed[0] & ~7L) | 7L;
            region(malformed, "negative").putLongArray("BlockStates", packed);
            rejected(malformed, "palette index");
            malformed = fixture.copy();
            region(malformed, "negative").getList("BlockStatePalette").orElseThrow().getCompoundOrEmpty(1)
                    .putString("Name", "minecraft:unregistered_fixture_block");
            rejected(malformed, "Unknown block");
            malformed = fixture.copy();
            region(malformed, "negative").getList("BlockStatePalette").orElseThrow().getCompoundOrEmpty(2)
                    .getCompoundOrEmpty("Properties").putString("type", "invalid");
            rejected(malformed, "Invalid type");
            malformed = fixture.copy();
            region(malformed, "negative").getCompoundOrEmpty("Size").putInt("x", 0);
            rejected(malformed, "region axis");
            malformed = fixture.copy();
            region(malformed, "negative").put("Size", xyz(8192, 8192, 8192));
            rejected(malformed, "region cells");
            malformed = fixture.copy();
            malformed.putInt("MinecraftDataVersion", SharedConstants.getCurrentVersion().dataVersion().version() + 1);
            rejected(malformed, "newer Minecraft");
            malformed = fixture.copy();
            malformed.putFloat("Version", 7);
            rejected(malformed, "integer Version");
            malformed = fixture.copy();
            region(malformed, "negative").getList("TileEntities").orElseThrow().getCompoundOrEmpty(0).putString("id", "minecraft:sign");
            rejected(malformed, "Invalid block entity");
            malformed = fixture.copy();
            region(malformed, "negative").put("Entities", StringTag.valueOf("bad list"));
            rejected(malformed, "list Entities");

            CompoundTag overlapping = fixture.copy();
            overlapping.getCompoundOrEmpty("Regions").put("overlap", regionTag(new BlockPos(2, 1, -2),
                    new BlockPos(1, 1, 1), List.of(stateTag("stone")), new int[] {0}));
            require(LitematicLoader.read(overlapping, "fixture").blocks().size() == 4, "Identical overlaps count once");
            region(overlapping, "overlap").getList("BlockStatePalette").orElseThrow().getCompoundOrEmpty(0)
                    .putString("Name", "minecraft:glass");
            rejected(overlapping, "Conflicting overlapping");

            CompoundTag chestOverlap = fixture.copy();
            CompoundTag sameChest = regionTag(new BlockPos(3, 1, -2), new BlockPos(1, 1, 1),
                    List.of(stateTag("chest")), new int[] {0});
            CompoundTag chestEntity = xyz(0, 0, 0); chestEntity.putString("id", "minecraft:chest");
            chestEntity.putString("CustomName", "fixture");
            ListTag chestEntities = new ListTag(); chestEntities.add(chestEntity); sameChest.put("TileEntities", chestEntities);
            chestOverlap.getCompoundOrEmpty("Regions").put("same-chest", sameChest);
            require(LitematicLoader.read(chestOverlap, "fixture").blocks().size() == 4,
                    "Identical overlapping block entities with different region origins must deduplicate");
            chestEntity.putString("CustomName", "different contents");
            rejected(chestOverlap, "Conflicting overlapping");

            CompoundTag large = rootTag();
            int[] values = new int[65_536];
            java.util.Arrays.fill(values, 1);
            large.getCompoundOrEmpty("Regions").put("large", regionTag(BlockPos.ZERO, new BlockPos(256, 1, 256),
                    List.of(stateTag("air"), stateTag("stone")), values));
            require(LitematicLoader.read(large, "large").blocks().size() == values.length, "Large schematic decoding");
        } finally { Files.deleteIfExists(file); }
    }

    private static void validateItemRules() {
        require(MaterialTracker.requirement(Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE)).count() == 2,
                "Double slabs consume two items");
        require(MaterialTracker.requirement(Blocks.SNOW.defaultBlockState().setValue(BlockStateProperties.LAYERS, 8)).count() == 8,
                "Snow layers consume the selected number of snow items");
        require(MaterialTracker.requirement(Blocks.SEA_PICKLE.defaultBlockState().setValue(BlockStateProperties.PICKLES, 3)).count() == 3,
                "Sea pickle counts");
        require(MaterialTracker.requirement(Blocks.TURTLE_EGG.defaultBlockState().setValue(BlockStateProperties.EGGS, 4)).count() == 4,
                "Turtle egg counts");
        require(MaterialTracker.requirement(Blocks.REDSTONE_WIRE.defaultBlockState()).item() == Items.REDSTONE, "Wire is redstone dust");
        require(MaterialTracker.requirement(Blocks.TRIPWIRE.defaultBlockState()).item() == Items.STRING, "Tripwire is string");
        require(MaterialTracker.requirement(Blocks.WHEAT.defaultBlockState()).item() == Items.WHEAT_SEEDS, "Wheat uses seeds");
        require(MaterialTracker.requirement(Blocks.WATER.defaultBlockState()).item() == Items.WATER_BUCKET, "Water uses a bucket");
        BlockState repeater = Blocks.REPEATER.defaultBlockState();
        require(MaterialTracker.remaining(repeater.setValue(BlockStateProperties.DELAY, 4), repeater).count() == 0,
                "Configuring an already placed repeater must not require a spare repeater");
        require(MaterialTracker.remaining(repeater.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST), repeater).count() == 1,
                "An incorrectly oriented repeater remains a block conflict");
        var stair = Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        require(new PlacementTransform(BlockPos.ZERO, 1, true).apply(stair).getValue(BlockStateProperties.HORIZONTAL_FACING) == Direction.NORTH,
                "Directional block-state mirroring must agree with coordinate transforms");
    }

    private static Schematic materialsFixture() {
        List<Schematic.Block> blocks = new ArrayList<>();
        add(blocks, 0, 0, 0, Blocks.STONE.defaultBlockState());
        add(blocks, 1, 0, 0, Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE));
        add(blocks, 2, 0, 0, Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        add(blocks, 2, 1, 0, Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        BlockState bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH);
        add(blocks, 4, 0, 0, bed.setValue(BedBlock.PART, BedPart.FOOT));
        add(blocks, 4, 0, 1, bed.setValue(BedBlock.PART, BedPart.HEAD));
        add(blocks, 6, 0, 0, Blocks.SUNFLOWER.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER));
        add(blocks, 6, 1, 0, Blocks.SUNFLOWER.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER));
        add(blocks, 8, 0, 0, Blocks.CANDLE.defaultBlockState().setValue(BlockStateProperties.CANDLES, 4));
        add(blocks, 9, 0, 0, Blocks.STONE.defaultBlockState());
        add(blocks, 1024, 0, 0, Blocks.STONE.defaultBlockState());
        return new Schematic("Materials fixture", blocks, new BlockPos(1025, 2, 2), 1, List.of());
    }
    private static void add(List<Schematic.Block> blocks, int x, int y, int z, BlockState state) {
        blocks.add(new Schematic.Block(new BlockPos(x, y, z), state, null));
    }
    private static MaterialTracker.Row row(MaterialTracker.Snapshot snapshot, Item item) {
        return snapshot.rows().stream().filter(row -> row.item() == item).findFirst().orElseThrow();
    }
    private static Schematic.Block blockAt(Schematic schematic, BlockPos pos) {
        return schematic.blocks().stream().filter(block -> block.pos().equals(pos)).findFirst().orElseThrow();
    }
    private static void rejected(CompoundTag tag, String expected) {
        try { LitematicLoader.read(tag, "fixture"); }
        catch (IOException exception) {
            require(exception.getMessage().contains(expected), "Wrong rejection: " + exception.getMessage());
            return;
        }
        throw new AssertionError("Malformed fixture was accepted: " + expected);
    }
    private static CompoundTag formatFixture() {
        CompoundTag root = rootTag();
        int[] ids = new int[24]; ids[0] = 1; ids[1] = 5; ids[21] = 2;
        CompoundTag negative = regionTag(new BlockPos(4, 2, 1), new BlockPos(-3, -2, -4),
                List.of(stateTag("air"), stateTag("stone"), stateTag("stone_slab", "type", "double"),
                        stateTag("oak_stairs"), stateTag("redstone_wire"), stateTag("chest")), ids);
        ListTag entities = new ListTag();
        CompoundTag entity = xyz(1, 0, 0); entity.putString("id", "minecraft:chest"); entity.putString("CustomName", "fixture");
        entities.add(entity); negative.put("TileEntities", entities);
        root.getCompoundOrEmpty("Regions").put("negative", negative);
        root.getCompoundOrEmpty("Regions").put("other", regionTag(new BlockPos(-2, 0, 5), new BlockPos(1, 1, 1),
                List.of(stateTag("bedrock")), new int[] {0}));
        return root;
    }
    private static CompoundTag rootTag() {
        CompoundTag tag = new CompoundTag(); tag.putInt("Version", 7);
        tag.putInt("MinecraftDataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        tag.put("Regions", new CompoundTag()); return tag;
    }
    private static CompoundTag region(CompoundTag root, String name) { return root.getCompoundOrEmpty("Regions").getCompoundOrEmpty(name); }
    private static CompoundTag regionTag(BlockPos pos, BlockPos size, List<CompoundTag> states, int[] ids) {
        CompoundTag tag = new CompoundTag(); tag.put("Position", xyz(pos.getX(), pos.getY(), pos.getZ()));
        tag.put("Size", xyz(size.getX(), size.getY(), size.getZ()));
        ListTag palette = new ListTag(); states.forEach(palette::add); tag.put("BlockStatePalette", palette);
        int bits = LitematicLoader.bitsForPalette(states.size());
        long[] words = new long[(ids.length * bits + 63) / 64];
        for (int index = 0; index < ids.length; index++) {
            long bit = (long) index * bits; int word = (int) (bit / 64), offset = (int) (bit % 64);
            words[word] |= (long) ids[index] << offset;
            if (offset + bits > 64) words[word + 1] |= (long) ids[index] >>> (64 - offset);
        }
        tag.putLongArray("BlockStates", words); return tag;
    }
    private static CompoundTag stateTag(String name, String... property) {
        CompoundTag tag = new CompoundTag(); tag.putString("Name", "minecraft:" + name);
        if (property.length > 0) { CompoundTag properties = new CompoundTag(); properties.putString(property[0], property[1]); tag.put("Properties", properties); }
        return tag;
    }
    private static CompoundTag xyz(int x, int y, int z) {
        CompoundTag tag = new CompoundTag(); tag.putInt("x", x); tag.putInt("y", y); tag.putInt("z", z); return tag;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
