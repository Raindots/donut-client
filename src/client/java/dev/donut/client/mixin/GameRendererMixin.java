package dev.donut.client.mixin;

import dev.donut.client.combat.AimAssistController;
import dev.donut.client.freecam.FreecamController;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void donut$assistAim(DeltaTracker time, CallbackInfo ci) {
        AimAssistController.renderFrame(time.getGameTimeDeltaPartialTick(false));
    }

    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void donut$hideHand(CallbackInfo ci) {
        if (FreecamController.enabled()) ci.cancel();
    }

    @Inject(method = "pick", at = @At("HEAD"), cancellable = true)
    private void donut$clearTarget(CallbackInfo ci) {
        if (!FreecamController.enabled()) return;
        var client = Minecraft.getInstance();
        var pos = FreecamController.getInstance().position();
        client.hitResult = BlockHitResult.miss(pos, Direction.DOWN, BlockPos.containing(pos));
        client.crosshairPickEntity = null;
        ci.cancel();
    }
}
