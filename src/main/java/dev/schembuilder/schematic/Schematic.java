package dev.schembuilder.schematic;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** A loaded schematic, normalised so its minimum corner is (0,0,0). */
public final class Schematic {
	public final String name;
	public final String format;
	public final String author;
	public final Path file;
	public final int sizeX, sizeY, sizeZ;
	public final int regions;
	public final List<SchematicBlock> blocks;
	/** Positions (BlockPos.asLong) inside the schematic that are explicitly air. */
	public final LongArrayList air;

	public Schematic(String name, String format, String author, Path file, int sizeX, int sizeY, int sizeZ,
					 int regions, List<SchematicBlock> blocks, LongArrayList air) {
		this.name = name;
		this.format = format;
		this.author = author;
		this.file = file;
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.regions = regions;
		this.blocks = blocks;
		this.air = air;
	}

	/** Collects blocks in arbitrary coordinates, then shifts everything so the minimum corner is 0,0,0. */
	public static final class Builder {
		private final List<SchematicBlock> blocks = new ArrayList<>();
		private final LongArrayList air = new LongArrayList();
		private int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		private int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

		/** Extends the bounds without adding a block (used for region corners so empty edges keep their size). */
		public void bound(int x, int y, int z) {
			minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
			maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
		}

		public void add(int x, int y, int z, BlockState state, NbtCompound nbt) {
			bound(x, y, z);
			if (state == null || state.isAir()) {
				air.add(BlockPos.asLong(x, y, z));
			} else {
				blocks.add(new SchematicBlock(new BlockPos(x, y, z), state, nbt));
			}
		}

		public Schematic build(String name, String format, String author, Path file, int regions) {
			if (minX == Integer.MAX_VALUE) {
				return new Schematic(name, format, author, file, 0, 0, 0, regions, List.of(), new LongArrayList());
			}
			List<SchematicBlock> shifted = new ArrayList<>(blocks.size());
			for (SchematicBlock b : blocks) {
				shifted.add(new SchematicBlock(b.pos().add(-minX, -minY, -minZ), b.state(), b.nbt()));
			}
			LongArrayList shiftedAir = new LongArrayList(air.size());
			for (int i = 0; i < air.size(); i++) {
				long l = air.getLong(i);
				shiftedAir.add(BlockPos.asLong(BlockPos.unpackLongX(l) - minX, BlockPos.unpackLongY(l) - minY, BlockPos.unpackLongZ(l) - minZ));
			}
			return new Schematic(name, format, author, file,
				maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1, regions, shifted, shiftedAir);
		}
	}
}
