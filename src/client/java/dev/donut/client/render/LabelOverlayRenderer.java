package dev.donut.client.render;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.FinderSettings;
import dev.donut.client.finder.FinderScanner;
import dev.donut.client.finder.FoundBlock;
import dev.donut.client.finder.TargetKind;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

public final class LabelOverlayRenderer {
    private static final int MAX_LABELS = 64;
    private final RenderStateDataKey<List<LabelData>> frame =
            RenderStateDataKey.create(() -> "Donut finder labels");
    private final ClientConfig config;
    private final FinderScanner scanner;
    private boolean registered;

    public LabelOverlayRenderer(ClientConfig config, FinderScanner scanner) {
        this.config = config;
        this.scanner = scanner;
    }

    public void register() {
        if (registered) return;
        registered = true;
        WorldRenderEvents.END_EXTRACTION.register(this::extract);
        WorldRenderEvents.END_MAIN.register(this::draw);
    }

    private void extract(WorldExtractionContext context) {
        var state = (FabricRenderState) context.worldState();
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) {
            state.setData(frame, List.of());
            return;
        }

        Vec3 camera = context.camera().position();
        List<LabelData> list = new ArrayList<>();

        for (FoundBlock block : scanner.snapshot()) {
            if (list.size() >= MAX_LABELS) break;
            FinderSettings settings = block.kind().isSpawner() ? config.spawner : config.storage;
            if (!settings.enabled || !settings.labels || !block.kind().included(settings)) continue;

            Vec3 center = Vec3.atCenterOf(block.pos());
            double dist = center.distanceTo(camera);
            if (dist > settings.range || dist > 48.0) continue; // cap label render distance for performance

            if (!context.world().hasChunkAt(block.pos())) continue;
            var currentState = context.world().getBlockState(block.pos());
            if (TargetKind.from(currentState) != block.kind()) continue;

            String text = block.kind().displayName();
            if (settings.distance) {
                text += String.format(" (%.1fm)", dist);
            }

            int color = settings.perTypeColors ? color(settings, block.kind()) : settings.color;
            Vec3 pos = new Vec3(center.x - camera.x, center.y + 0.6 - camera.y, center.z - camera.z);
            list.add(new LabelData(pos, text, 0xFF000000 | (color & 0xFFFFFF)));
        }

        state.setData(frame, List.copyOf(list));
    }

    private static int color(FinderSettings settings, TargetKind kind) {
        return switch (kind) {
            case CHEST, TRAPPED_CHEST -> settings.chestColor;
            case ENDER_CHEST -> settings.enderChestColor;
            case SHULKER -> settings.shulkerColor;
            case HOPPER -> settings.hopperColor;
            case DROPPER -> settings.dropperColor;
            case SPAWNER, TRIAL_SPAWNER -> settings.color;
        };
    }

    private void draw(WorldRenderContext context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) return;
        List<LabelData> labels = ((FabricRenderState) context.worldState()).getData(frame);
        if (labels == null || labels.isEmpty()) return;

        Font font = mc.font;
        var matrices = context.matrices();

        for (LabelData label : labels) {
            matrices.pushPose();
            matrices.translate(label.pos.x, label.pos.y, label.pos.z);
            matrices.mulPose(mc.gameRenderer.getMainCamera().rotation());
            matrices.scale(-0.025f, -0.025f, 0.025f);

            Matrix4f matrix4f = matrices.last().pose();
            float halfWidth = font.width(label.text) / 2.0f;
            
            // Draw text shadow / background
            var bufferSource = mc.renderBuffers().bufferSource();
            font.drawInBatch(
                    label.text,
                    -halfWidth,
                    0,
                    label.color,
                    false,
                    matrix4f,
                    bufferSource,
                    Font.DisplayMode.SEE_THROUGH,
                    0x80000000,
                    0xF000F0
            );
            bufferSource.endBatch();

            matrices.popPose();
        }
    }

    private record LabelData(Vec3 pos, String text, int color) {}
}
