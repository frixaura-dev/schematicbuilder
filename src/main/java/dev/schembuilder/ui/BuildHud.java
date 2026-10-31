package dev.schembuilder.ui;

import dev.schembuilder.SchemBuilderClient;
import dev.schembuilder.build.Builder;
import dev.schembuilder.build.Target;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;

/** Progress card in the top-right while a build is running or paused. */
public final class BuildHud {
	private BuildHud() {}

	private static float slide = 0f, shownProgress = 0f;
	private static long lastNs = System.nanoTime();
	private static long doneAt = 0;

	public static void render(DrawContext d) {
		long now = System.nanoTime();
		float dt = Math.min(0.1f, (now - lastNs) / 1e9f);
		lastNs = now;

		MinecraftClient mc = MinecraftClient.getInstance();
		Builder b = SchemBuilderClient.BUILDER;
		if (b.status() == Builder.Status.DONE && doneAt == 0) doneAt = System.currentTimeMillis();
		if (b.status() != Builder.Status.DONE) doneAt = 0;
		boolean show = !mc.options.hudHidden && (b.active() || (doneAt > 0 && System.currentTimeMillis() - doneAt < 5000));
		slide = Draw.approach(slide, show ? 1f : 0f, 10f, dt);
		if (slide < 0.01f) return;
		shownProgress = Draw.approach(shownProgress, b.progress(), 6f, dt);

		TextRenderer tr = mc.textRenderer;
		int sw = d.getScaledWindowWidth();
		int w = 180, h = 62;
		float e = Draw.easeOutCubic(slide);
		int x = (int) (sw - 8 - w + (1f - e) * (w + 16));
		int y = 8;
		float prev = Draw.alpha;
		Draw.alpha = e;

		Draw.shadow(d, x, y, w, h, 8, 1f);
		Draw.card(d, x, y, w, h, 8, Theme.PANEL, Theme.BORDER);
		Draw.roundGradient(d, x + 1, y + 1, w - 2, 2, 1, Theme.ACCENT, Theme.ACCENT_2);

		String state;
		int stateColor;
		switch (b.status()) {
			case RUNNING -> { state = b.phase().label.toUpperCase(); stateColor = Theme.GOOD; }
			case PAUSED -> { state = "PAUSED"; stateColor = Theme.WARN; }
			case DONE -> { state = "DONE"; stateColor = Theme.ACCENT; }
			default -> { state = "STOPPED"; stateColor = Theme.MUTED; }
		}
		String name = b.placement() != null ? b.placement().schematic.name : "";
		Draw.text(d, tr, Draw.ellipsize(tr, name, w - 70), x + 9, y + 8, Theme.TEXT);
		int pillW = (int) (tr.getWidth(state) * 0.75f) + 14;
		Draw.round(d, x + w - pillW - 7, y + 6, pillW, 11, 5, Draw.withAlpha(stateColor, 0.16f));
		float pulse = b.status() == Builder.Status.RUNNING ? (float) (0.6 + 0.4 * Math.sin(System.currentTimeMillis() / 220.0)) : 1f;
		Draw.round(d, x + w - pillW - 3, y + 10, 4, 4, 2, Draw.mulAlpha(stateColor, pulse));
		Draw.textScaled(d, tr, state, x + w - pillW + 3, y + 9, 0.75f, stateColor);

		int barY = y + 22;
		Draw.round(d, x + 9, barY, w - 18, 5, 2, 0xFF232835);
		int fill = Math.max(5, (int) ((w - 18) * shownProgress));
		Draw.roundGradient(d, x + 9, barY, fill, 5, 2, Theme.ACCENT, Theme.ACCENT_2);

		String count = String.format("%,d / %,d", b.done(), b.total());
		Draw.text(d, tr, count, x + 9, barY + 9, Theme.MUTED);
		int eta = b.etaSeconds();
		String etaText = eta < 0 ? Math.round(b.progress() * 100) + "%" : "~" + formatTime(eta);
		Draw.textRight(d, tr, etaText, x + w - 9, barY + 9, Theme.MUTED);

		Target cur = b.current();
		if (cur != null && !cur.clear) {
			d.getMatrices().push();
			d.getMatrices().translate(x + 9, y + 44, 0);
			d.getMatrices().scale(0.75f, 0.75f, 1f);
			d.drawItem(new ItemStack(cur.item), 0, 0);
			d.getMatrices().pop();
			Draw.text(d, tr, Draw.ellipsize(tr, b.detail(), w - 34), x + 24, y + 47, Theme.TEXT);
		} else if (b.status() == Builder.Status.PAUSED) {
			String key = SchemBuilderClient.pauseKey().getBoundKeyLocalizedText().getString();
			Draw.text(d, tr, "Press " + key + " to resume", x + 9, y + 47, Theme.FAINT);
		} else {
			Draw.text(d, tr, Draw.ellipsize(tr, b.detail(), w - 18), x + 9, y + 47, Theme.FAINT);
		}
		Draw.alpha = prev;
	}

	public static String formatTime(int seconds) {
		if (seconds >= 3600) return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
		if (seconds >= 60) return (seconds / 60) + "m " + (seconds % 60) + "s";
		return seconds + "s";
	}
}
