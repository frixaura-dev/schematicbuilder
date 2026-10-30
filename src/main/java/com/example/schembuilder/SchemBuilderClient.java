package com.example.schembuilder;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public class SchemBuilderClient implements ClientModInitializer {
    public static KeyBinding guiKey, toggleKey, panicKey;

    // 1.21.9+: key binds take a Category object instead of a translation string.
    // Translation key: key.category.schembuilder.main
    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(Identifier.of("schembuilder", "main"));

    @Override
    public void onInitializeClient() {
        Config.load();

        guiKey    = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.schembuilder.gui",    InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_B, CATEGORY));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.schembuilder.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V, CATEGORY));
        panicKey  = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.schembuilder.panic",  InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (guiKey.wasPressed()) { MaterialScanner.scan(); mc.setScreen(new BuilderScreen()); }
            while (toggleKey.wasPressed()) {
                BuildEngine.toggle();
                if (mc.player != null)
                    mc.player.sendMessage(Text.literal("§6[Builder] §f" + (BuildEngine.running ? "Started" : "Stopped")), true);
            }
            while (panicKey.wasPressed()) {
                BuildEngine.stop("Emergency stop");
                if (mc.player != null) mc.player.sendMessage(Text.literal("§c[Builder] Emergency stop"), true);
            }
            BuildEngine.tick(mc);
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> BuildEngine.stop("Disconnected"));

        // HudRenderCallback is deprecated; use the HUD element registry.
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.of("schembuilder", "status"), (ctx, tickCounter) -> {
            if (!BuildEngine.running) return;
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.options.hudHidden) return;
            ctx.drawTextWithShadow(mc.textRenderer, "§6Builder §a● §f" + BuildEngine.status, 6, 6, 0xFFFFFFFF);
            ctx.drawTextWithShadow(mc.textRenderer, "§7Placed: " + BuildEngine.placedThisSession
                    + "   [" + toggleKey.getBoundKeyLocalizedText().getString() + "] stop", 6, 18, 0xFFFFFFFF);
        });
    }
}
