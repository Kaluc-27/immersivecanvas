package com.example.immersivecanvas.client;

import com.example.immersivecanvas.CanvasBlockEntity;
import com.example.immersivecanvas.CanvasData;
import com.example.immersivecanvas.ImmersiveCanvas;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Une texture dynamique par tableau visible. L'image est agrandie par blocs entiers (jusqu'à ~256 px)
 * pour rester nette. Les GIF sont animés avec l'horloge réelle : tous les joueurs voient la même image.
 */
final class CanvasTextures {
    private static final int TARGET = 256;

    private static final class Entry {
        DynamicTexture tex;
        Identifier id;
        int w, h, scale;
        int revision = -1;
        byte[][] frames;
        int[] delays;
        long cycle;
        int shown = -1;
    }

    private static final Map<CanvasBlockEntity, Entry> MAP = new IdentityHashMap<>();
    private static int counter = 0;

    private CanvasTextures() {}

    static Identifier get(CanvasBlockEntity be) {
        CanvasData d = be.getData();
        if (!d.isValid()) {
            drop(be);
            return null;
        }
        Entry e = MAP.get(be);
        if (e == null || e.revision != be.getRevision()) {
            byte[][] frames = d.unpackFrames();
            if (frames == null) {
                drop(be);
                return null;
            }
            int w = d.width(), h = d.height();
            if (e == null || e.w != w || e.h != h) {
                if (e != null) release(e);
                e = new Entry();
                e.w = w;
                e.h = h;
                e.scale = Math.max(1, TARGET / Math.max(w, h));
                int n = counter++;
                e.tex = new DynamicTexture(() -> "immersivecanvas/" + n, new NativeImage(w * e.scale, h * e.scale, false));
                e.id = Identifier.fromNamespaceAndPath(ImmersiveCanvas.MODID, "canvas/" + n);
                Minecraft.getInstance().getTextureManager().register(e.id, e.tex);
                MAP.put(be, e);
            }
            e.frames = frames;
            e.delays = d.delays();
            long c = 0;
            for (int x : e.delays) c += x;
            e.cycle = Math.max(1, c);
            e.shown = -1;
            e.revision = be.getRevision();
        }
        int idx = frameAt(e, System.currentTimeMillis());
        if (idx != e.shown) {
            paint(e, idx);
            e.shown = idx;
        }
        return e.id;
    }

    private static int frameAt(Entry e, long now) {
        if (e.frames.length == 1) return 0;
        long t = now % e.cycle;
        for (int i = 0; i < e.delays.length; i++) {
            if (t < e.delays[i]) return i;
            t -= e.delays[i];
        }
        return e.delays.length - 1;
    }

    private static void paint(Entry e, int frame) {
        NativeImage img = e.tex.getPixels();
        if (img == null) return;
        byte[] rgb = e.frames[frame];
        int s = e.scale;
        int W = e.w * s, H = e.h * s;
        for (int y = 0; y < H; y++) {
            int row = (y / s) * e.w;
            for (int x = 0; x < W; x++) {
                int i = (row + x / s) * 3;
                img.setPixel(x, y, 0xFF000000 | ((rgb[i] & 0xFF) << 16) | ((rgb[i + 1] & 0xFF) << 8) | (rgb[i + 2] & 0xFF));
            }
        }
        e.tex.upload();
    }

    private static void drop(CanvasBlockEntity be) {
        Entry old = MAP.remove(be);
        if (old != null) release(old);
    }

    private static void release(Entry e) {
        Minecraft.getInstance().getTextureManager().release(e.id);
    }

    static void cleanup() {
        MAP.entrySet().removeIf(en -> {
            if (en.getKey().isRemoved()) {
                release(en.getValue());
                return true;
            }
            return false;
        });
    }

    static void clear() {
        for (Entry e : MAP.values()) release(e);
        MAP.clear();
    }
}
