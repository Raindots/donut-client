package dev.donut.client.mixin;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read vanilla's last transmitted state; the builder never sends an extra movement packet. */
@Mixin(LocalPlayer.class)
public interface BuilderLocalPlayerAccessor {
    @Accessor("yRotLast") float donut$lastSentYaw();
    @Accessor("xRotLast") float donut$lastSentPitch();
    @Accessor("lastSentInput") Input donut$lastSentInput();
}
