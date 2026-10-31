package dev.schembuilder.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Small slide-in notifications in the bottom-right corner. */
public final class Toasts {
	private Toasts() {}

	public enum Kind {
		INFO(Theme.ACCENT_2, "i"), SUCCESS(Theme.GOOD, "✔"), WARN(Theme.WARN, "!"), ERROR(Theme.BAD, "×");
		final int color;
		final String icon;

		Kind(int color, String icon) {
			this.color = color;
			this.icon = icon;
		}
	}

	private static final class Toast {
		final String title, body;
		final Kind kind;
		final long born = System.currentTimeMillis();
		final long life;
		float slide = 0f, y = -1;

		Toast(String title, String body, Kind kind, long life) {
			this.title = title;
			this.body = body;
			this.kind = kind;
			this.life = life;
		}
	}

	private static final List<Toast> TOASTS = new ArrayList<>();
	private static long lastFrame = System.nanoTime();

	public static void show(String title, String body, Kind kind) {
		MinecraftClient.getInstance().execute(() -> {
			TOASTS.add(new Toast(title, body, kind, 4200));
			if (TOASTS.size() > 4) TOASTS.remove(0);
			switch (kind) {
				case ERROR -> UiSound.error();
				case SUCCESS -> UiSound.done();
				case WARN -> UiSound.tick();
				default -> {}
			}
		});
	}

	public static void render(DrawContext d, int screenW, int screenH) {
		long nowNs = System.nanoTime();
		float dt = Math.min(0.1f, (nowNs - lastFrame) / 1e9f);
		lastFrame = nowNs;
		if (TOASTS.isEmpty()) return;

		TextRenderer tr = MinecraftClient.getInstance().textRenderer;
		long now = System.currentTimeMillis();
		float prevAlpha = Draw.alpha;
		Draw.alpha = 1f;

		int w = 190, h = 34, gap = 6;
		float targetY = screenH - 10;
		for (int i = TOASTS.size() - 1; i >= 0; i--) {
			Toast t = TOASTS.get(i);
			boolean leaving = now - t.born > t.life;
			t.slide = Draw.approach(t.slide, leaving ? 0f : 1f, leaving ? 10f : 12f, dt);
			targetY -= (h + gap) * Math.max(0.2f, t.slide);
			if (t.y < 0) t.y = targetY;
			t.y = Draw.approach(t.y, targetY, 14f, dt);

			float e = Draw.easeOutCubic(t.slide);
			int x = (int) (screenW - 10 - w * e);
			int y = (int) t.y;
			Draw.alpha = e;
			Draw.shadow(d, x, y, w, h, 7, 1f);
			Draw.card(d, x, y, w, h, 7, Theme.CARD, Theme.BORDER);
			Draw.round(d, x + 1, y + 1, 3, h - 2, 1, t.kind.color);
			Draw.round(d, x + 10, y + 9, 16, 16, 8, Draw.withAlpha(t.kind.color, 0.18f));
			Draw.textCentered(d, tr, t.kind.icon, x + 18, y + 13, t.kind.color);
			Draw.text(d, tr, Draw.ellipsize(tr, t.title, w - 42), x + 33, y + 7, Theme.TEXT);
			Draw.text(d, tr, Draw.ellipsize(tr, t.body, w - 42), x + 33, y + 19, Theme.MUTED);
			// life bar
			float lifeLeft = 1f - Math.min(1f, (now - t.born) / (float) t.life);
			Draw.rect(d, x + 4, y + h - 2, (int) ((w - 8) * lifeLeft), 1, Draw.withAlpha(t.kind.color, 0.5f));
		}
		Draw.alpha = prevAlpha;

		Iterator<Toast> it = TOASTS.iterator();
		while (it.hasNext()) {
			Toast t = it.next();
			if (now - t.born > t.life && t.slide < 0.02f) it.remove();
		}
	}
}
