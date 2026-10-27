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
 * Une texture dynamique (256x256) par canevas visible. Le dessin (16/32/64 px) est agrandi par
 * blocs entiers : pas besoin de configurer le filtrage "nearest", les pixels restent nets.
 */
final class CanvasTextures {
    private static final int TEX = 256;

    private static final class Entry {
        DynamicTexture tex;
        Identifier id;
        int res;
        int revision = -1;
    }

    private static final Map<CanvasBlockEntity, Entry> MAP = new IdentityHashMap<>();
    private static int counter = 0;

    private CanvasTextures() {}

    static Identifier get(CanvasBlockEntity be) {
        CanvasData d = be.getData();
        if (!d.isValid()) {
            Entry old = MAP.remove(be);
            if (old != null) release(old);
            return null;
        }
        Entry e = MAP.get(be);
        if (e != null && e.res != d.res()) {
            release(e);
            MAP.remove(be);
            e = null;
        }
        if (e == null) {
            e = new Entry();
            int n = counter++;
            e.tex = new DynamicTexture(() -> "immersivecanvas/" + n, new NativeImage(TEX, TEX, false));
            e.id = Identifier.fromNamespaceAndPath(ImmersiveCanvas.MODID, "canvas/" + n);
            e.res = d.res();
            Minecraft.getInstance().getTextureManager().register(e.id, e.tex);
            MAP.put(be, e);
        }
        if (e.revision != be.getRevision()) {
            paint(e, d);
            e.revision = be.getRevision();
        }
        return e.id;
    }

    private static void paint(Entry e, CanvasData d) {
        NativeImage img = e.tex.getPixels();
        if (img == null) return;
        int res = d.res();
        int scale = TEX / res;
        byte[] rgb = d.rgb();
        for (int y = 0; y < TEX; y++) {
            int row = (y / scale) * res;
            for (int x = 0; x < TEX; x++) {
                int i = (row + x / scale) * 3;
                int argb = 0xFF000000 | ((rgb[i] & 0xFF) << 16) | ((rgb[i + 1] & 0xFF) << 8) | (rgb[i + 2] & 0xFF);
                img.setPixel(x, y, argb);
            }
        }
        e.tex.upload();
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
