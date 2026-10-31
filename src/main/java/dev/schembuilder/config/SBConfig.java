package dev.schembuilder.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Saved to config/schembuilder.json. */
public final class SBConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static SBConfig instance;

	/** Break blocks that don't match the schematic and place the right one. */
	public boolean fixWrongBlocks = true;
	/** Also break blocks where the schematic has air. */
	public boolean clearAir = false;
	/** In creative, place items carrying the exact block state and block-entity NBT. */
	public boolean creativeNbt = true;
	/** Fill sign text from the schematic when the sign editor opens. */
	public boolean signText = true;
	/** Show outlines in the world. */
	public boolean overlay = true;
	/** Interface sounds. */
	public boolean sounds = true;
	/** Max camera turn per tick, in degrees. */
	public float rotationSpeed = 30f;
	/** Ticks to wait between actions. */
	public int actionDelay = 2;

	public static SBConfig get() {
		if (instance == null) load();
		return instance;
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("schembuilder.json");
	}

	public static void load() {
		Path p = path();
		if (Files.exists(p)) {
			try (Reader r = Files.newBufferedReader(p)) {
				instance = GSON.fromJson(r, SBConfig.class);
			} catch (Exception ignored) {
			}
		}
		if (instance == null) instance = new SBConfig();
		instance.rotationSpeed = Math.max(5f, Math.min(180f, instance.rotationSpeed));
		instance.actionDelay = Math.max(0, Math.min(20, instance.actionDelay));
	}

	public void save() {
		try (Writer w = Files.newBufferedWriter(path())) {
			GSON.toJson(this, w);
		} catch (Exception ignored) {
		}
	}
}
