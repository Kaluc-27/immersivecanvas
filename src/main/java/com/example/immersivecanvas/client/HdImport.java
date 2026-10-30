package com.example.immersivecanvas.client;

import com.example.immersivecanvas.CanvasData;
import com.example.immersivecanvas.MediaStore;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Iterator;

/** Prépare un fichier image/GIF pour l'envoi : vérifie, réduit si trop grand (images), crée la miniature. */
final class HdImport {
    record Result(byte[] bytes, int kind, int w, int h, BufferedImage thumb) {}

    private static final int MAX_STILL_BYTES = 6 * 1024 * 1024;

    private HdImport() {}

    static Result prepare(File file) throws IOException {
        if (!file.isFile()) throw new IOException("fichier introuvable");
        if (file.length() > MediaStore.MAX_BYTES) throw new IOException("fichier trop lourd (12 Mo max)");
        byte[] raw = Files.readAllBytes(file.toPath());
        int kind = MediaStore.sniffKind(raw);
        if (kind == 0) throw new IOException("format non supporté (png, jpg, gif)");
        int[] d = MediaStore.dims(raw);
        if (d == null) throw new IOException("image illisible");

        if (kind == CanvasData.MODE_GIF) {
            if (!CanvasData.mediaDimsOk(kind, d[0], d[1])) {
                throw new IOException("GIF trop grand (" + d[0] + "x" + d[1] + ", max 1280x720)");
            }
            BufferedImage first = ImageIO.read(new ByteArrayInputStream(raw));
            return new Result(raw, kind, d[0], d[1], first == null ? null : thumbOf(first));
        }

        BufferedImage img = ImageIO.read(new ByteArrayInputStream(raw));
        if (img == null) throw new IOException("image illisible");
        int w = img.getWidth(), h = img.getHeight();
        boolean fits = CanvasData.mediaDimsOk(kind, w, h);
        if (fits && raw.length <= MAX_STILL_BYTES) {
            return new Result(raw, kind, w, h, thumbOf(img));
        }

        // trop grande : on réduit pour tenir dans 1920x1080 (ou 1080x1920) puis on ré-encode en JPEG
        double s = Math.min(1.0, Math.min(1920.0 / Math.max(w, h), 1080.0 / Math.min(w, h)));
        int nw = Math.max(1, (int) Math.round(w * s)), nh = Math.max(1, (int) Math.round(h * s));
        int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
        int[] rgb = fits && nw == w ? argbToRgb(argb) : ImageImporter.fit(argb, w, h, nw, nh, ImageImporter.Fit.STRETCH);
        byte[] out = encodeJpeg(rgb, nw, nh, 0.92f);
        if (out.length > MAX_STILL_BYTES) out = encodeJpeg(rgb, nw, nh, 0.75f);
        BufferedImage scaled = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        scaled.setRGB(0, 0, nw, nh, rgb, 0, nw);
        return new Result(out, CanvasData.MODE_IMAGE, nw, nh, thumbOf(scaled));
    }

    private static int[] argbToRgb(int[] argb) {
        int[] out = new int[argb.length];
        for (int i = 0; i < out.length; i++) {
            int a = argb[i];
            float al = ((a >>> 24) & 255) / 255f;
            int r = (int) (((a >> 16) & 255) * al + 255 * (1 - al));
            int g = (int) (((a >> 8) & 255) * al + 255 * (1 - al));
            int b = (int) ((a & 255) * al + 255 * (1 - al));
            out[i] = (r << 16) | (g << 8) | b;
        }
        return out;
    }

    /** Miniature de 64 pixels maximum (pour la galerie). */
    static BufferedImage thumbOf(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
        return thumbOf(argb, w, h);
    }

    static BufferedImage thumbOf(int[] argb, int w, int h) {
        int m = Math.max(w, h);
        int tw = Math.max(1, w * 64 / m), th = Math.max(1, h * 64 / m);
        if (m <= 64) { tw = w; th = h; }
        int[] rgb = ImageImporter.fit(argb, w, h, tw, th, ImageImporter.Fit.STRETCH);
        BufferedImage t = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        t.setRGB(0, 0, tw, th, rgb, 0, tw);
        return t;
    }

    static byte[] encodeJpeg(int[] rgb, int w, int h, float quality) throws IOException {
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        bi.setRGB(0, 0, w, h, rgb, 0, w);
        Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpeg");
        if (!it.hasNext()) throw new IOException("encodeur JPEG indisponible");
        ImageWriter writer = it.next();
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
            ImageWriteParam p = writer.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(quality);
            writer.setOutput(ios);
            writer.write(null, new IIOImage(bi, null, null), p);
            ios.flush();
            return bos.toByteArray();
        } finally {
            writer.dispose();
        }
    }
}
