package dev.donut.client.gui;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.donut.client.autobuild.Schematic;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A batched, depth-sorted voxel model. Large models use a disclosed surface sample. */
final class SchematicModelPreview {
    private static final int MAX_VOXELS = 6000;
    private final List<Voxel> voxels = new ArrayList<>();
    private Schematic schematic;
    private double yaw = Math.PI / 4, pitch = .48, zoom = 1;
    private boolean sampled;

    void setSchematic(Schematic value) {
        if (schematic == value) return;
        schematic = value;
        voxels.clear();
        sampled = false;
        yaw = Math.PI / 4; pitch = .48; zoom = 1;
        if (value == null) return;
        List<Schematic.Block> candidates = value.blocks();
        // Removing enclosed voxels before sampling preserves a solid exterior for ordinary builds.
        if (candidates.size() <= 250_000) {
            Set<BlockPos> occupied = new HashSet<>();
            for (Schematic.Block block : candidates) occupied.add(block.pos());
            candidates = candidates.stream().filter(block -> {
                BlockPos p = block.pos();
                return !occupied.contains(p.above()) || !occupied.contains(p.below())
                        || !occupied.contains(p.north()) || !occupied.contains(p.south())
                        || !occupied.contains(p.east()) || !occupied.contains(p.west());
            }).toList();
        }
        int stride = Math.max(1, (candidates.size() + MAX_VOXELS - 1) / MAX_VOXELS);
        sampled = stride > 1;
        for (int i = 0; i < candidates.size(); i += stride) {
            Schematic.Block block = candidates.get(i);
            int rgb = block.state().getMapColor(EmptyBlockGetter.INSTANCE, block.pos()).col;
            if (rgb == 0) rgb = 0x88A2BE;
            voxels.add(new Voxel(block.pos(), rgb));
        }
    }

    boolean sampled() { return sampled; }
    void rotate(double dx, double dy) { yaw += dx * .012; pitch = Math.clamp(pitch + dy * .009, .12, 1.3); }
    void zoom(double scroll) { zoom = Math.clamp(zoom * Math.pow(1.12, scroll), .25, 5); }
    void reset() { yaw = Math.PI / 4; pitch = .48; zoom = 1; }

    void render(GuiGraphics g, int x, int y, int width, int height) {
        if (schematic == null || voxels.isEmpty()) return;
        BlockPos d = schematic.dimensions();
        double cy = Math.cos(yaw), sy = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
        double spanX = Math.abs(cy) * d.getX() + Math.abs(sy) * d.getZ();
        double spanY = Math.abs(sp * sy) * d.getX() + cp * d.getY() + Math.abs(sp * cy) * d.getZ();
        double unit = Math.min((width - 30) / Math.max(1, spanX), (height - 26) / Math.max(1, spanY)) * zoom;
        List<Face> faces = new ArrayList<>(voxels.size() * 3);
        int[][][] templates = {
                {{0,1,0},{1,1,0},{1,1,1},{0,1,1}},
                sy >= 0 ? new int[][]{{1,0,0},{1,0,1},{1,1,1},{1,1,0}} : new int[][]{{0,0,1},{0,0,0},{0,1,0},{0,1,1}},
                cy >= 0 ? new int[][]{{1,0,1},{0,0,1},{0,1,1},{1,1,1}} : new int[][]{{0,0,0},{1,0,0},{1,1,0},{0,1,0}}
        };
        for (Voxel voxel : voxels) {
            int[] colors = {shade(voxel.color, 1.08), shade(voxel.color, .72), shade(voxel.color, .88)};
            for (int face = 0; face < 3; face++) {
                float[] points = new float[8];
                double depth = 0;
                for (int i = 0; i < 4; i++) {
                    double px = voxel.pos.getX() + templates[face][i][0] - d.getX() / 2.0;
                    double py = voxel.pos.getY() + templates[face][i][1] - d.getY() / 2.0;
                    double pz = voxel.pos.getZ() + templates[face][i][2] - d.getZ() / 2.0;
                    double horizontal = cy * px - sy * pz, forward = sy * px + cy * pz;
                    points[i * 2] = (float) (x + width / 2.0 + horizontal * unit);
                    points[i * 2 + 1] = (float) (y + height / 2.0 + (forward * sp - py * cp) * unit);
                    depth += forward * cp + py * sp;
                }
                faces.add(new Face(points, UiPaint.withOpacity(colors[face]), depth));
            }
        }
        faces.sort(Comparator.comparingDouble(Face::depth));
        g.guiRenderState.submitGuiElement(new ModelMesh(g, x, y, width, height, faces));
    }

    private int shade(int rgb, double amount) {
        return 0xFF000000 | Math.min(255, (int) (((rgb >> 16) & 255) * amount)) << 16
                | Math.min(255, (int) (((rgb >> 8) & 255) * amount)) << 8 | Math.min(255, (int) ((rgb & 255) * amount));
    }
    private record Voxel(BlockPos pos, int color) { }
    private record Face(float[] points, int color, double depth) { }

    private static final class ModelMesh implements GuiElementRenderState {
        private final Matrix3x2fc pose;
        private final ScreenRectangle scissor, bounds;
        private final List<Face> faces;
        ModelMesh(GuiGraphics g, int x, int y, int width, int height, List<Face> faces) {
            this.pose = new Matrix3x2f(g.pose());
            this.scissor = g.scissorStack.peek();
            ScreenRectangle transformed = new ScreenRectangle(x, y, width, height).transformMaxBounds(pose);
            this.bounds = scissor == null ? transformed : transformed.intersection(scissor);
            this.faces = faces;
        }
        @Override public void buildVertices(VertexConsumer consumer) {
            // GUI projection flips screen Y; use the same winding as Minecraft's filled quads.
            for (Face face : faces) for (int i = 3; i >= 0; i--)
                consumer.addVertexWith2DPose(pose, face.points[i * 2], face.points[i * 2 + 1]).setColor(face.color);
        }
        @Override public RenderPipeline pipeline() { return RenderPipelines.GUI; }
        @Override public TextureSetup textureSetup() { return TextureSetup.noTexture(); }
        @Override public ScreenRectangle scissorArea() { return scissor; }
        @Override public ScreenRectangle bounds() { return bounds; }
    }
}
