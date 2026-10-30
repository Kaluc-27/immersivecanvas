package com.example.immersivecanvas.client;

import com.example.immersivecanvas.CanvasBlock;
import com.example.immersivecanvas.CanvasBlockEntity;
import com.example.immersivecanvas.CanvasData;
import com.example.immersivecanvas.ImmersiveCanvas;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Seul le bloc maître dessine. L'image est posée à plat contre le mur (aucune épaisseur) ;
 * un éventuel cadre est un liseré en relief (1/16 de bloc) autour de l'image.
 */
public class CanvasRenderer implements BlockEntityRenderer<CanvasBlockEntity, CanvasRenderer.State> {

    /** Textures des cadres, indexées par le style (0 = aucun). */
    private static final Identifier[] FRAME_TEXTURES = {
            null,
            Identifier.withDefaultNamespace("textures/block/oak_planks.png"),
            Identifier.withDefaultNamespace("textures/block/spruce_planks.png"),
            Identifier.withDefaultNamespace("textures/block/birch_planks.png"),
            Identifier.withDefaultNamespace("textures/block/dark_oak_planks.png"),
            Identifier.withDefaultNamespace("textures/block/gold_block.png"),
            Identifier.withDefaultNamespace("textures/block/iron_block.png"),
            Identifier.withDefaultNamespace("textures/block/obsidian.png")
    };
    private static final Identifier BLANK = Identifier.fromNamespaceAndPath(ImmersiveCanvas.MODID, "textures/block/canvas.png");

    private static final float WALL_GAP = 0.004f;   // l'image est à 0,004 bloc du mur (anti z-fighting)
    private static final float FRAME_W = 1.5f / 16f; // largeur du liseré
    private static final float FRAME_D = 1f / 16f;   // relief du cadre

    public static class State extends BlockEntityRenderState {
        public Identifier texture;
        public Direction facing = Direction.NORTH;
        public int bw = 1, bh = 1, style = 0;
    }

    public CanvasRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public State createRenderState() { return new State(); }

    @Override
    public void extractRenderState(CanvasBlockEntity be, State state, float partialTick, Vec3 cameraPos,
                                   ModelFeatureRenderer.CrumblingOverlay crumbling) {
        BlockEntityRenderer.super.extractRenderState(be, state, partialTick, cameraPos, crumbling);
        BlockState bs = be.getBlockState();
        state.facing = bs.getValue(CanvasBlock.FACING);
        state.texture = null;
        if (!CanvasBlock.isMaster(bs)) return;

        CanvasData d = be.getData();
        Identifier tex = CanvasTextures.get(be);
        if (tex != null && d.isValid()) {
            state.texture = tex;
            state.bw = d.bw();
            state.bh = d.bh();
            state.style = d.style();
        } else {
            // canevas vierge : toile unie 1x1
            state.texture = BLANK;
            state.bw = 1;
            state.bh = 1;
            state.style = 0;
        }
    }

    /** Pour que le grand tableau ne disparaisse pas quand le bloc maître sort de l'écran. */
    public AABB getRenderBoundingBox(CanvasBlockEntity be) {
        return be.getRenderBoundingBox();
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.texture == null) return;

        poseStack.pushPose();
        poseStack.translate(0.5, 0.5, 0.5);
        switch (state.facing) {
            case NORTH -> {}
            case SOUTH, EAST, WEST -> poseStack.mulPose(Axis.YP.rotationDegrees(180f - state.facing.toYRot()));
            case UP -> poseStack.mulPose(Axis.XP.rotationDegrees(90f));
            case DOWN -> poseStack.mulPose(Axis.XP.rotationDegrees(-90f));
        }
        poseStack.translate(-0.5, -0.5, -0.5);

        final int light = state.lightCoords;
        final int bw = state.bw, bh = state.bh;
        final boolean framed = state.style > 0 && state.style < FRAME_TEXTURES.length;
        final float zImg = 1f - WALL_GAP;          // le mur est du côté z = 1 (repère "face au nord")
        final float inset = framed ? FRAME_W : 0f;
        final float xl = 1f - inset;               // bord gauche (vu de face)
        final float xr = 1f - bw + inset;          // bord droit
        final float yt = bh - inset;
        final float yb = inset;

        collector.submitCustomGeometry(poseStack, RenderTypes.entityCutoutNoCull(state.texture), (pose, vc) -> {
            vertex(vc, pose, light, xl, yt, zImg, 0f, 0f, 0f, 0f, -1f);
            vertex(vc, pose, light, xl, yb, zImg, 0f, 1f, 0f, 0f, -1f);
            vertex(vc, pose, light, xr, yb, zImg, 1f, 1f, 0f, 0f, -1f);
            vertex(vc, pose, light, xr, yt, zImg, 1f, 0f, 0f, 0f, -1f);
        });

        if (framed) {
            collector.submitCustomGeometry(poseStack, RenderTypes.entityCutoutNoCull(FRAME_TEXTURES[state.style]),
                    (pose, vc) -> drawFrame(vc, pose, light, bw, bh, zImg));
        }

        poseStack.popPose();
    }

    private static void drawFrame(VertexConsumer vc, PoseStack.Pose pose, int light, int bw, int bh, float zBack) {
        float zFront = zBack - FRAME_D;
        float xl = 1f, xr = 1f - bw;
        // montants gauche et droit : toute la hauteur, par segments d'1 bloc (pour garder l'échelle de la texture)
        for (float y = 0; y < bh; y += 1f) {
            float y1 = Math.min(y + 1f, bh);
            box(vc, pose, light, xl - FRAME_W, xl, y, y1, zFront, zBack);
            box(vc, pose, light, xr, xr + FRAME_W, y, y1, zFront, zBack);
        }
        // traverses haute et basse : entre les montants
        float end = xl - FRAME_W;
        for (float x = xr + FRAME_W; x < end; x += 1f) {
            float x1 = Math.min(x + 1f, end);
            box(vc, pose, light, x, x1, 0f, FRAME_W, zFront, zBack);
            box(vc, pose, light, x, x1, bh - FRAME_W, bh, zFront, zBack);
        }
    }

    /** Parallélépipède : face avant + 4 côtés (le fond est contre le mur). Repère "face au nord". */
    private static void box(VertexConsumer vc, PoseStack.Pose pose, int light,
                            float xMin, float xMax, float yMin, float yMax, float zf, float zb) {
        float dx = xMax - xMin, dy = yMax - yMin, dz = zb - zf;
        // avant (normale -z) : gauche = xMax
        vertex(vc, pose, light, xMax, yMax, zf, 0, 0, 0, 0, -1);
        vertex(vc, pose, light, xMax, yMin, zf, 0, dy, 0, 0, -1);
        vertex(vc, pose, light, xMin, yMin, zf, dx, dy, 0, 0, -1);
        vertex(vc, pose, light, xMin, yMax, zf, dx, 0, 0, 0, -1);
        // dessus (+y)
        vertex(vc, pose, light, xMax, yMax, zf, 0, 0, 0, 1, 0);
        vertex(vc, pose, light, xMax, yMax, zb, 0, dz, 0, 1, 0);
        vertex(vc, pose, light, xMin, yMax, zb, dx, dz, 0, 1, 0);
        vertex(vc, pose, light, xMin, yMax, zf, dx, 0, 0, 1, 0);
        // dessous (-y)
        vertex(vc, pose, light, xMax, yMin, zf, 0, 0, 0, -1, 0);
        vertex(vc, pose, light, xMax, yMin, zb, 0, dz, 0, -1, 0);
        vertex(vc, pose, light, xMin, yMin, zb, dx, dz, 0, -1, 0);
        vertex(vc, pose, light, xMin, yMin, zf, dx, 0, 0, -1, 0);
        // côté x = xMax (+x)
        vertex(vc, pose, light, xMax, yMax, zf, 0, 0, 1, 0, 0);
        vertex(vc, pose, light, xMax, yMin, zf, 0, dy, 1, 0, 0);
        vertex(vc, pose, light, xMax, yMin, zb, dz, dy, 1, 0, 0);
        vertex(vc, pose, light, xMax, yMax, zb, dz, 0, 1, 0, 0);
        // côté x = xMin (-x)
        vertex(vc, pose, light, xMin, yMax, zf, 0, 0, -1, 0, 0);
        vertex(vc, pose, light, xMin, yMin, zf, 0, dy, -1, 0, 0);
        vertex(vc, pose, light, xMin, yMin, zb, dz, dy, -1, 0, 0);
        vertex(vc, pose, light, xMin, yMax, zb, dz, 0, -1, 0, 0);
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose pose, int light,
                               float x, float y, float z, float u, float v, float nx, float ny, float nz) {
        vc.addVertex(pose, x, y, z).setColor(255, 255, 255, 255).setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nx, ny, nz);
    }
}
