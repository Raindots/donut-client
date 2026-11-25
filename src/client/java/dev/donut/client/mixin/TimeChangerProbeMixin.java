package dev.donut.client.mixin;

import dev.donut.client.render.TimeChanger;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.attribute.SpatialAttributeInterpolator;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EnvironmentAttributeProbe.class)
abstract class TimeChangerProbeMixin {
    @Shadow Level level;
    @Shadow Vec3 position;
    @Shadow @Final SpatialAttributeInterpolator biomeInterpolator;

    @Inject(method = "getValue", at = @At("HEAD"), cancellable = true)
    private <Value> void donut$sampleVisualTime(EnvironmentAttribute<Value> attribute, float partialTick,
                                               CallbackInfoReturnable<Value> cir) {
        Value value = TimeChanger.sample((EnvironmentAttributeProbe) (Object) this,
                level, position, biomeInterpolator, attribute);
        if (value != null) cir.setReturnValue(value);
    }
}
