package com.example.immersivecanvas.client;

import com.example.immersivecanvas.CanvasData;
import com.example.immersivecanvas.MediaStore;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Mémoire locale des tableaux déjà enregistrés par ce joueur (dossier immersivecanvas/library).
 * Un index texte + un fichier .cvd (données) + une miniature .png par entrée. 60 entrées maximum.
 */
final class MediaLibrary {
    record Entry(String id, long time, int mode, String media, String key) {}

    private static final int MAX_ENTRIES = 60;
    private static int counter = 0;

    private MediaLibrary() {}

    private static Path dir() { return MediaClient.root().resolve("library"); }
    private static Path index() { return dir().resolve("index.txt"); }
    static Path thumbPath(String id) { return dir().resolve(id + ".png"); }
    private static Path dataPath(String id) { return dir().resolve(id + ".cvd"); }

    /** Plus récent d'abord. */
    static List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        try {
            if (!Files.isRegularFile(index())) return out;
            for (String line : Files.readAllLines(index(), StandardCharsets.UTF_8)) {
                String[] p = line.split("\t");
                if (p.length < 5) continue;
                try {
                    out.add(new Entry(p[0], Long.parseLong(p[1]), Integer.parseInt(p[2]), "-".equals(p[3]) ? "" : p[3], p[4]));
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        out.sort((a, b) -> Long.compare(b.time(), a.time()));
        return out;
    }

    private static void write(List<Entry> entries) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            sb.append(e.id()).append('\t').append(e.time()).append('\t').append(e.mode()).append('\t')
                    .append(e.media().isEmpty() ? "-" : e.media()).append('\t').append(e.key()).append('\n');
        }
        Files.createDirectories(dir());
        Files.writeString(index(), sb.toString(), StandardCharsets.UTF_8);
    }

    private static String keyOf(CanvasData d) {
        return d.isPixel() ? MediaStore.hashOf(d.packed()) + ":" + d.bw() + "x" + d.bh() + ":" + d.ppb() : d.media();
    }

    /** Ajoute (ou rafraîchit) une entrée. */
    static void add(CanvasData data, BufferedImage thumb) throws IOException {
        List<Entry> entries = list();
        String key = keyOf(data);
        String id = null;
        for (Entry e : entries) if (e.key().equals(key)) id = e.id();
        if (id == null) id = Long.toString(System.currentTimeMillis(), 36) + (counter++);
        entries.removeIf(e -> e.key().equals(key));

        Files.createDirectories(dir());
        ByteBuf buf = Unpooled.buffer();
        CanvasData.STREAM_CODEC.encode(buf, data);
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        buf.release();
        Files.write(dataPath(id), bytes);
        if (thumb != null) ImageIO.write(thumb, "png", thumbPath(id).toFile());

        entries.add(0, new Entry(id, System.currentTimeMillis(), data.mode(), data.media(), key));
        while (entries.size() > MAX_ENTRIES) {
            Entry old = entries.remove(entries.size() - 1);
            Files.deleteIfExists(dataPath(old.id()));
            Files.deleteIfExists(thumbPath(old.id()));
        }
        write(entries);
    }

    static CanvasData load(String id) {
        try {
            byte[] bytes = Files.readAllBytes(dataPath(id));
            ByteBuf buf = Unpooled.wrappedBuffer(bytes);
            return CanvasData.STREAM_CODEC.decode(buf);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static BufferedImage readThumb(String id) {
        try {
            Path p = thumbPath(id);
            return Files.isRegularFile(p) ? ImageIO.read(p.toFile()) : null;
        } catch (IOException e) {
            return null;
        }
    }

    static void delete(String id) {
        try {
            List<Entry> entries = list();
            entries.removeIf(e -> e.id().equals(id));
            Files.deleteIfExists(dataPath(id));
            Files.deleteIfExists(thumbPath(id));
            write(entries);
        } catch (IOException ignored) {
        }
    }

    /** Empreintes des fichiers à ne jamais supprimer du cache. */
    static Set<String> pinned() {
        Set<String> s = new HashSet<>();
        for (Entry e : list()) if (!e.media().isEmpty()) s.add(e.media());
        return s;
    }
}
