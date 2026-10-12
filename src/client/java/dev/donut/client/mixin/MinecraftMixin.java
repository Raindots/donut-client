package dev.donut.client.mixin;

import dev.donut.client.freecam.FreecamController;
import dev.donut.client.autobuild.AutoBuilderController;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
abstract class MinecraftMixin {
    @Inject(method = "handleKeybinds", at = @At("HEAD"))
    private void donut$clearActions(CallbackInfo ci) {
        if (FreecamController.enabled()) FreecamController.getInstance().suppressActions((Minecraft) (Object) this);
    }

    @Inject(method = {"startUseItem", "pickBlock", "continueAttack"}, at = @At("HEAD"), cancellable = true)
    private void donut$blockInteractions(CallbackInfo ci) {
        if (FreecamController.enabled() || AutoBuilderController.get().isRunning()) ci.cancel();
    }

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void donut$blockAttack(CallbackInfoReturnable<Boolean> cir) {
        if (FreecamController.enabled() || AutoBuilderController.get().isRunning()) cir.setReturnValue(false);
    }
}
