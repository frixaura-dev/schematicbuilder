package dev.schembuilder.ui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.MathHelper;

/** Drawing helpers: rounded rects, soft shadows, gradients, fading text. All colours ARGB. */
public final class Draw {
	private Draw() {}

	/** Global opacity multiplier, used for fade in/out animations. */
	public static float alpha = 1f;

	public static int fade(int argb) {
		int a = Math.round(((argb >>> 24) & 0xFF) * MathHelper.clamp(alpha, 0f, 1f));
		return (a << 24) | (argb & 0xFFFFFF);
	}

	public static int withAlpha(int argb, float a) {
		int na = Math.round(MathHelper.clamp(a, 0f, 1f) * 255f);
		return (na << 24) | (argb & 0xFFFFFF);
	}

	public static int mulAlpha(int argb, float m) {
		int a = Math.round(((argb >>> 24) & 0xFF) * MathHelper.clamp(m, 0f, 1f));
		return (a << 24) | (argb & 0xFFFFFF);
	}

	public static int lerp(int c1, int c2, float t) {
		t = MathHelper.clamp(t, 0f, 1f);
		int a = (int) MathHelper.lerp(t, (c1 >>> 24) & 0xFF, (c2 >>> 24) & 0xFF);
		int r = (int) MathHelper.lerp(t, (c1 >> 16) & 0xFF, (c2 >> 16) & 0xFF);
		int g = (int) MathHelper.lerp(t, (c1 >> 8) & 0xFF, (c2 >> 8) & 0xFF);
		int b = (int) MathHelper.lerp(t, c1 & 0xFF, c2 & 0xFF);
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	public static void rect(DrawContext d, int x, int y, int w, int h, int color) {
		if (w <= 0 || h <= 0) return;
		int c = fade(color);
		if ((c >>> 24) == 0) return;
		d.fill(x, y, x + w, y + h, c);
	}

	public static void round(DrawContext d, int x, int y, int w, int h, int r, int color) {
		if (w <= 0 || h <= 0) return;
		r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
		int c = fade(color);
		if ((c >>> 24) == 0) return;
		d.fill(x, y + r, x + w, y + h - r, c);
		for (int i = 0; i < r; i++) {
			double dy = r - i - 0.5;
			int inset = (int) Math.round(r - Math.sqrt(Math.max(0, r * r - dy * dy)));
			d.fill(x + inset, y + i, x + w - inset, y + i + 1, c);
			d.fill(x + inset, y + h - i - 1, x + w - inset, y + h - i, c);
		}
	}

	/** Rounded card with a 1px border. */
	public static void card(DrawContext d, int x, int y, int w, int h, int r, int fill, int border) {
		round(d, x, y, w, h, r, border);
		round(d, x + 1, y + 1, w - 2, h - 2, Math.max(0, r - 1), fill);
	}

	/** Soft drop shadow made of stacked translucent rounded rects. */
	public static void shadow(DrawContext d, int x, int y, int w, int h, int r, float strength) {
		for (int i = 8; i >= 1; i--) {
			float a = strength * 0.11f * (1f - i / 9f);
			round(d, x - i, y - i + 4, w + i * 2, h + i * 2, r + i, withAlpha(0x000000, a));
		}
	}

	/** Horizontal gradient inside a rounded rect. */
	public static void roundGradient(DrawContext d, int x, int y, int w, int h, int r, int c1, int c2) {
		if (w <= 0 || h <= 0) return;
		r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
		for (int i = 0; i < w; i++) {
			int inset = 0;
			if (i < r) {
				double dx = r - i - 0.5;
				inset = (int) Math.round(r - Math.sqrt(Math.max(0, r * r - dx * dx)));
			} else if (i >= w - r) {
				double dx = i - (w - r) + 0.5;
				inset = (int) Math.round(r - Math.sqrt(Math.max(0, r * r - dx * dx)));
			}
			int c = fade(lerp(c1, c2, w <= 1 ? 0 : i / (float) (w - 1)));
			if ((c >>> 24) == 0) continue;
			d.fill(x + i, y + inset, x + i + 1, y + h - inset, c);
		}
	}

	public static void text(DrawContext d, TextRenderer tr, String s, int x, int y, int color) {
		int c = fade(color);
		if ((c >>> 24) < 6) return;
		d.drawText(tr, s, x, y, c, false);
	}

	public static void textShadow(DrawContext d, TextRenderer tr, String s, int x, int y, int color) {
		int c = fade(color);
		if ((c >>> 24) < 6) return;
		d.drawText(tr, s, x, y, c, true);
	}

	public static void textCentered(DrawContext d, TextRenderer tr, String s, int cx, int y, int color) {
		text(d, tr, s, cx - tr.getWidth(s) / 2, y, color);
	}

	public static void textRight(DrawContext d, TextRenderer tr, String s, int right, int y, int color) {
		text(d, tr, s, right - tr.getWidth(s), y, color);
	}

	public static void textScaled(DrawContext d, TextRenderer tr, String s, int x, int y, float scale, int color) {
		d.getMatrices().push();
		d.getMatrices().translate(x, y, 0);
		d.getMatrices().scale(scale, scale, 1f);
		text(d, tr, s, 0, 0, color);
		d.getMatrices().pop();
	}

	public static String ellipsize(TextRenderer tr, String s, int maxWidth) {
		if (tr.getWidth(s) <= maxWidth) return s;
		String dots = "…";
		return tr.trimToWidth(s, Math.max(0, maxWidth - tr.getWidth(dots))) + dots;
	}

	/** Eight-dot loading spinner. */
	public static void spinner(DrawContext d, int cx, int cy, float radius, float time, int color) {
		for (int i = 0; i < 8; i++) {
			double ang = (i / 8.0) * Math.PI * 2;
			float phase = (float) ((time * 1.4 - i / 8.0) % 1.0);
			if (phase < 0) phase += 1;
			float a = 0.15f + 0.85f * (1f - phase);
			int px = cx + (int) Math.round(Math.cos(ang) * radius);
			int py = cy + (int) Math.round(Math.sin(ang) * radius);
			round(d, px - 2, py - 2, 4, 4, 2, mulAlpha(color, a));
		}
	}

	/** Exponential smoothing toward a target, frame-rate independent. */
	public static float approach(float current, float target, float speed, float dt) {
		return current + (target - current) * (1f - (float) Math.exp(-speed * dt));
	}

	public static float easeOutCubic(float t) {
		t = MathHelper.clamp(t, 0f, 1f);
		float u = 1f - t;
		return 1f - u * u * u;
	}

	public static float easeOutBack(float t) {
		t = MathHelper.clamp(t, 0f, 1f);
		float c1 = 1.70158f, c3 = c1 + 1f;
		return 1f + c3 * (float) Math.pow(t - 1, 3) + c1 * (float) Math.pow(t - 1, 2);
	}
}
