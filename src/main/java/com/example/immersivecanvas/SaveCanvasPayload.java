package com.example.immersivecanvas;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Client -> serveur : un morceau (max 30 Ko) du dessin sérialisé. Le serveur recolle les morceaux,
 * valide tout, puis applique. (La limite d'un paquet client->serveur est de 32 Ko.)
 */
public record SaveCanvasPayload(BlockPos pos, int index, int total, byte[] data) implements CustomPacketPayload {
    public static final int CHUNK = 30_000;
    public static final int MAX_CHUNKS = 40;

    public static final Type<SaveCanvasPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(ImmersiveCanvas.MODID, "save_canvas"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SaveCanvasPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, SaveCanvasPayload::pos,
            ByteBufCodecs.VAR_INT, SaveCanvasPayload::index,
            ByteBufCodecs.VAR_INT, SaveCanvasPayload::total,
            ByteBufCodecs.byteArray(CHUNK), SaveCanvasPayload::data,
            SaveCanvasPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private static final class Pending {
        final BlockPos pos;
        final int total;
        int next = 0;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        Pending(BlockPos pos, int total) { this.pos = pos; this.total = total; }
    }

    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    public static void handle(SaveCanvasPayload p, IPayloadContext ctx) {
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
                pend = new Pending(p.pos(), p.total());
                PENDING.put(id, pend);
            }
            if (pend == null || !pend.pos.equals(p.pos()) || pend.total != p.total() || pend.next != p.index()) {
                PENDING.remove(id);
                return;
            }
            pend.out.write(p.data(), 0, p.data().length);
            pend.next++;
            if (pend.next == pend.total) {
                PENDING.remove(id);
                finish(player, pend.pos, pend.out.toByteArray());
            }
        });
    }

    private static void finish(ServerPlayer player, BlockPos pos, byte[] bytes) {
        CanvasData data;
        try {
            ByteBuf buf = Unpooled.wrappedBuffer(bytes);
            data = CanvasData.STREAM_CODEC.decode(buf);
        } catch (RuntimeException e) {
            return;
        }
        // Ne jamais faire confiance au client
        if (!data.isValid()) return;
        Level level = player.level();
        if (!level.isLoaded(pos)) return;
        if (player.distanceToSqr(Vec3.atCenterOf(pos)) > 8.0 * 8.0) return;
        if (data.isPixel()) {
            if (data.unpackFrames() == null) return;
        } else {
            MinecraftServer server = level.getServer();
            if (server == null) return;
            byte[] media = MediaStore.read(server, data.media());
            if (media == null) {
                player.displayClientMessage(Component.translatable("message.immersivecanvas.media_missing"), true);
                return;
            }
            int[] d = MediaStore.dims(media);
            if (MediaStore.sniffKind(media) != data.mode() || d == null || d[0] != data.mw() || d[1] != data.mh()) return;
        }
        CanvasGroup.apply(player, pos, data);
    }
}
