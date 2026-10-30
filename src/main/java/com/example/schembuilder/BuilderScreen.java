package com.example.schembuilder;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/** Tabbed control panel: Overview / Materials / Settings, with a live stats card on the left. */
public class BuilderScreen extends Screen {
    private enum Tab {
        OVERVIEW("Overview"), MATERIALS("Materials"), SETTINGS("Settings");
        final String label;
        Tab(String l) { label = l; }
    }

    // remembered between openings
    private static Tab tab = Tab.OVERVIEW;
    private static String filter = "";
    private static boolean onlyMissing = false;

    // ARGB colours
    private static final int BG = 0xF0101418, HEADER = 0xFF151A20, CARD = 0xFF1B2128, BORDER = 0xFF39434E;
    private static final int ACCENT = 0xFF3CC8A0, TEXT = 0xFFE6EDF3, MUTED = 0xFF8B98A5, TRACK = 0xFF0E1216;
    private static final int GREEN = 0xFF55E07A, YELLOW = 0xFFFFD84D, RED = 0xFFFF6B6B, BLUE = 0xFF5AB0FF;

    private static final int HEADER_H = 26;
    private static final int ROW_H = 20;

    private int px, py, pw, ph;
    private int leftX, leftW;
    private int cx, cy, cw, ch;          // content area (right of the left card)
    private int scroll = 0;
    private int sinceScan = 0;
    private ButtonWidget startBtn;

    public BuilderScreen() { super(Text.literal("Schematic Builder")); }

    // ================================================================ layout
    @Override
    protected void init() {
        pw = Math.min(width - 12, 470);
        ph = Math.min(height - 12, 292);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        leftX = px + 8;
        leftW = 134;
        int top = py + HEADER_H + 8;

        startBtn = addDrawableChild(ButtonWidget.builder(startLabel(), b -> BuildEngine.toggle())
                .dimensions(leftX, top, leftW, 24).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("⟳ Refresh materials"), b -> MaterialScanner.scan())
                .dimensions(leftX, top + 28, leftW, 18).build());

        int tabX = leftX + leftW + 10;
        int tabW = (px + pw - 8 - tabX - 8) / 3;
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            Tab t = tabs[i];
            addDrawableChild(ButtonWidget.builder(Text.literal(t == tab ? "§e§l" + t.label : t.label), b -> {
                tab = t; scroll = 0; clearAndInit();
            }).dimensions(tabX + i * (tabW + 4), top, tabW, 18).build());
        }

        cx = tabX;
        cy = top + 24;
        cw = px + pw - 8 - tabX;
        ch = py + ph - 30 - cy;

        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
                .dimensions(px + pw - 8 - 72, py + ph - 22, 72, 16).build());

        switch (tab) {
            case SETTINGS -> initSettings();
            case MATERIALS -> initMaterials();
            case OVERVIEW -> initOverview();
        }
    }

    private void initOverview() {
        int half = (cw - 4) / 2;
        int y = cy + ch - 18;
        addToggle(cx, y, half, 16, "Auto-walk", Config.I.autoWalk, v -> Config.I.autoWalk = v);
        addToggle(cx + half + 4, y, half, 16, "Crouch at edges", Config.I.edgeSneak, v -> Config.I.edgeSneak = v);
    }

    private void initMaterials() {
        int fw = cw - 100;
        TextFieldWidget search = new TextFieldWidget(textRenderer, cx, cy, fw, 16, Text.literal("Search"));
        search.setMaxLength(30);
        search.setText(filter);
        search.setChangedListener(s -> { filter = s; scroll = 0; });
        addDrawableChild(search);
        addDrawableChild(ButtonWidget.builder(missingLabel(), b -> {
            onlyMissing = !onlyMissing; b.setMessage(missingLabel()); scroll = 0;
        }).dimensions(cx + fw + 4, cy, 96, 16).build());
    }

    private void initSettings() {
        int colW = (cw - 8) / 2;
        int lx = cx, rx = cx + colW + 8;
        int rh = ch >= 180 ? 21 : 18, bh = rh - 3;
        int y = cy + 14;

        addDrawableChild(new IntSlider(lx, y, colW, bh, "Min delay", 0, 20, Config.I.minDelay, v -> Config.I.minDelay = v, v -> v + " ticks")); y += rh;
        addDrawableChild(new IntSlider(lx, y, colW, bh, "Max delay", 1, 40, Config.I.maxDelay, v -> Config.I.maxDelay = v, v -> v + " ticks")); y += rh;
        addDrawableChild(new IntSlider(lx, y, colW, bh, "Turn speed", 3, 60, Config.I.turnSpeed, v -> Config.I.turnSpeed = v, v -> v + "°/tick")); y += rh;
        addDrawableChild(new IntSlider(lx, y, colW, bh, "Reach", 20, 60, Config.I.reachTenths, v -> Config.I.reachTenths = v, v -> (v / 10.0) + " blocks")); y += rh;
        addDrawableChild(new IntSlider(lx, y, colW, bh, "Search radius", 8, 64, Config.I.searchRadius, v -> Config.I.searchRadius = v, v -> v + " blocks")); y += rh + 6;
        addDrawableChild(ButtonWidget.builder(Text.literal("§cReset to defaults"), b -> {
            Config.I = new Config(); Config.save(); clearAndInit();
        }).dimensions(lx, y, colW, bh).build());

        y = cy + 14;
        addToggle(rx, y, colW, bh, "Auto-walk", Config.I.autoWalk, v -> Config.I.autoWalk = v); y += rh;
        addToggle(rx, y, colW, bh, "Crouch at edges", Config.I.edgeSneak, v -> Config.I.edgeSneak = v); y += rh;
        addToggle(rx, y, colW, bh, "Allow drops (<=3)", Config.I.allowDrops, v -> Config.I.allowDrops = v); y += rh;
        addToggle(rx, y, colW, bh, "Layer by layer", Config.I.layerByLayer, v -> Config.I.layerByLayer = v); y += rh;
        addToggle(rx, y, colW, bh, "Creative auto-give", Config.I.creativeFill, v -> Config.I.creativeFill = v); y += rh;
        addToggle(rx, y, colW, bh, "Line-of-sight check", Config.I.lineOfSight, v -> Config.I.lineOfSight = v); y += rh;
        addToggle(rx, y, colW, bh, "Show HUD card", Config.I.showHud, v -> Config.I.showHud = v);
    }

    private void addToggle(int x, int y, int w, int h, String name, boolean init, Consumer<Boolean> set) {
        boolean[] state = {init};
        addDrawableChild(ButtonWidget.builder(toggleText(name, init), b -> {
            state[0] = !state[0];
            set.accept(state[0]);
            b.setMessage(toggleText(name, state[0]));
        }).dimensions(x, y, w, h).build());
    }

    private static Text toggleText(String n, boolean on) { return Text.literal(n + ": " + (on ? "§aON" : "§cOFF")); }
    private static Text missingLabel() { return Text.literal("Only missing: " + (onlyMissing ? "§aON" : "§7OFF")); }
    private static Text startLabel() { return Text.literal(BuildEngine.running ? "§c■ Stop building" : "§a▶ Start building"); }

    @Override public void close() { Config.save(); super.close(); }
    @Override public boolean shouldPause() { return false; }

    // ================================================================ ticking
    @Override
    public void tick() {
        // keep the numbers fresh while the menu is open (only when the schematic is small enough to scan cheaply)
        if (++sinceScan >= 100) {
            sinceScan = 0;
            if (MaterialScanner.volume() <= 200_000) MaterialScanner.scan();
        }
    }

    // ================================================================ drawing
    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        startBtn.setMessage(startLabel());

        // panels first, then widgets on top, then dynamic content
        ctx.fill(px, py, px + pw, py + ph, BG);
        Gfx.border(ctx, px, py, pw, ph, BORDER);
        drawHeader(ctx);
        card(ctx, leftX, py + HEADER_H + 8 + 52, leftW, py + ph - 30 - (py + HEADER_H + 8 + 52));
        card(ctx, cx - 2, cy - 2, cw + 4, ch + 4);

        super.render(ctx, mx, my, delta);

        drawStats(ctx);
        switch (tab) {
            case OVERVIEW -> drawOverview(ctx);
            case MATERIALS -> drawMaterials(ctx, mx, my);
            case SETTINGS -> drawSettingsLabels(ctx);
        }
        drawFooter(ctx);
    }

    private void card(DrawContext ctx, int x, int y, int w, int h) {
        ctx.fill(x, y, x + w, y + h, CARD);
        Gfx.border(ctx, x, y, w, h, BORDER);
    }

    private void drawHeader(DrawContext ctx) {
        ctx.fill(px + 1, py + 1, px + pw - 1, py + HEADER_H, HEADER);
        ctx.fill(px + 1, py + HEADER_H, px + pw - 1, py + HEADER_H + 1, ACCENT);
        ctx.drawTextWithShadow(textRenderer, "§lSchematic Builder", px + 10, py + 9, ACCENT);

        int col = modeColor();
        String label = BuildEngine.running ? "RUNNING · " + BuildEngine.mode.toUpperCase(Locale.ROOT) : "IDLE";
        int w = textRenderer.getWidth(label);
        int rx = px + pw - 12 - w;
        ctx.fill(rx - 12, py + 11, rx - 6, py + 17, col);
        ctx.drawTextWithShadow(textRenderer, label, rx, py + 9, col);
    }

    private int modeColor() {
        if (!BuildEngine.running) return MUTED;
        return switch (BuildEngine.mode) {
            case "Walking", "Arriving" -> BLUE;
            case "Waiting" -> YELLOW;
            case "Idle" -> RED;
            default -> GREEN;
        };
    }

    private void drawStats(DrawContext ctx) {
        int x = leftX + 6;
        int y = py + HEADER_H + 8 + 52 + 6;
        int w = leftW - 12;

        int total = MaterialScanner.totalBlocks, built = MaterialScanner.builtBlocks;
        float pct = total == 0 ? 0f : built / (float) total;

        ctx.drawTextWithShadow(textRenderer, "PROGRESS", x, y, MUTED); y += 11;
        ctx.fill(x, y, x + w, y + 8, TRACK);
        ctx.fill(x, y, x + (int) (w * pct), y + 8, ACCENT);
        Gfx.border(ctx, x, y, w, 8, BORDER);
        y += 12;
        ctx.drawTextWithShadow(textRenderer, String.format(Locale.ROOT, "%,d / %,d  (%.1f%%)", built, total, pct * 100), x, y, TEXT);
        y += 16;

        long ms = BuildEngine.activeMs();
        double perMin = ms > 5000 ? BuildEngine.placedThisSession / (ms / 60000.0) : 0;
        int remaining = Math.max(0, total - built);

        stat(ctx, x, y, w, "Placed", String.valueOf(BuildEngine.placedThisSession)); y += 11;
        stat(ctx, x, y, w, "Speed", perMin > 0 ? String.format(Locale.ROOT, "%.0f /min", perMin) : "--"); y += 11;
        stat(ctx, x, y, w, "Time", fmtTime(ms)); y += 11;
        stat(ctx, x, y, w, "ETA", perMin > 0 && remaining > 0 ? fmtTime((long) (remaining / perMin * 60000)) : "--"); y += 11;
        stat(ctx, x, y, w, "Left", String.format(Locale.ROOT, "%,d", remaining)); y += 11;
        stat(ctx, x, y, w, "Mode", BuildEngine.mode);
    }

    private void stat(DrawContext ctx, int x, int y, int w, String k, String v) {
        ctx.drawTextWithShadow(textRenderer, k, x, y, MUTED);
        ctx.drawTextWithShadow(textRenderer, v, x + w - textRenderer.getWidth(v), y, TEXT);
    }

    private void drawFooter(DrawContext ctx) {
        String hint = "[" + key(SchemBuilderClient.guiKey) + "] menu   [" + key(SchemBuilderClient.toggleKey)
                + "] start/stop   [" + key(SchemBuilderClient.panicKey) + "] emergency stop   (rebind in Controls)";
        ctx.drawTextWithShadow(textRenderer, hint, px + 10, py + ph - 18, MUTED);
    }

    private static String key(net.minecraft.client.option.KeyBinding k) {
        return k == null ? "?" : k.getBoundKeyLocalizedText().getString();
    }

    // ---------------------------------------------------------------- overview
    private void drawOverview(DrawContext ctx) {
        int x = cx + 6, w = cw - 12;
        int y = cy + 5;
        int bottom = cy + ch - 22;
        ctx.enableScissor(cx, cy, cx + cw, bottom);

        ctx.drawTextWithShadow(textRenderer, "STATUS", x, y, MUTED); y += 11;
        y = wrapped(ctx, BuildEngine.status, x, y, w, modeColor() == MUTED ? TEXT : modeColor()) + 6;

        List<LitematicaBridge.Region> regs = LitematicaBridge.regions();
        ctx.drawTextWithShadow(textRenderer, "SCHEMATIC", x, y, MUTED); y += 11;
        if (regs.isEmpty()) {
            y = wrapped(ctx, "None detected yet.", x, y, w, YELLOW) + 4;
            if (LitematicaBridge.lastError != null) y = wrapped(ctx, LitematicaBridge.lastError, x, y, w, RED) + 6;
            ctx.drawTextWithShadow(textRenderer, "HOW TO START", x, y, MUTED); y += 11;
            for (String s : new String[]{
                    "1. Press M > Load Schematics, load your file",
                    "2. Place it in the world (Create placement)",
                    "3. Stand near it, click Refresh materials",
                    "4. Press Start building"})
                y = wrapped(ctx, s, x, y, w, TEXT) + 1;
        } else {
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (LitematicaBridge.Region r : regs) {
                minX = Math.min(minX, r.minX()); minY = Math.min(minY, r.minY()); minZ = Math.min(minZ, r.minZ());
                maxX = Math.max(maxX, r.maxX()); maxY = Math.max(maxY, r.maxY()); maxZ = Math.max(maxZ, r.maxZ());
            }
            String name = regs.size() > 1 ? regs.size() + " placements" : regs.get(0).name();
            y = wrapped(ctx, name, x, y, w, TEXT);
            y = wrapped(ctx, (maxX - minX + 1) + " x " + (maxY - minY + 1) + " x " + (maxZ - minZ + 1) + " blocks   at "
                    + minX + ", " + minY + ", " + minZ, x, y, w, MUTED) + 6;
            if (LitematicaBridge.lastError != null) y = wrapped(ctx, LitematicaBridge.lastError, x, y, w, RED) + 4;

            ctx.drawTextWithShadow(textRenderer, "WHAT IT DOES", x, y, MUTED); y += 11;
            for (String s : new String[]{
                    "Walks to the next spot it can build from",
                    "Crouches when a 3+ block drop is nearby",
                    "Places blocks in reach, bottom layer first"})
                y = wrapped(ctx, "• " + s, x, y, w, TEXT) + 1;
        }
        ctx.disableScissor();
    }

    private int wrapped(DrawContext ctx, String s, int x, int y, int w, int color) {
        List<OrderedText> lines = textRenderer.wrapLines(Text.literal(s), w);
        for (OrderedText l : lines) {
            ctx.drawTextWithShadow(textRenderer, l, x, y, color);
            y += 10;
        }
        return y;
    }

    // ---------------------------------------------------------------- settings
    private void drawSettingsLabels(DrawContext ctx) {
        int colW = (cw - 8) / 2;
        ctx.drawTextWithShadow(textRenderer, "TIMING & REACH", cx + 2, cy + 3, MUTED);
        ctx.drawTextWithShadow(textRenderer, "BEHAVIOUR", cx + colW + 10, cy + 3, MUTED);
    }

    // ---------------------------------------------------------------- materials
    private List<MaterialScanner.Entry> filtered() {
        String f = filter.trim().toLowerCase(Locale.ROOT);
        List<MaterialScanner.Entry> out = new ArrayList<>();
        for (MaterialScanner.Entry e : MaterialScanner.entries) {
            if (onlyMissing && e.missing == 0) continue;
            if (!f.isEmpty() && !e.item.getName().getString().toLowerCase(Locale.ROOT).contains(f)) continue;
            out.add(e);
        }
        return out;
    }

    private int listTop() { return cy + 22; }
    private int listBottom() { return cy + ch; }

    private void drawMaterials(DrawContext ctx, int mx, int my) {
        List<MaterialScanner.Entry> list = filtered();
        int top = listTop(), bottom = listBottom();
        int maxScroll = Math.max(0, list.size() * ROW_H - (bottom - top));
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        if (filter.isEmpty()) ctx.drawTextWithShadow(textRenderer, "Search blocks...", cx + 5, cy + 4, 0xFF5C6773);

        if (list.isEmpty()) {
            int mid = (cx + cx + cw) / 2;
            String a = MaterialScanner.entries.isEmpty() ? "No schematic detected." : "Nothing matches the filter.";
            ctx.drawCenteredTextWithShadow(textRenderer, a, mid, top + 20, MUTED);
            if (MaterialScanner.entries.isEmpty())
                ctx.drawCenteredTextWithShadow(textRenderer, "Place one in Litematica, then press Refresh.", mid, top + 32, MUTED);
            return;
        }

        boolean creative = client != null && client.player != null && client.player.isCreative() && Config.I.creativeFill;
        boolean wide = cw >= 330;
        int rightEdge = cx + cw - 10;

        ctx.enableScissor(cx, top, cx + cw, bottom);
        int y = top - scroll;
        int rowIdx = 0;
        for (MaterialScanner.Entry e : list) {
            if (y + ROW_H >= top && y <= bottom) {
                if ((rowIdx & 1) == 0) ctx.fill(cx + 1, y, cx + cw - 1, y + ROW_H, 0x18FFFFFF);
                int have = client == null || client.player == null ? 0 : client.player.getInventory().count(e.item);
                boolean done = e.missing == 0;
                int color = done ? GREEN : (creative || have >= e.missing) ? YELLOW : RED;

                ctx.drawItem(new ItemStack(e.item), cx + 3, y + 2);

                String right = done ? "done"
                        : "need " + fmtQty(e.missing) + "  have " + (creative ? "∞" : String.valueOf(have));
                int rw = textRenderer.getWidth(right);
                ctx.drawTextWithShadow(textRenderer, right, rightEdge - rw, y + 6, color);

                int nameRight = rightEdge - rw - 6;
                if (wide) {
                    int bw = 40, bx = nameRight - bw;
                    float p = e.total == 0 ? 0 : (e.total - e.missing) / (float) e.total;
                    ctx.fill(bx, y + 8, bx + bw, y + 12, TRACK);
                    ctx.fill(bx, y + 8, bx + (int) (bw * p), y + 12, done ? GREEN : ACCENT);
                    nameRight = bx - 6;
                }
                String name = textRenderer.trimToWidth(e.item.getName().getString(), Math.max(20, nameRight - (cx + 24)));
                ctx.drawTextWithShadow(textRenderer, name, cx + 24, y + 6, TEXT);
            }
            y += ROW_H;
            rowIdx++;
        }
        ctx.disableScissor();

        // scrollbar
        if (maxScroll > 0) {
            int trackH = bottom - top;
            int thumbH = Math.max(14, trackH * trackH / (trackH + maxScroll));
            int thumbY = top + (int) ((trackH - thumbH) * (scroll / (float) maxScroll));
            ctx.fill(cx + cw - 5, top, cx + cw - 2, bottom, TRACK);
            ctx.fill(cx + cw - 5, thumbY, cx + cw - 2, thumbY + thumbH, ACCENT);
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        if (tab == Tab.MATERIALS) scroll -= (int) (vertical * ROW_H);
        return true;
    }

    // ================================================================ formatting
    private static String fmtQty(int n) {
        return n >= 64 ? n + " (" + (n / 64) + "s+" + (n % 64) + ")" : String.valueOf(n);
    }

    private static String fmtTime(long ms) {
        long s = ms / 1000, h = s / 3600, m = (s % 3600) / 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s % 60) : String.format(Locale.ROOT, "%02d:%02d", m, s % 60);
    }

    // ================================================================ slider
    private static class IntSlider extends SliderWidget {
        private final String label; private final int min, max;
        private final IntConsumer onChange; private final IntFunction<String> fmt;

        IntSlider(int x, int y, int w, int h, String label, int min, int max, int cur, IntConsumer onChange, IntFunction<String> fmt) {
            super(x, y, w, h, Text.empty(), (cur - min) / (double) (max - min));
            this.label = label; this.min = min; this.max = max; this.onChange = onChange; this.fmt = fmt;
            updateMessage();
        }
        private int val() { return min + (int) Math.round(value * (max - min)); }
        @Override protected void updateMessage() { setMessage(Text.literal(label + ": " + fmt.apply(val()))); }
        @Override protected void applyValue() { onChange.accept(val()); }
    }
}
