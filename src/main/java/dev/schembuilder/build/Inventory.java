package dev.schembuilder.build;

import dev.schembuilder.schematic.StateParser;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BlockStateComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/** Getting the right item into the hand. */
public final class Inventory {
	private Inventory() {}

	public static int count(ClientPlayerEntity p, Item item) {
		PlayerInventory inv = p.getInventory();
		int n = 0;
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getStack(i);
			if (s.isOf(item)) n += s.getCount();
		}
		return n;
	}

	public static boolean has(ClientPlayerEntity p, Item item) {
		return p.isCreative() || count(p, item) > 0;
	}

	/** Hotbar slot to use when moving items in: an empty one, else one not holding a tool, else the last. */
	private static int spareSlot(PlayerInventory inv) {
		for (int i = 0; i < 9; i++) if (inv.getStack(i).isEmpty()) return i;
		for (int i = 8; i >= 0; i--) {
			ItemStack s = inv.getStack(i);
			if (!s.isDamageable()) return i;
		}
		return 8;
	}

	/**
	 * Makes sure the item is in the selected slot.
	 * Returns true if it is ready now; false if a swap happened this tick (wait a tick) or the item is missing.
	 */
	public static boolean select(MinecraftClient mc, Target t, boolean creativeNbt) {
		ClientPlayerEntity p = mc.player;
		if (p == null || mc.interactionManager == null) return false;
		PlayerInventory inv = p.getInventory();

		if (p.isCreative()) {
			ItemStack want = creativeStack(t, creativeNbt);
			ItemStack held = inv.getStack(inv.selectedSlot);
			if (ItemStack.areItemsAndComponentsEqual(held, want)) return true;
			int slot = inv.selectedSlot;
			inv.setStack(slot, want);
			mc.interactionManager.clickCreativeStack(want, 36 + slot);
			return false;
		}

		if (inv.getStack(inv.selectedSlot).isOf(t.item)) return true;
		for (int i = 0; i < 9; i++) {
			if (inv.getStack(i).isOf(t.item)) {
				inv.selectedSlot = i;
				return true;
			}
		}
		for (int i = 9; i < 36; i++) {
			if (inv.getStack(i).isOf(t.item)) {
				int hotbar = spareSlot(inv);
				// Player inventory screen handler: main inventory slots 9-35 keep their index.
				mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, i, hotbar, SlotActionType.SWAP, p);
				inv.selectedSlot = hotbar;
				return false;
			}
		}
		return false;
	}

	/** Creative item that places the exact state and block-entity data from the schematic. */
	public static ItemStack creativeStack(Target t, boolean withData) {
		ItemStack stack = new ItemStack(t.item);
		if (!withData) return stack;
		stack.set(DataComponentTypes.BLOCK_STATE, new BlockStateComponent(StateParser.propertyMap(t.state)));
		if (t.nbt != null && !t.nbt.isEmpty()) {
			NbtCompound data = t.nbt.copy();
			data.remove("x");
			data.remove("y");
			data.remove("z");
			if (!data.contains("id")) {
				String id = blockEntityId(t.state);
				if (id != null) data.putString("id", id);
			}
			if (data.contains("id")) stack.set(DataComponentTypes.BLOCK_ENTITY_DATA, NbtComponent.of(data));
		}
		return stack;
	}

	private static String blockEntityId(BlockState state) {
		if (!(state.getBlock() instanceof BlockEntityProvider provider)) return null;
		BlockEntity be = provider.createBlockEntity(BlockPos.ORIGIN, state);
		if (be == null) return null;
		Identifier id = Registries.BLOCK_ENTITY_TYPE.getId(be.getType());
		return id == null ? null : id.toString();
	}

	/** Picks the fastest hotbar tool for mining a state. */
	public static void selectTool(ClientPlayerEntity p, BlockState state) {
		PlayerInventory inv = p.getInventory();
		int best = inv.selectedSlot;
		float bestSpeed = inv.getStack(best).getMiningSpeedMultiplier(state);
		for (int i = 0; i < 9; i++) {
			float s = inv.getStack(i).getMiningSpeedMultiplier(state);
			if (s > bestSpeed) {
				bestSpeed = s;
				best = i;
			}
		}
		inv.selectedSlot = best;
	}
}
