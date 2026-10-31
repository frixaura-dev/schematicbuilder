package dev.schembuilder.build;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.ComparatorBlock;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.enums.SlabType;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;

import java.util.Objects;
import java.util.Set;

/**
 * Decides whether a block in the world matches the schematic.
 * Properties the game derives from neighbours (fence connections, stair shape,
 * redstone power, crop age...) are ignored, since no placement can control them.
 */
public final class StateMatcher {
	private StateMatcher() {}

	public enum Diff {
		/** Matches. */
		MATCH,
		/** Right block, a property can be changed by right-clicking it (repeater delay, door open...). */
		FIXABLE,
		/** Right block, needs more of the same item added (slab to double, more candles, snow layers...). */
		COUNT,
		/** Right block, wrong orientation or similar: needs breaking and replacing. */
		MISMATCH,
		/** Different block entirely. */
		WRONG_BLOCK
	}

	private static final Set<String> IGNORED = Set.of(
		"waterlogged", "power", "distance", "persistent", "age", "stage", "moisture", "snowy",
		"in_wall", "attached", "disarmed", "triggered", "occupied", "has_record", "has_book",
		"has_bottle_0", "has_bottle_1", "has_bottle_2", "lit", "bites", "level", "honey_level",
		"charges", "hatch", "drag", "bloom", "unstable", "enabled", "extended", "locked", "short",
		"signal_fire", "berries", "tilt", "instrument", "bottom", "cracked", "crafting",
		"sculk_sensor_phase", "can_summon", "shrieking", "thickness", "conditional", "shape",
		"north", "south", "east", "west", "up", "down", "half_occupied", "dusted", "ominous",
		"vault_state", "trial_spawner_state", "natural", "creaking_heart_state"
	);

	private static final Set<String> COUNT_PROPS = Set.of("layers", "candles", "pickles", "eggs", "flower_amount");

	private static boolean ignored(BlockState state, Property<?> p) {
		String name = p.getName();
		if (IGNORED.contains(name)) return true;
		if (name.equals("powered")) return !(state.getBlock() instanceof LeverBlock);
		if (name.equals("type") && state.getBlock() instanceof ChestBlock) return true;
		if (name.equals("mode") && !(state.getBlock() instanceof ComparatorBlock)) return true;
		return false;
	}

	private static boolean fixableProp(BlockState state, Property<?> p) {
		if (p == Properties.DELAY || p == Properties.NOTE || p == Properties.INVERTED) return true;
		if (p == Properties.COMPARATOR_MODE) return true;
		if (p == Properties.POWERED && state.getBlock() instanceof LeverBlock) return true;
		if (p == Properties.OPEN) {
			return !state.isOf(Blocks.IRON_DOOR) && !state.isOf(Blocks.IRON_TRAPDOOR);
		}
		return false;
	}

	public static Diff diff(BlockState actual, BlockState target) {
		if (actual.getBlock() != target.getBlock()) return Diff.WRONG_BLOCK;
		Diff result = Diff.MATCH;
		for (Property<?> p : target.getProperties()) {
			if (ignored(target, p) || !actual.contains(p)) continue;
			Object a = actual.get(p);
			Object t = target.get(p);
			if (Objects.equals(a, t)) continue;

			if (COUNT_PROPS.contains(p.getName()) && p instanceof IntProperty ip) {
				int av = actual.get(ip), tv = target.get(ip);
				if (av < tv) {
					if (result == Diff.MATCH) result = Diff.COUNT;
					continue;
				}
				return Diff.MISMATCH;
			}
			if (p == Properties.SLAB_TYPE && target.getBlock() instanceof SlabBlock) {
				if (target.get(Properties.SLAB_TYPE) == SlabType.DOUBLE) {
					if (result == Diff.MATCH) result = Diff.COUNT;
					continue;
				}
				return Diff.MISMATCH;
			}
			if (fixableProp(target, p)) {
				result = Diff.FIXABLE;
				continue;
			}
			return Diff.MISMATCH;
		}
		return result;
	}

	public static boolean matches(BlockState actual, BlockState target) {
		return diff(actual, target) == Diff.MATCH;
	}

	/**
	 * Would placing this state get us to the target (possibly after right-click fixes or adding more)?
	 * Used by the solver to accept a simulated placement.
	 */
	public static boolean placementCompatible(BlockState placed, BlockState target) {
		Diff d = diff(placed, target);
		return d == Diff.MATCH || d == Diff.FIXABLE || d == Diff.COUNT;
	}

	/** Right-clicks needed to cycle the first differing fixable property. 0 if none. */
	public static int fixClicks(BlockState actual, BlockState target) {
		if (actual.getBlock() != target.getBlock()) return 0;
		if (target.contains(Properties.DELAY) && actual.contains(Properties.DELAY)) {
			int a = actual.get(Properties.DELAY), t = target.get(Properties.DELAY);
			if (a != t) return Math.floorMod(t - a, 4);
		}
		if (target.contains(Properties.NOTE) && actual.contains(Properties.NOTE)) {
			int a = actual.get(Properties.NOTE), t = target.get(Properties.NOTE);
			if (a != t) return Math.floorMod(t - a, 25);
		}
		if (target.contains(Properties.COMPARATOR_MODE) && !actual.get(Properties.COMPARATOR_MODE).equals(target.get(Properties.COMPARATOR_MODE))) return 1;
		if (target.contains(Properties.INVERTED) && !actual.get(Properties.INVERTED).equals(target.get(Properties.INVERTED))) return 1;
		if (target.getBlock() instanceof LeverBlock && !actual.get(Properties.POWERED).equals(target.get(Properties.POWERED))) return 1;
		if (target.contains(Properties.OPEN) && fixableProp(target, Properties.OPEN)
			&& !actual.get(Properties.OPEN).equals(target.get(Properties.OPEN))) return 1;
		return 0;
	}
}
