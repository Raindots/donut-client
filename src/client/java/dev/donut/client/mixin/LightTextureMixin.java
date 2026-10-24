package dev.donut.client.mixin;

import dev.donut.client.module.visual.FullBrightModule;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LightTexture.class)
abstract class LightTextureMixin {
    @Shadow
    private boolean updateLightTexture;

    private boolean donut$lastFullBrightState;

    @Redirect(
            method = "updateLightTexture",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/player/LocalPlayer;hasEffect(Lnet/minecraft/core/Holder;)Z"
            )
    )
    private boolean donut$hasNightVisionEffect(LocalPlayer player, Holder<MobEffect> effect) {
        if (effect.equals(MobEffects.NIGHT_VISION) && FullBrightModule.isFullBrightActive()) {
            return true;
        }
        return player.hasEffect(effect);
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void donut$onTick(CallbackInfo ci) {
        boolean active = FullBrightModule.isFullBrightActive();
        if (active != donut$lastFullBrightState) {
            donut$lastFullBrightState = active;
            this.updateLightTexture = true;
        }
    }
}
