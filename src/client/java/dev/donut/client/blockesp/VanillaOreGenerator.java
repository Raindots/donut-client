package dev.donut.client.blockesp;

import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import com.mojang.serialization.Lifecycle;
import java.io.IOException;
import java.net.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.SystemReport;
import net.minecraft.commands.Commands;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.Services;
import net.minecraft.server.WorldStem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.Util;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.debugchart.LocalSampleLogger;
import net.minecraft.util.debugchart.SampleLogger;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A worker-owned vanilla world used only for generation. It never starts a server loop,
 * opens a listener, connects players, ticks gameplay, or accesses the user's saved worlds.
 * FULL chunks are required: FEATURES alone can still receive ores from neighboring chunks.
 */
public final class VanillaOreGenerator implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Donut/BlockESP");
    private final Thread owner = Thread.currentThread();
    private final Path scratch;
    private final PackRepository packs;
    private LevelStorageSource.LevelStorageAccess storage;
    private WorldStem stem;
    private PredictionServer server;
    private int requests;
    private boolean closed;

    public VanillaOreGenerator(long seed, Path scratchParent) throws Exception {
        Files.createDirectories(scratchParent);
        scratch = Files.createTempDirectory(scratchParent, "generation-");
        packs = ServerPacksSource.createVanillaTrustedRepository();
        try {
            packs.reload();
            packs.setSelected(List.of("vanilla"));
            stem = loadVanilla(seed, packs);
            storage = LevelStorageSource.createDefault(scratch).createAccess("prediction");
            server = new PredictionServer(storage, packs, stem, scratch);
            server.prepare();
        } catch (Exception | Error error) {
            close();
            throw error;
        }
    }

    private static WorldStem loadVanilla(long seed, PackRepository packs) {
        CloseableResourceManager resources = new MultiPackResourceManager(PackType.SERVER_DATA, packs.openAllSelected());
        try {
            var layers = RegistryLayer.createRegistryAccess();
            // A connected world has already bound the static block/item/fluid tags. Reuse those
            // lookups directly: staging another tag load creates unresolved recipe tag sets, while
            // committing it would overwrite the live world's tag data.
            var staticLookups = layers.getAccessForLoading(RegistryLayer.WORLDGEN).listRegistries().toList();
            RegistryAccess.Frozen worldgen = RegistryDataLoader.load(resources, staticLookups, RegistryDataLoader.WORLDGEN_REGISTRIES);
            List<HolderLookup.RegistryLookup<?>> lookups = Stream.concat(staticLookups.stream(), worldgen.listRegistries()).toList();
            RegistryAccess.Frozen dimensions = RegistryDataLoader.load(resources, lookups, RegistryDataLoader.DIMENSION_REGISTRIES);
            var normal = WorldPresets.createNormalWorldDimensions(HolderLookup.Provider.create(lookups.stream()))
                    .bake(dimensions.lookupOrThrow(Registries.LEVEL_STEM));
            LayeredRegistryAccess<RegistryLayer> registries = layers.replaceFrom(RegistryLayer.WORLDGEN, worldgen, normal.dimensionsRegistryAccess());
            var configuration = WorldDataConfiguration.DEFAULT;
            var rules = new GameRules(configuration.enabledFeatures());
            var settings = new LevelSettings("BlockESP prediction", GameType.SPECTATOR, false, Difficulty.PEACEFUL,
                    false, rules, configuration);
            var data = new PrimaryLevelData(settings, new WorldOptions(seed, true, false),
                    PrimaryLevelData.SpecialWorldProperty.NONE, Lifecycle.stable());
            // Skip the normal spawn search/preload: only requested chunks should be generated.
            data.setInitialized(true);
            var reloadable = ReloadableServerResources.loadResources(resources, registries, List.of(),
                    configuration.enabledFeatures(), Commands.CommandSelection.INTEGRATED,
                    LevelBasedPermissionSet.ALL, Util.backgroundExecutor(), Runnable::run).join();
            // Deliberately do NOT call updateStaticRegistryTags(): that would overwrite the active world's tags.
            return new WorldStem(resources, reloadable, registries, data);
        } catch (RuntimeException | Error error) {
            resources.close();
            throw error;
        }
    }

    public List<OreMarker> predict(int chunkX, int chunkZ) {
        return findOres(generateChunk(chunkX, chunkZ));
    }

    private ChunkAccess generateChunk(int chunkX, int chunkZ) {
        checkOwner();
        if (closed) throw new IllegalStateException("Generator is closed");
        ChunkAccess chunk = server.overworld().getChunk(chunkX, chunkZ);
        // Expire request tickets and let vanilla release chunks. Do not tick mobs, blocks, or players.
        if (++requests % 8 == 0) {
            server.overworld().getChunkSource().tick(() -> true, false);
            while (server.overworld().getChunkSource().pollTask()) { }
        }
        return chunk;
    }

    /** Section palettes skip all sections that cannot contain either exact target block. */
    public static List<OreMarker> findOres(ChunkAccess chunk) {
        var result = new ArrayList<OreMarker>();
        var cursor = new net.minecraft.core.BlockPos.MutableBlockPos();
        var sections = chunk.getSections();
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            var section = sections[sectionIndex];
            if (section.hasOnlyAir() || !section.maybeHas(state -> OreMarker.kind(state) != null)) continue;
            int bottom = chunk.getSectionYFromSectionIndex(sectionIndex) * 16;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                var kind = OreMarker.kind(section.getBlockState(x, y, z));
                if (kind != null) {
                    cursor.set(chunk.getPos().getMinBlockX() + x, bottom + y, chunk.getPos().getMinBlockZ() + z);
                    result.add(new OreMarker(cursor.immutable(), kind));
                }
            }
        }
        return List.copyOf(result);
    }

    /** Additional guard beyond the normal ticket expiry; the controller recreates large generation sessions. */
    public boolean shouldRecycle() { return requests >= 128; }

    private void checkOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("World generation must stay on its owning worker");
    }

    @Override public void close() {
        checkOwner();
        if (closed) return;
        closed = true;
        try {
            if (server != null) server.closePrediction();
        } catch (Exception error) { LOGGER.warn("Could not close BlockESP generation world", error); }
        finally {
            if (stem != null) stem.close();
            if (storage != null) try { storage.close(); } catch (IOException error) { LOGGER.warn("Could not release prediction storage", error); }
            // This is exclusively the unique temporary directory created by this instance.
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            } catch (IOException error) { LOGGER.warn("Could not remove BlockESP scratch directory {}", scratch, error); }
        }
    }

    private static final class PredictionServer extends MinecraftServer {
        private final SampleLogger samples = new LocalSampleLogger(1);
        private final ReloadableServerResources dataResources;
        PredictionServer(LevelStorageSource.LevelStorageAccess storage, PackRepository packs, WorldStem stem, Path scratch) {
            super(Thread.currentThread(), storage, packs, stem, Proxy.NO_PROXY, DataFixers.getDataFixer(),
                    Services.create(new YggdrasilAuthenticationService(Proxy.NO_PROXY), scratch.toFile()), new SilentLoadListener());
            dataResources = stem.dataPackResources();
            setPlayerList(new PlayerList(this, stem.registries(), storage.createPlayerStorage(), notificationManager()) { });
            getPlayerList().setViewDistance(2);
            getPlayerList().setSimulationDistance(2);
        }
        void prepare() {
            createLevels();
        }
        void closePrediction() throws IOException {
            // No tick loop was started. Drain generation work and close only this world's resources.
            getConnection().stop();
            for (ServerLevel level : getAllLevels()) {
                level.getChunkSource().deactivateTicketsOnClosing();
                while (level.getChunkSource().pollTask()) { }
                level.close();
            }
        }
        @Override protected boolean initServer() { return false; }
        @Override public LevelBasedPermissionSet operatorUserPermissions() { return LevelBasedPermissionSet.ALL; }
        @Override public PermissionSet getFunctionCompilationPermissions() { return LevelBasedPermissionSet.ALL; }
        @Override public boolean shouldRconBroadcast() { return false; }
        @Override protected SampleLogger getTickTimeLogger() { return samples; }
        @Override public boolean isTickTimeLoggingEnabled() { return false; }
        @Override public SystemReport fillServerSystemReport(SystemReport report) { return report; }
        @Override public boolean isDedicatedServer() { return false; }
        @Override public int getRateLimitPacketsPerSecond() { return 0; }
        @Override public int getMaxPlayers() { return 0; }
        @Override public <T> T getOrThrow(net.fabricmc.fabric.api.resource.v1.DataResourceStore.Key<T> key) {
            return ((net.fabricmc.fabric.impl.resource.FabricDataResourceStoreHolder) dataResources)
                    .fabric$getDataResourceStore().getOrThrow(key);
        }
        @Override public boolean useNativeTransport() { return false; }
        @Override public boolean isPublished() { return false; }
        @Override public boolean shouldInformAdmins() { return false; }
        @Override public boolean isSingleplayerOwner(NameAndId nameAndId) { return false; }
    }

    private static final class SilentLoadListener implements LevelLoadListener {
        @Override public void start(Stage stage, int chunks) { }
        @Override public void update(Stage stage, int completed, int total) { }
        @Override public void finish(Stage stage) { }
        @Override public void updateFocus(net.minecraft.resources.ResourceKey<Level> dimension, ChunkPos pos) { }
    }
}
