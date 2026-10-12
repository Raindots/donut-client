package dev.donut.client.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.chunkfinder.ChunkFinderScanner;
import dev.donut.client.autobuild.MaterialTracker;
import dev.donut.client.config.FinderSettings;
import dev.donut.client.config.RenderMode;
import dev.donut.client.finder.FinderScanner;
import dev.donut.client.finder.FoundBlock;
import dev.donut.client.finder.TargetKind;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/** Batches finder overlays using the 1.21.11 render pipeline and render-state APIs. */
public final class HighlightRenderer implements AutoCloseable {
    private final RenderStateDataKey<List<DrawBox>> frame =
            RenderStateDataKey.create(() -> "Donut finder highlights");
    private static final RenderPipeline FILL_VISIBLE = pipeline("fill_visible", false, false);
    private static final RenderPipeline FILL_THROUGH = pipeline("fill_through", false, true);
    private static final RenderPipeline LINE_VISIBLE = pipeline("line_visible", true, false);
    private static final RenderPipeline LINE_THROUGH = pipeline("line_through", true, true);
    private static final Vector4f WHITE = new Vector4f(1, 1, 1, 1);
    private static final Vector3f ZERO = new Vector3f();
    private static final Matrix4f IDENTITY = new Matrix4f();

    // Corner bits are X=1, Y=2, Z=4. Winding is outward for all six faces.
    private static final int[][] FACES = {
            {0, 4, 6, 2}, {5, 1, 3, 7}, {4, 0, 1, 5},
            {2, 6, 7, 3}, {1, 0, 2, 3}, {4, 5, 7, 6}
    };

    private final ClientConfig config;
    private final FinderScanner scanner;
    private final ChunkFinderScanner chunkScanner;
    private final SchematicGhosts ghosts = new SchematicGhosts();
    private final Batch fillVisible = new Batch(FILL_VISIBLE);
    private final Batch fillThrough = new Batch(FILL_THROUGH);
    private final Batch lineVisible = new Batch(LINE_VISIBLE);
    private final Batch lineThrough = new Batch(LINE_THROUGH);
    private boolean registered;
    private boolean closed;

    public HighlightRenderer(ClientConfig config, FinderScanner scanner) {
        this(config, scanner, null);
    }

    public HighlightRenderer(ClientConfig config, FinderScanner scanner, ChunkFinderScanner chunkScanner) {
        this.config = config;
        this.scanner = scanner;
        this.chunkScanner = chunkScanner;
    }

    public void register() {
        if (registered) return;
        registered = true;
        WorldRenderEvents.END_EXTRACTION.register(this::extract);
        WorldRenderEvents.END_MAIN.register(this::draw);
    }

    private static RenderPipeline pipeline(String name, boolean lines, boolean throughWalls) {
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("donut", "pipeline/" + name))
                .withVertexShader(lines ? "core/rendertype_lines" : "core/position_color")
                .withFragmentShader("core/position_color")
                .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                .withVertexFormat(lines ? DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH
                        : DefaultVertexFormat.POSITION_COLOR, lines ? VertexFormat.Mode.LINES : VertexFormat.Mode.QUADS)
                .withBlend(BlendFunction.TRANSLUCENT)
                .withCull(!lines)
                .withDepthWrite(false)
                .withDepthTestFunction(throughWalls ? DepthTestFunction.NO_DEPTH_TEST : DepthTestFunction.LEQUAL_DEPTH_TEST);
        if (lines) {
            // The vanilla line vertex shader expands each segment in screen space.
            // The position-color fragment shader keeps these overlays independent of fog.
            builder.withUniform("Globals", UniformType.UNIFORM_BUFFER)
                    .withUniform("Fog", UniformType.UNIFORM_BUFFER);
        }
        return RenderPipelines.register(builder.build());
    }

    private void extract(WorldExtractionContext context) {
        FabricRenderState state = (FabricRenderState) context.worldState();
        if (closed) {
            state.setData(frame, List.of());
            return;
        }

        Vec3 camera = context.worldState().cameraRenderState.pos;
        List<DrawBox> boxes = new ArrayList<>();
        for (FoundBlock block : scanner.snapshot()) {
            FinderSettings settings = block.kind().isSpawner() ? config.spawner : config.storage;
            if (!settings.enabled || !block.kind().included(settings)) continue;
            double distance = Vec3.atCenterOf(block.pos()).distanceTo(camera);
            if (distance > settings.range) continue;

            if (!context.world().hasChunkAt(block.pos())) continue;
            var currentState = context.world().getBlockState(block.pos());
            if (TargetKind.from(currentState) != block.kind()) continue;
            VoxelShape shape = currentState.getShape(context.world(), block.pos());
            if (shape.isEmpty()) continue;
            AABB bounds = shape.bounds().move(block.pos()).inflate(0.003);
            if (!context.frustum().isVisible(bounds)) continue;

            double fade = settings.distanceFade ? fade(distance, settings.range) : 1.0;
            int color = color(settings, block.kind());
            int fill = settings.mode != RenderMode.OUTLINE ? argb(color, settings.fillOpacity * fade) : 0;
            int outline = settings.mode != RenderMode.FILL ? argb(color, settings.outlineOpacity * fade) : 0;
            if ((fill >>> 24) == 0 && (outline >>> 24) == 0) continue;
            // Subtract in double precision before making vertices, including near the world border.
            boxes.add(new DrawBox(bounds.move(-camera.x, -camera.y, -camera.z), fill, outline,
                    (float) Math.clamp(settings.lineWidth, 0.5, 8.0), settings.throughWalls));
        }
        if (chunkScanner != null && config.chunkFinder.enabled && config.chunkFinder.alpha > 0) {
            var settings = config.chunkFinder;
            for (var chunk : chunkScanner.snapshot()) {
                if (!context.world().hasChunk(chunk.chunkX(), chunk.chunkZ())) continue;
                double x = chunk.chunkX() * 16.0, z = chunk.chunkZ() * 16.0;
                double opacity = settings.alpha / 255.0;
                for (var layer : chunk.layers()) {
                    AABB bounds = new AABB(x, layer.minY() - .05, z,
                            x + 16, layer.maxY() + 1.05, z + 16);
                    if (!context.frustum().isVisible(bounds)) continue;
                    boxes.add(new DrawBox(bounds.move(-camera.x, -camera.y, -camera.z),
                            argb(settings.color, opacity * .06), argb(settings.color, opacity), 1.0f, true));
                }
            }
        }
        for (SchematicGhosts.Ghost ghost : ghosts.nearby(camera)) {
            boolean loaded = MaterialTracker.isLoaded(context.world(), ghost.pos());
            var actual = loaded ? context.world().getBlockState(ghost.pos()) : null;
            if (actual != null && actual.equals(ghost.state())) continue;
            boolean conflict = actual != null && !actual.canBeReplaced() && !actual.is(ghost.state().getBlock());
            int color = !loaded ? 0xB788FF : conflict ? 0xFF657B : ghost.state().getMapColor(context.world(), ghost.pos()).col;
            if (color == 0) color = config.accentColor;
            VoxelShape shape = ghost.state().getShape(context.world(), ghost.pos());
            AABB bounds = (shape.isEmpty() ? new AABB(0, 0, 0, 1, 1, 1) : shape.bounds())
                    .move(ghost.pos()).inflate(.002);
            if (!context.frustum().isVisible(bounds)) continue;
            boxes.add(new DrawBox(bounds.move(-camera.x, -camera.y, -camera.z),
                    argb(color, config.autoBuilder.previewOpacity), argb(color, .8), 1.3f, false));
        }
        state.setData(frame, List.copyOf(boxes));
    }

    private static int color(FinderSettings settings, TargetKind kind) {
        if (!settings.perTypeColors) return settings.color;
        return switch (kind) {
            case CHEST, TRAPPED_CHEST, ENDER_CHEST -> settings.chestColor;
            case SHULKER -> settings.shulkerColor;
            case DROPPER -> settings.dropperColor;
            case SPAWNER, TRIAL_SPAWNER -> settings.color;
        };
    }

    private static double fade(double distance, double range) {
        double progress = Math.clamp((distance / Math.max(1.0, range) - 0.5) * 2.0, 0.0, 1.0);
        return 1.0 - progress * progress * (3.0 - 2.0 * progress);
    }

    private static int argb(int rgb, double opacity) {
        return ((int) Math.round(Math.clamp(opacity, 0.0, 1.0) * 255.0) << 24) | (rgb & 0xFFFFFF);
    }

    private void draw(WorldRenderContext context) {
        if (closed) return;
        List<DrawBox> boxes = ((FabricRenderState) context.worldState()).getData(frame);
        if (boxes == null || boxes.isEmpty()) return;
        PoseStack.Pose pose = context.matrices().last();
        for (DrawBox box : boxes) {
            if ((box.fill >>> 24) != 0) {
                filled((box.throughWalls ? fillThrough : fillVisible).begin(), pose.pose(), box.bounds, box.fill);
            }
            if ((box.outline >>> 24) != 0) {
                outlined((box.throughWalls ? lineThrough : lineVisible).begin(), pose, box.bounds, box.outline, box.width);
            }
        }
        // Drawing all fills before lines keeps translucent surfaces from dimming their own outlines.
        fillVisible.flush();
        fillThrough.flush();
        lineVisible.flush();
        lineThrough.flush();
    }

    private static void filled(BufferBuilder buffer, Matrix4fc pose, AABB bounds, int color) {
        for (int[] face : FACES) {
            for (int corner : face) {
                buffer.addVertex(pose, x(bounds, corner), y(bounds, corner), z(bounds, corner)).setColor(color);
            }
        }
    }

    private static void outlined(BufferBuilder buffer, PoseStack.Pose pose, AABB bounds, int color, float width) {
        for (int corner = 0; corner < 8; corner++) {
            for (int axis = 1; axis <= 4; axis *= 2) {
                if ((corner & axis) != 0) continue;
                int other = corner | axis;
                float normalX = axis == 1 ? 1 : 0;
                float normalY = axis == 2 ? 1 : 0;
                float normalZ = axis == 4 ? 1 : 0;
                buffer.addVertex(pose, x(bounds, corner), y(bounds, corner), z(bounds, corner))
                        .setColor(color).setNormal(pose, normalX, normalY, normalZ).setLineWidth(width);
                buffer.addVertex(pose, x(bounds, other), y(bounds, other), z(bounds, other))
                        .setColor(color).setNormal(pose, normalX, normalY, normalZ).setLineWidth(width);
            }
        }
    }

    private static float x(AABB box, int corner) { return (float) ((corner & 1) == 0 ? box.minX : box.maxX); }
    private static float y(AABB box, int corner) { return (float) ((corner & 2) == 0 ? box.minY : box.maxY); }
    private static float z(AABB box, int corner) { return (float) ((corner & 4) == 0 ? box.minZ : box.maxZ); }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        fillVisible.close();
        fillThrough.close();
        lineVisible.close();
        lineThrough.close();
    }

    private record DrawBox(AABB bounds, int fill, int outline, float width, boolean throughWalls) { }

    /** A persistent CPU allocator and rotating GPU vertex buffer for one pipeline. */
    private static final class Batch implements AutoCloseable {
        private final RenderPipeline pipeline;
        private final ByteBufferBuilder allocator = new ByteBufferBuilder(64 * 1024);
        private BufferBuilder builder;
        private MappableRingBuffer vertices;

        private Batch(RenderPipeline pipeline) {
            this.pipeline = pipeline;
        }

        private BufferBuilder begin() {
            if (builder == null) builder = new BufferBuilder(allocator, pipeline.getVertexFormatMode(), pipeline.getVertexFormat());
            return builder;
        }

        private void flush() {
            if (builder == null) return;
            MeshData mesh = builder.buildOrThrow();
            builder = null;
            try (mesh) {
                int bytes = mesh.vertexBuffer().remaining();
                if (vertices == null || vertices.size() < bytes) {
                    if (vertices != null) vertices.close();
                    vertices = new MappableRingBuffer(() -> "Donut highlights", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, bytes);
                }
                try (GpuBuffer.MappedView mapped = RenderSystem.getDevice().createCommandEncoder()
                        .mapBuffer(vertices.currentBuffer().slice(0, bytes), false, true)) {
                    MemoryUtil.memCopy(mesh.vertexBuffer(), mapped.data());
                }

                GpuBuffer indices;
                VertexFormat.IndexType indexType;
                if (pipeline.getVertexFormatMode() == VertexFormat.Mode.QUADS) {
                    mesh.sortQuads(allocator, RenderSystem.getProjectionType().vertexSorting());
                    indices = pipeline.getVertexFormat().uploadImmediateIndexBuffer(mesh.indexBuffer());
                    indexType = mesh.drawState().indexType();
                } else {
                    RenderSystem.AutoStorageIndexBuffer sequential = RenderSystem.getSequentialBuffer(pipeline.getVertexFormatMode());
                    indices = sequential.getBuffer(mesh.drawState().indexCount());
                    indexType = sequential.type();
                }

                GpuBufferSlice transform = RenderSystem.getDynamicUniforms()
                        .writeTransform(RenderSystem.getModelViewMatrix(), WHITE, ZERO, IDENTITY);
                var target = Minecraft.getInstance().getMainRenderTarget();
                try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                        () -> "Donut finder overlay", target.getColorTextureView(), OptionalInt.empty(),
                        target.getDepthTextureView(), OptionalDouble.empty())) {
                    pass.setPipeline(pipeline);
                    RenderSystem.bindDefaultUniforms(pass);
                    pass.setUniform("DynamicTransforms", transform);
                    pass.setVertexBuffer(0, vertices.currentBuffer());
                    pass.setIndexBuffer(indices, indexType);
                    pass.drawIndexed(0, 0, mesh.drawState().indexCount(), 1);
                }
                vertices.rotate();
            }
        }

        @Override
        public void close() {
            builder = null;
            allocator.close();
            if (vertices != null) {
                vertices.close();
                vertices = null;
            }
        }
    }
}
