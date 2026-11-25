package dev.donut.client.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ClientLevel.class)
public interface TimeChangerClientLevelAccessor {
    @Invoker("addEnvironmentAttributeLayers")
    EnvironmentAttributeSystem.Builder donut$addEnvironmentLayers(EnvironmentAttributeSystem.Builder builder);
}
