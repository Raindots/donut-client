package dev.donut.client.preview;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class StoragePreviewService {
    private StoragePreviewService() {}

    public static List<ItemStack> getShulkerItems(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Collections.emptyList();
        ItemContainerContents container = stack.get(DataComponents.CONTAINER);
        if (container == null) return Collections.emptyList();

        List<ItemStack> items = new ArrayList<>();
        for (ItemStack item : container.nonEmptyItems()) {
            if (!item.isEmpty()) {
                items.add(item);
            }
        }
        return Collections.unmodifiableList(items);
    }

    public static boolean hasPreview(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        ItemContainerContents container = stack.get(DataComponents.CONTAINER);
        return container != null && container.nonEmptyStream().findAny().isPresent();
    }
}
