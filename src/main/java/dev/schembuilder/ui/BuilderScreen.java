package dev.schembuilder.ui;

import dev.schembuilder.SchemBuilderClient;
import dev.schembuilder.build.Builder;
import dev.schembuilder.config.SBConfig;
import dev.schembuilder.schematic.Placement;
import dev.schembuilder.schematic.Schematic;
import dev.schembuilder.schematic.SchematicBlock;
import dev.schembuilder.schematic.SchematicLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.enums.SlabType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The main interface. Drawn immediate-mode each frame: every interactive element registers a
 * hit box while drawing, and input handlers look those up. Hover, toggle and slider states are
 * eased per element for smooth animation.
 */
public class BuilderScreen extends Screen {
	private record Hit(String id, int x, int y, int w, int h, Runnable action, SliderSpec slider) {
		boolean contains(double mx, double my) {
			return mx >= x && my >= y && mx < x + w && my < y + h;
		}
	}

	private record SliderSpec(int x, int w, float min, float max, Consumer<Float> set) {
		void apply(double mx) {
			float t = MathHelper.clamp((float) ((mx - x) / w), 0f, 1f);
			set.accept(min + (max - min) * t);
		}
	}

	private record Material(Item item, String name, int need, int have) {}

	private static final int PRIMARY = 0, SECONDARY = 1, DANGER = 2;
	private static final Set<String> COUNT_PROPS = Set.of("layers", "candles", "pickles", "eggs", "flower_amount");

	private final MinecraftClient mc = MinecraftClient.getInstance();
	private final Map<String, Float> anims = new HashMap<>();
	private final List<Hit> hits = new ArrayList<>();
	private final Map<Path, Long> sizes = new HashMap<>();

	private long lastNs = System.nanoTime();
	private float dt, time, open;
	private boolean closing;
	private float panelScale = 1f;
	private int pcx, pcy;

	private List<Path> files = new ArrayList<>();
	private String search = "";
	private boolean searchFocused;
	private float fileScroll, fileScrollT, matScroll, matScrollT, setScroll, setScrollT;
	private int[] fileRegion = new int[4], matRegion = new int[4], setRegion = new int[4];
	private int[] clip;

	private String hovered, prevHovered, dragging;
	private Path loadingPath;
	private Schematic matsFor;
	private Map<Item, Integer> needs = new LinkedHashMap<>();
	private List<Material> materials = List.of();
	private long matsAt;

	public BuilderScreen() {
		super(Text.literal("SchemBuilder"));
	}

	@Override
	protected void init() {
		refreshFiles();
	}

	private void refreshFiles() {
		files = SchematicLoader.list();
		sizes.clear();
		for (Path p : files) {
			try {
				sizes.put(p, Files.size(p));
			} catch (Exception ignored) {
			}
		}
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	@Override
	public void close() {
		if (!closing) {
			closing = true;
			UiSound.close();
			SBConfig.get().save();
		}
	}

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
		// Custom dim drawn in render() so it fades with the panel.
	}

	// ======================================================================= render

	@Override
	public void render(DrawContext d, int mouseX, int mouseY, float delta) {
		long now = System.nanoTime();
		dt = Math.min(0.1f, (now - lastNs) / 1e9f);
		lastNs = now;
		time += dt;
		if (open == 0f && !closing) UiSound.open();
		open = Draw.approach(open, closing ? 0f : 1f, closing ? 18f : 10f, dt);
		if (closing && open < 0.03f) {
			Draw.alpha = 1f;
			mc.setScreen(null);
			return;
		}

		float e = Draw.easeOutCubic(open);
		Draw.alpha = e;
		d.fillGradient(0, 0, width, height, Draw.fade(0xA0040508), Draw.fade(0xD006080C));

		int pw = Math.min(width - 24, 680);
		int ph = Math.min(height - 24, 400);
		int px = (width - pw) / 2;
		int py = (height - ph) / 2;
		pcx = width / 2;
		pcy = height / 2;
		panelScale = 0.93f + 0.07f * (closing ? e : Draw.easeOutBack(open));

		// Mouse in panel space (undo the open/close zoom).
		double mx = pcx + (mouseX - pcx) / panelScale;
		double my = pcy + (mouseY - pcy) / panelScale + (1f - e) * 8f;

		d.getMatrices().push();
		d.getMatrices().translate(pcx, pcy - (1f - e) * 8f, 0);
		d.getMatrices().scale(panelScale, panelScale, 1f);
		d.getMatrices().translate(-pcx, -pcy, 0);

		hits.clear();
		prevHovered = hovered;
		hovered = null;
		this.mouseXf = mx;
		this.mouseYf = my;

		Draw.shadow(d, px, py, pw, ph, 12, 1.6f);
		Draw.card(d, px, py, pw, ph, 12, Theme.PANEL, Theme.BORDER);
		Draw.roundGradient(d, px + 12, py + 1, pw - 24, 2, 1, Theme.ACCENT, Theme.ACCENT_2);

		drawHeader(d, px, py, pw);

		int top = py + 40;
		int footerH = 44;
		int bottom = py + ph - footerH - 4;
		int ch = bottom - top;
		int leftW = pw < 600 ? 160 : 196;
		int rightW = pw < 600 ? 176 : 206;
		int lx = px + 10;
		int rx = px + pw - 10 - rightW;
		int mxCol = lx + leftW + 8;
		int midW = rx - 8 - mxCol;

		drawFiles(d, lx, top, leftW, ch);
		drawDetails(d, mxCol, top, midW, ch);
		drawSettings(d, rx, top, rightW, ch);
		drawFooter(d, px, py + ph - footerH, pw, footerH);

		d.getMatrices().pop();

		if (hovered != null && !hovered.equals(prevHovered) && open > 0.9f
			&& (hovered.startsWith("btn") || hovered.startsWith("file") || hovered.startsWith("tog"))) {
			UiSound.hover();
		}

		Draw.alpha = 1f;
		Toasts.render(d, width, height);
	}

	private double mouseXf, mouseYf;

	// ---------------------------------------------------------------- header

	private void drawHeader(DrawContext d, int px, int py, int pw) {
		TextRenderer tr = textRenderer;
		// Logo: gradient tile with a 3x3 block grid.
		int lx = px + 14, ly = py + 11;
		Draw.roundGradient(d, lx, ly, 18, 18, 5, Theme.ACCENT, Theme.ACCENT_2);
		for (int i = 0; i < 3; i++) {
			for (int j = 0; j < 3; j++) {
				float a = (i + j) % 2 == 0 ? 0.95f : 0.55f;
				Draw.rect(d, lx + 4 + i * 4, ly + 4 + j * 4, 3, 3, Draw.withAlpha(0x0B0D12, a));
			}
		}
		Draw.textScaled(d, tr, "SchemBuilder", lx + 26, ly + 1, 1.25f, Theme.TEXT);
		int tw = (int) (tr.getWidth("SchemBuilder") * 1.25f);
		Draw.text(d, tr, "schematic printer", lx + 26 + tw + 7, ly + 4, Theme.FAINT);

		// Close button
		int cx = px + pw - 32, cy = py + 10;
		boolean over = hit("btn_close", cx, cy, 20, 20, this::close);
		float hv = anim("btn_close", over ? 1 : 0, 18);
		Draw.round(d, cx, cy, 20, 20, 6, Draw.withAlpha(Theme.BAD, 0.18f * hv));
		Draw.textCentered(d, tr, "×", cx + 10, cy + 6, Draw.lerp(Theme.MUTED, Theme.BAD, hv));

		// Status pill
		Builder b = SchemBuilderClient.BUILDER;
		String label;
		int color;
		switch (b.status()) {
			case RUNNING -> { label = "Building " + Math.round(b.progress() * 100) + "%"; color = Theme.GOOD; }
			case PAUSED -> { label = "Paused"; color = Theme.WARN; }
			case DONE -> { label = "Finished"; color = Theme.ACCENT; }
			default -> { label = SchemBuilderClient.placement != null ? "Ready" : "Idle"; color = Theme.MUTED; }
		}
		int pw2 = tr.getWidth(label) + 22;
		int sx = cx - 8 - pw2;
		Draw.round(d, sx, cy + 2, pw2, 16, 8, Draw.withAlpha(color, 0.13f));
		float pulse = b.status() == Builder.Status.RUNNING ? (float) (0.55 + 0.45 * Math.sin(time * 5)) : 1f;
		Draw.round(d, sx + 7, cy + 8, 4, 4, 2, Draw.mulAlpha(color, pulse));
		Draw.text(d, tr, label, sx + 15, cy + 6, color);
	}

	// ---------------------------------------------------------------- file list

	private void drawFiles(DrawContext d, int x, int y, int w, int h) {
		TextRenderer tr = textRenderer;
		Draw.card(d, x, y, w, h, 9, Theme.CARD, Theme.BORDER);
		Draw.textScaled(d, tr, "SCHEMATICS", x + 10, y + 9, 0.75f, Theme.FAINT);
		List<Path> list = filtered();
		String count = String.valueOf(list.size());
		int bw = tr.getWidth(count) + 8;
		Draw.round(d, x + w - 10 - bw, y + 6, bw, 11, 5, 0xFF1F2430);
		Draw.textCentered(d, tr, count, x + w - 10 - bw / 2, y + 8, Theme.MUTED);

		// Search box
		int sy = y + 22, sh = 18;
		boolean over = hit("search", x + 8, sy, w - 16, sh, () -> searchFocused = true);
		float f = anim("searchFocus", searchFocused ? 1 : 0, 14);
		float hv = anim("search", over ? 1 : 0, 18);
		Draw.card(d, x + 8, sy, w - 16, sh, 6, Theme.CARD_HI,
			Draw.lerp(Draw.lerp(Theme.BORDER, 0xFF343B4A, hv), Theme.ACCENT, f));
		Draw.text(d, tr, "⌕", x + 14, sy + 5, Draw.lerp(Theme.FAINT, Theme.ACCENT, f));
		if (search.isEmpty() && !searchFocused) {
			Draw.text(d, tr, "Search…", x + 25, sy + 5, Theme.FAINT);
		} else {
			String shown = search;
			while (tr.getWidth(shown) > w - 44 && !shown.isEmpty()) shown = shown.substring(1);
			Draw.text(d, tr, shown, x + 25, sy + 5, Theme.TEXT);
			if (searchFocused && (time % 1.0f) < 0.55f) {
				Draw.rect(d, x + 25 + tr.getWidth(shown) + 1, sy + 4, 1, 10, Theme.ACCENT);
			}
		}

		// List
		int ly = sy + sh + 6;
		int lh = h - (ly - y) - 32;
		fileRegion = new int[]{x + 4, ly, w - 8, lh};
		int rowH = 30, gap = 3;
		int contentH = list.size() * (rowH + gap);
		fileScrollT = MathHelper.clamp(fileScrollT, 0, Math.max(0, contentH - lh));
		fileScroll = Draw.approach(fileScroll, fileScrollT, 16f, dt);

		beginClip(d, x + 4, ly, w - 8, lh);
		Path selected = SchemBuilderClient.placement != null ? SchemBuilderClient.placement.schematic.file : null;
		for (int i = 0; i < list.size(); i++) {
			Path p = list.get(i);
			int ry = ly + i * (rowH + gap) - Math.round(fileScroll);
			if (ry + rowH < ly || ry > ly + lh) continue;
			String id = "file:" + p;
			boolean rowOver = hit(id, x + 8, ry, w - 16, rowH, () -> loadSchematic(p));
			float rh = anim(id, rowOver ? 1 : 0, 18);
			boolean sel = p.equals(selected);
			float sa = anim(id + ":sel", sel ? 1 : 0, 12);
			Draw.round(d, x + 8, ry, w - 16, rowH, 6, Draw.withAlpha(0xFFFFFF, 0.035f * rh + 0.0f));
			if (sa > 0.01f) {
				Draw.round(d, x + 8, ry, w - 16, rowH, 6, Draw.withAlpha(Theme.ACCENT, 0.10f * sa));
				Draw.round(d, x + 8, ry + 6, 2, (int) ((rowH - 12) * sa), 1, Theme.ACCENT);
			}
			int tx = x + 16 + (int) (3 * rh);
			String name = Draw.ellipsize(tr, SchematicLoader.displayName(p), w - 46);
			Draw.text(d, tr, name, tx, ry + 6, sel ? Theme.TEXT : Draw.lerp(0xFFC9CDD8, Theme.TEXT, rh));
			String fmt = SchematicLoader.format(p);
			int badgeW = (int) (tr.getWidth(fmt) * 0.65f) + 8;
			int bc = Theme.badge(fmt);
			Draw.round(d, tx, ry + 17, badgeW, 9, 3, Draw.withAlpha(bc, 0.18f));
			Draw.textScaled(d, tr, fmt, tx + 4, ry + 19, 0.65f, bc);
			Draw.text(d, tr, sizeText(sizes.getOrDefault(p, 0L)), tx + badgeW + 5, ry + 17, Theme.FAINT);
			if (p.equals(loadingPath)) {
				Draw.spinner(d, x + w - 22, ry + rowH / 2, 5, time, Theme.ACCENT);
			}
		}
		if (list.isEmpty()) {
			String msg = files.isEmpty() ? "No schematics yet" : "No matches";
			Draw.textCentered(d, tr, msg, x + w / 2, ly + 18, Theme.FAINT);
		}
		endClip(d);
		scrollbar(d, x + w - 6, ly, lh, contentH, fileScroll);

		// Footer buttons
		int by = y + h - 26;
		button(d, "btn_folder", x + 8, by, w - 16 - 26, 18, "Open folder", SECONDARY, true,
			() -> Util.getOperatingSystem().open(SchematicLoader.folder().toFile()));
		button(d, "btn_refresh", x + w - 8 - 22, by, 22, 18, "↻", SECONDARY, true, () -> {
			refreshFiles();
			Toasts.show("Refreshed", files.size() + " schematics found", Toasts.Kind.INFO);
		});
	}

	private List<Path> filtered() {
		if (search.isBlank()) return files;
		String q = search.toLowerCase(Locale.ROOT);
		List<Path> out = new ArrayList<>();
		for (Path p : files) if (SchematicLoader.displayName(p).toLowerCase(Locale.ROOT).contains(q)) out.add(p);
		return out;
	}

	private static String sizeText(long bytes) {
		if (bytes >= 1024 * 1024) return String.format("%.1f MB", bytes / 1048576.0);
		if (bytes >= 1024) return String.format("%.1f KB", bytes / 1024.0);
		return bytes + " B";
	}

	private void loadSchematic(Path p) {
		if (SchemBuilderClient.BUILDER.active()) {
			Toasts.show("Build in progress", "Stop the current build first", Toasts.Kind.WARN);
			return;
		}
		if (loadingPath != null) return;
		loadingPath = p;
		UiSound.click();
		CompletableFuture.supplyAsync(() -> {
			try {
				return SchematicLoader.load(p);
			} catch (Exception ex) {
				throw new RuntimeException(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(), ex);
			}
		}).whenComplete((schem, err) -> mc.execute(() -> {
			loadingPath = null;
			if (err != null || schem == null) {
				Throwable cause = err != null && err.getCause() != null ? err.getCause() : err;
				Toasts.show("Couldn't read file", cause == null ? "Unknown error" : String.valueOf(cause.getMessage()), Toasts.Kind.ERROR);
				return;
			}
			ClientPlayerEntity player = mc.player;
			BlockPos origin = player != null ? player.getBlockPos() : BlockPos.ORIGIN;
			SchemBuilderClient.placement = new Placement(schem, origin);
			matScrollT = 0;
			UiSound.select();
			Toasts.show("Loaded " + schem.name, String.format("%,d blocks · placed at your feet", schem.blocks.size()), Toasts.Kind.SUCCESS);
		}));
	}

	// ---------------------------------------------------------------- details / materials

	private void drawDetails(DrawContext d, int x, int y, int w, int h) {
		TextRenderer tr = textRenderer;
		Draw.card(d, x, y, w, h, 9, Theme.CARD, Theme.BORDER);
		Placement pl = SchemBuilderClient.placement;

		if (loadingPath != null) {
			Draw.spinner(d, x + w / 2, y + h / 2 - 8, 9, time, Theme.ACCENT);
			Draw.textCentered(d, tr, "Reading schematic…", x + w / 2, y + h / 2 + 10, Theme.MUTED);
			return;
		}
		if (pl == null) {
			int cy = y + h / 2 - 30;
			float bob = (float) Math.sin(time * 2.2) * 2f;
			int gx = x + w / 2 - 15, gy = (int) (cy + bob);
			for (int i = 0; i < 3; i++) {
				for (int j = 0; j < 3; j++) {
					float a = 0.12f + 0.1f * (float) Math.sin(time * 3 + i + j);
					Draw.round(d, gx + i * 11, gy + j * 11, 9, 9, 2, Draw.withAlpha(Theme.ACCENT, a + 0.1f));
				}
			}
			Draw.textCentered(d, tr, "No schematic selected", x + w / 2, cy + 42, Theme.TEXT);
			Draw.textCentered(d, tr, "Pick one on the left, or drop", x + w / 2, cy + 56, Theme.MUTED);
			Draw.textCentered(d, tr, ".litematic  .schem  .nbt  files into", x + w / 2, cy + 67, Theme.MUTED);
			Draw.textCentered(d, tr, ".minecraft/schematics", x + w / 2, cy + 80, Theme.ACCENT);
			return;
		}

		Schematic s = pl.schematic;
		Draw.textScaled(d, tr, Draw.ellipsize(tr, s.name, (int) ((w - 20) / 1.2f)), x + 10, y + 10, 1.2f, Theme.TEXT);
		String sub = (s.author == null || s.author.isBlank() ? "" : "by " + s.author + "  ·  ") + s.format;
		Draw.text(d, tr, Draw.ellipsize(tr, sub, w - 20), x + 10, y + 24, Theme.FAINT);

		int cx = x + 10, cy = y + 38;
		cx = chip(d, cx, cy, pl.sizeX() + "×" + pl.sizeY() + "×" + pl.sizeZ(), Theme.ACCENT);
		cx = chip(d, cx, cy, String.format("%,d blocks", s.blocks.size()), Theme.ACCENT_2);
		if (s.regions > 1) chip(d, cx, cy, s.regions + " regions", Theme.BADGE_SCHEM);

		// Materials
		computeMaterials(s);
		int my = cy + 22;
		Draw.textScaled(d, tr, "MATERIALS", x + 10, my, 0.75f, Theme.FAINT);
		boolean creative = mc.player != null && mc.player.isCreative();
		long missingTypes = materials.stream().filter(m -> m.have < m.need).count();
		String summary = creative ? "Creative · unlimited" : missingTypes == 0 ? "You have everything" : missingTypes + " missing";
		int sc = creative || missingTypes == 0 ? Theme.GOOD : Theme.WARN;
		Draw.textRight(d, tr, summary, x + w - 12, my - 1, sc);

		int ly = my + 12;
		int lh = y + h - 8 - ly;
		matRegion = new int[]{x + 4, ly, w - 8, lh};
		int rowH = 24;
		int contentH = materials.size() * rowH;
		matScrollT = MathHelper.clamp(matScrollT, 0, Math.max(0, contentH - lh));
		matScroll = Draw.approach(matScroll, matScrollT, 16f, dt);

		beginClip(d, x + 4, ly, w - 8, lh);
		for (int i = 0; i < materials.size(); i++) {
			Material m = materials.get(i);
			int ry = ly + i * rowH - Math.round(matScroll);
			if (ry + rowH < ly || ry > ly + lh) continue;
			if (i % 2 == 0) Draw.round(d, x + 6, ry, w - 12, rowH - 2, 5, 0x08FFFFFF);
			d.drawItem(new ItemStack(m.item), x + 10, ry + 3);
			Draw.text(d, tr, Draw.ellipsize(tr, m.name, w - 110), x + 31, ry + 4, Theme.TEXT);
			float frac = creative ? 1f : Math.min(1f, m.have / (float) Math.max(1, m.need));
			int col = frac >= 1f ? Theme.GOOD : frac > 0f ? Theme.WARN : Theme.BAD;
			String counts = creative ? String.format("%,d", m.need) : String.format("%,d / %,d", Math.min(m.have, m.need), m.need);
			Draw.textRight(d, tr, counts, x + w - 14, ry + 4, frac >= 1f ? Theme.MUTED : col);
			int bw = w - 31 - 14;
			Draw.round(d, x + 31, ry + 15, bw, 3, 1, 0xFF232835);
			float shown = anim("mat:" + i, frac, 8);
			Draw.round(d, x + 31, ry + 15, Math.max(2, (int) (bw * shown)), 3, 1, col);
		}
		endClip(d);
		scrollbar(d, x + w - 6, ly, lh, contentH, matScroll);
	}

	private int chip(DrawContext d, int x, int y, String text, int color) {
		int w = textRenderer.getWidth(text) + 12;
		Draw.round(d, x, y, w, 14, 7, Draw.withAlpha(color, 0.12f));
		Draw.text(d, textRenderer, text, x + 6, y + 3, Draw.lerp(color, 0xFFFFFFFF, 0.25f));
		return x + w + 5;
	}

	private void computeMaterials(Schematic s) {
		if (matsFor != s) {
			matsFor = s;
			needs = new LinkedHashMap<>();
			for (SchematicBlock b : s.blocks) {
				BlockState st = b.state();
				if (Builder.automatic(st)) continue;
				Item item = st.getBlock().asItem();
				if (item == Items.AIR || !(item instanceof BlockItem)) continue;
				needs.merge(item, amount(st), Integer::sum);
			}
			matsAt = 0;
		}
		long now = System.currentTimeMillis();
		if (now - matsAt < 500) return;
		matsAt = now;
		List<Material> out = new ArrayList<>();
		ClientPlayerEntity p = mc.player;
		for (Map.Entry<Item, Integer> en : needs.entrySet()) {
			int have = 0;
			if (p != null) {
				for (int i = 0; i < 36; i++) {
					ItemStack st = p.getInventory().getStack(i);
					if (st.isOf(en.getKey())) have += st.getCount();
				}
			}
			out.add(new Material(en.getKey(), new ItemStack(en.getKey()).getName().getString(), en.getValue(), have));
		}
		out.sort((a, b) -> {
			boolean am = a.have < a.need, bm = b.have < b.need;
			if (am != bm) return am ? -1 : 1;
			return Integer.compare(b.need, a.need);
		});
		materials = out;
	}

	private static int amount(BlockState st) {
		if (st.getBlock() instanceof SlabBlock && st.get(Properties.SLAB_TYPE) == SlabType.DOUBLE) return 2;
		for (Property<?> p : st.getProperties()) {
			if (p instanceof IntProperty ip && COUNT_PROPS.contains(p.getName())) return Math.max(1, st.get(ip));
		}
		return 1;
	}

	// ---------------------------------------------------------------- placement + settings

	private void drawSettings(DrawContext d, int x, int y, int w, int h) {
		TextRenderer tr = textRenderer;
		Draw.card(d, x, y, w, h, 9, Theme.CARD, Theme.BORDER);
		setRegion = new int[]{x, y + 4, w, h - 8};

		int contentH = 352;
		setScrollT = MathHelper.clamp(setScrollT, 0, Math.max(0, contentH - (h - 8)));
		setScroll = Draw.approach(setScroll, setScrollT, 16f, dt);
		beginClip(d, x + 1, y + 4, w - 2, h - 8);
		int cy = y + 9 - Math.round(setScroll);
		int ix = x + 10, iw = w - 20;

		Placement pl = SchemBuilderClient.placement;
		boolean editable = pl != null && !SchemBuilderClient.BUILDER.active();

		Draw.textScaled(d, tr, "PLACEMENT", ix, cy, 0.75f, Theme.FAINT);
		cy += 12;
		String[] axes = {"X", "Y", "Z"};
		int[] colors = {0xFFF87171, 0xFF4ADE80, 0xFF60A5FA};
		for (int a = 0; a < 3; a++) {
			final int axis = a;
			Draw.round(d, ix, cy, iw, 18, 5, 0xFF171B24);
			Draw.round(d, ix + 5, cy + 5, 8, 8, 2, Draw.withAlpha(colors[a], 0.25f));
			Draw.textCentered(d, tr, axes[a], ix + 9, cy + 5, colors[a]);
			String val = pl == null ? "—" : String.valueOf(axis == 0 ? pl.origin.getX() : axis == 1 ? pl.origin.getY() : pl.origin.getZ());
			Draw.text(d, tr, val, ix + 19, cy + 5, pl == null ? Theme.FAINT : Theme.TEXT);
			button(d, "btn_m" + a, ix + iw - 38, cy + 2, 17, 14, "−", SECONDARY, editable, () -> nudge(axis, -step()));
			button(d, "btn_p" + a, ix + iw - 19, cy + 2, 17, 14, "+", SECONDARY, editable, () -> nudge(axis, step()));
			cy += 21;
		}
		Draw.round(d, ix, cy, iw, 18, 5, 0xFF171B24);
		Draw.text(d, tr, "Rotation", ix + 6, cy + 5, Theme.MUTED);
		String rot = pl == null ? "—" : pl.rotationDegrees() + "°";
		Draw.text(d, tr, rot, ix + 56, cy + 5, Theme.TEXT);
		button(d, "btn_rot", ix + iw - 52, cy + 2, 50, 14, "↻ Rotate", SECONDARY, editable, () -> {
			pl.rotateClockwise();
			UiSound.tick();
		});
		cy += 22;
		button(d, "btn_here", ix, cy, iw, 18, "Move to my position", SECONDARY, editable && mc.player != null, () -> {
			pl.origin = mc.player.getBlockPos();
			Toasts.show("Moved", "Schematic placed at your feet", Toasts.Kind.INFO);
		});
		cy += 22;
		Draw.text(d, tr, "Shift-click ± to move by 10", ix, cy, Theme.FAINT);
		cy += 16;

		SBConfig c = SBConfig.get();
		Draw.textScaled(d, tr, "SETTINGS", ix, cy, 0.75f, Theme.FAINT);
		cy += 12;
		toggle(d, "tog_fix", ix, cy, iw, "Fix wrong blocks", c.fixWrongBlocks, v -> c.fixWrongBlocks = v); cy += 19;
		toggle(d, "tog_air", ix, cy, iw, "Clear air blocks", c.clearAir, v -> c.clearAir = v); cy += 19;
		toggle(d, "tog_nbt", ix, cy, iw, "Creative: exact NBT", c.creativeNbt, v -> c.creativeNbt = v); cy += 19;
		toggle(d, "tog_sign", ix, cy, iw, "Write sign text", c.signText, v -> c.signText = v); cy += 19;
		toggle(d, "tog_overlay", ix, cy, iw, "World overlay", c.overlay, v -> c.overlay = v); cy += 19;
		toggle(d, "tog_sound", ix, cy, iw, "Interface sounds", c.sounds, v -> c.sounds = v); cy += 23;
		slider(d, "sl_speed", ix, cy, iw, "Turn speed", c.rotationSpeed, 5, 90, Math.round(c.rotationSpeed) + "°/tick",
			v -> c.rotationSpeed = Math.round(v));
		cy += 28;
		slider(d, "sl_delay", ix, cy, iw, "Action delay", c.actionDelay, 0, 10,
			c.actionDelay == 0 ? "none" : c.actionDelay + (c.actionDelay == 1 ? " tick" : " ticks"),
			v -> c.actionDelay = Math.round(v));
		endClip(d);
		scrollbar(d, x + w - 6, y + 4, h - 8, contentH, setScroll);
	}

	private int step() {
		return hasShiftDown() ? 10 : 1;
	}

	private void nudge(int axis, int amount) {
		Placement pl = SchemBuilderClient.placement;
		if (pl == null) return;
		pl.origin = pl.origin.add(axis == 0 ? amount : 0, axis == 1 ? amount : 0, axis == 2 ? amount : 0);
	}

	// ---------------------------------------------------------------- footer

	private void drawFooter(DrawContext d, int px, int fy, int pw, int fh) {
		TextRenderer tr = textRenderer;
		Builder b = SchemBuilderClient.BUILDER;
		Draw.rect(d, px + 12, fy, pw - 24, 1, Theme.BORDER);

		int btnW = 84, gap = 6;
		int buttons = b.active() ? 2 : 1;
		int bx = px + pw - 12 - buttons * btnW - (buttons - 1) * gap;
		int by = fy + 11;

		int barX = px + 14, barW = bx - 16 - barX;
		int barY = fy + 13;
		float prog = anim("progress", b.progress(), 5);
		Draw.round(d, barX, barY, barW, 6, 3, 0xFF1C202A);
		int fill = Math.max(6, (int) (barW * prog));
		if (b.total() > 0) {
			Draw.roundGradient(d, barX, barY, fill, 6, 3, Theme.ACCENT, Theme.ACCENT_2);
			if (b.status() == Builder.Status.RUNNING) {
				int sx = barX + (int) ((time * 0.6f % 1.4f - 0.2f) * fill);
				int s0 = Math.max(barX, sx), s1 = Math.min(barX + fill, sx + 24);
				if (s1 > s0) Draw.roundGradient(d, s0, barY + 1, s1 - s0, 4, 2, 0x00FFFFFF, 0x55FFFFFF);
			}
		}

		String left;
		String right;
		if (b.total() > 0 && b.status() != Builder.Status.STOPPED) {
			left = String.format("%,d / %,d blocks  ·  %d%%", b.done(), b.total(), Math.round(b.progress() * 100));
			int eta = b.etaSeconds();
			right = b.status() == Builder.Status.RUNNING
				? b.phase().label + (eta >= 0 ? "  ·  ~" + BuildHud.formatTime(eta) + " left" : "")
				: b.status() == Builder.Status.PAUSED ? "Paused" : "Done";
		} else {
			left = SchemBuilderClient.placement == null ? "Select a schematic to begin" : "Stand on a block inside the outline, then start";
			right = "";
		}
		Draw.text(d, tr, Draw.ellipsize(tr, left, barW - tr.getWidth(right) - 10), barX, barY + 11, Theme.MUTED);
		Draw.textRight(d, tr, right, barX + barW, barY + 11, Theme.FAINT);

		if (b.status() == Builder.Status.RUNNING) {
			button(d, "btn_pause", bx, by, btnW, 22, "Pause", SECONDARY, true, b::pause);
			button(d, "btn_stop", bx + btnW + gap, by, btnW, 22, "Stop", DANGER, true, b::stop);
		} else if (b.status() == Builder.Status.PAUSED) {
			button(d, "btn_resume", bx, by, btnW, 22, "Resume", PRIMARY, true, () -> {
				b.resume();
				close();
			});
			button(d, "btn_stop", bx + btnW + gap, by, btnW, 22, "Stop", DANGER, true, b::stop);
		} else {
			boolean can = SchemBuilderClient.placement != null && loadingPath == null;
			button(d, "btn_start", bx, by, btnW, 22, "Start build", PRIMARY, can, () -> {
				if (b.start(SchemBuilderClient.placement)) close();
			});
		}
	}

	// ======================================================================= widgets

	private float anim(String key, float target, float speed) {
		Float cur = anims.get(key);
		float v = cur == null ? target : Draw.approach(cur, target, speed, dt);
		anims.put(key, v);
		return v;
	}

	private boolean hit(String id, int x, int y, int w, int h, Runnable action) {
		return hit(id, x, y, w, h, action, null);
	}

	private boolean hit(String id, int x, int y, int w, int h, Runnable action, SliderSpec slider) {
		int hx = x, hy = y, hw = w, hh = h;
		if (clip != null) {
			int x2 = Math.min(x + w, clip[0] + clip[2]), y2 = Math.min(y + h, clip[1] + clip[3]);
			hx = Math.max(x, clip[0]);
			hy = Math.max(y, clip[1]);
			hw = x2 - hx;
			hh = y2 - hy;
			if (hw <= 0 || hh <= 0) return false;
		}
		Hit hitBox = new Hit(id, hx, hy, hw, hh, action, slider);
		hits.add(hitBox);
		boolean over = (dragging == null || dragging.equals(id)) && hitBox.contains(mouseXf, mouseYf);
		if (over) hovered = id;
		return over || id.equals(dragging);
	}

	private void button(DrawContext d, String id, int x, int y, int w, int h, String label, int style, boolean enabled, Runnable action) {
		boolean over = enabled && hit(id, x, y, w, h, action);
		float hv = anim(id, over ? 1 : 0, 18);
		float en = anim(id + ":en", enabled ? 1 : 0, 12);
		float prev = Draw.alpha;
		Draw.alpha = prev * (0.4f + 0.6f * en);
		int yo = -Math.round(hv);
		int textColor;
		switch (style) {
			case PRIMARY -> {
				if (hv > 0.01f) Draw.round(d, x - 2, y + yo - 2, w + 4, h + 4, 8, Draw.withAlpha(Theme.ACCENT, 0.18f * hv));
				Draw.roundGradient(d, x, y + yo, w, h, 6,
					Draw.lerp(Theme.ACCENT, 0xFFFFFFFF, 0.12f * hv), Draw.lerp(Theme.ACCENT_2, 0xFFFFFFFF, 0.12f * hv));
				textColor = 0xFF0A0C11;
			}
			case DANGER -> {
				Draw.card(d, x, y + yo, w, h, 6, Draw.lerp(0xFF221316, 0xFF34181C, hv), Draw.lerp(0xFF3D2025, Theme.BAD, hv * 0.6f));
				textColor = Theme.BAD;
			}
			default -> {
				Draw.card(d, x, y + yo, w, h, 6, Draw.lerp(Theme.CARD_HI, 0xFF20252F, hv), Draw.lerp(Theme.BORDER, 0xFF3A4252, hv));
				textColor = Draw.lerp(0xFFC9CDD8, 0xFFFFFFFF, hv);
			}
		}
		Draw.textCentered(d, textRenderer, label, x + w / 2, y + yo + (h - 8) / 2, textColor);
		Draw.alpha = prev;
	}

	private void toggle(DrawContext d, String id, int x, int y, int w, String label, boolean value, Consumer<Boolean> set) {
		boolean over = hit(id, x - 4, y - 1, w + 8, 17, () -> {
			set.accept(!value);
			UiSound.toggle(!value);
			SBConfig.get().save();
		});
		float hv = anim(id, over ? 1 : 0, 18);
		float k = anim(id + ":k", value ? 1 : 0, 14);
		if (hv > 0.01f) Draw.round(d, x - 4, y - 1, w + 8, 17, 5, Draw.withAlpha(0xFFFFFF, 0.04f * hv));
		Draw.text(d, textRenderer, label, x, y + 3, Draw.lerp(Theme.MUTED, Theme.TEXT, Math.max(hv, k * 0.6f)));
		int sw = 22, sh = 12, sx = x + w - sw, sy = y + 1;
		Draw.round(d, sx, sy, sw, sh, 6, Draw.lerp(0xFF2A2F3B, Theme.ACCENT, k));
		float kp = Draw.easeOutBack(k);
		int kx = sx + 2 + Math.round((sw - 12) * MathHelper.clamp(kp, -0.1f, 1.1f));
		Draw.round(d, kx, sy + 2, 8, 8, 4, Draw.lerp(0xFFB8BECB, 0xFFFFFFFF, k));
	}

	private void slider(DrawContext d, String id, int x, int y, int w, String label, float value, float min, float max,
						String valueText, Consumer<Float> set) {
		TextRenderer tr = textRenderer;
		int ty = y + 14;
		SliderSpec spec = new SliderSpec(x, w, min, max, set);
		boolean over = hit(id, x - 2, ty - 5, w + 4, 14, null, spec);
		float hv = anim(id, over ? 1 : 0, 18);
		Draw.text(d, tr, label, x, y, Draw.lerp(Theme.MUTED, Theme.TEXT, hv));
		Draw.textRight(d, tr, valueText, x + w, y, Theme.TEXT);
		float t = (value - min) / (max - min);
		float ta = anim(id + ":v", t, 22);
		Draw.round(d, x, ty, w, 4, 2, 0xFF2A2F3B);
		Draw.roundGradient(d, x, ty, Math.max(4, (int) (w * ta)), 4, 2, Theme.ACCENT, Theme.ACCENT_2);
		int kx = x + (int) (w * ta) - 5;
		if (hv > 0.01f) Draw.round(d, kx - 3, ty - 6, 16, 16, 8, Draw.withAlpha(Theme.ACCENT, 0.2f * hv));
		Draw.round(d, kx, ty - 3, 10, 10, 5, 0xFFFFFFFF);
	}

	private void scrollbar(DrawContext d, int x, int y, int h, int contentH, float scroll) {
		if (contentH <= h) return;
		int barH = Math.max(16, (int) (h * (h / (float) contentH)));
		int by = y + (int) ((h - barH) * (scroll / (contentH - h)));
		Draw.round(d, x, by, 3, barH, 1, 0x40FFFFFF);
	}

	private void beginClip(DrawContext d, int x, int y, int w, int h) {
		clip = new int[]{x, y, w, h};
		d.enableScissor(x, y, x + w, y + h);
	}

	private void endClip(DrawContext d) {
		clip = null;
		d.disableScissor();
	}

	private static boolean in(int[] r, double mx, double my) {
		return mx >= r[0] && my >= r[1] && mx < r[0] + r[2] && my < r[1] + r[3];
	}

	private double toPanelX(double mx) { return pcx + (mx - pcx) / panelScale; }
	private double toPanelY(double my) { return pcy + (my - pcy) / panelScale + (1f - Draw.easeOutCubic(open)) * 8f; }

	// ======================================================================= input

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (open < 0.85f || closing) return true;
		double mx = toPanelX(mouseX), my = toPanelY(mouseY);
		boolean wasFocused = searchFocused;
		searchFocused = false;
		if (button != 0) return true;
		for (int i = hits.size() - 1; i >= 0; i--) {
			Hit h = hits.get(i);
			if (!h.contains(mx, my)) continue;
			if (h.slider != null) {
				dragging = h.id;
				h.slider.apply(mx);
				UiSound.tick();
				return true;
			}
			if (h.action != null) {
				if (h.id.startsWith("btn")) UiSound.click();
				h.action.run();
				if (h.id.equals("search")) searchFocused = true;
				return true;
			}
		}
		if (wasFocused) UiSound.tick();
		return true;
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
		if (dragging != null) {
			double mx = toPanelX(mouseX);
			for (Hit h : hits) {
				if (h.id.equals(dragging) && h.slider != null) {
					h.slider.apply(mx);
					break;
				}
			}
			return true;
		}
		return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		if (dragging != null) {
			dragging = null;
			SBConfig.get().save();
			UiSound.tick();
		}
		return super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		double mx = toPanelX(mouseX), my = toPanelY(mouseY);
		float amount = (float) (-verticalAmount * 26);
		if (in(fileRegion, mx, my)) fileScrollT += amount;
		else if (in(matRegion, mx, my)) matScrollT += amount;
		else if (in(setRegion, mx, my)) setScrollT += amount;
		return true;
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (searchFocused) {
			if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_ENTER) {
				searchFocused = false;
				return true;
			}
			if (keyCode == GLFW.GLFW_KEY_BACKSPACE && !search.isEmpty()) {
				search = hasControlDown() ? "" : search.substring(0, search.length() - 1);
				fileScrollT = 0;
				return true;
			}
			return true;
		}
		if (keyCode == GLFW.GLFW_KEY_F && hasControlDown()) {
			searchFocused = true;
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean charTyped(char chr, int modifiers) {
		if (searchFocused && chr >= 32 && chr != 127 && search.length() < 48) {
			search += chr;
			fileScrollT = 0;
			return true;
		}
		return super.charTyped(chr, modifiers);
	}
}
