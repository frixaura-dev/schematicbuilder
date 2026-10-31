package dev.schembuilder.schematic;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.math.BlockPos;

import java.nio.file.Path;

/** Reads Litematica .litematic files (all regions merged into one schematic). */
public final class LitematicReader {
	private LitematicReader() {}

	public static Schematic read(NbtCompound root, Path file, String fallbackName) {
		NbtCompound meta = root.getCompound("Metadata");
		String name = meta.contains("Name", NbtElement.STRING_TYPE) && !meta.getString("Name").isBlank()
			? meta.getString("Name") : fallbackName;
		String author = meta.getString("Author");

		NbtCompound regions = root.getCompound("Regions");
		Schematic.Builder out = new Schematic.Builder();
		int regionCount = 0;

		for (String regionName : regions.getKeys()) {
			NbtCompound region = regions.getCompound(regionName);
			regionCount++;

			BlockPos pos = readPos(region.getCompound("Position"));
			BlockPos size = readPos(region.getCompound("Size"));
			int sx = Math.abs(size.getX()), sy = Math.abs(size.getY()), sz = Math.abs(size.getZ());
			if (sx == 0 || sy == 0 || sz == 0) continue;
			// Negative sizes mean the region extends towards negative coordinates from its position.
			int ox = pos.getX() + (size.getX() < 0 ? size.getX() + 1 : 0);
			int oy = pos.getY() + (size.getY() < 0 ? size.getY() + 1 : 0);
			int oz = pos.getZ() + (size.getZ() < 0 ? size.getZ() + 1 : 0);

			out.bound(ox, oy, oz);
			out.bound(ox + sx - 1, oy + sy - 1, oz + sz - 1);

			NbtList paletteTag = region.getList("BlockStatePalette", NbtElement.COMPOUND_TYPE);
			BlockState[] palette = new BlockState[Math.max(1, paletteTag.size())];
			for (int i = 0; i < paletteTag.size(); i++) {
				BlockState s = StateParser.fromNbt(paletteTag.getCompound(i));
				palette[i] = s == null ? Blocks.AIR.getDefaultState() : s;
			}
			if (palette[0] == null) palette[0] = Blocks.AIR.getDefaultState();

			Long2ObjectOpenHashMap<NbtCompound> tiles = new Long2ObjectOpenHashMap<>();
			NbtList tileList = region.getList("TileEntities", NbtElement.COMPOUND_TYPE);
			for (int i = 0; i < tileList.size(); i++) {
				NbtCompound t = tileList.getCompound(i).copy();
				int tx = t.getInt("x"), ty = t.getInt("y"), tz = t.getInt("z");
				t.remove("x"); t.remove("y"); t.remove("z");
				tiles.put(BlockPos.asLong(tx, ty, tz), t);
			}

			long[] data = region.getLongArray("BlockStates");
			int bits = Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(palette.length - 1));
			long layer = (long) sx * sz;
			long total = layer * sy;

			for (long index = 0; index < total; index++) {
				int id = get(data, index, bits);
				BlockState state = id >= 0 && id < palette.length ? palette[id] : Blocks.AIR.getDefaultState();
				int y = (int) (index / layer);
				int rem = (int) (index % layer);
				int z = rem / sx;
				int x = rem % sx;
				NbtCompound nbt = state.isAir() ? null : tiles.get(BlockPos.asLong(x, y, z));
				out.add(ox + x, oy + y, oz + z, state, nbt);
			}
		}
		return out.build(name, "LITEMATIC", author, file, regionCount);
	}

	private static BlockPos readPos(NbtCompound tag) {
		return new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
	}

	/** Litematica's packed array: entries may straddle two longs. */
	private static int get(long[] arr, long index, int bits) {
		long mask = (1L << bits) - 1L;
		long startOffset = index * bits;
		int startIndex = (int) (startOffset >> 6);
		int endIndex = (int) (((index + 1L) * bits - 1L) >> 6);
		int startBit = (int) (startOffset & 63L);
		if (startIndex >= arr.length) return 0;
		if (startIndex == endIndex || endIndex >= arr.length) {
			return (int) ((arr[startIndex] >>> startBit) & mask);
		}
		int endBit = 64 - startBit;
		return (int) (((arr[startIndex] >>> startBit) | (arr[endIndex] << endBit)) & mask);
	}
}
