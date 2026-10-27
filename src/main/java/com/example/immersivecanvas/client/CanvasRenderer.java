package com.example.immersivecanvas.client;

import com.example.immersivecanvas.CanvasBlock;
import com.example.immersivecanvas.CanvasBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
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
import net.minecraft.world.phys.Vec3;

/** Dessine l'image du canevas sur la face avant de la plaque. */
public class CanvasRenderer implements BlockEntityRenderer<CanvasBlockEntity, CanvasRenderer.State> {

    public static class State extends BlockEntityRenderState {
        public Identifier texture;
        public Direction facing = Direction.NORTH;
    }

    public CanvasRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public State createRenderState() { return new State(); }

    @Override
    public void extractRenderState(CanvasBlockEntity be, State state, float partialTick, Vec3 cameraPos,
                                   ModelFeatureRenderer.CrumblingOverlay crumbling) {
        BlockEntityRenderer.super.extractRenderState(be, state, partialTick, cameraPos, crumbling);
        state.facing = be.getBlockState().getValue(CanvasBlock.FACING);
        state.texture = CanvasTextures.get(be);
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
        final float z = 14f / 16f - 0.002f; // juste devant la plaque (modèle orienté nord)

        collector.submitCustomGeometry(poseStack, RenderTypes.entityCutoutNoCull(state.texture), (pose, vc) -> {
            // Vu de face (on regarde vers le sud) : l'est est à gauche. Sens anti-horaire.
            vc.addVertex(pose, 1f, 1f, z).setColor(255, 255, 255, 255).setUv(0f, 0f)
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0f, 0f, -1f);
            vc.addVertex(pose, 1f, 0f, z).setColor(255, 255, 255, 255).setUv(0f, 1f)
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0f, 0f, -1f);
            vc.addVertex(pose, 0f, 0f, z).setColor(255, 255, 255, 255).setUv(1f, 1f)
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0f, 0f, -1f);
            vc.addVertex(pose, 0f, 1f, z).setColor(255, 255, 255, 255).setUv(1f, 0f)
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0f, 0f, -1f);
        });

        poseStack.popPose();
    }
}
