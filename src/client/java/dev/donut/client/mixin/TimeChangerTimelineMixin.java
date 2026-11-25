package dev.donut.client.mixin;

import dev.donut.client.render.TimeChanger;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.function.LongSupplier;

@Mixin(EnvironmentAttributeSystem.Builder.class)
abstract class TimeChangerTimelineMixin implements TimeChanger.TimelineClock {
    @Unique private LongSupplier donut$timelineClock;

    @Override
    public void donut$setTimelineClock(LongSupplier clock) {
        donut$timelineClock = clock;
    }

    @ModifyVariable(method = "addTimelineLayer", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private LongSupplier donut$useVisualClock(LongSupplier clock) {
        return donut$timelineClock == null ? clock : donut$timelineClock;
    }
}
