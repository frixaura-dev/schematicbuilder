package com.example.schembuilder;

import net.minecraft.client.gui.DrawContext;

/** Small drawing helpers (DrawContext.drawBorder no longer exists in 1.21.11). */
final class Gfx {
    private Gfx() {}

    /** 1px outline of the rectangle (x, y, w, h), drawn with four filled strips. */
    static void border(DrawContext ctx, int x, int y, int w, int h, int color) {
        ctx.fill(x, y, x + w, y + 1, color);               // top
        ctx.fill(x, y + h - 1, x + w, y + h, color);       // bottom
        ctx.fill(x, y + 1, x + 1, y + h - 1, color);       // left
        ctx.fill(x + w - 1, y + 1, x + w, y + h - 1, color); // right
    }
}
