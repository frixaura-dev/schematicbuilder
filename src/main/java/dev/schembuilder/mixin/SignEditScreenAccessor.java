package dev.schembuilder.mixin;

import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Used to fill in sign text from the schematic when the vanilla sign editor opens. */
@Mixin(AbstractSignEditScreen.class)
public interface SignEditScreenAccessor {
	@Accessor("messages")
	String[] schembuilder$messages();

	@Accessor("blockEntity")
	SignBlockEntity schembuilder$blockEntity();

	@Accessor("front")
	boolean schembuilder$front();
}
