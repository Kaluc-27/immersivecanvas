package com.example.immersivecanvas.client;

import com.example.immersivecanvas.CanvasBlock;
import com.example.immersivecanvas.CanvasBlockEntity;
import com.example.immersivecanvas.CanvasGroup;
import com.example.immersivecanvas.ImmersiveCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

@EventBusSubscriber(modid = ImmersiveCanvas.MODID, value = Dist.CLIENT)
public final class CanvasClient {
    private static int tickCounter = 0;

    private CanvasClient() {}

    /** Ouvre l'éditeur sur le bloc maître du tableau, quel que soit le bloc cliqué. */
    public static void openEditor(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        BlockState st = mc.level.getBlockState(pos);
        if (!(st.getBlock() instanceof CanvasBlock)) return;
        BlockPos master = CanvasGroup.masterOf(st, pos);
        if (mc.level.getBlockEntity(master) instanceof CanvasBlockEntity be) {
            mc.setScreen(new CanvasEditorScreen(master, be.getData()));
        }
    }

    @SubscribeEvent
    static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ImmersiveCanvas.CANVAS_BE.get(), CanvasRenderer::new);
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (++tickCounter >= 100) {
            tickCounter = 0;
            CanvasTextures.cleanup();
        }
    }

    @SubscribeEvent
    static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) CanvasTextures.clear();
    }
}
