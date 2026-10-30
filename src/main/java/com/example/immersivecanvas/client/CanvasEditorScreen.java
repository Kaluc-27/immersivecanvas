package com.example.immersivecanvas.client;

import com.example.immersivecanvas.CanvasData;
import com.example.immersivecanvas.ImmersiveCanvas;
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

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.function.DoubleConsumer;

/**
 * Éditeur de pixel-art : crayon, gomme, seau, pipette, annuler/rétablir, couleurs HSV/hex/palette,
 * grands tableaux (jusqu'à 4x4 blocs), 16/32/64 px par bloc, images multiples (GIF animé), import d'image.
 */
public class CanvasEditorScreen extends Screen {
    private record Snap(int bw, int bh, int ppb, int style, List<int[]> frames, List<Integer> delays, int cur) {}

    private enum Tool { PENCIL, ERASER, FILL, PICKER }

    private static final int WHITE = 0xFFFFFF;
    private static final int[] PALETTE = {
            0x000000, 0x1D2B53, 0x7E2553, 0x008751, 0xAB5236, 0x5F574F, 0xC2C3C7, 0xFFF1E8,
            0xFF004D, 0xFFA300, 0xFFEC27, 0x00E436, 0x29ADFF, 0x83769C, 0xFF77A8, 0xFFCCAA,
            0xFFFFFF, 0x9B2226, 0xCA6702, 0xE9D8A6, 0x94D2BD, 0x0A9396, 0x005F73, 0x001219,
            0x3A0CA3, 0x7209B7, 0xF72585, 0x4CC9F0, 0x6A994E, 0x386641, 0x8D6E63, 0x4E342E};
    private static final int SW = 12;
    private static int texCounter = 0;

    private final BlockPos pos;
    private int bw, bh, ppb, style, W, H;
    private final List<int[]> frames = new ArrayList<>();
    private final List<Integer> delays = new ArrayList<>();
    private int cur = 0;

    private final Deque<Snap> undo = new ArrayDeque<>();
    private final Deque<Snap> redo = new ArrayDeque<>();

    private Tool tool = Tool.PENCIL;
    private int color = 0x000000;
    private float hue, sat, val;
    private boolean painting;
    private int lastX, lastY;
    private boolean playing;
    private long playStart;
    private ImageImporter.Fit fit = ImageImporter.Fit.CROP;
    private String pathText = "";
    private Component status = Component.empty();
    private boolean updatingHex, updatingDelay;

    // texture de l'éditeur
    private DynamicTexture tex;
    private Identifier texId;
    private int texW, texH;
    private boolean texDirty = true, texUpload = false;

    // mise en page
    private int gx, gy, panelX, paletteY, texScale, dispW, dispH;
    private double zoom;
    private final Button[] toolButtons = new Button[Tool.values().length];
    private Button playButton;
    private HsvSlider hSlider, sSlider, vSlider;
    private EditBox hexBox, pathBox, delayBox, wBox, hBox;

    public CanvasEditorScreen(BlockPos pos, CanvasData data) {
        super(Component.translatable("gui.immersivecanvas.title"));
        this.pos = pos;
        byte[][] raw = data.isValid() ? data.unpackFrames() : null;
        if (raw != null) {
            bw = data.bw();
            bh = data.bh();
            ppb = data.ppb();
            style = data.style();
            for (int i = 0; i < raw.length; i++) {
                frames.add(CanvasData.toInts(raw[i]));
                delays.add(data.delays()[i]);
            }
        } else {
            bw = 1;
            bh = 1;
            ppb = 32;
            int[] blank = new int[ppb * ppb];
            Arrays.fill(blank, WHITE);
            frames.add(blank);
            delays.add(100);
        }
        W = bw * ppb;
        H = bh * ppb;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private int[] px() { return frames.get(cur); }

    private int maxFramesAllowed() {
        return Math.max(1, Math.min(CanvasData.MAX_FRAMES, CanvasData.MAX_RAW / (W * H * 3)));
    }

    // ------------------------------------------------------------------ widgets

    @Override
    protected void init() {
        int availW = Math.max(64, width - 270), availH = Math.max(64, height - 90);
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
        gx = 10;
        gy = 18;
        panelX = gx + dispW + 14;
        int w = 192;
        ensureTexture();

        Tool[] tools = Tool.values();
        for (int i = 0; i < tools.length; i++) {
            final Tool t = tools[i];
            toolButtons[i] = addRenderableWidget(Button.builder(toolLabel(t), b -> {
                tool = t;
                refreshTools();
            }).bounds(panelX + i * 48, 18, 46, 18).build());
        }
        refreshTools();

        addRenderableWidget(Button.builder(Component.translatable("gui.immersivecanvas.undo"), b -> undo())
                .bounds(panelX, 40, 46, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.immersivecanvas.redo"), b -> redo())
                .bounds(panelX + 48, 40, 46, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.immersivecanvas.clear"), b -> clearFrame())
                .bounds(panelX + 96, 40, 46, 18).build());
        playButton = addRenderableWidget(Button.builder(playLabel(), b -> togglePlay())
                .bounds(panelX + 144, 40, 46, 18).build());

        wBox = addRenderableWidget(new EditBox(font, panelX, 62, 34, 18, Component.literal("L")));
        wBox.setMaxLength(2);
        wBox.setFilter(t -> t.matches("[0-9]*"));
        wBox.setValue(String.valueOf(bw));
        hBox = addRenderableWidget(new EditBox(font, panelX + 46, 62, 34, 18, Component.literal("H")));
        hBox.setMaxLength(2);
        hBox.setFilter(t -> t.matches("[0-9]*"));
        hBox.setValue(String.valueOf(bh));
        addRenderableWidget(Button.builder(Component.translatable("gui.immersivecanvas.ppb", ppb), b -> resize(bw, bh, nextPpb()))
                .bounds(panelX + 84, 62, 50, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.immersivecanvas.apply"), b -> applySize())
                .bounds(panelX + 138, 62, 52, 18).build());

        addRenderableWidget(Button.builder(Component.literal("<"), b -> gotoFrame(cur - 1))
                .bounds(panelX, 84, 46, 18).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> gotoFrame(cur + 1))
                .bounds(panelX + 48, 84, 46, 18).build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> addFrame())
                .bounds(panelX + 96, 84, 46, 18).build());
        addRenderableWidget(Button.builder(Component.literal("-"), b -> delFrame())
                .bounds(panelX + 144, 84, 46, 18).build());

        delayBox = addRenderableWidget(new EditBox(font, panelX + 62, 106, 56, 16, Component.literal("ms")));
        delayBox.setMaxLength(5);
        delayBox.setFilter(s -> s.matches("[0-9]*"));
        delayBox.setResponder(s -> {
            if (updatingDelay || s.isEmpty()) return;
            try {
                delays.set(cur, Mth.clamp(Integer.parseInt(s), CanvasData.MIN_DELAY, CanvasData.MAX_DELAY));
            } catch (NumberFormatException ignored) {}
        });
        syncDelayBox();
        addRenderableWidget(Button.builder(Component.translatable("gui.immersivecanvas.delay_all"), b -> {
            int d = delays.get(cur);
            for (int i = 0; i < delays.size(); i++) delays.set(i, d);
        }).bounds(panelX + 122, 105, 46, 18).build());

        hSlider = addRenderableWidget(new HsvSlider(panelX, 128, w, 16, "H", hue, v -> { hue = (float) v; fromHsv(); }));
        sSlider = addRenderableWidget(new HsvSlider(panelX, 146, w, 16, "S", sat, v -> { sat = (float) v; fromHsv(); }));
        vSlider = addRenderableWidget(new HsvSlider(panelX, 164, w, 16, "V", val, v -> { val = (float) v; fromHsv(); }));

        hexBox = addRenderableWidget(new EditBox(font, panelX, 186, 70, 18, Component.literal("hex")));
        hexBox.setMaxLength(6);
        hexBox.setFilter(s -> s.matches("[0-9a-fA-F]*"));
        hexBox.setValue(String.format("%06X", color));
        hexBox.setResponder(s -> {
            if (!updatingHex && s.length() == 6) setColor(Integer.parseInt(s, 16), true);
        });
        paletteY = 210;

        int by = height - 52;
        pathBox = addRenderableWidget(new EditBox(font, 10, by, 170, 18, Component.translatable("gui.immersivecanvas.path")));
        pathBox.setMaxLength(512);
        pathBox.setHint(Component.translatable("gui.immersivecanvas.path"));
        pathBox.setValue(pathText);
        pathBox.setResponder(s -> pathText = s);
        addRenderableWidget(Button.builder(Component.translatable("gui.immersivecanvas.browse"), b -> browse())
                .bounds(184, by, 56, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.immersivecanvas.import"), b -> importImage(pathText))
                .bounds(244, by, 56, 18).build());
        addRenderableWidget(Button.builder(fitLabel(), b -> {
            fit = ImageImporter.Fit.values()[(fit.ordinal() + 1) % ImageImporter.Fit.values().length];
            b.setMessage(fitLabel());
        }).bounds(304, by, 60, 18).build());
        addRenderableWidget(Button.builder(styleLabel(), b -> {
            style = (style + 1) % CanvasData.STYLES;
            b.setMessage(styleLabel());
        }).bounds(368, by, 100, 18).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.immersivecanvas.save"), b -> save())
                .bounds(10, height - 28, 90, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.immersivecanvas.cancel"), b -> onClose())
                .bounds(104, height - 28, 90, 20).build());
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
        return Component.translatable("gui.immersivecanvas.fit." + fit.name().toLowerCase());
    }

    private Component styleLabel() {
        return Component.translatable("gui.immersivecanvas.style", Component.translatable("gui.immersivecanvas.style." + style));
    }

    /** Résolution suivante, en sautant celles qui dépasseraient la limite de pixels pour la taille actuelle. */
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

    private void applySize() {
        try {
            int nbw = Mth.clamp(Integer.parseInt(wBox.getValue()), 1, CanvasData.MAX_BLOCKS);
            int nbh = Mth.clamp(Integer.parseInt(hBox.getValue()), 1, CanvasData.MAX_BLOCKS);
            if (nbw != bw || nbh != bh) resize(nbw, nbh, ppb);
            else { wBox.setValue(String.valueOf(bw)); hBox.setValue(String.valueOf(bh)); }
        } catch (NumberFormatException ignored) {
            wBox.setValue(String.valueOf(bw));
            hBox.setValue(String.valueOf(bh));
        }
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

    // ------------------------------------------------------------------ texture de l'éditeur

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
        if (tex != null && texId != null) {
            Minecraft.getInstance().getTextureManager().release(texId);
        }
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
        super.removed();
    }

    // ------------------------------------------------------------------ couleurs

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

    // ------------------------------------------------------------------ historique

    private Snap snapshot() {
        List<int[]> copy = new ArrayList<>(frames.size());
        for (int[] f : frames) copy.add(f.clone());
        return new Snap(bw, bh, ppb, style, copy, new ArrayList<>(delays), cur);
    }

    private void pushUndo() {
        undo.push(snapshot());
        int max = Math.max(4, Math.min(40, 24_000_000 / Math.max(1, frames.size() * W * H * 4)));
        while (undo.size() > max) undo.removeLast();
        redo.clear();
    }

    private void restore(Snap s) {
        boolean dimsChanged = s.bw() != bw || s.bh() != bh || s.ppb() != ppb;
        bw = s.bw();
        bh = s.bh();
        ppb = s.ppb();
        style = s.style();
        W = bw * ppb;
        H = bh * ppb;
        frames.clear();
        for (int[] f : s.frames()) frames.add(f.clone());
        delays.clear();
        delays.addAll(s.delays());
        cur = Math.min(s.cur(), frames.size() - 1);
        texDirty = true;
        if (dimsChanged) rebuildWidgets(); else syncDelayBox();
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

    // ------------------------------------------------------------------ images (frames) / taille

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
        playButton.setMessage(playLabel());
        texDirty = true;
    }

    private void resize(int nbw, int nbh, int nppb) {
        nbw = Mth.clamp(nbw, 1, CanvasData.MAX_BLOCKS);
        nbh = Mth.clamp(nbh, 1, CanvasData.MAX_BLOCKS);
        boolean reduced = false;
        while ((long) nbw * nppb * nbh * nppb * 3 > CanvasData.MAX_RAW && nppb > CanvasData.PPBS[0]) {
            int[] list = CanvasData.PPBS;
            for (int i = list.length - 1; i > 0; i--) if (list[i] == nppb) { nppb = list[i - 1]; break; }
            reduced = true;
        }
        pushUndo();
        int nW = nbw * nppb, nH = nbh * nppb;
        int maxF = Math.max(1, Math.min(CanvasData.MAX_FRAMES, CanvasData.MAX_RAW / (nW * nH * 3)));
        boolean trimmed = frames.size() > maxF;
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
        bw = nbw;
        bh = nbh;
        ppb = nppb;
        W = nW;
        H = nH;
        cur = Math.min(cur, frames.size() - 1);
        playing = false;
        if (reduced) status = Component.translatable("gui.immersivecanvas.ppb_reduced", ppb);
        else if (trimmed) status = Component.translatable("gui.immersivecanvas.trimmed", frames.size());
        texDirty = true;
        rebuildWidgets();
    }

    // ------------------------------------------------------------------ dessin

    private int[] cellAt(double mx, double my) {
        if (mx < gx || my < gy) return null;
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

    // ------------------------------------------------------------------ souris

    private int paletteIndexAt(double mx, double my) {
        int col = (int) Math.floor((mx - panelX) / SW), row = (int) Math.floor((my - paletteY) / SW);
        if (mx < panelX || my < paletteY || col >= 16 || row >= 2) return -1;
        return row * 16 + col;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        double mx = event.x(), my = event.y();
        int button = event.button();

        int p = paletteIndexAt(mx, my);
        if (p >= 0 && button == 0) {
            setColor(PALETTE[p], false);
            return true;
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

    // ------------------------------------------------------------------ import

    private void browse() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(5);
            filters.put(stack.UTF8("*.gif")).put(stack.UTF8("*.png")).put(stack.UTF8("*.jpg"))
                    .put(stack.UTF8("*.jpeg")).put(stack.UTF8("*.bmp"));
            filters.flip();
            String result = TinyFileDialogs.tinyfd_openFileDialog("Import", null, filters, "Images / GIF", false);
            if (result != null) {
                pathText = result;
                pathBox.setValue(result);
                importImage(result);
            }
        } catch (Throwable t) {
            status = Component.translatable("gui.immersivecanvas.import_error", String.valueOf(t.getMessage()));
        }
    }

    private void importImage(String path) {
        if (path == null || path.isBlank()) return;
        try {
            File f = new File(path.trim().replace("\"", ""));
            ImageImporter.Result r = ImageImporter.load(f, W, H, fit, maxFramesAllowed());
            pushUndo();
            frames.clear();
            frames.addAll(r.frames());
            delays.clear();
            delays.addAll(r.delays());
            cur = 0;
            playing = false;
            if (playButton != null) playButton.setMessage(playLabel());
            texDirty = true;
            syncDelayBox();
            status = Component.translatable("gui.immersivecanvas.imported", frames.size());
        } catch (Exception e) {
            status = Component.translatable("gui.immersivecanvas.import_error", String.valueOf(e.getMessage()));
        }
    }

    // ------------------------------------------------------------------ sauvegarde

    private void save() {
        int[] d = delays.stream().mapToInt(Integer::intValue).toArray();
        CanvasData data = CanvasData.pack(bw, bh, ppb, style, d, frames);
        ByteBuf buf = Unpooled.buffer();
        CanvasData.STREAM_CODEC.encode(buf, data);
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        buf.release();

        int total = (bytes.length + SaveCanvasPayload.CHUNK - 1) / SaveCanvasPayload.CHUNK;
        if (total > SaveCanvasPayload.MAX_CHUNKS) {
            status = Component.translatable("gui.immersivecanvas.too_big");
            return;
        }
        for (int i = 0; i < total; i++) {
            int from = i * SaveCanvasPayload.CHUNK;
            int to = Math.min(bytes.length, from + SaveCanvasPayload.CHUNK);
            ClientPacketDistributor.sendToServer(new SaveCanvasPayload(pos, i, total, Arrays.copyOfRange(bytes, from, to)));
        }
        onClose();
    }

    // ------------------------------------------------------------------ rendu

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        if (playing && frames.size() > 1) {
            long cycle = 0;
            for (int x : delays) cycle += x;
            long t = (System.currentTimeMillis() - playStart) % Math.max(1, cycle);
            int idx = 0;
            for (int i = 0; i < delays.size(); i++) {
                if (t < delays.get(i)) { idx = i; break; }
                t -= delays.get(i);
                idx = i;
            }
            if (idx != cur) {
                cur = idx;
                texDirty = true;
            }
        }

        g.drawString(font, title, gx, 6, 0xFFFFFFFF);

        // image
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

        // image courante + aperçu couleur
        g.drawString(font, Component.translatable("gui.immersivecanvas.frame", cur + 1, frames.size()),
                panelX, 110, 0xFFFFFFFF);
        g.fill(panelX + 74, 185, panelX + 100, 205, 0xFFFFFFFF);
        g.fill(panelX + 75, 186, panelX + 99, 204, 0xFF000000 | color);
        g.drawString(font, "x", panelX + 37, 67, 0xFFFFFFFF);
        g.drawString(font, W + "x" + H + " px", panelX + 108, 191, 0xFFAAAAAA);

        // palette
        for (int i = 0; i < PALETTE.length; i++) {
            int x0 = panelX + (i % 16) * SW, y0 = paletteY + (i / 16) * SW;
            g.fill(x0, y0, x0 + SW - 1, y0 + SW - 1, 0xFF000000 | PALETTE[i]);
            if (PALETTE[i] == color) {
                g.fill(x0, y0, x0 + SW - 1, y0 + 1, 0xFFFFFFFF);
                g.fill(x0, y0 + SW - 2, x0 + SW - 1, y0 + SW - 1, 0xFFFFFFFF);
            }
        }

        g.drawString(font, status, 10, height - 66, 0xFFFFFFFF);
    }
}
