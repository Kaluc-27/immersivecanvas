package com.example.immersivecanvas;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.Arrays;
import java.util.Base64;

/** Contenu d'un canevas : résolution (16/32/64) + pixels RGB (3 octets par pixel). */
public record CanvasData(int res, byte[] rgb) {
    public static final int MAX_RES = 64;
    public static final int MAX_BYTES = MAX_RES * MAX_RES * 3; // 12288 < 32 Ko (limite C2S)
    public static final CanvasData EMPTY = new CanvasData(0, new byte[0]);

    private static final Codec<byte[]> BYTES = Codec.STRING.comapFlatMap(s -> {
        try {
            return DataResult.success(Base64.getDecoder().decode(s));
        } catch (IllegalArgumentException e) {
            return DataResult.error(() -> "Base64 invalide");
        }
    }, b -> Base64.getEncoder().encodeToString(b));

    public static final Codec<CanvasData> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("res").forGetter(CanvasData::res),
            BYTES.fieldOf("rgb").forGetter(CanvasData::rgb)
    ).apply(i, CanvasData::new));

    public static final StreamCodec<ByteBuf, CanvasData> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, CanvasData::res,
            ByteBufCodecs.byteArray(MAX_BYTES), CanvasData::rgb,
            CanvasData::new);

    public boolean isValid() {
        return (res == 16 || res == 32 || res == 64) && rgb.length == res * res * 3;
    }

    /** @return pixels 0xRRGGBB */
    public int[] toPixels() {
        int[] px = new int[res * res];
        for (int i = 0; i < px.length; i++) {
            px[i] = ((rgb[i * 3] & 0xFF) << 16) | ((rgb[i * 3 + 1] & 0xFF) << 8) | (rgb[i * 3 + 2] & 0xFF);
        }
        return px;
    }

    public static CanvasData fromPixels(int res, int[] px) {
        byte[] out = new byte[px.length * 3];
        for (int i = 0; i < px.length; i++) {
            out[i * 3] = (byte) (px[i] >> 16);
            out[i * 3 + 1] = (byte) (px[i] >> 8);
            out[i * 3 + 2] = (byte) px[i];
        }
        return new CanvasData(res, out);
    }

    @Override public boolean equals(Object o) {
        return o instanceof CanvasData d && d.res == res && Arrays.equals(d.rgb, rgb);
    }
    @Override public int hashCode() { return 31 * res + Arrays.hashCode(rgb); }
    @Override public String toString() { return "CanvasData[" + res + "x" + res + "]"; }
}
