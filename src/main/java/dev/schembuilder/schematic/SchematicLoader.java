package dev.schembuilder.schematic;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class SchematicLoader {
	private SchematicLoader() {}

	public static Path folder() {
		Path dir = FabricLoader.getInstance().getGameDir().resolve("schematics");
		try {
			Files.createDirectories(dir);
		} catch (IOException ignored) {
		}
		return dir;
	}

	public static boolean supported(Path p) {
		String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
		return n.endsWith(".litematic") || n.endsWith(".schem") || n.endsWith(".nbt");
	}

	public static String format(Path p) {
		String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
		if (n.endsWith(".litematic")) return "LITEMATIC";
		if (n.endsWith(".schem")) return "SCHEM";
		return "NBT";
	}

	public static String displayName(Path p) {
		String n = p.getFileName().toString();
		int dot = n.lastIndexOf('.');
		return dot > 0 ? n.substring(0, dot) : n;
	}

	public static List<Path> list() {
		Path dir = folder();
		List<Path> out = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(dir, 4)) {
			walk.filter(Files::isRegularFile).filter(SchematicLoader::supported).forEach(out::add);
		} catch (IOException ignored) {
		}
		out.sort(Comparator.comparing(p -> displayName(p).toLowerCase(Locale.ROOT)));
		return out;
	}

	public static Schematic load(Path file) throws IOException {
		NbtCompound root;
		try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
			root = NbtIo.readCompressed(in, NbtSizeTracker.ofUnlimitedBytes());
		}
		String name = displayName(file);
		return switch (format(file)) {
			case "LITEMATIC" -> LitematicReader.read(root, file, name);
			case "SCHEM" -> SpongeReader.read(root, file, name);
			default -> StructureReader.read(root, file, name);
		};
	}
}
