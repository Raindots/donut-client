package dev.donut.client.mixin;

import dev.donut.client.staff.StaffDetectorController;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** RETURN runs after vanilla applies the packet on its client-thread dispatch. */
@Mixin(ClientPacketListener.class)
abstract class StaffDetectorPacketMixin {
    @Inject(method = "handlePlayerInfoUpdate", at = @At("RETURN"))
    private void donut$staffRoster(ClientboundPlayerInfoUpdatePacket packet, CallbackInfo ci) {
        StaffDetectorController.rosterUpdated((ClientPacketListener) (Object) this, packet);
    }
    @Inject(method = "handlePlayerInfoRemove", at = @At("RETURN"))
    private void donut$staffRemove(ClientboundPlayerInfoRemovePacket packet, CallbackInfo ci) {
        StaffDetectorController.rosterRemoved((ClientPacketListener) (Object) this, packet);
    }
    @Inject(method = "handleCommandSuggestions", at = @At("RETURN"))
    private void donut$staffSuggestions(ClientboundCommandSuggestionsPacket packet, CallbackInfo ci) {
        StaffDetectorController.suggestionsApplied((ClientPacketListener) (Object) this, packet);
    }
}
