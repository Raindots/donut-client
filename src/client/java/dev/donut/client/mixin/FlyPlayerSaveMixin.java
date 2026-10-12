package dev.donut.client.mixin;

import dev.donut.client.fly.FlyController;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Saves the player's normal flight abilities without changing the live flight state. */
@Mixin(Player.class)
abstract class FlyPlayerSaveMixin {
    @Redirect(method = "addAdditionalSaveData", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Abilities;pack()Lnet/minecraft/world/entity/player/Abilities$Packed;"))
    private Abilities.Packed donut$saveNormalFlightAbilities(Abilities abilities) {
        return FlyController.abilitiesForSave((Player) (Object) this, abilities);
    }
}
