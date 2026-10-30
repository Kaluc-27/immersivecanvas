package com.example.immersivecanvas;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Client -> serveur : « envoie-moi le fichier d'empreinte X » (en morceaux de 500 Ko). */
public record MediaRequestPayload(String hash) implements CustomPacketPayload {
    public static final Type<MediaRequestPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(ImmersiveCanvas.MODID, "media_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MediaRequestPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(40), MediaRequestPayload::hash,
            MediaRequestPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private static final Map<UUID, Map<String, Long>> RECENT = new HashMap<>();

    public static void handle(MediaRequestPayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            String hash = p.hash();
            if (!CanvasData.validHash(hash)) return;

            // anti-spam : même fichier au plus une fois toutes les 3 s par joueur
            long now = System.currentTimeMillis();
            Map<String, Long> m = RECENT.computeIfAbsent(player.getUUID(), k -> new HashMap<>());
            Long last = m.get(hash);
            if (last != null && now - last < 3000) return;
            if (m.size() > 200) m.clear();
            m.put(hash, now);

            MinecraftServer server = player.level().getServer();
            if (server == null) return;
            byte[] bytes = MediaStore.read(server, hash);
            if (bytes == null) {
                PacketDistributor.sendToPlayer(player, new MediaChunkPayload(hash, 0, 0, new byte[0]));
                return;
            }
            int total = (bytes.length + MediaChunkPayload.CHUNK - 1) / MediaChunkPayload.CHUNK;
            for (int i = 0; i < total; i++) {
                int from = i * MediaChunkPayload.CHUNK;
                int to = Math.min(bytes.length, from + MediaChunkPayload.CHUNK);
                PacketDistributor.sendToPlayer(player, new MediaChunkPayload(hash, i, total, Arrays.copyOfRange(bytes, from, to)));
            }
        });
    }
}
