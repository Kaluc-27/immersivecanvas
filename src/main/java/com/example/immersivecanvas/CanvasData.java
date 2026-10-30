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
 * Contenu d'un tableau (jusqu'à 24x24 blocs).
 * mode 0 = pixel-art (pixels compressés dans le bloc),
 * mode 1 = image HD (jusqu'à 1920x1080) et mode 2 = GIF (jusqu'à 1280x720) :
 * dans ces deux cas le bloc ne contient que l'empreinte (hash) du fichier, stocké dans le dossier du monde.
 */
public record CanvasData(int mode, int bw, int bh, int ppb, int style, int fit,
                         int[] delays, byte[] packed, String media, int mw, int mh) {
    public static final int MODE_PIXEL = 0, MODE_IMAGE = 1, MODE_GIF = 2;
    public static final int MAX_BLOCKS = 24;
    public static final int[] PPBS = {4, 8, 16, 32, 64};
    public static final int STYLES = 8;
    public static final int MAX_FRAMES = 64;
    public static final int MAX_RAW = 1_048_576;
    public static final int MAX_PACKED = 1_100_000;
    public static final int MIN_DELAY = 20;
    public static final int MAX_DELAY = 10_000;

    public static final CanvasData EMPTY = new CanvasData(0, 0, 0, 0, 0, 0, new int[0], new byte[0], "", 0, 0);

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
            Codec.INT.optionalFieldOf("mode", 0).forGetter(CanvasData::mode),
            Codec.INT.fieldOf("bw").forGetter(CanvasData::bw),
            Codec.INT.fieldOf("bh").forGetter(CanvasData::bh),
            Codec.INT.optionalFieldOf("ppb", 0).forGetter(CanvasData::ppb),
            Codec.INT.optionalFieldOf("style", 0).forGetter(CanvasData::style),
            Codec.INT.optionalFieldOf("fit", 0).forGetter(CanvasData::fit),
            INTS.optionalFieldOf("delays", new int[0]).forGetter(CanvasData::delays),
            BYTES.optionalFieldOf("packed", new byte[0]).forGetter(CanvasData::packed),
            Codec.STRING.optionalFieldOf("media", "").forGetter(CanvasData::media),
            Codec.INT.optionalFieldOf("mw", 0).forGetter(CanvasData::mw),
            Codec.INT.optionalFieldOf("mh", 0).forGetter(CanvasData::mh)
    ).apply(i, CanvasData::new));

    private static final StreamCodec<ByteBuf, int[]> INTS_STREAM =
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(MAX_FRAMES)).map(
                    l -> l.stream().mapToInt(Integer::intValue).toArray(),
                    a -> Arrays.stream(a).boxed().toList());

    private static final StreamCodec<ByteBuf, byte[]> PACKED_STREAM = ByteBufCodecs.byteArray(MAX_PACKED);
    private static final StreamCodec<ByteBuf, String> MEDIA_STREAM = ByteBufCodecs.stringUtf8(40);

    public static final StreamCodec<ByteBuf, CanvasData> STREAM_CODEC = StreamCodec.of(CanvasData::write, CanvasData::read);

    private static void write(ByteBuf buf, CanvasData d) {
        ByteBufCodecs.VAR_INT.encode(buf, d.mode);
        ByteBufCodecs.VAR_INT.encode(buf, d.bw);
        ByteBufCodecs.VAR_INT.encode(buf, d.bh);
        ByteBufCodecs.VAR_INT.encode(buf, d.ppb);
        ByteBufCodecs.VAR_INT.encode(buf, d.style);
        ByteBufCodecs.VAR_INT.encode(buf, d.fit);
        INTS_STREAM.encode(buf, d.delays);
        PACKED_STREAM.encode(buf, d.packed);
        MEDIA_STREAM.encode(buf, d.media);
        ByteBufCodecs.VAR_INT.encode(buf, d.mw);
        ByteBufCodecs.VAR_INT.encode(buf, d.mh);
    }

    private static CanvasData read(ByteBuf buf) {
        int mode = ByteBufCodecs.VAR_INT.decode(buf);
        int bw = ByteBufCodecs.VAR_INT.decode(buf);
        int bh = ByteBufCodecs.VAR_INT.decode(buf);
        int ppb = ByteBufCodecs.VAR_INT.decode(buf);
        int style = ByteBufCodecs.VAR_INT.decode(buf);
        int fit = ByteBufCodecs.VAR_INT.decode(buf);
        int[] delays = INTS_STREAM.decode(buf);
        byte[] packed = PACKED_STREAM.decode(buf);
        String media = MEDIA_STREAM.decode(buf);
        int mw = ByteBufCodecs.VAR_INT.decode(buf);
        int mh = ByteBufCodecs.VAR_INT.decode(buf);
        return new CanvasData(mode, bw, bh, ppb, style, fit, delays, packed, media, mw, mh);
    }

    // ------------------------------------------------------------------ fabriques

    public static CanvasData pixel(int bw, int bh, int ppb, int style, int[] delays, List<int[]> frames) {
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
        return new CanvasData(MODE_PIXEL, bw, bh, ppb, style, 0, delays.clone(), bos.toByteArray(), "", 0, 0);
    }

    public static CanvasData media(int kind, int bw, int bh, int style, int fit, String hash, int mw, int mh) {
        return new CanvasData(kind, bw, bh, 0, style, fit, new int[0], new byte[0], hash, mw, mh);
    }

    // ------------------------------------------------------------------ accès

    public boolean isPixel() { return mode == MODE_PIXEL; }
    public boolean isMedia() { return mode == MODE_IMAGE || mode == MODE_GIF; }
    public int width() { return bw * ppb; }
    public int height() { return bh * ppb; }
    public int frames() { return delays.length; }
    public long rawSize() { return (long) width() * height() * 3L * frames(); }

    public static boolean validPpb(int ppb) {
        for (int p : PPBS) if (p == ppb) return true;
        return false;
    }

    public static boolean validHash(String h) {
        if (h == null || h.length() != 32) return false;
        for (int i = 0; i < 32; i++) {
            char c = h.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }

    /** Dimensions autorisées : image 1920x1080 (ou 1080x1920), GIF 1280x720 (ou 720x1280). */
    public static boolean mediaDimsOk(int kind, int w, int h) {
        if (w < 1 || h < 1) return false;
        int hi = Math.max(w, h), lo = Math.min(w, h);
        return kind == MODE_GIF ? (hi <= 1280 && lo <= 720) : (hi <= 1920 && lo <= 1080);
    }

    /** Vérification structurelle rapide (ne décompresse rien, ne lit aucun fichier). */
    public boolean isValid() {
        if (bw < 1 || bw > MAX_BLOCKS || bh < 1 || bh > MAX_BLOCKS) return false;
        if (style < 0 || style >= STYLES) return false;
        if (fit < 0 || fit > 2) return false;
        if (mode == MODE_PIXEL) {
            if (!validPpb(ppb)) return false;
            if (delays.length < 1 || delays.length > MAX_FRAMES) return false;
            for (int d : delays) if (d < MIN_DELAY || d > MAX_DELAY) return false;
            if (rawSize() > MAX_RAW) return false;
            return packed.length > 0 && packed.length <= MAX_PACKED;
        }
        if (mode == MODE_IMAGE || mode == MODE_GIF) {
            return validHash(media) && mediaDimsOk(mode, mw, mh);
        }
        return false;
    }

    /** Pixel-art : décompresse (un tableau RGB par image), ou null si invalide. */
    public byte[][] unpackFrames() {
        if (mode != MODE_PIXEL || !isValid()) return null;
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
        return o instanceof CanvasData d && d.mode == mode && d.bw == bw && d.bh == bh && d.ppb == ppb
                && d.style == style && d.fit == fit && d.mw == mw && d.mh == mh && d.media.equals(media)
                && Arrays.equals(d.delays, delays) && Arrays.equals(d.packed, packed);
    }
    @Override public int hashCode() {
        int h = mode;
        h = 31 * h + bw;
        h = 31 * h + bh;
        h = 31 * h + ppb;
        h = 31 * h + style;
        h = 31 * h + fit;
        h = 31 * h + media.hashCode();
        return 31 * h + Arrays.hashCode(delays) * 17 + Arrays.hashCode(packed);
    }
    @Override public String toString() { return "CanvasData[mode " + mode + ", " + bw + "x" + bh + " blocs]"; }
}
