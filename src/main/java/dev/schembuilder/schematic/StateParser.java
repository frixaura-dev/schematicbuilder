package dev.schembuilder.schematic;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/** Turns palette entries ({Name, Properties} compounds or "id[k=v]" strings) into block states. */
public final class StateParser {
	private StateParser() {}

	@Nullable
	public static BlockState fromNbt(NbtCompound tag) {
		BlockState state = defaultState(tag.getString("Name"));
		if (state == null) return null;
		if (tag.contains("Properties", NbtElement.COMPOUND_TYPE)) {
			NbtCompound props = tag.getCompound("Properties");
			for (String key : props.getKeys()) {
				state = withProperty(state, key, props.getString(key));
			}
		}
		return state;
	}

	@Nullable
	public static BlockState fromString(String text) {
		String s = text.trim();
		int bracket = s.indexOf('[');
		String name = bracket < 0 ? s : s.substring(0, bracket);
		BlockState state = defaultState(name);
		if (state == null) return null;
		if (bracket >= 0) {
			int end = s.lastIndexOf(']');
			if (end < bracket) end = s.length();
			String inner = s.substring(bracket + 1, end);
			for (String part : inner.split(",")) {
				int eq = part.indexOf('=');
				if (eq <= 0) continue;
				state = withProperty(state, part.substring(0, eq).trim(), part.substring(eq + 1).trim());
			}
		}
		return state;
	}

	@Nullable
	private static BlockState defaultState(String name) {
		if (name == null || name.isEmpty()) return null;
		Identifier id = Identifier.tryParse(name.trim());
		if (id == null || !Registries.BLOCK.containsId(id)) return null;
		Block block = Registries.BLOCK.get(id);
		return block.getDefaultState();
	}

	private static BlockState withProperty(BlockState state, String key, String value) {
		Property<?> property = state.getBlock().getStateManager().getProperty(key);
		if (property == null) return state;
		return apply(state, property, value);
	}

	private static <T extends Comparable<T>> BlockState apply(BlockState state, Property<T> property, String value) {
		return property.parse(value).map(v -> state.with(property, v)).orElse(state);
	}

	public static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
		return property.name(state.get(property));
	}

	/** All properties as name -> value strings (used for the creative block_state component). */
	public static Map<String, String> propertyMap(BlockState state) {
		Map<String, String> out = new LinkedHashMap<>();
		for (Property<?> p : state.getProperties()) {
			out.put(p.getName(), valueName(state, p));
		}
		return out;
	}
}
