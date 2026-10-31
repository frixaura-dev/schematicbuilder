package dev.schembuilder.schematic;

import net.minecraft.block.BlockState;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/** Where a schematic sits in the world: origin is the minimum corner after rotation. */
public final class Placement {
	public final Schematic schematic;
	public BlockPos origin;
	public BlockRotation rotation = BlockRotation.NONE;

	public Placement(Schematic schematic, BlockPos origin) {
		this.schematic = schematic;
		this.origin = origin;
	}

	private boolean swapped() {
		return rotation == BlockRotation.CLOCKWISE_90 || rotation == BlockRotation.COUNTERCLOCKWISE_90;
	}

	public int sizeX() { return swapped() ? schematic.sizeZ : schematic.sizeX; }
	public int sizeY() { return schematic.sizeY; }
	public int sizeZ() { return swapped() ? schematic.sizeX : schematic.sizeZ; }

	private BlockPos rotatedMin() {
		BlockPos a = BlockPos.ORIGIN.rotate(rotation);
		BlockPos b = new BlockPos(schematic.sizeX - 1, schematic.sizeY - 1, schematic.sizeZ - 1).rotate(rotation);
		return new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
	}

	public BlockPos toWorld(BlockPos relative) {
		BlockPos r = relative.rotate(rotation);
		BlockPos min = rotatedMin();
		return origin.add(r.getX() - min.getX(), r.getY() - min.getY(), r.getZ() - min.getZ());
	}

	public BlockState toWorld(BlockState state) {
		return state.rotate(rotation);
	}

	public BlockPos max() {
		return origin.add(sizeX() - 1, sizeY() - 1, sizeZ() - 1);
	}

	public Box box() {
		BlockPos max = max();
		return new Box(origin.getX(), origin.getY(), origin.getZ(), max.getX() + 1, max.getY() + 1, max.getZ() + 1);
	}

	public boolean containsXZ(BlockPos p) {
		BlockPos max = max();
		return p.getX() >= origin.getX() && p.getX() <= max.getX() && p.getZ() >= origin.getZ() && p.getZ() <= max.getZ();
	}

	public void rotateClockwise() {
		rotation = rotation.rotate(BlockRotation.CLOCKWISE_90);
	}

	public int rotationDegrees() {
		return switch (rotation) {
			case NONE -> 0;
			case CLOCKWISE_90 -> 90;
			case CLOCKWISE_180 -> 180;
			case COUNTERCLOCKWISE_90 -> 270;
		};
	}
}
