package dev.donut.client.render;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.TimeChangerSettings;
import dev.donut.client.mixin.TimeChangerClientLevelAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.attribute.SpatialAttributeInterpolator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;

import java.util.Set;
import java.util.function.LongSupplier;

/** Samples a separate environment for camera visuals, using the world's existing layers. */
public final class TimeChanger {
    private static final Set<EnvironmentAttribute<?>> VISUAL_ATTRIBUTES = Set.of(
            EnvironmentAttributes.SUN_ANGLE, EnvironmentAttributes.MOON_ANGLE,
            EnvironmentAttributes.STAR_ANGLE, EnvironmentAttributes.STAR_BRIGHTNESS,
            EnvironmentAttributes.SUNRISE_SUNSET_COLOR, EnvironmentAttributes.SKY_COLOR,
            EnvironmentAttributes.CLOUD_COLOR, EnvironmentAttributes.FOG_COLOR,
            EnvironmentAttributes.SKY_LIGHT_COLOR, EnvironmentAttributes.SKY_LIGHT_FACTOR
    );

    private static TimeChanger instance;
    private final ClientConfig config;
    private ClientLevel world;
    private EnvironmentAttributeSystem environment;
    private long gameTime;
    private long visualDayTime;

    private TimeChanger(ClientConfig config) {
        this.config = config;
    }

    public static TimeChanger install(ClientConfig config) {
        instance = new TimeChanger(config);
        return instance;
    }

    public static <Value> Value sample(EnvironmentAttributeProbe probe, Level level, Vec3 position,
                                       SpatialAttributeInterpolator biomes, EnvironmentAttribute<Value> attribute) {
        if (instance == null || !instance.config.timeChanger.enabled || !VISUAL_ATTRIBUTES.contains(attribute)
                || !(level instanceof ClientLevel clientLevel) || position == null) return null;
        Minecraft client = Minecraft.getInstance();
        var dimension = level.dimensionType();
        if (client.level != level || client.gameRenderer.getMainCamera().attributeProbe() != probe
                || dimension.hasFixedTime() || dimension.skybox() != DimensionType.Skybox.OVERWORLD) return null;

        return instance.environment(clientLevel).getValue(attribute, position, biomes);
    }

    private EnvironmentAttributeSystem environment(ClientLevel level) {
        long nextGameTime = level.getGameTime();
        long dayTime = level.getDayTime();
        long nextVisualDayTime = dayTime - Math.floorMod(dayTime, TimeChangerSettings.TICKS_PER_DAY)
                + config.timeChanger.time;
        if (level != world) {
            visualDayTime = nextVisualDayTime;
            var builder = EnvironmentAttributeSystem.builder();
            ((TimelineClock) builder).donut$setTimelineClock(() -> visualDayTime);
            environment = ((TimeChangerClientLevelAccessor) level).donut$addEnvironmentLayers(builder).build();
            world = level;
        } else if (nextGameTime != gameTime || nextVisualDayTime != visualDayTime) {
            visualDayTime = nextVisualDayTime;
            environment.invalidateTickCache();
        }
        gameTime = nextGameTime;
        return environment;
    }

    public void disconnect() {
        environment = null;
        world = null;
    }

    public interface TimelineClock {
        void donut$setTimelineClock(LongSupplier clock);
    }
}
