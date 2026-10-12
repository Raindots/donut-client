package dev.donut.client.mixin;

import dev.donut.client.fly.FlyController;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Remember received flight permissions without changing the packet or its vanilla handler. */
@Mixin(ClientPacketListener.class)
abstract class FlyAbilitiesMixin {
    @Inject(method = "handlePlayerAbilities", at = @At("TAIL"))
    private void donut$rememberServerFlight(ClientboundPlayerAbilitiesPacket packet, CallbackInfo ci) {
        FlyController.onServerAbilities(packet);
    }
}
