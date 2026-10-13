package dev.donut.client.finder;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.FinderSettings;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;

public enum TargetKind {
    CHEST, TRAPPED_CHEST, ENDER_CHEST, SHULKER, DROPPER, SPAWNER, TRIAL_SPAWNER, ANCIENT_DEBRIS;

    public boolean isSpawner() { return this == SPAWNER || this == TRIAL_SPAWNER; }
    public boolean isDebris() { return this == ANCIENT_DEBRIS; }
    public boolean isStorage() { return !isSpawner() && !isDebris(); }

    public FinderSettings settings(ClientConfig config) {
        return isSpawner() ? config.spawner : isDebris() ? config.debris : config.storage;
    }

    public boolean included(FinderSettings settings) {
        return switch (this) {
            case CHEST -> settings.chests;
            case TRAPPED_CHEST -> settings.trappedChests;
            case ENDER_CHEST -> settings.enderChests;
            case SHULKER -> settings.shulkers;
            case DROPPER -> settings.droppers;
            case SPAWNER, ANCIENT_DEBRIS -> true;
            case TRIAL_SPAWNER -> settings.trialSpawners;
        };
    }

    public static TargetKind from(BlockState state) {
        if (state.is(Blocks.CHEST)) return CHEST;
        if (state.is(Blocks.TRAPPED_CHEST)) return TRAPPED_CHEST;
        if (state.is(Blocks.ENDER_CHEST)) return ENDER_CHEST;
        if (state.getBlock() instanceof ShulkerBoxBlock) return SHULKER;
        if (state.is(Blocks.DROPPER)) return DROPPER;
        if (state.is(Blocks.SPAWNER)) return SPAWNER;
        if (state.is(Blocks.TRIAL_SPAWNER)) return TRIAL_SPAWNER;
        if (state.is(Blocks.ANCIENT_DEBRIS)) return ANCIENT_DEBRIS;
        return null;
    }
}
