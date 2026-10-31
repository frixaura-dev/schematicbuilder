package dev.schembuilder;

import dev.schembuilder.build.Builder;
import dev.schembuilder.config.SBConfig;
import dev.schembuilder.render.OverlayRenderer;
import dev.schembuilder.schematic.Placement;
import dev.schembuilder.ui.BuildHud;
import dev.schembuilder.ui.BuilderScreen;
import dev.schembuilder.ui.Toasts;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public final class SchemBuilderClient implements ClientModInitializer {
	public static final Builder BUILDER = new Builder();
	/** The schematic currently selected in the interface (and where it sits). */
	public static Placement placement;

	private static KeyBinding openKey, pauseKey, stopKey;

	public static KeyBinding pauseKey() {
		return pauseKey;
	}

	@Override
	public void onInitializeClient() {
		SBConfig.load();

		openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.schembuilder.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_B, "category.schembuilder"));
		pauseKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.schembuilder.pause", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_N, "category.schembuilder"));
		stopKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.schembuilder.stop", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "category.schembuilder"));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openKey.wasPressed()) {
				if (client.currentScreen == null) client.setScreen(new BuilderScreen());
			}
			while (pauseKey.wasPressed()) {
				if (BUILDER.status() == Builder.Status.RUNNING) {
					BUILDER.pause();
					Toasts.show("Build paused", "Press " + pauseKey.getBoundKeyLocalizedText().getString() + " to resume", Toasts.Kind.INFO);
				} else if (BUILDER.status() == Builder.Status.PAUSED) {
					BUILDER.resume();
					Toasts.show("Build resumed", "", Toasts.Kind.INFO);
				}
			}
			while (stopKey.wasPressed()) {
				if (BUILDER.active()) {
					BUILDER.stop();
					Toasts.show("Build stopped", "", Toasts.Kind.WARN);
				}
			}
			BUILDER.tick();
		});

		HudRenderCallback.EVENT.register((context, tickCounter) -> {
			BuildHud.render(context);
			if (net.minecraft.client.MinecraftClient.getInstance().currentScreen == null) {
				Toasts.render(context, context.getScaledWindowWidth(), context.getScaledWindowHeight());
			}
		});

		WorldRenderEvents.LAST.register(OverlayRenderer::render);

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(BUILDER::stop));
	}
}
