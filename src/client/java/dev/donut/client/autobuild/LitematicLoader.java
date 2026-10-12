package dev.donut.client.autobuild;

import java.io.IOException;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** Strict bounded reader for the post-flattening Litematica format (versions 4–7). */
public final class LitematicLoader {
    public static final int MAX_BLOCKS = 1_000_000;
    public static final int MAX_VOLUME = 4_000_000;
    public static final long MAX_FILE_BYTES = 32L * 1024 * 1024;
    public static final long MAX_NBT_BYTES = 128L * 1024 * 1024;
    private static final int MAX_REGIONS = 512;
    private static final int MAX_AXIS = 8192;
    private static final int MAX_SPAN = 65536;
    private LitematicLoader() {}

    public static Schematic load(Path file) throws IOException {
        if (!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".litematic"))
            throw invalid("Choose a .litematic file");
        if (!Files.isRegularFile(file)) throw invalid("Schematic file is missing");
        long length = Files.size(file);
        if (length <= 0 || length > MAX_FILE_BYTES) throw invalid("File must be between 1 byte and 32 MiB");
        try {
            CompoundTag root;
            try (BufferedInputStream input = new BufferedInputStream(Files.newInputStream(file))) {
                input.mark(2);
                int first = input.read(), second = input.read();
                input.reset();
                NbtAccounter account = new NbtAccounter(MAX_NBT_BYTES, 64);
                root = first == 0x1f && second == 0x8b ? NbtIo.readCompressed(input, account)
                        : NbtIo.read(new DataInputStream(input), account);
            }
            return read(root, file.getFileName().toString().replaceFirst("(?i)\\.litematic$", ""));
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("Invalid or oversized .litematic: " + concise(e), e);
        }
    }

    /** Also used by fixture tests; all structural validation is identical to file loading. */
    public static Schematic read(CompoundTag root, String fallbackName) throws IOException {
        if (root == null) throw invalid("Schematic has no root compound");
        int version = integer(root, "Version");
        if (version < 4 || version > 7)
            throw invalid("Unsupported Litematica version " + version + "; re-save using a current Litematica version");
        int dataVersion = integer(root, "MinecraftDataVersion");
        if (dataVersion < 1451) throw invalid("Pre-1.13 schematics must be converted in Litematica first");
        if (dataVersion > SharedConstants.getCurrentVersion().dataVersion().version())
            throw invalid("Schematic was saved by a newer Minecraft version");
        CompoundTag regionTags = compound(root, "Regions");
        if (regionTags.isEmpty() || regionTags.size() > MAX_REGIONS)
            throw invalid("Schematic must have 1–512 regions");
        Set<String> warnings = new LinkedHashSet<>();
        if (dataVersion != SharedConstants.getCurrentVersion().dataVersion().version())
            warnings.add("Older Minecraft data: block names and properties are validated; re-save in current Litematica if conversion is needed.");
        List<Region> regions = new ArrayList<>();
        long totalVolume = 0;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (String key : regionTags.keySet().stream().sorted().toList()) {
            CompoundTag tag = compound(regionTags, key);
            BlockPos position = position(compound(tag, "Position"));
            BlockPos signedSize = position(compound(tag, "Size"));
            int sx = axis(signedSize.getX()), sy = axis(signedSize.getY()), sz = axis(signedSize.getZ());
            long volume = (long) sx * sy * sz;
            totalVolume += volume;
            if (totalVolume > MAX_VOLUME) throw invalid("Schematic exceeds 4,000,000 region cells");
            BlockPos min = new BlockPos(minimum(position.getX(), signedSize.getX()),
                    minimum(position.getY(), signedSize.getY()), minimum(position.getZ(), signedSize.getZ()));
            minX = Math.min(minX, min.getX()); minY = Math.min(minY, min.getY()); minZ = Math.min(minZ, min.getZ());
            maxX = Math.max(maxX, Math.addExact(min.getX(), sx - 1));
            maxY = Math.max(maxY, Math.addExact(min.getY(), sy - 1));
            maxZ = Math.max(maxZ, Math.addExact(min.getZ(), sz - 1));
            ListTag paletteNbt = list(tag, "BlockStatePalette");
            if (paletteNbt.isEmpty() || paletteNbt.size() > 65536) throw invalid("Invalid palette in region " + key);
            List<BlockState> palette = new ArrayList<>(paletteNbt.size());
            for (Tag entry : paletteNbt) {
                if (!(entry instanceof CompoundTag state)) throw invalid("Non-compound palette entry in " + key);
                palette.add(readState(state));
            }
            long[] packed = tag.getLongArray("BlockStates").orElseThrow(() -> invalid("Missing BlockStates in " + key));
            int bits = bitsForPalette(palette.size());
            long needed = (volume * bits + 63) / 64;
            if (packed.length != needed) throw invalid("Wrong packed BlockStates length in " + key + ": expected " + needed);
            Map<BlockPos, CompoundTag> entities = new HashMap<>();
            if (tag.contains("TileEntities")) {
                ListTag tileTags = list(tag, "TileEntities");
                if (tileTags.size() > volume) throw invalid("More block entities than region cells in " + key);
                for (Tag entry : tileTags) {
                    if (!(entry instanceof CompoundTag entity)) throw invalid("Invalid block entity in " + key);
                    BlockPos local = position(entity);
                    if (local.getX() < 0 || local.getX() >= sx || local.getY() < 0 || local.getY() >= sy
                            || local.getZ() < 0 || local.getZ() >= sz)
                        throw invalid("Block entity outside region " + key);
                    if (entities.put(local, entity) != null) throw invalid("Duplicate block entity in region " + key);
                }
            }
            if (!entities.isEmpty()) warnings.add("Stored block-entity data is retained but cannot be restored by ordinary block placement.");
            if (!optionalCompoundList(tag, "Entities").isEmpty()) warnings.add("Entities are not built automatically.");
            if (!optionalCompoundList(tag, "PendingBlockTicks").isEmpty() || !optionalCompoundList(tag, "PendingFluidTicks").isEmpty())
                warnings.add("Scheduled redstone and fluid ticks are not restored.");
            regions.add(new Region(key, min, sx, sy, sz, (int) volume, palette, packed, bits, entities));
        }
        BlockPos dimensions = new BlockPos(span(minX, maxX), span(minY, maxY), span(minZ, maxZ));
        BlockPos minimum = new BlockPos(minX, minY, minZ);
        Map<BlockPos, Schematic.Block> blocks = new HashMap<>();
        int identicalOverlaps = 0;
        for (Region region : regions) {
            for (int index = 0; index < region.volume; index++) {
                int id = packedIndex(region.packed, region.bits, index);
                if (id >= region.palette.size()) throw invalid("Out-of-range palette index in region " + region.name);
                BlockState state = region.palette.get(id);
                int x = index % region.sx, z = index / region.sx % region.sz, y = index / (region.sx * region.sz);
                BlockPos local = new BlockPos(x, y, z);
                CompoundTag entity = region.entities.get(local);
                if (entity != null) validateBlockEntity(state, entity, region.name);
                if (state.isAir()) continue;
                BlockPos normalized = region.minimum.offset(local).subtract(minimum);
                if (entity != null) {
                    entity = entity.copy();
                    entity.putInt("x", normalized.getX()); entity.putInt("y", normalized.getY()); entity.putInt("z", normalized.getZ());
                }
                Schematic.Block placed = new Schematic.Block(normalized, state, entity);
                Schematic.Block previous = blocks.putIfAbsent(normalized, placed);
                if (previous != null) {
                    if (previous.state() != state || !Objects.equals(previous.blockEntity(), entity))
                        throw invalid("Conflicting overlapping regions at " + normalized.toShortString());
                    identicalOverlaps++;
                }
                if (blocks.size() > MAX_BLOCKS) throw invalid("Schematic exceeds 1,000,000 non-air blocks");
            }
        }
        if (blocks.isEmpty()) throw invalid("Schematic contains no non-air blocks");
        if (identicalOverlaps > 0) warnings.add("Identical overlapping blocks were counted once (" + identicalOverlaps + ").");
        for (Schematic.Block block : blocks.values()) {
            if (!MaterialTracker.isSecondary(block.state())) continue;
            Schematic.Block primary = blocks.get(MaterialTracker.primaryPosition(block.pos(), block.state()));
            if (primary == null || primary.state().getBlock() != block.state().getBlock()
                    || MaterialTracker.isSecondary(primary.state())) {
                warnings.add("Some door, bed or plant secondary halves have no base in the schematic and require manual attention.");
                break;
            }
        }
        String name = root.getCompoundOrEmpty("Metadata").getStringOr("Name", fallbackName).strip();
        if (name.isEmpty()) name = fallbackName;
        if (name.length() > 160) name = name.substring(0, 160);
        List<Schematic.Block> ordered = new ArrayList<>(blocks.values());
        ordered.sort(Comparator.comparingInt((Schematic.Block block) -> block.pos().getY())
                .thenComparingInt(block -> block.pos().getZ()).thenComparingInt(block -> block.pos().getX()));
        return new Schematic(name, ordered, dimensions, regions.size(), List.copyOf(warnings));
    }

    public static int bitsForPalette(int size) {
        if (size < 1 || size > 65536) throw new IllegalArgumentException("Palette size out of range");
        return Math.max(2, 32 - Integer.numberOfLeadingZeros(size - 1));
    }

    /** Litematica packs across 64-bit boundaries, unlike Minecraft's padded chunk containers. */
    public static int packedIndex(long[] packed, int bits, int index) {
        if (bits < 2 || bits > 16 || index < 0) throw new IllegalArgumentException("Invalid packed block index");
        long start = (long) index * bits;
        int word = (int) (start >>> 6), offset = (int) (start & 63);
        if (word >= packed.length || (offset + bits > 64 && word + 1 >= packed.length))
            throw new IllegalArgumentException("Packed block index exceeds backing array");
        long value = packed[word] >>> offset;
        if (offset + bits > 64) value |= packed[word + 1] << (64 - offset);
        return (int) (value & ((1L << bits) - 1));
    }

    private static BlockState readState(CompoundTag tag) throws IOException {
        String name = tag.getString("Name").orElseThrow(() -> invalid("Palette block has no Name"));
        Identifier id = Identifier.tryParse(name);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) throw invalid("Unknown block: " + name);
        Block block = BuiltInRegistries.BLOCK.getValue(id);
        BlockState state = block.defaultBlockState();
        if (tag.contains("Properties")) {
            CompoundTag properties = compound(tag, "Properties");
            for (String key : properties.keySet()) {
                Property<?> property = block.getStateDefinition().getProperty(key);
                if (property == null) throw invalid("Unknown property " + key + " on " + name);
                String value = properties.getString(key).orElseThrow(() -> invalid("Invalid property value " + key));
                state = setProperty(state, property, value);
            }
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState setProperty(BlockState state, Property<T> property, String text) throws IOException {
        T value = property.getValue(text).orElseThrow(() -> invalid("Invalid " + property.getName() + "=" + text));
        return state.setValue(property, value);
    }

    private static int integer(CompoundTag tag, String key) throws IOException {
        if (tag.get(key) == null || tag.get(key).getId() != Tag.TAG_INT) throw invalid("Missing integer " + key);
        return tag.getInt(key).orElseThrow(() -> invalid("Missing integer " + key));
    }
    private static CompoundTag compound(CompoundTag tag, String key) throws IOException {
        return tag.getCompound(key).orElseThrow(() -> invalid("Missing compound " + key));
    }
    private static ListTag list(CompoundTag tag, String key) throws IOException {
        return tag.getList(key).orElseThrow(() -> invalid("Missing list " + key));
    }
    private static ListTag optionalCompoundList(CompoundTag tag, String key) throws IOException {
        if (!tag.contains(key)) return new ListTag();
        ListTag result = list(tag, key);
        for (Tag entry : result) if (!(entry instanceof CompoundTag)) throw invalid("Non-compound " + key + " entry");
        return result;
    }
    private static void validateBlockEntity(BlockState state, CompoundTag tag, String region) throws IOException {
        if (!state.hasBlockEntity()) throw invalid("Block entity attached to a non-entity block in " + region);
        String name = tag.getString("id").orElseThrow(() -> invalid("Block entity has no id in " + region));
        Identifier id = Identifier.tryParse(name);
        if (id == null || !BuiltInRegistries.BLOCK_ENTITY_TYPE.containsKey(id)
                || !BuiltInRegistries.BLOCK_ENTITY_TYPE.getValue(id).isValid(state))
            throw invalid("Invalid block entity " + name + " for " + BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }
    private static BlockPos position(CompoundTag tag) throws IOException {
        return new BlockPos(integer(tag, "x"), integer(tag, "y"), integer(tag, "z"));
    }
    private static int axis(int signed) throws IOException {
        long value = Math.abs((long) signed);
        if (value < 1 || value > MAX_AXIS) throw invalid("Each region axis must contain 1–8192 blocks");
        return (int) value;
    }
    private static int minimum(int position, int signedSize) throws IOException {
        long value = (long) position + Math.min(0, signedSize + 1);
        if (Math.abs(value) > 30_000_000L) throw invalid("Region coordinates are outside supported bounds");
        return (int) value;
    }
    private static int span(int minimum, int maximum) throws IOException {
        long span = (long) maximum - minimum + 1;
        if (span > MAX_SPAN) throw invalid("Schematic bounds exceed 65,536 blocks on one axis");
        return (int) span;
    }
    private static IOException invalid(String message) { return new IOException(message); }
    private static String concise(Exception exception) {
        String message = exception.getMessage();
        return message == null ? exception.getClass().getSimpleName() : message.substring(0, Math.min(message.length(), 240));
    }
    private record Region(String name, BlockPos minimum, int sx, int sy, int sz, int volume,
                          List<BlockState> palette, long[] packed, int bits, Map<BlockPos, CompoundTag> entities) {}
}
