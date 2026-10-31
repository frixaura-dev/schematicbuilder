package dev.schembuilder.build;

import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

/** One block the builder is responsible for. */
public final class Target {
	public final BlockPos pos;
	public final BlockState state;
	@Nullable public final NbtCompound nbt;
	public final Item item;
	/** True where the schematic wants air (only used when "clear air" is on). */
	public final boolean clear;

	public boolean done;
	public boolean wrong;
	public boolean failed;
	public int attempts;
	public int breakAttempts;
	public long deferUntil;

	public Target(BlockPos pos, BlockState state, @Nullable NbtCompound nbt, Item item, boolean clear) {
		this.pos = pos;
		this.state = state;
		this.nbt = nbt;
		this.item = item;
		this.clear = clear;
	}
}
