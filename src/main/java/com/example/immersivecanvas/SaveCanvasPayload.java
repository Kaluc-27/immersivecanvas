package com.example.immersivecanvas;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client -> serveur : le joueur enregistre son dessin. */
public record SaveCanvasPayload(BlockPos pos, CanvasData data) implements CustomPacketPayload {
    public static final Type<SaveCanvasPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(ImmersiveCanvas.MODID, "save_canvas"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SaveCanvasPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, SaveCanvasPayload::pos,
            CanvasData.STREAM_CODEC, SaveCanvasPayload::data,
            SaveCanvasPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(SaveCanvasPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            Level level = player.level();
            BlockPos pos = payload.pos();
            // Validations serveur : ne jamais faire confiance au client
            if (!payload.data().isValid()) return;
            if (!level.isLoaded(pos)) return;
            if (player.distanceToSqr(Vec3.atCenterOf(pos)) > 8.0 * 8.0) return;
            if (level.getBlockEntity(pos) instanceof CanvasBlockEntity be) {
                be.setData(payload.data());
            }
        });
    }
}
