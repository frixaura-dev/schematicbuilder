package dev.schembuilder.schematic;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.math.BlockPos;

import java.nio.file.Path;

/** Reads Sponge .schem files, versions 1, 2 and 3 (WorldEdit / FAWE exports). */
public final class SpongeReader {
	private SpongeReader() {}

	public static Schematic read(NbtCompound root, Path file, String fallbackName) {
		NbtCompound s = root.contains("Schematic", NbtElement.COMPOUND_TYPE) ? root.getCompound("Schematic") : root;
		int version = s.getInt("Version");
		int w = s.getShort("Width") & 0xFFFF;
		int h = s.getShort("Height") & 0xFFFF;
		int l = s.getShort("Length") & 0xFFFF;

		NbtCompound paletteTag;
		byte[] data;
		NbtList blockEntities;
		if (version >= 3) {
			NbtCompound blocks = s.getCompound("Blocks");
			paletteTag = blocks.getCompound("Palette");
			data = blocks.getByteArray("Data");
			blockEntities = blocks.getList("BlockEntities", NbtElement.COMPOUND_TYPE);
		} else {
			paletteTag = s.getCompound("Palette");
			data = s.getByteArray("BlockData");
			blockEntities = s.contains("BlockEntities", NbtElement.LIST_TYPE)
				? s.getList("BlockEntities", NbtElement.COMPOUND_TYPE)
				: s.getList("TileEntities", NbtElement.COMPOUND_TYPE);
		}

		int max = 0;
		for (String key : paletteTag.getKeys()) max = Math.max(max, paletteTag.getInt(key));
		BlockState[] palette = new BlockState[max + 1];
		for (String key : paletteTag.getKeys()) {
			BlockState st = StateParser.fromString(key);
			palette[paletteTag.getInt(key)] = st == null ? Blocks.AIR.getDefaultState() : st;
		}

		Long2ObjectOpenHashMap<NbtCompound> tiles = new Long2ObjectOpenHashMap<>();
		for (int i = 0; i < blockEntities.size(); i++) {
			NbtCompound tag = blockEntities.getCompound(i);
			int[] p = tag.getIntArray("Pos");
			if (p.length < 3) continue;
			NbtCompound nbt = version >= 3 && tag.contains("Data", NbtElement.COMPOUND_TYPE)
				? tag.getCompound("Data").copy() : tag.copy();
			nbt.remove("Pos");
			nbt.remove("Id");
			String id = tag.getString("Id");
			if (!id.isEmpty()) nbt.putString("id", id);
			tiles.put(BlockPos.asLong(p[0], p[1], p[2]), nbt);
		}

		Schematic.Builder out = new Schematic.Builder();
		if (w > 0 && h > 0 && l > 0) {
			out.bound(0, 0, 0);
			out.bound(w - 1, h - 1, l - 1);
		}
		int total = w * h * l;
		int i = 0, index = 0;
		while (i < data.length && index < total) {
			int value = 0, shift = 0;
			byte b;
			do {
				b = data[i++];
				value |= (b & 0x7F) << shift;
				shift += 7;
			} while ((b & 0x80) != 0 && i < data.length && shift < 35);

			int y = index / (w * l);
			int rem = index % (w * l);
			int z = rem / w;
			int x = rem % w;
			BlockState state = value >= 0 && value < palette.length && palette[value] != null
				? palette[value] : Blocks.AIR.getDefaultState();
			NbtCompound nbt = state.isAir() ? null : tiles.get(BlockPos.asLong(x, y, z));
			out.add(x, y, z, state, nbt);
			index++;
		}

		NbtCompound meta = s.getCompound("Metadata");
		String name = meta.contains("Name", NbtElement.STRING_TYPE) ? meta.getString("Name") : fallbackName;
		String author = meta.getString("Author");
		return out.build(name.isBlank() ? fallbackName : name, "SCHEM", author, file, 1);
	}
}
