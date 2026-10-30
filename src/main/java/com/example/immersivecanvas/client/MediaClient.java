package com.example.immersivecanvas.client;

import com.example.immersivecanvas.CanvasData;
import com.example.immersivecanvas.ImmersiveCanvas;
import com.example.immersivecanvas.MediaRequestPayload;
import com.example.immersivecanvas.MediaStore;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Côté client : récupère les images/GIF HD (cache disque, sinon demande au serveur), les décode
 * en arrière-plan et fournit une texture prête à dessiner.
 */
public final class MediaClient {
    private enum State { NEW, WAITING, LOADING, READY, FAILED }

    private static final class Entry {
        final String hash;
        State state = State.NEW;
        long lastUsed, requestedAt;
        int tries;
        ByteArrayOutputStream asm;
        int nextChunk;
        volatile NativeImage still;
        volatile GifPlayer gif;
        volatile boolean decodeFailed;
        volatile int w, h;
        DynamicTexture tex;
        Identifier id;

        Entry(String hash) { this.hash = hash; }
    }

    private static final Map<String, Entry> MAP = new HashMap<>();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "immersivecanvas-media");
        t.setDaemon(true);
        return t;
    });
    private static final long CACHE_LIMIT = 400L * 1024 * 1024;
    private static final long CACHE_TARGET = 300L * 1024 * 1024;

    private MediaClient() {}

    // ------------------------------------------------------------------ fichiers

    static Path root() {
        return FMLPaths.GAMEDIR.get().resolve("immersivecanvas");
    }

    static Path cacheFile(String hash) {
        return root().resolve("cache").resolve(hash + ".bin");
    }

    /** Enregistre un fichier dans le cache local (déjà vérifié par l'appelant). */
    static void store(String hash, byte[] bytes) {
        try {
            Path f = cacheFile(hash);
            Files.createDirectories(f.getParent());
            if (!Files.exists(f)) Files.write(f, bytes);
            else Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
            prune();
        } catch (IOException ignored) {
        }
    }

    static byte[] readCached(String hash) {
        try {
            Path f = cacheFile(hash);
            return Files.isRegularFile(f) ? Files.readAllBytes(f) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static void prune() {
        try {
            Path dir = root().resolve("cache");
            if (!Files.isDirectory(dir)) return;
            List<Path> files;
            try (Stream<Path> s = Files.list(dir)) {
                files = new ArrayList<>(s.filter(Files::isRegularFile).toList());
            }
            long total = 0;
            for (Path p : files) total += Files.size(p);
            if (total <= CACHE_LIMIT) return;
            Set<String> pinned = MediaLibrary.pinned();
            files.sort(Comparator.comparingLong(p -> {
                try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return 0L; }
            }));
            for (Path p : files) {
                if (total <= CACHE_TARGET) break;
                String name = p.getFileName().toString();
                String hash = name.endsWith(".bin") ? name.substring(0, name.length() - 4) : name;
                if (pinned.contains(hash)) continue;
                long size = Files.size(p);
                Files.deleteIfExists(p);
                total -= size;
            }
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------ texture

    /** Appelé à chaque image par le rendu en jeu et l'éditeur. Retourne null tant que ce n'est pas prêt. */
    public static Identifier texture(String hash, int kind) {
        if (!CanvasData.validHash(hash)) return null;
        long now = System.currentTimeMillis();
        Entry e = MAP.computeIfAbsent(hash, Entry::new);
        e.lastUsed = now;
        switch (e.state) {
            case NEW -> {
                if (Files.isRegularFile(cacheFile(hash))) {
                    e.state = State.LOADING;
                    POOL.execute(() -> decode(e, readCached(hash)));
                } else {
                    request(e, now);
                }
                return null;
            }
            case WAITING -> {
                if (now - e.requestedAt > 15_000) {
                    if (++e.tries >= 3) e.state = State.FAILED;
                    else request(e, now);
                }
                return null;
            }
            case LOADING -> {
                if (e.decodeFailed) {
                    e.state = State.FAILED;
                    return null;
                }
                if (e.still != null) {
                    e.tex = new DynamicTexture(() -> "immersivecanvas/media", e.still);
                    e.still = null;
                    registerTexture(e);
                } else if (e.gif != null) {
                    e.tex = new DynamicTexture(() -> "immersivecanvas/gif", new NativeImage(e.w, e.h, false));
                    registerTexture(e);
                }
                return e.state == State.READY ? e.id : null;
            }
            case READY -> {
                if (e.gif != null) e.gif.update(e.tex, now);
                return e.id;
            }
            default -> {
                return null;
            }
        }
    }

    public static boolean isFailed(String hash) {
        Entry e = MAP.get(hash);
        return e != null && e.state == State.FAILED;
    }

    private static void registerTexture(Entry e) {
        e.id = Identifier.fromNamespaceAndPath(ImmersiveCanvas.MODID, "media/" + e.hash);
        Minecraft.getInstance().getTextureManager().register(e.id, e.tex);
        e.state = State.READY;
    }

    private static void request(Entry e, long now) {
        e.state = State.WAITING;
        e.requestedAt = now;
        e.asm = null;
        e.nextChunk = 0;
        ClientPacketDistributor.sendToServer(new MediaRequestPayload(e.hash));
    }

    /** Réponse du serveur (thread principal). */
    public static void onChunk(String hash, int index, int total, byte[] data) {
        Entry e = MAP.get(hash);
        if (e == null || e.state != State.WAITING) return;
        if (total <= 0) {
            e.state = State.FAILED;
            return;
        }
        if (index == 0) {
            e.asm = new ByteArrayOutputStream();
            e.nextChunk = 0;
        }
        if (e.asm == null || index != e.nextChunk) {
            e.state = State.NEW;
            return;
        }
        e.asm.write(data, 0, data.length);
        e.nextChunk++;
        if (e.nextChunk == total) {
            byte[] bytes = e.asm.toByteArray();
            e.asm = null;
            if (!MediaStore.hashOf(bytes).equals(hash)) {
                e.state = State.FAILED;
                return;
            }
            store(hash, bytes);
            e.state = State.LOADING;
            POOL.execute(() -> decode(e, bytes));
        }
    }

    // ------------------------------------------------------------------ décodage (thread d'arrière-plan)

    private static void decode(Entry e, byte[] bytes) {
        try {
            if (bytes == null) throw new IOException("introuvable");
            int kind = MediaStore.sniffKind(bytes);
            int[] d = MediaStore.dims(bytes);
            if (kind == 0 || d == null || !CanvasData.mediaDimsOk(kind, d[0], d[1])) throw new IOException("refusé");
            if (kind == CanvasData.MODE_GIF) {
                GifPlayer p = GifPlayer.open(bytes);
                e.w = p.w;
                e.h = p.h;
                p.start();
                e.gif = p;
            } else {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
                if (img == null) throw new IOException("illisible");
                int w = img.getWidth(), h = img.getHeight();
                NativeImage ni = new NativeImage(w, h, false);
                int[] row = new int[w];
                for (int y = 0; y < h; y++) {
                    img.getRGB(0, y, w, 1, row, 0, w);
                    for (int x = 0; x < w; x++) ni.setPixel(x, y, row[x] | 0xFF000000);
                }
                e.w = w;
                e.h = h;
                e.still = ni;
            }
        } catch (Throwable t) {
            e.decodeFailed = true;
        }
    }

    // ------------------------------------------------------------------ nettoyage

    private static void dispose(Entry e) {
        if (e.gif != null) e.gif.close();
        if (e.still != null) e.still.close();
        if (e.id != null) Minecraft.getInstance().getTextureManager().release(e.id);
    }

    /** Libère les médias non affichés depuis 30 s. */
    static void cleanup() {
        long now = System.currentTimeMillis();
        MAP.entrySet().removeIf(en -> {
            Entry e = en.getValue();
            if (now - e.lastUsed > 30_000) {
                dispose(e);
                return true;
            }
            return false;
        });
    }

    static void clear() {
        for (Entry e : MAP.values()) dispose(e);
        MAP.clear();
    }
}
