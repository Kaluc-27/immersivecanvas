package com.example.immersivecanvas.client;

import com.example.immersivecanvas.CanvasData;
import com.example.immersivecanvas.SaveCanvasPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.function.DoubleConsumer;

/** Éditeur de pixel-art : crayon, gomme, seau, pipette, undo/redo, couleurs HSV/hex, import d'image. */
public class CanvasEditorScreen extends Screen {
    private record Snap(int res, int[] px) {}

    private enum Tool { PENCIL, ERASER, FILL, PICKER }

    private static final int[] SIZES = {16, 32, 64};
    private static final int WHITE = 0xFFFFFF;
    private static final int[] PALETTE = {
            0x000000, 0x1D2B53, 0x7E2553, 0x008751, 0xAB5236, 0x5F574F, 0xC2C3C7, 0xFFF1E8,
            0xFF004D, 0xFFA300, 0xFFEC27, 0x00E436, 0x29ADFF, 0x83769C, 0xFF77A8, 0xFFCCAA,
            0xFFFFFF, 0x9B2226, 0xCA6702, 0xE9D8A6, 0x94D2BD, 0x0A9396, 0x005F73, 0x001219,
            0x3A0CA3, 0x7209B7, 0xF72585, 0x4CC9F0, 0x6A994E, 0x386641, 0x8D6E63, 0x4E342E};
    private static final int SW = 12; // taille d'une pastille de palette
    private static final int MAX_UNDO = 64;

    private final BlockPos pos;
    private int res;
    private int[] px;
    private final Deque<Snap> undo = new ArrayDeque<>();
    private final Deque<Snap> redo = new ArrayDeque<>();

    private Tool tool = Tool.PENCIL;
    private int color = 0x000000;
    private float hue, sat, val;
    private boolean painting;
    private int lastX, lastY;
    private String pathText = "";
    private Component status = Component.empty();
    private boolean updatingHex;

    // mise en page (recalculée dans init)
    private int gx, gy, cell, panelX, paletteY;
    private final Button[] toolButtons = new Button[Tool.values().length];
    private HsvSlider hSlider, sSlider, vSlider;
    private EditBox hexBox, pathBox;

    public CanvasEditorScreen(BlockPos pos, CanvasData data) {
        super(Component.translatable("gui.immersivecanvas.title"));
        this.pos = pos;
        if (data.isValid()) {
            res = data.res();
            px = data.toPixels();
        } else {
            res = 32;
            px = new int[res * res];
            Arrays.fill(px, WHITE);
        }
    }

    @Override
    public boolean isPauseScreen() { return false; }

    // ------------------------------------------------------------------ widgets

    @Override
    protected void init() {
        cell = Math.max(2, Math.min((height - 80) / res, (width - 260) / res));
        gx = 10;
        gy = 18;
        panelX = gx + cell * res + 14;
        int w = 192;

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
        addRenderableWidget(Button.builder(Component.translatable("gui.immersivecanvas.clear"), b -> clearCanvas())
                .bounds(panelX + 96, 40, 46, 18).build());
        addRenderableWidget(Button.builder(Component.literal(res + "px"), b -> cycleSize())
                .bounds(panelX + 144, 40, 46, 18).build());

        hSlider = addRenderableWidget(new HsvSlider(panelX, 64, w, 20, "H", hue, v -> { hue = (float) v; fromHsv(); }));
        sSlider = addRenderableWidget(new HsvSlider(panelX, 86, w, 20, "S", sat, v -> { sat = (float) v; fromHsv(); }));
        vSlider = addRenderableWidget(new HsvSlider(panelX, 108, w, 20, "V", val, v -> { val = (float) v; fromHsv(); }));

        hexBox = addRenderableWidget(new EditBox(font, panelX, 134, 70, 18, Component.literal("hex")));
        hexBox.setMaxLength(6);
        hexBox.setFilter(s -> s.matches("[0-9a-fA-F]*"));
        hexBox.setValue(String.format("%06X", color));
        hexBox.setResponder(s -> {
            if (!updatingHex && s.length() == 6) setColor(Integer.parseInt(s, 16), true);
        });
        paletteY = 158;

        // ligne d'import + boutons du bas
        int by = height - 52;
        pathBox = addRenderableWidget(new EditBox(font, 10, by, 200, 18, Component.translatable("gui.immersivecanvas.path")));
        pathBox.setMaxLength(512);
        pathBox.setHint(Component.translatable("gui.immersivecanvas.path"));
        pathBox.setValue(pathText);
        pathBox.setResponder(s -> pathText = s);
        addRenderableWidget(Button.builder(Component.translatable("gui.immersivecanvas.browse"), b -> browse())
                .bounds(214, by, 60, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.immersivecanvas.import"), b -> importImage(pathText))
                .bounds(278, by, 60, 18).build());

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

    /** Slider 0..1 avec callback. */
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

    // ------------------------------------------------------------------ undo / redo

    private Snap snapshot() { return new Snap(res, px.clone()); }

    private void pushUndo() {
        undo.push(snapshot());
        while (undo.size() > MAX_UNDO) undo.removeLast();
        redo.clear();
    }

    private void restore(Snap s) {
        boolean resChanged = s.res() != res;
        res = s.res();
        px = s.px().clone();
        if (resChanged) rebuildWidgets();
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

    private void clearCanvas() {
        pushUndo();
        Arrays.fill(px, WHITE);
    }

    private void cycleSize() {
        int idx = 0;
        for (int i = 0; i < SIZES.length; i++) if (SIZES[i] == res) idx = i;
        int nr = SIZES[(idx + 1) % SIZES.length];
        pushUndo();
        int[] out = new int[nr * nr];
        for (int y = 0; y < nr; y++)
            for (int x = 0; x < nr; x++)
                out[y * nr + x] = px[(y * res / nr) * res + (x * res / nr)];
        px = out;
        res = nr;
        rebuildWidgets();
    }

    // ------------------------------------------------------------------ dessin

    private int[] cellAt(double mx, double my) {
        if (mx < gx || my < gy) return null;
        int cx = (int) Math.floor((mx - gx) / cell), cy = (int) Math.floor((my - gy) / cell);
        return (cx < res && cy < res) ? new int[]{cx, cy} : null;
    }

    private void plot(int x, int y) {
        px[y * res + x] = tool == Tool.ERASER ? WHITE : color;
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
        int target = px[sy * res + sx];
        if (target == fill) return;
        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(sy * res + sx);
        while (!stack.isEmpty()) {
            int i = stack.pop();
            if (px[i] != target) continue;
            px[i] = fill;
            int x = i % res, y = i / res;
            if (x > 0) stack.push(i - 1);
            if (x < res - 1) stack.push(i + 1);
            if (y > 0) stack.push(i - res);
            if (y < res - 1) stack.push(i + res);
        }
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

        int[] c = cellAt(mx, my);
        if (c == null) return false;

        if (button == 1 || tool == Tool.PICKER) { // clic droit = pipette rapide
            setColor(px[c[1] * res + c[0]], false);
            if (tool == Tool.PICKER) { tool = Tool.PENCIL; refreshTools(); }
            return true;
        }
        if (button == 0) {
            if (tool == Tool.FILL) {
                if (px[c[1] * res + c[0]] != color) {
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
            PointerBuffer filters = stack.mallocPointer(4);
            filters.put(stack.UTF8("*.png")).put(stack.UTF8("*.jpg")).put(stack.UTF8("*.jpeg")).put(stack.UTF8("*.bmp"));
            filters.flip();
            String result = TinyFileDialogs.tinyfd_openFileDialog("Import", null, filters, "Images", false);
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
            BufferedImage img = ImageIO.read(new File(path.trim().replace("\"", "")));
            if (img == null) throw new IllegalArgumentException("format");
            int[] out = scaleToSquare(img, res);
            pushUndo();
            px = out;
            status = Component.translatable("gui.immersivecanvas.imported");
        } catch (Exception e) {
            status = Component.translatable("gui.immersivecanvas.import_error", String.valueOf(e.getMessage()));
        }
    }

    /** Recadre au centre en carré puis réduit en moyennant les pixels (alpha composé sur blanc). */
    private static int[] scaleToSquare(BufferedImage img, int n) {
        int w = img.getWidth(), h = img.getHeight(), size = Math.min(w, h);
        int ox = (w - size) / 2, oy = (h - size) / 2;
        int[] out = new int[n * n];
        for (int dy = 0; dy < n; dy++) {
            int y0 = oy + dy * size / n, y1 = Math.max(y0 + 1, oy + (dy + 1) * size / n);
            for (int dx = 0; dx < n; dx++) {
                int x0 = ox + dx * size / n, x1 = Math.max(x0 + 1, ox + (dx + 1) * size / n);
                long r = 0, g = 0, b = 0;
                int count = 0;
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        int argb = img.getRGB(x, y);
                        float a = ((argb >>> 24) & 255) / 255f;
                        r += (int) (((argb >> 16) & 255) * a + 255 * (1 - a));
                        g += (int) (((argb >> 8) & 255) * a + 255 * (1 - a));
                        b += (int) ((argb & 255) * a + 255 * (1 - a));
                        count++;
                    }
                }
                out[dy * n + dx] = ((int) (r / count) << 16) | ((int) (g / count) << 8) | (int) (b / count);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ sauvegarde / rendu

    private void save() {
        ClientPacketDistributor.sendToServer(new SaveCanvasPayload(pos, CanvasData.fromPixels(res, px)));
        onClose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        g.drawString(font, title, gx, 6, 0xFFFFFFFF);

        // grille
        int size = cell * res;
        g.fill(gx - 1, gy - 1, gx + size + 1, gy + size + 1, 0xFF000000);
        for (int y = 0; y < res; y++) {
            for (int x = 0; x < res; x++) {
                int x0 = gx + x * cell, y0 = gy + y * cell;
                g.fill(x0, y0, x0 + cell, y0 + cell, 0xFF000000 | px[y * res + x]);
            }
        }
        int[] hover = cellAt(mouseX, mouseY);
        if (hover != null) {
            int x0 = gx + hover[0] * cell, y0 = gy + hover[1] * cell;
            g.fill(x0, y0, x0 + cell, y0 + 1, 0xFFFFFFFF);
            g.fill(x0, y0 + cell - 1, x0 + cell, y0 + cell, 0xFFFFFFFF);
            g.fill(x0, y0, x0 + 1, y0 + cell, 0xFFFFFFFF);
            g.fill(x0 + cell - 1, y0, x0 + cell, y0 + cell, 0xFFFFFFFF);
        }

        // aperçu couleur courante
        g.fill(panelX + 74, 133, panelX + 100, 153, 0xFFFFFFFF);
        g.fill(panelX + 75, 134, panelX + 99, 152, 0xFF000000 | color);

        // palette
        for (int i = 0; i < PALETTE.length; i++) {
            int x0 = panelX + (i % 16) * SW, y0 = paletteY + (i / 16) * SW;
            g.fill(x0, y0, x0 + SW - 1, y0 + SW - 1, 0xFF000000 | PALETTE[i]);
            if (PALETTE[i] == color) {
                g.fill(x0, y0, x0 + SW - 1, y0 + 1, 0xFFFFFFFF);
                g.fill(x0, y0 + SW - 2, x0 + SW - 1, y0 + SW - 1, 0xFFFFFFFF);
            }
        }

        g.drawString(font, status, 344, height - 48, 0xFFFFFFFF);
    }
}
