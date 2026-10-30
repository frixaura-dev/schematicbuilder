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

    private static int rescanTimer = 0;

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

            // keep the progress numbers fresh while building (cheap for small/medium builds only)
            if (BuildEngine.running && ++rescanTimer >= 200) {
                rescanTimer = 0;
                if (MaterialScanner.volume() <= 150_000) MaterialScanner.scan();
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> BuildEngine.stop("Disconnected"));

        // HudRenderCallback is deprecated; use the HUD element registry.
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.of("schembuilder", "status"), (ctx, tickCounter) -> {
            if (!BuildEngine.running || !Config.I.showHud) return;
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.options.hudHidden) return;

            int x = 6, y = 6, w = 176, h = 50;
            ctx.fill(x, y, x + w, y + h, 0xB0101418);
            Gfx.border(ctx, x, y, w, h, 0xFF39434E);

            int accent = switch (BuildEngine.mode) {
                case "Walking", "Arriving" -> 0xFF5AB0FF;
                case "Waiting" -> 0xFFFFD84D;
                case "Idle" -> 0xFFFF6B6B;
                default -> 0xFF55E07A;
            };
            ctx.fill(x + 1, y + 1, x + 3, y + h - 1, accent);

            ctx.drawTextWithShadow(mc.textRenderer, "● " + BuildEngine.mode.toUpperCase(), x + 8, y + 5, accent);
            ctx.drawTextWithShadow(mc.textRenderer, mc.textRenderer.trimToWidth(BuildEngine.status, w - 16), x + 8, y + 16, 0xFFE6EDF3);

            int total = MaterialScanner.totalBlocks, built = MaterialScanner.builtBlocks;
            float pct = total == 0 ? 0f : built / (float) total;
            int bw = w - 16;
            ctx.fill(x + 8, y + 29, x + 8 + bw, y + 34, 0xFF0E1216);
            ctx.fill(x + 8, y + 29, x + 8 + (int) (bw * pct), y + 34, 0xFF3CC8A0);

            String foot = "Placed " + BuildEngine.placedThisSession
                    + (total > 0 ? String.format("  ·  %.0f%%", pct * 100) : "")
                    + "  ·  [" + toggleKey.getBoundKeyLocalizedText().getString() + "] stop";
            ctx.drawTextWithShadow(mc.textRenderer, foot, x + 8, y + 38, 0xFF8B98A5);
        });
    }
}
