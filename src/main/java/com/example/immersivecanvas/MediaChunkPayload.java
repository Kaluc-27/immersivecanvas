package com.example.immersivecanvas;

import com.example.immersivecanvas.client.MediaClient;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Serveur -> client : un morceau d'un fichier demandé. total = 0 signifie « fichier introuvable ». */
public record MediaChunkPayload(String hash, int index, int total, byte[] data) implements CustomPacketPayload {
    public static final int CHUNK = 500_000;

    public static final Type<MediaChunkPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(ImmersiveCanvas.MODID, "media_chunk"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MediaChunkPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(40), MediaChunkPayload::hash,
            ByteBufCodecs.VAR_INT, MediaChunkPayload::index,
            ByteBufCodecs.VAR_INT, MediaChunkPayload::total,
            ByteBufCodecs.byteArray(CHUNK), MediaChunkPayload::data,
            MediaChunkPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(MediaChunkPayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> MediaClient.onChunk(p.hash(), p.index(), p.total(), p.data()));
    }
}
