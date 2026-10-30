package com.example.immersivecanvas;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;

/**
 * Contenu d'un tableau : taille en blocs (bw x bh, jusqu'à 24x24), pixels par bloc, style de cadre,
 * durée de chaque image (ms) et pixels RGB de toutes les images, compressés (deflate).
 */
public record CanvasData(int bw, int bh, int ppb, int style, int[] delays, byte[] packed) {
    public static final int MAX_BLOCKS = 24;
    public static final int[] PPBS = {4, 8, 16, 32, 64};
    public static final int STYLES = 8;             // 0 = pas de cadre
    public static final int MAX_FRAMES = 64;
    public static final int MAX_RAW = 1_048_576;    // octets RGB non compressés, toutes images confondues
    public static final int MAX_PACKED = 1_100_000; // octets compressés
    public static final int MIN_DELAY = 20;
    public static final int MAX_DELAY = 10_000;

    public static final CanvasData EMPTY = new CanvasData(0, 0, 0, 0, new int[0], new byte[0]);

    private static final Codec<byte[]> BYTES = Codec.STRING.comapFlatMap(s -> {
        try {
            return DataResult.success(Base64.getDecoder().decode(s));
        } catch (IllegalArgumentException e) {
            return DataResult.error(() -> "Base64 invalide");
        }
    }, b -> Base64.getEncoder().encodeToString(b));

    private static final Codec<int[]> INTS = Codec.INT.listOf().xmap(
            l -> l.stream().mapToInt(Integer::intValue).toArray(),
            a -> Arrays.stream(a).boxed().toList());

    public static final Codec<CanvasData> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("bw").forGetter(CanvasData::bw),
            Codec.INT.fieldOf("bh").forGetter(CanvasData::bh),
            Codec.INT.fieldOf("ppb").forGetter(CanvasData::ppb),
            Codec.INT.optionalFieldOf("style", 0).forGetter(CanvasData::style),
            INTS.fieldOf("delays").forGetter(CanvasData::delays),
            BYTES.fieldOf("packed").forGetter(CanvasData::packed)
    ).apply(i, CanvasData::new));

    private static final StreamCodec<ByteBuf, int[]> INTS_STREAM =
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(MAX_FRAMES)).map(
                    l -> l.stream().mapToInt(Integer::intValue).toArray(),
                    a -> Arrays.stream(a).boxed().toList());

    public static final StreamCodec<ByteBuf, CanvasData> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, CanvasData::bw,
            ByteBufCodecs.VAR_INT, CanvasData::bh,
            ByteBufCodecs.VAR_INT, CanvasData::ppb,
            ByteBufCodecs.VAR_INT, CanvasData::style,
            INTS_STREAM, CanvasData::delays,
            ByteBufCodecs.byteArray(MAX_PACKED), CanvasData::packed,
            CanvasData::new);

    public int width() { return bw * ppb; }
    public int height() { return bh * ppb; }
    public int frames() { return delays.length; }
    public long rawSize() { return (long) width() * height() * 3L * frames(); }

    public static boolean validPpb(int ppb) {
        for (int p : PPBS) if (p == ppb) return true;
        return false;
    }

    /** Vérification structurelle rapide (ne décompresse pas). */
    public boolean isValid() {
        if (bw < 1 || bw > MAX_BLOCKS || bh < 1 || bh > MAX_BLOCKS) return false;
        if (!validPpb(ppb)) return false;
        if (style < 0 || style >= STYLES) return false;
        if (delays.length < 1 || delays.length > MAX_FRAMES) return false;
        for (int d : delays) if (d < MIN_DELAY || d > MAX_DELAY) return false;
        if (rawSize() > MAX_RAW) return false;
        return packed.length > 0 && packed.length <= MAX_PACKED;
    }

    public static CanvasData pack(int bw, int bh, int ppb, int style, int[] delays, List<int[]> frames) {
        int n = bw * ppb * bh * ppb;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Deflater def = new Deflater(6);
        try (DeflaterOutputStream out = new DeflaterOutputStream(bos, def)) {
            byte[] row = new byte[n * 3];
            for (int[] f : frames) {
                for (int i = 0; i < n; i++) {
                    row[i * 3] = (byte) (f[i] >> 16);
                    row[i * 3 + 1] = (byte) (f[i] >> 8);
                    row[i * 3 + 2] = (byte) f[i];
                }
                out.write(row);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            def.end();
        }
        return new CanvasData(bw, bh, ppb, style, delays.clone(), bos.toByteArray());
    }

    /** Décompresse : un tableau RGB (3 octets/pixel) par image, ou null si les données sont invalides. */
    public byte[][] unpackFrames() {
        if (!isValid()) return null;
        int frameBytes = width() * height() * 3;
        byte[] raw = new byte[frameBytes * frames()];
        Inflater inf = new Inflater();
        try {
            inf.setInput(packed);
            int off = 0;
            while (off < raw.length) {
                int n = inf.inflate(raw, off, raw.length - off);
                if (n == 0 && (inf.finished() || inf.needsInput() || inf.needsDictionary())) return null;
                off += n;
            }
            if (inf.inflate(new byte[1]) > 0) return null;
        } catch (DataFormatException e) {
            return null;
        } finally {
            inf.end();
        }
        byte[][] out = new byte[frames()][];
        for (int i = 0; i < out.length; i++) {
            out[i] = Arrays.copyOfRange(raw, i * frameBytes, (i + 1) * frameBytes);
        }
        return out;
    }

    public static int[] toInts(byte[] rgb) {
        int[] px = new int[rgb.length / 3];
        for (int i = 0; i < px.length; i++) {
            px[i] = ((rgb[i * 3] & 0xFF) << 16) | ((rgb[i * 3 + 1] & 0xFF) << 8) | (rgb[i * 3 + 2] & 0xFF);
        }
        return px;
    }

    @Override public boolean equals(Object o) {
        return o instanceof CanvasData d && d.bw == bw && d.bh == bh && d.ppb == ppb && d.style == style
                && Arrays.equals(d.delays, delays) && Arrays.equals(d.packed, packed);
    }
    @Override public int hashCode() {
        return 31 * (31 * (31 * (bw * 31 + bh) + ppb) + style) + Arrays.hashCode(delays) * 17 + Arrays.hashCode(packed);
    }
    @Override public String toString() { return "CanvasData[" + width() + "x" + height() + ", " + frames() + " img]"; }
}
