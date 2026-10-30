package com.example.schembuilder;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

public class BuilderScreen extends Screen {
    private static final int PANEL_W = 160;
    private static final int ROW_H = 20;

    // GUI colours need an explicit alpha byte since 1.21.6 (0xFFRRGGBB).
    private static final int WHITE = 0xFFFFFFFF, GREY = 0xFFAAAAAA;
    private static final int GREEN = 0xFF55FF55, YELLOW = 0xFFFFFF55, RED = 0xFFFF5555;

    private ButtonWidget startBtn;
    private int scroll = 0;
    private int listTop, listBottom, listLeft, listRight;

    public BuilderScreen() { super(Text.literal("Schematic Builder")); }

    // ------------------------------------------------------------------ setup
    @Override
    protected void init() {
        int x = 10, y = 28, w = PANEL_W, h = 20, gap = 4;

        startBtn = addDrawableChild(ButtonWidget.builder(startLabel(), b -> {
            BuildEngine.toggle();
            b.setMessage(startLabel());
        }).dimensions(x, y, w, h).build());
        y += h + gap;

        addDrawableChild(ButtonWidget.builder(Text.literal("Refresh materials"), b -> MaterialScanner.scan())
                .dimensions(x, y, w, h).build());
        y += h + gap + 4;

        addToggle(x, y, w, h, "Layer by layer", Config.I.layerByLayer, v -> Config.I.layerByLayer = v); y += h + gap;
        addToggle(x, y, w, h, "Creative auto-give", Config.I.creativeFill, v -> Config.I.creativeFill = v); y += h + gap;
        addToggle(x, y, w, h, "Line-of-sight check", Config.I.lineOfSight, v -> Config.I.lineOfSight = v); y += h + gap + 4;

        addDrawableChild(new IntSlider(x, y, w, h, "Min delay", 0, 20, Config.I.minDelay, v -> Config.I.minDelay = v, v -> v + "t")); y += h + gap;
        addDrawableChild(new IntSlider(x, y, w, h, "Max delay", 1, 40, Config.I.maxDelay, v -> Config.I.maxDelay = v, v -> v + "t")); y += h + gap;
        addDrawableChild(new IntSlider(x, y, w, h, "Reach", 20, 60, Config.I.reachTenths, v -> Config.I.reachTenths = v, v -> (v / 10.0) + "")); y += h + gap;
        addDrawableChild(new IntSlider(x, y, w, h, "Turn speed", 3, 60, Config.I.turnSpeed, v -> Config.I.turnSpeed = v, v -> v + "°/t"));

        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
                .dimensions(x, height - 28, w, h).build());

        listLeft = x + w + 12; listRight = width - 10;
        listTop = 48; listBottom = height - 34;
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
    private static Text startLabel() { return Text.literal(BuildEngine.running ? "§c■ Stop building" : "§a▶ Start building"); }

    @Override
    public void close() { Config.save(); super.close(); }

    // ----------------------------------------------------------------- render
    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        startBtn.setMessage(startLabel());

        // Since 1.21.6, Screen.render() draws the background (blur) itself - and it may only
        // happen once per frame. So: call super first, then draw our own content on top.
        super.render(ctx, mx, my, delta);

        ctx.drawCenteredTextWithShadow(textRenderer, "§6§lSchematic Builder", width / 2, 10, WHITE);

        // --- status block under the controls
        int sy = height - 70;
        ctx.drawTextWithShadow(textRenderer, "Schematic: §f" + trim(LitematicaBridge.placementName(), 16), 10, sy, GREY);
        ctx.drawTextWithShadow(textRenderer, "Status: §f" + trim(BuildEngine.status, 22), 10, sy + 11, GREY);
        ctx.drawTextWithShadow(textRenderer, "Placed this run: §f" + BuildEngine.placedThisSession, 10, sy + 22, GREY);

        // --- progress bar
        int total = MaterialScanner.totalBlocks, built = MaterialScanner.builtBlocks;
        float pct = total == 0 ? 0 : built / (float) total;
        ctx.drawTextWithShadow(textRenderer, "Progress: §f" + built + " / " + total + String.format(" (%.1f%%)", pct * 100), listLeft, 24, GREY);
        int barW = listRight - listLeft;
        ctx.fill(listLeft, 36, listLeft + barW, 42, 0xFF303030);
        ctx.fill(listLeft, 36, listLeft + (int) (barW * pct), 42, 0xFF3CC84B);

        // --- keybind hint
        String hint = "§7Keys: §f" + SchemBuilderClient.guiKey.getBoundKeyLocalizedText().getString() + " §7menu  §f"
                + SchemBuilderClient.toggleKey.getBoundKeyLocalizedText().getString() + " §7start/stop  §f"
                + SchemBuilderClient.panicKey.getBoundKeyLocalizedText().getString() + " §7emergency stop  (rebind in Controls)";
        ctx.drawTextWithShadow(textRenderer, hint, listLeft, height - 22, WHITE);

        // --- material list
        ctx.fill(listLeft - 2, listTop - 2, listRight + 2, listBottom + 2, 0x90000000);
        List<MaterialScanner.Entry> list = MaterialScanner.entries;
        int maxScroll = Math.max(0, list.size() * ROW_H - (listBottom - listTop));
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        if (list.isEmpty()) {
            int cx = (listLeft + listRight) / 2;
            ctx.drawCenteredTextWithShadow(textRenderer, "§7No schematic detected. Load & place one in Litematica,", cx, listTop + 20, WHITE);
            ctx.drawCenteredTextWithShadow(textRenderer, "§7then press 'Refresh materials'.", cx, listTop + 32, WHITE);
            if (LitematicaBridge.lastError != null)
                ctx.drawCenteredTextWithShadow(textRenderer, "§c" + trim(LitematicaBridge.lastError, 70), cx, listTop + 50, WHITE);
        }

        ctx.enableScissor(listLeft, listTop, listRight, listBottom);
        int y = listTop - scroll;
        for (MaterialScanner.Entry e : list) {
            if (y + ROW_H >= listTop && y <= listBottom) {
                int have = client.player == null ? 0 : client.player.getInventory().count(e.item);
                boolean creative = client.player != null && client.player.isCreative() && Config.I.creativeFill;
                int color = e.missing == 0 ? GREEN : (creative || have >= e.missing) ? YELLOW : RED;

                ctx.drawItem(new ItemStack(e.item), listLeft + 3, y + 2);
                ctx.drawTextWithShadow(textRenderer, trim(e.item.getName().getString(), 28), listLeft + 24, y + 6, WHITE);

                String right = e.missing == 0 ? "done"
                        : "need " + e.missing + "/" + e.total + "  have " + (creative ? "∞" : String.valueOf(have));
                ctx.drawTextWithShadow(textRenderer, right, listRight - 6 - textRenderer.getWidth(right), y + 6, color);
            }
            y += ROW_H;
        }
        ctx.disableScissor();
    }

    private String trim(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 1) + "…"; }

    // 1.20.2+: separate horizontal / vertical scroll amounts.
    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        scroll -= (int) (vertical * ROW_H);
        return true;
    }

    @Override
    public boolean shouldPause() { return false; }

    // ----------------------------------------------------------------- slider
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
