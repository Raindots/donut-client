package dev.donut.client.mixin;

import dev.donut.client.freecam.FreecamController;
import dev.donut.client.autobuild.AutoBuilderController;
import dev.donut.client.combat.AnchorMacroController;
import dev.donut.client.combat.CrystalAuraController;
import dev.donut.client.combat.TriggerbotController;
import dev.donut.client.spectrebuilder.BuilderPlacement;
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

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void donut$useItem(CallbackInfo ci) {
        Minecraft client = (Minecraft) (Object) this;
        if (donut$automationBlocksInput() || AnchorMacroController.consumeUse(client) || CrystalAuraController.consumeUse(client)) {
            ci.cancel();
        }
    }

    @Inject(method = {"pickBlock", "continueAttack"}, at = @At("HEAD"), cancellable = true)
    private void donut$blockInteractions(CallbackInfo ci) {
        if (donut$automationBlocksInput()) ci.cancel();
    }

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void donut$blockAttack(CallbackInfoReturnable<Boolean> cir) {
        if (donut$automationBlocksInput()) cir.setReturnValue(false);
        else TriggerbotController.noteManualAttack();
    }

    private static boolean donut$automationBlocksInput() {
        return FreecamController.enabled() || AutoBuilderController.get().isRunning() || BuilderPlacement.placing();
    }
}
