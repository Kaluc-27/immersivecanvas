package com.example.immersivecanvas.client;

import com.example.immersivecanvas.CanvasData;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/** Lecture d'images (png/jpg/bmp) et de GIF animés, adaptées à une grille W x H. */
final class ImageImporter {
    enum Fit { CROP, FIT, STRETCH }

    record Result(List<int[]> frames, List<Integer> delays) {}

    private ImageImporter() {}

    static Result load(File file, int W, int H, Fit fit, int maxFrames) throws IOException {
        try (ImageInputStream iis = ImageIO.createImageInputStream(file)) {
            if (iis == null) throw new IOException("illisible");
            Iterator<ImageReader> it = ImageIO.getImageReaders(iis);
            if (!it.hasNext()) throw new IOException("format non supporté");
            ImageReader reader = it.next();
            try {
                reader.setInput(iis, false, false);
                boolean gif = "gif".equalsIgnoreCase(reader.getFormatName());
                int n = gif ? reader.getNumImages(true) : 1;
                if (!gif || n <= 1) {
                    BufferedImage img = reader.read(0);
                    int w = img.getWidth(), h = img.getHeight();
                    int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
                    List<int[]> frames = new ArrayList<>();
                    List<Integer> delays = new ArrayList<>();
                    frames.add(fit(argb, w, h, W, H, fit));
                    delays.add(100);
                    return new Result(frames, delays);
                }
                return loadGif(reader, n, W, H, fit, maxFrames);
            } finally {
                reader.dispose();
            }
        }
    }

    private static Result loadGif(ImageReader reader, int n, int W, int H, Fit fit, int maxFrames) throws IOException {
        int sw = 0, sh = 0;
        IIOMetadata sm = reader.getStreamMetadata();
        if (sm != null) {
            Node root = sm.getAsTree("javax_imageio_gif_stream_1.0");
            Node lsd = child(root, "LogicalScreenDescriptor");
            if (lsd != null) {
                sw = intAttr(lsd, "logicalScreenWidth");
                sh = intAttr(lsd, "logicalScreenHeight");
            }
        }
        if (sw <= 0 || sh <= 0) {
            BufferedImage f0 = reader.read(0);
            sw = f0.getWidth();
            sh = f0.getHeight();
        }
        int[] canvas = new int[sw * sh];
        int group = (int) Math.ceil(n / (double) Math.max(1, maxFrames));
        List<int[]> frames = new ArrayList<>();
        List<Integer> delays = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            BufferedImage fr = reader.read(i);
            Node tree = reader.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0");
            Node desc = child(tree, "ImageDescriptor");
            int left = desc != null ? intAttr(desc, "imageLeftPosition") : 0;
            int top = desc != null ? intAttr(desc, "imageTopPosition") : 0;
            Node gce = child(tree, "GraphicControlExtension");
            int delay = gce != null ? intAttr(gce, "delayTime") : 10;
            String disposal = gce != null ? ((IIOMetadataNode) gce).getAttribute("disposalMethod") : "none";

            int[] prev = "restoreToPrevious".equals(disposal) ? canvas.clone() : null;
            int fw = fr.getWidth(), fh = fr.getHeight();
            for (int y = 0; y < fh; y++) {
                for (int x = 0; x < fw; x++) {
                    int cx = left + x, cy = top + y;
                    if (cx < 0 || cy < 0 || cx >= sw || cy >= sh) continue;
                    int argb = fr.getRGB(x, y);
                    if ((argb >>> 24) != 0) canvas[cy * sw + cx] = argb;
                }
            }
            int ms = delay <= 1 ? 100 : delay * 10;
            if (i % group == 0) {
                frames.add(fit(canvas, sw, sh, W, H, fit));
                delays.add(ms);
            } else {
                int last = delays.size() - 1;
                delays.set(last, delays.get(last) + ms);
            }
            if ("restoreToBackgroundColor".equals(disposal)) {
                for (int y = top; y < Math.min(sh, top + fh); y++) {
                    for (int x = left; x < Math.min(sw, left + fw); x++) {
                        if (x >= 0 && y >= 0) canvas[y * sw + x] = 0;
                    }
                }
            } else if (prev != null) {
                System.arraycopy(prev, 0, canvas, 0, canvas.length);
            }
        }
        for (int i = 0; i < delays.size(); i++) {
            delays.set(i, Math.max(CanvasData.MIN_DELAY, Math.min(CanvasData.MAX_DELAY, delays.get(i))));
        }
        return new Result(frames, delays);
    }

    private static Node child(Node parent, String name) {
        if (parent == null) return null;
        for (Node c = parent.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (name.equals(c.getNodeName())) return c;
        }
        return null;
    }

    private static int intAttr(Node node, String name) {
        try {
            return Integer.parseInt(((IIOMetadataNode) node).getAttribute(name));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Adapte une image ARGB à la grille W x H (moyenne de zone), transparence composée sur blanc. */
    static int[] fit(int[] src, int sw, int sh, int W, int H, Fit mode) {
        int[] out = new int[W * H];
        Arrays.fill(out, 0xFFFFFF);
        double sx0 = 0, sy0 = 0, sw2 = sw, sh2 = sh;
        int dx0 = 0, dy0 = 0, dw = W, dh = H;
        switch (mode) {
            case CROP -> {
                double target = (double) W / H, aspect = (double) sw / sh;
                if (aspect > target) { sw2 = sh * target; sx0 = (sw - sw2) / 2; }
                else { sh2 = sw / target; sy0 = (sh - sh2) / 2; }
            }
            case FIT -> {
                double scale = Math.min((double) W / sw, (double) H / sh);
                dw = Math.max(1, Math.min(W, (int) Math.round(sw * scale)));
                dh = Math.max(1, Math.min(H, (int) Math.round(sh * scale)));
                dx0 = (W - dw) / 2;
                dy0 = (H - dh) / 2;
            }
            case STRETCH -> {}
        }
        for (int y = 0; y < dh; y++) {
            int ya = (int) Math.floor(sy0 + y * sh2 / dh);
            int yb = (int) Math.floor(sy0 + (y + 1) * sh2 / dh);
            ya = Math.max(0, Math.min(sh - 1, ya));
            yb = Math.max(ya + 1, Math.min(sh, yb));
            for (int x = 0; x < dw; x++) {
                int xa = (int) Math.floor(sx0 + x * sw2 / dw);
                int xb = (int) Math.floor(sx0 + (x + 1) * sw2 / dw);
                xa = Math.max(0, Math.min(sw - 1, xa));
                xb = Math.max(xa + 1, Math.min(sw, xb));
                long r = 0, g = 0, b = 0;
                int count = 0;
                for (int yy = ya; yy < yb; yy++) {
                    for (int xx = xa; xx < xb; xx++) {
                        int argb = src[yy * sw + xx];
                        float a = ((argb >>> 24) & 255) / 255f;
                        r += (int) (((argb >> 16) & 255) * a + 255 * (1 - a));
                        g += (int) (((argb >> 8) & 255) * a + 255 * (1 - a));
                        b += (int) ((argb & 255) * a + 255 * (1 - a));
                        count++;
                    }
                }
                out[(dy0 + y) * W + dx0 + x] = ((int) (r / count) << 16) | ((int) (g / count) << 8) | (int) (b / count);
            }
        }
        return out;
    }
}
