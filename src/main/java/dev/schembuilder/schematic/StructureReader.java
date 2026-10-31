package dev.schembuilder.schematic;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;

import java.nio.file.Path;

/** Reads vanilla structure block .nbt files. */
public final class StructureReader {
	private StructureReader() {}

	public static Schematic read(NbtCompound root, Path file, String fallbackName) {
		NbtList paletteTag;
		if (root.contains("palette", NbtElement.LIST_TYPE)) {
			paletteTag = root.getList("palette", NbtElement.COMPOUND_TYPE);
		} else {
			NbtList palettes = root.getList("palettes", NbtElement.LIST_TYPE);
			paletteTag = palettes.isEmpty() ? new NbtList() : palettes.getList(0);
		}
		BlockState[] palette = new BlockState[paletteTag.size()];
		for (int i = 0; i < paletteTag.size(); i++) {
			BlockState s = StateParser.fromNbt(paletteTag.getCompound(i));
			palette[i] = s == null ? Blocks.AIR.getDefaultState() : s;
		}

		Schematic.Builder out = new Schematic.Builder();
		NbtList size = root.getList("size", NbtElement.INT_TYPE);
		if (size.size() == 3 && size.getInt(0) > 0 && size.getInt(1) > 0 && size.getInt(2) > 0) {
			out.bound(0, 0, 0);
			out.bound(size.getInt(0) - 1, size.getInt(1) - 1, size.getInt(2) - 1);
		}

		NbtList blocks = root.getList("blocks", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < blocks.size(); i++) {
			NbtCompound b = blocks.getCompound(i);
			NbtList pos = b.getList("pos", NbtElement.INT_TYPE);
			if (pos.size() < 3) continue;
			int idx = b.getInt("state");
			BlockState state = idx >= 0 && idx < palette.length ? palette[idx] : Blocks.AIR.getDefaultState();
			NbtCompound nbt = b.contains("nbt", NbtElement.COMPOUND_TYPE) ? b.getCompound("nbt").copy() : null;
			out.add(pos.getInt(0), pos.getInt(1), pos.getInt(2), state, nbt);
		}
		return out.build(fallbackName, "NBT", root.getString("author"), file, 1);
	}
}
