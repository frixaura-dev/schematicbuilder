package dev.schembuilder.schematic;

import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

/** One non-air block of a schematic, position relative to the schematic's minimum corner. */
public record SchematicBlock(BlockPos pos, BlockState state, @Nullable NbtCompound nbt) {
}
