package com.example.immersivecanvas;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Client -> serveur : un morceau (30 Ko) d'un fichier image/GIF. Le fichier est enregistré une fois complet. */
public record MediaUploadPayload(int index, int total, byte[] data) implements CustomPacketPayload {
    public static final int CHUNK = 30_000;
    public static final int MAX_CHUNKS = MediaStore.MAX_BYTES / CHUNK + 2;

    public static final Type<MediaUploadPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(ImmersiveCanvas.MODID, "media_upload"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MediaUploadPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, MediaUploadPayload::index,
            ByteBufCodecs.VAR_INT, MediaUploadPayload::total,
            ByteBufCodecs.byteArray(CHUNK), MediaUploadPayload::data,
            MediaUploadPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private static final class Pending {
        final int total;
        int next = 0;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        Pending(int total) { this.total = total; }
    }

    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    public static void handle(MediaUploadPayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            UUID id = player.getUUID();
            if (p.total() < 1 || p.total() > MAX_CHUNKS || p.index() < 0 || p.index() >= p.total()
                    || p.data().length > CHUNK) {
                PENDING.remove(id);
                return;
            }
            Pending pend = PENDING.get(id);
            if (p.index() == 0) {
                pend = new Pending(p.total());
                PENDING.put(id, pend);
            }
            if (pend == null || pend.total != p.total() || pend.next != p.index()) {
                PENDING.remove(id);
                return;
            }
            pend.out.write(p.data(), 0, p.data().length);
            pend.next++;
            if (pend.next == pend.total) {
                PENDING.remove(id);
                MinecraftServer server = player.level().getServer();
                if (server == null) return;
                if (MediaStore.put(server, pend.out.toByteArray()) == null) {
                    player.displayClientMessage(Component.translatable("message.immersivecanvas.media_invalid"), true);
                }
            }
        });
    }
}
