package com.example.immersivecanvas.client;

import com.example.immersivecanvas.CanvasData;
import com.example.immersivecanvas.ImmersiveCanvas;
import com.example.immersivecanvas.MediaStore;
import com.example.immersivecanvas.MediaUploadPayload;
import com.example.immersivecanvas.SaveCanvasPayload;
import com.mojang.blaze3d.platform.NativeImage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleConsumer;
import javax.imageio.ImageIO;

/**
 * Éditeur : deux modes (pixel-art / image HD ou GIF), un panneau à onglets à droite
 * (Dessin, Anim., Taille, Import, Galerie) et l'aperçu à gauche — rien ne se chevauche.
 */
public class CanvasEditorScreen extends Screen {
    private record Snap(int bw, int bh, int ppb, int style, int w, int h, List<int[]> frames, List<Integer> delays, int cur) {}
    private record Thumb(DynamicTexture tex, Identifier id, int w, int h) {}

    private enum Tool { PENCIL, ERASER, FILL, PICKER }
    private enum Page { DRAW, ANIM, SIZE, IMPORT, GALLERY }

    private static final int PANEL_W = 224;
    private static final int SW = 13;
    private static final int Y0 = 50;
    private static final int GCOLS = 4, GROWS = 3, GCW = 56, GCH = 46;
    private static final int WHITE = 0xFFFFFF;
    private static final int[] PALETTE = {
            0x000000, 0x1D2B53, 0x7E2553, 0x008751, 0xAB5236, 0x5F574F, 0xC2C3C7, 0xFFF1E8,
            0xFF004D, 0xFFA300, 0xFFEC27, 0x00E436, 0x29ADFF, 0x83769C, 0xFF77A8, 0xFFCCAA,
            0xFFFFFF, 0x9B2226, 0xCA6702, 0xE9D8A6, 0x94D2BD, 0x0A9396, 0x005F73, 0x001219,
            0x3A0CA3, 0x7209B7, 0xF72585, 0x4CC9F0, 0x6A994E, 0x386641, 0x8D6E63, 0x4E342E};
    private static int texCounter = 0;

    private final BlockPos pos;

    // ---- commun
    private int mode;               // 0 = pixel-art, 1 = image HD / GIF
    private Page page;
    private int bw, bh, style, fit;  // fit : 0 recadrer, 1 ajuster, 2 étirer
    private Component status = Component.empty();
    private BufferedImage thumb;

    // ---- pixel-art
    private int ppb, W, H;
    private final List<int[]> frames = new ArrayList<>();
    private final List<Integer> delays = new ArrayList<>();
    private int cur;
    private final Deque<Snap> undo = new ArrayDeque<>();
    private final Deque<Snap> redo = new ArrayDeque<>();
    private Tool tool = Tool.PENCIL;
    private int color = 0x000000;
    private float hue, sat, val;
    private boolean painting;
    private int lastX, lastY;
    private boolean playing;
    private long playStart;
    private String pathText = "";
    private boolean updatingHex, updatingDelay;
    private DynamicTexture tex;
    private Identifier texId;
    private int texW, texH;
    private boolean texDirty = true, texUpload = false;

    // ---- image HD / GIF
    private String mediaHash = "", originalHash = "";
    private int mediaKind, mw, mh;
    private byte[] mediaBytes;

    // ---- mise en page
    private int panelX, gx, gy, texScale, dispW, dispH, paletteY, hdW, hdH;
    private double zoom;
    private final Button[] toolButtons = new Button[Tool.values().length];
    private Button playButton;
    private HsvSlider hSlider, sSlider, vSlider;
    private EditBox hexBox, pathBox, delayBox, wBox, hBox;

    // ---- galerie
    private List<MediaLibrary.Entry> libEntries = new ArrayList<>();
    private int galleryPage;
    private String selectedId;
    private final Map<String, Thumb> thumbs = new HashMap<>();

    public CanvasEditorScreen(BlockPos pos, CanvasData data) {
        super(Component.translatable("gui.immersivecanvas.title"));
        this.pos = pos;
        byte[][] raw = (data.isValid() && data.isPixel()) ? data.unpackFrames() : null;
        if (raw != null) {
            mode = 0;
            bw = data.bw();
            bh = data.bh();
            ppb = data.ppb();
            style = data.style();
            W = data.width();
            H = data.height();
            for (int i = 0; i < raw.length; i++) {
                frames.add(CanvasData.toInts(raw[i]));
                delays.add(data.delays()[i]);
            }
            page = Page.DRAW;
        } else if (data.isValid() && data.isMedia()) {
            mode = 1;
            bw = data.bw();
            bh = data.bh();
            style = data.style();
            fit = data.fit();
            mediaHash = data.media();
            originalHash = data.media();
            mediaKind = data.mode();
            mw = data.mw();
            mh = data.mh();
            initBlankPixels();
            page = Page.SIZE;
        } else {
            mode = 0;
            bw = 1;
            bh = 1;
            initBlankPixels();
            page = Page.DRAW;
        }
    }

    private void initBlankPixels() {
        ppb = 32;
        while (ppb > 4 && (long) bw * ppb * bh * ppb * 3 > CanvasData.MAX_RAW) ppb = prevPpb(ppb);
        W = bw * ppb;
        H = bh * ppb;
        frames.clear();
        delays.clear();
        int[] blank = new int[W * H];
        Arrays.fill(blank, WHITE);
        frames.add(blank);
        delays.add(100);
        cur = 0;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private int[] px() { return frames.get(cur); }

    private int maxFramesAllowed() {
        return Math.max(1, Math.min(CanvasData.MAX_FRAMES, CanvasData.MAX_RAW / (W * H * 3)));
    }

    private static int prevPpb(int p) {
        int[] l = CanvasData.PPBS;
        for (int i = l.length - 1; i > 0; i--) if (l[i] == p) return l[i - 1];
        return l[0];
    }

    private int nextPpb() {
        int[] list = CanvasData.PPBS;
        int idx = 0;
        for (int i = 0; i < list.length; i++) if (list[i] == ppb) idx = i;
        for (int k = 1; k <= list.length; k++) {
            int cand = list[(idx + k) % list.length];
            if ((long) bw * cand * bh * cand * 3 <= CanvasData.MAX_RAW) return cand;
        }
        return ppb;
    }

    private List<Page> pages() {
        return mode == 0
                ? List.of(Page.DRAW, Page.ANIM, Page.SIZE, Page.IMPORT, Page.GALLERY)
                : List.of(Page.SIZE, Page.IMPORT, Page.GALLERY);
    }

    // =================================================================== construction de l'écran

    @Override
    protected void init() {
        panelX = width - PANEL_W - 8;
        gx = 10;
        gy = 18;
        int availW = Math.max(64, panelX - gx - 12), availH = Math.max(64, height - gy - 22);
        if (mode == 0) {
            layoutPixel(availW, availH);
            ensureTexture();
        } else {
            layoutHd(availW, availH);
        }

        List<Page> ps = pages();
        if (!ps.contains(page)) page = ps.get(0);

        addRenderableWidget(Button.builder(modeLabel(), b -> switchMode()).bounds(panelX, 4, PANEL_W, 16).build());
        int tw = PANEL_W / ps.size();
        for (int i = 0; i < ps.size(); i++) {
            final Page pg = ps.get(i);
            Component label = Component.translatable("gui.immersivecanvas.page." + pg.name().toLowerCase());
            if (pg == page) label = label.copy().withStyle(ChatFormatting.YELLOW);
            addRenderableWidget(Button.builder(label, b -> {
                page = pg;
                rebuildWidgets();
            }).bounds(panelX + i * tw, 24, tw - 2, 18).build());
        }

        switch (page) {
            case DRAW -> buildDraw();
            case ANIM -> buildAnim();
            case SIZE -> buildSize();
            case IMPORT -> buildImport();
            case GALLERY -> buildGallery();
        }

        addRenderableWidget(Button.builder(Component.translatable("gui.immersivecanvas.save"), b -> save())
                .bounds(panelX, height - 24, 110, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.immersivecanvas.cancel"), b -> onClose())
                .bounds(panelX + 114, height - 24, 110, 20).build());
    }

    private Component modeLabel() {
        return Component.translatable("gui.immersivecanvas.mode",
                Component.translatable(mode == 0 ? "gui.immersivecanvas.mode.pixel" : "gui.immersivecanvas.mode.hd"));
    }

    private void layoutPixel(int availW, int availH) {
        double fitZoom = Math.min(availW / (double) W, availH / (double) H);
        if (fitZoom >= 1) {
            texScale = Math.min(32, (int) fitZoom);
            zoom = texScale;
        } else {
            texScale = 1;
            zoom = fitZoom;
        }
        dispW = Math.max(1, (int) Math.round(W * zoom));
        dispH = Math.max(1, (int) Math.round(H * zoom));
    }

    private void layoutHd(int availW, int availH) {
        double aspect = bw / (double) bh;
        hdW = availW;
        hdH = (int) (availW / aspect);
        if (hdH > availH) {
            hdH = availH;
            hdW = (int) (availH * aspect);
        }
        hdW = Math.max(8, hdW);
        hdH = Math.max(8, hdH);
    }

    private void switchMode() {
        playing = false;
        if (mode == 0) {
            mode = 1;
            page = Page.SIZE;
        } else {
            adaptPixelsToBlocks();
            mode = 0;
            page = Page.DRAW;
        }
        texDirty = true;
        rebuildWidgets();
    }

    // ---- page DESSIN
    private void buildDraw() {
        Tool[] tools = Tool.values();
        for (int i = 0; i < tools.length; i++) {
            final Tool t = tools[i];
            toolButtons[i] = addRenderableWidget(Button.builder(toolLabel(t), b -> {
                tool = t;
                refreshTools();
            }).bounds(panelX + i * 56, Y0, 54, 18).build());
        }
        refreshTools();
        addRenderableWidget(Button.builder(Component.translatable("gui.immersivecanvas.undo"), b -> undo())
                .bounds(panelX, Y0 + 22, 72, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.immersivecanvas.redo"), b -> redo())
                .bounds(panelX + 76, Y0 + 22, 72, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.immersivecanvas.clear"), b -> clearFrame())
                .bounds(panelX + 152, Y0 + 22, 72, 18).build());

        hSlider = addRenderableWidget(new HsvSlider(panelX, Y0 + 46, PANEL_W, 16, "H", hue, v -> { hue = (float) v; fromHsv(); }));
        sSlider = addRenderableWidget(new HsvSlider(panelX, Y0 + 64, PANEL_W, 16, "S", sat, v -> { sat = (float) v; fromHsv(); }));
        vSlider = addRenderableWidget(new HsvSlider(panelX, Y0 + 82, PANEL_W, 16, "V", val, v -> { val = (float) v; fromHsv(); }));

        hexBox = addRenderableWidget(new EditBox(font, panelX, Y0 + 104, 70, 18, Component.literal("hex")));
        hexBox.setMaxLength(6);
        hexBox.setFilter(s -> s.matches("[0-9a-fA-F]*"));
        hexBox.setValue(String.format("%06X", color));
        hexBox.setResponder(s -> {
            if (!updatingHex && s.length() == 6) setColor(Integer.parseInt(s, 16), true);
        });
        paletteY = Y0 + 128;
    }

    // ---- page ANIMATION
    private void buildAnim() {
        String[] labels = {"<", ">", "+", "-"};
        for (int i = 0; i < 4; i++) {
            final int k = i;
            addRenderableWidget(Button.builder(Component.literal(labels[i]), b -> {
                switch (k) {
                    case 0 -> gotoFrame(cur - 1);
                    case 1 -> gotoFrame(cur + 1);
                    case 2 -> addFrame();
                    default -> delFrame();
                }
            }).bounds(panelX + i * 56, Y0, 54, 18).build());
        }
        delayBox = addRenderableWidget(new EditBox(font, panelX, Y0 + 42, 56, 16, Component.literal("ms")));
        delayBox.setMaxLength(5);
        delayBox.setFilter(s -> s.matches("[0-9]*"));
        delayBox.setResponder(s -> {
            if (updatingDelay || s.isEmpty()) return;
            try {
                delays.set(cur, Mth.clamp(Integer.parseInt(s), CanvasData.MIN_DELAY, CanvasData.MAX_DELAY));
            } catch (NumberFormatException ignored) {
            }
        });
        syncDelayBox();
        addRenderableWidget(Button.builder(Component.translatable("gui.immersivecanvas.delay_all"), b -> {
            int d = delays.get(cur);
            for (int i = 0; i < delays.size(); i++) delays.set(i, d);
        }).bounds(panelX + 84, Y0 + 41, 60, 18).build());
        playButton = addRenderableWidget(Button.builder(playLabel(), b -> togglePlay())
                .bounds(panelX + 148, Y0 + 41, 76, 18).build());
    }

    // ---- page TAILLE
    private void buildSize() {
        wBox = addRenderableWidget(new EditBox(font, panelX, Y0, 38, 18, Component.literal("L")));
        wBox.setMaxLength(2);
        wBox.setFilter(t -> t.matches("[0-9]*"));
        wBox.setValue(String.valueOf(bw));
        hBox = addRenderableWidget(new EditBox(font, panelX + 50, Y0, 38, 18, Component.literal("H")));
        hBox.setMaxLength(2);
        hBox.setFilter(t -> t.matches("[0-9]*"));
        hBox.setValue(String.valueOf(bh));
        addRenderableWidget(Button.builder(Component.translatable("gui.immersivecanvas.apply"), b -> applySize())
                .bounds(panelX + 94, Y0, 50, 18).build());
        if (mode == 0) {
            addRenderableWidget(Button.builder(Component.translatable("gui.immersivecanvas.ppb", ppb), b -> resize(bw, bh, nextPpb()))
                    .bounds(panelX, Y0 + 24, 110, 18).build());
        } else {
            addRenderableWidget(Button.builder(Component.translatable("gui.immersivecanvas.auto"), b -> autoSize())
                    .bounds(panelX + 148, Y0, 76, 18).build());
            addRenderableWidget(Button.builder(fitLabel(), b -> {
                fit = (fit + 1) % 3;
                b.setMessage(fitLabel());
            }).bounds(panelX, Y0 + 24, 110, 18).build());
        }
        addRenderableWidget(Button.builder(styleLabel(), b -> {
            style = (style + 1) % CanvasData.STYLES;
            b.setMessage(styleLabel());
        }).bounds(panelX, Y0 + 48, PANEL_W, 18).build());
    }

    // ---- page IMPORT
    private void buildImport() {
        pathBox = addRenderableWidget(new EditBox(font, panelX, Y0, PANEL_W, 18, Component.translatable("gui.immersivecanvas.path")));
        pathBox.setMaxLength(512);
        pathBox.setHint(Component.translatable("gui.immersivecanvas.path"));
        pathBox.setValue(pathText);
        pathBox.setResponder(s -> pathText = s);
        addRenderableWidget(Button.builder(Component.translatable("gui.immersivecanvas.browse"), b -> browse())
                .bounds(panelX, Y0 + 22, 70, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.immersivecanvas.import"), b -> doImport(pathText))
                .bounds(panelX + 74, Y0 + 22, 70, 18).build());
        if (mode == 0) {
            addRenderableWidget(Button.builder(fitLabel(), b -> {
                fit = (fit + 1) % 3;
                b.setMessage(fitLabel());
            }).bounds(panelX + 148, Y0 + 22, 76, 18).build());
        }
    }

    // ---- page GALERIE
    private void buildGallery() {
        libEntries = MediaLibrary.list();
        int perPage = GCOLS * GROWS;
        int pages = Math.max(1, (libEntries.size() + perPage - 1) / perPage);
        galleryPage = Mth.clamp(galleryPage, 0, pages - 1);
        for (int i = galleryPage * perPage; i < Math.min(libEntries.size(), (galleryPage + 1) * perPage); i++) {
            ensureThumb(libEntries.get(i).id());
        }
        int by = Y0 + GROWS * GCH + 4;
        addRenderableWidget(Button.builder(Component.literal("<"), b -> { galleryPage--; rebuildWidgets(); })
                .bounds(panelX, by, 28, 18).build()).active = galleryPage > 0;
        addRenderableWidget(Button.builder(Component.literal(">"), b -> { galleryPage++; rebuildWidgets(); })
                .bounds(panelX + 30, by, 28, 18).build()).active = galleryPage < pages - 1;
        addRenderableWidget(Button.builder(Component.translatable("gui.immersivecanvas.use"), b -> {
            if (selectedId != null) applyLibrary(selectedId);
        }).bounds(panelX + 62, by, 78, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.immersivecanvas.delete"), b -> {
            if (selectedId != null) {
                MediaLibrary.delete(selectedId);
                selectedId = null;
                rebuildWidgets();
            }
        }).bounds(panelX + 144, by, 80, 18).build());
    }

    private Component toolLabel(Tool t) {
        Component c = Component.translatable("gui.immersivecanvas.tool." + t.name().toLowerCase());
        return t == tool ? c.copy().withStyle(ChatFormatting.YELLOW) : c;
    }

    private void refreshTools() {
        Tool[] tools = Tool.values();
        for (int i = 0; i < tools.length; i++) {
            if (toolButtons[i] != null) toolButtons[i].setMessage(toolLabel(tools[i]));
        }
    }

    private Component playLabel() {
        return Component.translatable(playing ? "gui.immersivecanvas.stop" : "gui.immersivecanvas.play");
    }

    private Component fitLabel() {
        return Component.translatable("gui.immersivecanvas.fit." + new String[]{"crop", "fit", "stretch"}[fit]);
    }

    private Component styleLabel() {
        return Component.translatable("gui.immersivecanvas.style", Component.translatable("gui.immersivecanvas.style." + style));
    }

    private static final class HsvSlider extends AbstractSliderButton {
        private final String label;
        private final DoubleConsumer onChange;

        HsvSlider(int x, int y, int w, int h, String label, double value, DoubleConsumer onChange) {
            super(x, y, w, h, Component.empty(), value);
            this.label = label;
            this.onChange = onChange;
            updateMessage();
        }

        void setVal(double v) {
            this.value = Mth.clamp(v, 0, 1);
            updateMessage();
        }

        @Override protected void updateMessage() {
            setMessage(Component.literal((label == null ? "" : label) + ": " + (int) Math.round(value * 100)));
        }

        @Override protected void applyValue() {
            if (onChange != null) onChange.accept(value);
        }
    }

    // =================================================================== texture de l'éditeur (pixel-art)

    private void ensureTexture() {
        int tw = W * texScale, th = H * texScale;
        if (tex != null && texW == tw && texH == th) return;
        releaseTexture();
        texW = tw;
        texH = th;
        int n = texCounter++;
        tex = new DynamicTexture(() -> "immersivecanvas/editor" + n, new NativeImage(tw, th, false));
        texId = Identifier.fromNamespaceAndPath(ImmersiveCanvas.MODID, "editor/" + n);
        Minecraft.getInstance().getTextureManager().register(texId, tex);
        texDirty = true;
    }

    private void releaseTexture() {
        if (tex != null && texId != null) Minecraft.getInstance().getTextureManager().release(texId);
        tex = null;
        texId = null;
    }

    private void repaintAll() {
        NativeImage img = tex.getPixels();
        if (img == null) return;
        int[] p = px();
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int argb = 0xFF000000 | p[y * W + x];
                for (int dy = 0; dy < texScale; dy++)
                    for (int dx = 0; dx < texScale; dx++)
                        img.setPixel(x * texScale + dx, y * texScale + dy, argb);
            }
        }
        tex.upload();
        texDirty = false;
        texUpload = false;
    }

    private void paintCell(int x, int y) {
        NativeImage img = tex.getPixels();
        if (img == null) return;
        int argb = 0xFF000000 | px()[y * W + x];
        for (int dy = 0; dy < texScale; dy++)
            for (int dx = 0; dx < texScale; dx++)
                img.setPixel(x * texScale + dx, y * texScale + dy, argb);
        texUpload = true;
    }

    @Override
    public void removed() {
        releaseTexture();
        releaseThumbs();
        super.removed();
    }

    // =================================================================== couleurs

    private void fromHsv() {
        color = Mth.hsvToRgb(hue, sat, val) & 0xFFFFFF;
        syncHex();
    }

    private void setColor(int rgb, boolean fromHex) {
        color = rgb & 0xFFFFFF;
        float[] hsv = rgbToHsv(color);
        hue = hsv[0];
        sat = hsv[1];
        val = hsv[2];
        if (hSlider != null) { hSlider.setVal(hue); sSlider.setVal(sat); vSlider.setVal(val); }
        if (!fromHex) syncHex();
    }

    private void syncHex() {
        if (hexBox == null) return;
        updatingHex = true;
        hexBox.setValue(String.format("%06X", color));
        updatingHex = false;
    }

    private void syncDelayBox() {
        if (delayBox == null) return;
        updatingDelay = true;
        delayBox.setValue(String.valueOf(delays.get(cur)));
        updatingDelay = false;
    }

    private static float[] rgbToHsv(int rgb) {
        float r = ((rgb >> 16) & 255) / 255f, g = ((rgb >> 8) & 255) / 255f, b = (rgb & 255) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        float h;
        if (d == 0) h = 0;
        else if (max == r) h = ((g - b) / d % 6f) / 6f;
        else if (max == g) h = ((b - r) / d + 2f) / 6f;
        else h = ((r - g) / d + 4f) / 6f;
        if (h < 0) h += 1f;
        return new float[]{h, max == 0 ? 0 : d / max, max};
    }

    // =================================================================== historique

    private Snap snapshot() {
        List<int[]> copy = new ArrayList<>(frames.size());
        for (int[] f : frames) copy.add(f.clone());
        return new Snap(bw, bh, ppb, style, W, H, copy, new ArrayList<>(delays), cur);
    }

    private void pushUndo() {
        undo.push(snapshot());
        int max = Math.max(4, Math.min(40, 24_000_000 / Math.max(1, frames.size() * W * H * 4)));
        while (undo.size() > max) undo.removeLast();
        redo.clear();
    }

    private void restore(Snap s) {
        bw = s.bw();
        bh = s.bh();
        ppb = s.ppb();
        style = s.style();
        W = s.w();
        H = s.h();
        frames.clear();
        for (int[] f : s.frames()) frames.add(f.clone());
        delays.clear();
        delays.addAll(s.delays());
        cur = Math.min(s.cur(), frames.size() - 1);
        texDirty = true;
        rebuildWidgets();
    }

    private void undo() {
        if (undo.isEmpty()) return;
        redo.push(snapshot());
        restore(undo.pop());
    }

    private void redo() {
        if (redo.isEmpty()) return;
        undo.push(snapshot());
        restore(redo.pop());
    }

    private void clearFrame() {
        pushUndo();
        Arrays.fill(px(), WHITE);
        texDirty = true;
    }

    // =================================================================== images (frames) et taille

    private void gotoFrame(int idx) {
        playing = false;
        if (playButton != null) playButton.setMessage(playLabel());
        cur = Math.floorMod(idx, frames.size());
        texDirty = true;
        syncDelayBox();
    }

    private void addFrame() {
        if (frames.size() >= maxFramesAllowed()) {
            status = Component.translatable("gui.immersivecanvas.limit");
            return;
        }
        pushUndo();
        frames.add(cur + 1, px().clone());
        delays.add(cur + 1, delays.get(cur));
        gotoFrame(cur + 1);
    }

    private void delFrame() {
        if (frames.size() <= 1) return;
        pushUndo();
        frames.remove(cur);
        delays.remove(cur);
        gotoFrame(Math.min(cur, frames.size() - 1));
    }

    private void togglePlay() {
        playing = !playing;
        playStart = System.currentTimeMillis();
        if (playButton != null) playButton.setMessage(playLabel());
        texDirty = true;
    }

    private void applySize() {
        try {
            int nbw = Mth.clamp(Integer.parseInt(wBox.getValue()), 1, CanvasData.MAX_BLOCKS);
            int nbh = Mth.clamp(Integer.parseInt(hBox.getValue()), 1, CanvasData.MAX_BLOCKS);
            if (mode == 0) {
                if (nbw != bw || nbh != bh) resize(nbw, nbh, ppb);
            } else {
                bw = nbw;
                bh = nbh;
                rebuildWidgets();
            }
        } catch (NumberFormatException ignored) {
            wBox.setValue(String.valueOf(bw));
            hBox.setValue(String.valueOf(bh));
        }
    }

    private void autoSize() {
        if (mw <= 0 || mh <= 0) return;
        bh = Mth.clamp((int) Math.round(bw * mh / (double) mw), 1, CanvasData.MAX_BLOCKS);
        rebuildWidgets();
    }

    /** Pixel-art : nouvelle taille en blocs et/ou nouvelle résolution (rééchantillonne toutes les images). */
    private void resize(int nbw, int nbh, int nppb) {
        nbw = Mth.clamp(nbw, 1, CanvasData.MAX_BLOCKS);
        nbh = Mth.clamp(nbh, 1, CanvasData.MAX_BLOCKS);
        boolean reduced = false;
        while (nppb > 4 && (long) nbw * nppb * nbh * nppb * 3 > CanvasData.MAX_RAW) {
            nppb = prevPpb(nppb);
            reduced = true;
        }
        pushUndo();
        int before = frames.size();
        bw = nbw;
        bh = nbh;
        ppb = nppb;
        resamplePixels(nbw * nppb, nbh * nppb);
        playing = false;
        if (reduced) status = Component.translatable("gui.immersivecanvas.ppb_reduced", ppb);
        else if (frames.size() < before) status = Component.translatable("gui.immersivecanvas.trimmed", frames.size());
        texDirty = true;
        rebuildWidgets();
    }

    private void resamplePixels(int nW, int nH) {
        int maxF = Math.max(1, Math.min(CanvasData.MAX_FRAMES, CanvasData.MAX_RAW / (nW * nH * 3)));
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < Math.min(maxF, frames.size()); i++) {
            int[] src = frames.get(i), dst = new int[nW * nH];
            for (int y = 0; y < nH; y++)
                for (int x = 0; x < nW; x++)
                    dst[y * nW + x] = src[(y * H / nH) * W + (x * W / nW)];
            out.add(dst);
        }
        while (delays.size() > out.size()) delays.remove(delays.size() - 1);
        frames.clear();
        frames.addAll(out);
        W = nW;
        H = nH;
        cur = Math.min(cur, frames.size() - 1);
    }

    /** Au retour en mode pixel-art : adapte l'image aux dimensions en blocs choisies dans le mode HD. */
    private void adaptPixelsToBlocks() {
        int p = ppb;
        while (p > 4 && (long) bw * p * bh * p * 3 > CanvasData.MAX_RAW) p = prevPpb(p);
        if (bw * p != W || bh * p != H) {
            pushUndo();
            resamplePixels(bw * p, bh * p);
        }
        ppb = p;
    }

    // =================================================================== dessin

    private int[] cellAt(double mx, double my) {
        if (mode != 0 || mx < gx || my < gy) return null;
        int cx = (int) Math.floor((mx - gx) / zoom), cy = (int) Math.floor((my - gy) / zoom);
        return (cx < W && cy < H && mx < gx + dispW && my < gy + dispH) ? new int[]{cx, cy} : null;
    }

    private void plot(int x, int y) {
        px()[y * W + x] = tool == Tool.ERASER ? WHITE : color;
        paintCell(x, y);
    }

    private void line(int x0, int y0, int x1, int y1) {
        int dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1, err = dx + dy;
        while (true) {
            plot(x0, y0);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }

    private void floodFill(int sx, int sy, int fill) {
        int[] p = px();
        int target = p[sy * W + sx];
        if (target == fill) return;
        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(sy * W + sx);
        while (!stack.isEmpty()) {
            int i = stack.pop();
            if (p[i] != target) continue;
            p[i] = fill;
            int x = i % W, y = i / W;
            if (x > 0) stack.push(i - 1);
            if (x < W - 1) stack.push(i + 1);
            if (y > 0) stack.push(i - W);
            if (y < H - 1) stack.push(i + W);
        }
        texDirty = true;
    }

    // =================================================================== souris

    private int paletteIndexAt(double mx, double my) {
        int col = (int) Math.floor((mx - panelX) / SW), row = (int) Math.floor((my - paletteY) / SW);
        if (mx < panelX || my < paletteY || col >= 16 || row >= 2) return -1;
        return row * 16 + col;
    }

    private int galleryIndexAt(double mx, double my) {
        if (mx < panelX || my < Y0) return -1;
        int col = (int) Math.floor((mx - panelX) / GCW), row = (int) Math.floor((my - Y0) / GCH);
        if (col >= GCOLS || row >= GROWS) return -1;
        return galleryPage * GCOLS * GROWS + row * GCOLS + col;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        double mx = event.x(), my = event.y();
        int button = event.button();

        if (page == Page.GALLERY) {
            int idx = galleryIndexAt(mx, my);
            if (idx >= 0 && idx < libEntries.size()) {
                selectedId = libEntries.get(idx).id();
                if (doubleClick) applyLibrary(selectedId);
                return true;
            }
            return false;
        }
        if (mode != 0) return false;

        if (page == Page.DRAW && button == 0) {
            int p = paletteIndexAt(mx, my);
            if (p >= 0) {
                setColor(PALETTE[p], false);
                return true;
            }
        }
        if (playing) return false;

        int[] c = cellAt(mx, my);
        if (c == null) return false;

        if (button == 1 || tool == Tool.PICKER) {
            setColor(px()[c[1] * W + c[0]], false);
            if (tool == Tool.PICKER) { tool = Tool.PENCIL; refreshTools(); }
            return true;
        }
        if (button == 0) {
            if (tool == Tool.FILL) {
                if (px()[c[1] * W + c[0]] != color) {
                    pushUndo();
                    floodFill(c[0], c[1], color);
                }
            } else {
                pushUndo();
                painting = true;
                lastX = c[0];
                lastY = c[1];
                plot(c[0], c[1]);
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (painting && event.button() == 0) {
            int[] c = cellAt(event.x(), event.y());
            if (c != null) {
                line(lastX, lastY, c[0], c[1]);
                lastX = c[0];
                lastY = c[1];
            }
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        painting = false;
        return super.mouseReleased(event);
    }

    // =================================================================== import

    private void browse() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(5);
            filters.put(stack.UTF8("*.gif")).put(stack.UTF8("*.png")).put(stack.UTF8("*.jpg"))
                    .put(stack.UTF8("*.jpeg")).put(stack.UTF8("*.bmp"));
            filters.flip();
            String result = TinyFileDialogs.tinyfd_openFileDialog("Import", null, filters, "Images / GIF", false);
            if (result != null) {
                pathText = result;
                if (pathBox != null) pathBox.setValue(result);
                doImport(result);
            }
        } catch (Throwable t) {
            status = Component.translatable("gui.immersivecanvas.import_error", String.valueOf(t.getMessage()));
        }
    }

    private void doImport(String path) {
        if (path == null || path.isBlank()) return;
        if (mode == 0) importPixel(path); else importHd(path);
    }

    private void importPixel(String path) {
        try {
            File f = new File(path.trim().replace("\"", ""));
            ImageImporter.Result r = ImageImporter.load(f, W, H, ImageImporter.Fit.values()[fit], maxFramesAllowed());
            pushUndo();
            frames.clear();
            frames.addAll(r.frames());
            delays.clear();
            delays.addAll(r.delays());
            cur = 0;
            playing = false;
            texDirty = true;
            status = Component.translatable("gui.immersivecanvas.imported", frames.size());
        } catch (Exception e) {
            status = Component.translatable("gui.immersivecanvas.import_error", String.valueOf(e.getMessage()));
        }
    }

    private void importHd(String path) {
        try {
            HdImport.Result r = HdImport.prepare(new File(path.trim().replace("\"", "")));
            mediaBytes = r.bytes();
            mediaKind = r.kind();
            mw = r.w();
            mh = r.h();
            mediaHash = MediaStore.hashOf(r.bytes());
            thumb = r.thumb();
            MediaClient.store(mediaHash, r.bytes());
            if (bw == 1 && bh == 1) {
                bw = 4;
                bh = Mth.clamp((int) Math.round(4 * mh / (double) mw), 1, CanvasData.MAX_BLOCKS);
            }
            status = Component.translatable("gui.immersivecanvas.hd_imported", mw, mh);
            rebuildWidgets();
        } catch (Exception e) {
            status = Component.translatable("gui.immersivecanvas.import_error", String.valueOf(e.getMessage()));
        }
    }

    // =================================================================== galerie

    private void ensureThumb(String id) {
        if (thumbs.containsKey(id)) return;
        try {
            BufferedImage bi = MediaLibrary.readThumb(id);
            if (bi == null) return;
            int w = bi.getWidth(), h = bi.getHeight();
            NativeImage ni = new NativeImage(w, h, false);
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                    ni.setPixel(x, y, 0xFF000000 | bi.getRGB(x, y));
            int n = texCounter++;
            DynamicTexture t = new DynamicTexture(() -> "immersivecanvas/thumb" + n, ni);
            Identifier tid = Identifier.fromNamespaceAndPath(ImmersiveCanvas.MODID, "thumb/" + n);
            Minecraft.getInstance().getTextureManager().register(tid, t);
            thumbs.put(id, new Thumb(t, tid, w, h));
        } catch (Exception ignored) {
        }
    }

    private void releaseThumbs() {
        for (Thumb t : thumbs.values()) Minecraft.getInstance().getTextureManager().release(t.id());
        thumbs.clear();
    }

    private void applyLibrary(String id) {
        CanvasData d = MediaLibrary.load(id);
        if (d == null || !d.isValid()) {
            status = Component.translatable("gui.immersivecanvas.import_error", "?");
            return;
        }
        BufferedImage t = MediaLibrary.readThumb(id);
        if (d.isPixel()) {
            byte[][] raw = d.unpackFrames();
            if (raw == null) {
                status = Component.translatable("gui.immersivecanvas.import_error", "?");
                return;
            }
            pushUndo();
            mode = 0;
            bw = d.bw();
            bh = d.bh();
            ppb = d.ppb();
            style = d.style();
            W = d.width();
            H = d.height();
            frames.clear();
            delays.clear();
            for (int i = 0; i < raw.length; i++) {
                frames.add(CanvasData.toInts(raw[i]));
                delays.add(d.delays()[i]);
            }
            cur = 0;
            page = Page.DRAW;
        } else {
            mode = 1;
            bw = d.bw();
            bh = d.bh();
            style = d.style();
            fit = d.fit();
            mediaHash = d.media();
            mediaKind = d.mode();
            mw = d.mw();
            mh = d.mh();
            mediaBytes = null;
            page = Page.SIZE;
            adaptPixelsKeepOnly();
        }
        playing = false;
        thumb = t;
        texDirty = true;
        rebuildWidgets();
    }

    /** En mode HD, les pixels du mode pixel-art restent tels quels (ils seront adaptés si on y revient). */
    private void adaptPixelsKeepOnly() {}

    // =================================================================== envoi

    private BufferedImage pixelThumb() {
        int[] argb = px().clone();
        for (int i = 0; i < argb.length; i++) argb[i] |= 0xFF000000;
        return HdImport.thumbOf(argb, W, H);
    }

    private boolean sendCanvas(CanvasData data) {
        ByteBuf buf = Unpooled.buffer();
        CanvasData.STREAM_CODEC.encode(buf, data);
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        buf.release();
        int total = (bytes.length + SaveCanvasPayload.CHUNK - 1) / SaveCanvasPayload.CHUNK;
        if (total > SaveCanvasPayload.MAX_CHUNKS) {
            status = Component.translatable("gui.immersivecanvas.too_big");
            return false;
        }
        for (int i = 0; i < total; i++) {
            int from = i * SaveCanvasPayload.CHUNK;
            int to = Math.min(bytes.length, from + SaveCanvasPayload.CHUNK);
            ClientPacketDistributor.sendToServer(new SaveCanvasPayload(pos, i, total, Arrays.copyOfRange(bytes, from, to)));
        }
        return true;
    }

    private boolean uploadMedia(byte[] bytes) {
        int total = (bytes.length + MediaUploadPayload.CHUNK - 1) / MediaUploadPayload.CHUNK;
        if (total > MediaUploadPayload.MAX_CHUNKS) {
            status = Component.translatable("gui.immersivecanvas.too_big");
            return false;
        }
        for (int i = 0; i < total; i++) {
            int from = i * MediaUploadPayload.CHUNK;
            int to = Math.min(bytes.length, from + MediaUploadPayload.CHUNK);
            ClientPacketDistributor.sendToServer(new MediaUploadPayload(i, total, Arrays.copyOfRange(bytes, from, to)));
        }
        return true;
    }

    private void save() {
        CanvasData data;
        BufferedImage th = thumb;
        if (mode == 0) {
            int[] d = delays.stream().mapToInt(Integer::intValue).toArray();
            data = CanvasData.pixel(bw, bh, ppb, style, d, frames);
            th = pixelThumb();
        } else {
            if (mediaHash.isEmpty()) {
                status = Component.translatable("gui.immersivecanvas.no_media");
                return;
            }
            data = CanvasData.media(mediaKind, bw, bh, style, fit, mediaHash, mw, mh);
            if (!mediaHash.equals(originalHash)) {
                byte[] bytes = mediaBytes != null ? mediaBytes : MediaClient.readCached(mediaHash);
                if (bytes == null) {
                    status = Component.translatable("gui.immersivecanvas.media_unavailable");
                    return;
                }
                if (!uploadMedia(bytes)) return;
            }
        }
        if (!sendCanvas(data)) return;
        try {
            MediaLibrary.add(data, th != null ? th : new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB));
        } catch (Exception ignored) {
        }
        onClose();
    }

    // =================================================================== rendu

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        if (mode == 0 && playing && frames.size() > 1) {
            long cycle = 0;
            for (int x : delays) cycle += x;
            long t = (System.currentTimeMillis() - playStart) % Math.max(1, cycle);
            int idx = 0;
            for (int i = 0; i < delays.size(); i++) {
                idx = i;
                if (t < delays.get(i)) break;
                t -= delays.get(i);
            }
            if (idx != cur) {
                cur = idx;
                texDirty = true;
            }
        }

        g.drawString(font, title, gx, 6, 0xFFFFFFFF);
        if (mode == 0) renderPixel(g, mouseX, mouseY); else renderHd(g);
        renderPanel(g);
        g.drawString(font, status, gx, height - 14, 0xFFFFFFFF);
    }

    private void renderPixel(GuiGraphics g, int mouseX, int mouseY) {
        g.fill(gx - 1, gy - 1, gx + dispW + 1, gy + dispH + 1, 0xFF000000);
        if (texDirty) repaintAll();
        else if (texUpload) { tex.upload(); texUpload = false; }
        g.blit(RenderPipelines.GUI_TEXTURED, texId, gx, gy, 0f, 0f, dispW, dispH, texW, texH, texW, texH);

        int[] hover = playing ? null : cellAt(mouseX, mouseY);
        if (hover != null) {
            int x0 = gx + (int) Math.floor(hover[0] * zoom), y0 = gy + (int) Math.floor(hover[1] * zoom);
            int x1 = Math.max(x0 + 1, gx + (int) Math.floor((hover[0] + 1) * zoom));
            int y1 = Math.max(y0 + 1, gy + (int) Math.floor((hover[1] + 1) * zoom));
            int o = 0xFFFFFFFF;
            g.fill(x0, y0, x1, y0 + 1, o);
            g.fill(x0, y1 - 1, x1, y1, o);
            g.fill(x0, y0, x0 + 1, y1, o);
            g.fill(x1 - 1, y0, x1, y1, o);
        }
    }

    private void renderHd(GuiGraphics g) {
        int bx = gx, by = gy;
        g.fill(bx - 1, by - 1, bx + hdW + 1, by + hdH + 1, 0xFF000000);
        g.fill(bx, by, bx + hdW, by + hdH, 0xFFEFE8D6);

        if (mediaHash.isEmpty()) {
            g.drawString(font, Component.translatable("gui.immersivecanvas.no_media"), bx + 6, by + 6, 0xFF555555);
        } else {
            Identifier id = MediaClient.texture(mediaHash, mediaKind);
            if (id == null) {
                g.drawString(font, Component.translatable("gui.immersivecanvas.loading"), bx + 6, by + 6, 0xFF555555);
            } else {
                double boxAspect = hdW / (double) hdH, imgAspect = mw / (double) mh;
                int dx = bx, dy = by, dw = hdW, dh = hdH;
                int ru = 0, rv = 0, rw = mw, rh = mh;
                if (fit == 0) {
                    if (imgAspect > boxAspect) {
                        rw = Math.max(1, (int) (mh * boxAspect));
                        ru = (mw - rw) / 2;
                    } else {
                        rh = Math.max(1, (int) (mw / boxAspect));
                        rv = (mh - rh) / 2;
                    }
                } else if (fit == 1) {
                    if (imgAspect > boxAspect) {
                        dh = Math.max(1, (int) (hdW / imgAspect));
                        dy = by + (hdH - dh) / 2;
                    } else {
                        dw = Math.max(1, (int) (hdH * imgAspect));
                        dx = bx + (hdW - dw) / 2;
                    }
                }
                g.blit(RenderPipelines.GUI_TEXTURED, id, dx, dy, (float) ru, (float) rv, dw, dh, rw, rh, mw, mh);
            }
        }
        // repères des blocs
        for (int i = 1; i < bw; i++) {
            int x = bx + i * hdW / bw;
            g.fill(x, by, x + 1, by + hdH, 0x66000000);
        }
        for (int j = 1; j < bh; j++) {
            int y = by + j * hdH / bh;
            g.fill(bx, y, bx + hdW, y + 1, 0x66000000);
        }
    }

    private void renderPanel(GuiGraphics g) {
        switch (page) {
            case DRAW -> {
                g.fill(panelX + 74, Y0 + 103, panelX + 100, Y0 + 123, 0xFFFFFFFF);
                g.fill(panelX + 75, Y0 + 104, panelX + 99, Y0 + 122, 0xFF000000 | color);
                g.drawString(font, W + "x" + H + " px", panelX + 106, Y0 + 109, 0xFFAAAAAA);
                for (int i = 0; i < PALETTE.length; i++) {
                    int x0 = panelX + (i % 16) * SW, y0 = paletteY + (i / 16) * SW;
                    g.fill(x0, y0, x0 + SW - 1, y0 + SW - 1, 0xFF000000 | PALETTE[i]);
                    if (PALETTE[i] == color) {
                        g.fill(x0, y0, x0 + SW - 1, y0 + 1, 0xFFFFFFFF);
                        g.fill(x0, y0 + SW - 2, x0 + SW - 1, y0 + SW - 1, 0xFFFFFFFF);
                    }
                }
            }
            case ANIM -> {
                g.drawString(font, Component.translatable("gui.immersivecanvas.frame", cur + 1, frames.size()),
                        panelX, Y0 + 26, 0xFFFFFFFF);
                g.drawString(font, "ms", panelX + 62, Y0 + 46, 0xFFAAAAAA);
                long total = 0;
                for (int d : delays) total += d;
                g.drawString(font, Component.translatable("gui.immersivecanvas.duration", total), panelX, Y0 + 68, 0xFFAAAAAA);
            }
            case SIZE -> {
                g.drawString(font, "x", panelX + 42, Y0 + 5, 0xFFFFFFFF);
                g.drawString(font, Component.translatable("gui.immersivecanvas.blocks", bw, bh), panelX, Y0 + 74, 0xFFAAAAAA);
                if (mode == 0) {
                    g.drawString(font, W + "x" + H + " px", panelX, Y0 + 86, 0xFFAAAAAA);
                } else if (!mediaHash.isEmpty()) {
                    g.drawString(font, mw + "x" + mh + " px", panelX, Y0 + 86, 0xFFAAAAAA);
                }
            }
            case IMPORT -> {
                if (mode == 1) {
                    g.drawString(font, Component.translatable("gui.immersivecanvas.hd_hint1"), panelX, Y0 + 50, 0xFFAAAAAA);
                    g.drawString(font, Component.translatable("gui.immersivecanvas.hd_hint2"), panelX, Y0 + 62, 0xFFAAAAAA);
                    g.drawString(font, Component.translatable("gui.immersivecanvas.hd_hint3"), panelX, Y0 + 74, 0xFFAAAAAA);
                }
            }
            case GALLERY -> renderGallery(g);
        }
    }

    private void renderGallery(GuiGraphics g) {
        int per = GCOLS * GROWS;
        int pages = Math.max(1, (libEntries.size() + per - 1) / per);
        if (libEntries.isEmpty()) {
            g.drawString(font, Component.translatable("gui.immersivecanvas.gallery_empty"), panelX, Y0 + 6, 0xFFAAAAAA);
        }
        for (int k = 0; k < per; k++) {
            int idx = galleryPage * per + k;
            if (idx >= libEntries.size()) break;
            MediaLibrary.Entry e = libEntries.get(idx);
            int x = panelX + (k % GCOLS) * GCW, y = Y0 + (k / GCOLS) * GCH;
            boolean sel = e.id().equals(selectedId);
            g.fill(x, y, x + GCW - 2, y + GCH - 2, sel ? 0xFFFFFFFF : 0xFF3A3A3A);
            g.fill(x + 1, y + 1, x + GCW - 3, y + GCH - 3, 0xFF1E1E1E);
            Thumb t = thumbs.get(e.id());
            if (t != null) {
                double s = Math.min((GCW - 8) / (double) t.w(), (GCH - 8) / (double) t.h());
                int dw = Math.max(1, (int) (t.w() * s)), dh = Math.max(1, (int) (t.h() * s));
                int dx = x + (GCW - 2 - dw) / 2, dy = y + (GCH - 2 - dh) / 2;
                g.blit(RenderPipelines.GUI_TEXTURED, t.id(), dx, dy, 0f, 0f, dw, dh, t.w(), t.h(), t.w(), t.h());
            }
        }
        int by = Y0 + GROWS * GCH + 4;
        g.drawString(font, Component.translatable("gui.immersivecanvas.page_of", galleryPage + 1, pages), panelX, by + 24, 0xFFAAAAAA);
    }
}
