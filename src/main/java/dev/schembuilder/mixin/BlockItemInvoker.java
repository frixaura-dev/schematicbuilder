package dev.schembuilder.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Lets the placement solver ask an item "what state would you place here?"
 * without placing anything. Calls the item-level method so wall torches,
 * wall signs, banners and skulls resolve to the right block variant.
 */
@Mixin(BlockItem.class)
public interface BlockItemInvoker {
	@Invoker("getPlacementState")
	BlockState schembuilder$placementState(ItemPlacementContext context);
}
