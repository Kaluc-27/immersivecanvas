package com.example.immersivecanvas.client;

import com.example.immersivecanvas.MediaStore;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Lit un GIF image par image dans un thread d'arrière-plan (seulement 3 images en mémoire),
 * pour pouvoir afficher de longs GIF 720p sans les décompresser entièrement.
 * Le rythme suit l'horloge réelle : tous les joueurs voient la même image au même moment.
 */
final class GifPlayer {
    private record Frame(long abs, int[] px) {}

    final int w, h, n;
    private final int[] delays;
    private final long cycle;
    private final byte[] bytes;
    private final ArrayBlockingQueue<Frame> ready = new ArrayBlockingQueue<>(2);
    private final ArrayBlockingQueue<int[]> free = new ArrayBlockingQueue<>(4);
    private volatile boolean stop;
    private volatile boolean failed;
    private Thread thread;
    private long baseCycle;

    private GifPlayer(byte[] bytes, int w, int h, int[] delays) {
        this.bytes = bytes;
        this.w = w;
        this.h = h;
        this.delays = delays;
        this.n = delays.length;
        long c = 0;
        for (int d : delays) c += d;
        this.cycle = Math.max(1, c);
    }

    static GifPlayer open(byte[] bytes) throws IOException {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (iis == null) throw new IOException("illisible");
            Iterator<ImageReader> it = ImageIO.getImageReaders(iis);
            if (!it.hasNext()) throw new IOException("format");
            ImageReader reader = it.next();
            try {
                reader.setInput(iis, false, false);
                int n = reader.getNumImages(true);
                if (n < 1) throw new IOException("GIF vide");
                int[] size = MediaStore.gifLogicalSize(reader);
                int w, h;
                if (size != null) {
                    w = size[0];
                    h = size[1];
                } else {
                    BufferedImage f = reader.read(0);
                    w = f.getWidth();
                    h = f.getHeight();
                }
                int[] delays = new int[n];
                for (int i = 0; i < n; i++) delays[i] = delayMs(reader.getImageMetadata(i));
                return new GifPlayer(bytes, w, h, delays);
            } finally {
                reader.dispose();
            }
        }
    }

    private static int delayMs(IIOMetadata meta) {
        Node tree = meta.getAsTree("javax_imageio_gif_image_1.0");
        Node gce = MediaStore.child(tree, "GraphicControlExtension");
        int d = gce != null ? MediaStore.intAttr(gce, "delayTime") : 10;
        int ms = d <= 1 ? 100 : d * 10;
        return Math.max(20, ms);
    }

    void start() {
        baseCycle = System.currentTimeMillis() / cycle;
        for (int i = 0; i < 3; i++) free.offer(new int[w * h]);
        thread = new Thread(this::run, "immersivecanvas-gif");
        thread.setDaemon(true);
        thread.start();
    }

    void close() {
        stop = true;
        if (thread != null) thread.interrupt();
    }

    private void run() {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> it = ImageIO.getImageReaders(iis);
            ImageReader reader = it.next();
            try {
                reader.setInput(iis, false, false);
                int[] canvas = new int[w * h];
                long cycleId = baseCycle;
                while (!stop) {
                    Arrays.fill(canvas, 0);
                    for (int i = 0; i < n && !stop; i++) {
                        BufferedImage fr = reader.read(i);
                        Node tree = reader.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0");
                        Node desc = MediaStore.child(tree, "ImageDescriptor");
                        int left = desc != null ? MediaStore.intAttr(desc, "imageLeftPosition") : 0;
                        int top = desc != null ? MediaStore.intAttr(desc, "imageTopPosition") : 0;
                        Node gce = MediaStore.child(tree, "GraphicControlExtension");
                        String disposal = gce != null ? ((IIOMetadataNode) gce).getAttribute("disposalMethod") : "none";

                        int[] prev = "restoreToPrevious".equals(disposal) ? canvas.clone() : null;
                        int fw = fr.getWidth(), fh = fr.getHeight();
                        for (int y = 0; y < fh; y++) {
                            int cy = top + y;
                            if (cy < 0 || cy >= h) continue;
                            for (int x = 0; x < fw; x++) {
                                int cx = left + x;
                                if (cx < 0 || cx >= w) continue;
                                int argb = fr.getRGB(x, y);
                                if ((argb >>> 24) != 0) canvas[cy * w + cx] = argb;
                            }
                        }

                        int[] out = free.take();
                        for (int k = 0; k < out.length; k++) {
                            int a = canvas[k];
                            out[k] = (a >>> 24) == 0 ? 0xFFFFFFFF : (a | 0xFF000000);
                        }
                        ready.put(new Frame(cycleId * n + i, out));

                        if ("restoreToBackgroundColor".equals(disposal)) {
                            for (int y = Math.max(0, top); y < Math.min(h, top + fh); y++)
                                for (int x = Math.max(0, left); x < Math.min(w, left + fw); x++)
                                    canvas[y * w + x] = 0;
                        } else if (prev != null) {
                            System.arraycopy(prev, 0, canvas, 0, canvas.length);
                        }
                    }
                    cycleId++;
                }
            } finally {
                reader.dispose();
            }
        } catch (InterruptedException ignored) {
            // arrêt demandé
        } catch (Throwable t) {
            failed = true;
        }
    }

    private int frameAt(long phase) {
        long t = phase;
        for (int i = 0; i < n; i++) {
            if (t < delays[i]) return i;
            t -= delays[i];
        }
        return n - 1;
    }

    /** Thread principal : met la texture à jour si une nouvelle image est due. */
    void update(DynamicTexture tex, long now) {
        if (failed) return;
        long desired = (now / cycle) * n + frameAt(now % cycle);
        int[] show = null;
        Frame f;
        while ((f = ready.peek()) != null && f.abs() <= desired) {
            ready.poll();
            if (show != null) free.offer(show);
            show = f.px();
        }
        if (show != null) {
            NativeImage img = tex.getPixels();
            if (img != null) {
                for (int y = 0; y < h; y++) {
                    int row = y * w;
                    for (int x = 0; x < w; x++) img.setPixel(x, y, show[row + x]);
                }
                tex.upload();
            }
            free.offer(show);
        }
    }
}
