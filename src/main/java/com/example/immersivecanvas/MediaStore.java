package com.example.immersivecanvas;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;

/**
 * Stockage des images/GIF HD dans le dossier du monde (adressage par contenu : nom = empreinte SHA-256).
 * Le serveur ne décode jamais les pixels : il lit seulement l'en-tête pour vérifier le type et les dimensions.
 */
public final class MediaStore {
    public static final int MAX_BYTES = 12 * 1024 * 1024;

    static {
        ImageIO.setUseCache(false);
    }

    private MediaStore() {}

    /** 1 = image (png/jpg), 2 = gif, 0 = inconnu. */
    public static int sniffKind(byte[] b) {
        if (b == null || b.length < 12) return 0;
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') return CanvasData.MODE_GIF;
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return CanvasData.MODE_IMAGE;
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return CanvasData.MODE_IMAGE;
        return 0;
    }

    public static String hashOf(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            return HexFormat.of().formatHex(d, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Dimensions lues dans l'en-tête (écran logique pour un GIF) ou null. */
    public static int[] dims(byte[] data) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            if (iis == null) return null;
            Iterator<ImageReader> it = ImageIO.getImageReaders(iis);
            if (!it.hasNext()) return null;
            ImageReader r = it.next();
            try {
                r.setInput(iis, true, true);
                if ("gif".equalsIgnoreCase(r.getFormatName())) {
                    int[] s = gifLogicalSize(r);
                    if (s != null) return s;
                }
                return new int[]{r.getWidth(0), r.getHeight(0)};
            } finally {
                r.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public static int[] gifLogicalSize(ImageReader reader) {
        try {
            IIOMetadata sm = reader.getStreamMetadata();
            if (sm != null) {
                Node root = sm.getAsTree("javax_imageio_gif_stream_1.0");
                Node lsd = child(root, "LogicalScreenDescriptor");
                if (lsd != null) {
                    int w = intAttr(lsd, "logicalScreenWidth"), h = intAttr(lsd, "logicalScreenHeight");
                    if (w > 0 && h > 0) return new int[]{w, h};
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return null;
    }

    public static Node child(Node parent, String name) {
        if (parent == null) return null;
        for (Node c = parent.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (name.equals(c.getNodeName())) return c;
        }
        return null;
    }

    public static int intAttr(Node node, String name) {
        try {
            return Integer.parseInt(((IIOMetadataNode) node).getAttribute(name));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ fichiers du monde

    private static Path dir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("immersivecanvas_media");
    }

    public static boolean exists(MinecraftServer server, String hash) {
        return CanvasData.validHash(hash) && Files.isRegularFile(dir(server).resolve(hash + ".bin"));
    }

    public static byte[] read(MinecraftServer server, String hash) {
        if (!CanvasData.validHash(hash)) return null;
        try {
            Path f = dir(server).resolve(hash + ".bin");
            if (!Files.isRegularFile(f) || Files.size(f) > MAX_BYTES) return null;
            return Files.readAllBytes(f);
        } catch (IOException e) {
            return null;
        }
    }

    /** Valide puis enregistre. Retourne l'empreinte, ou null si le fichier est refusé. */
    public static String put(MinecraftServer server, byte[] data) {
        if (data == null || data.length < 16 || data.length > MAX_BYTES) return null;
        int kind = sniffKind(data);
        if (kind == 0) return null;
        int[] d = dims(data);
        if (d == null || !CanvasData.mediaDimsOk(kind, d[0], d[1])) return null;
        String hash = hashOf(data);
        try {
            Path dir = dir(server);
            Files.createDirectories(dir);
            Path f = dir.resolve(hash + ".bin");
            if (!Files.exists(f)) Files.write(f, data);
            return hash;
        } catch (IOException e) {
            return null;
        }
    }
}
