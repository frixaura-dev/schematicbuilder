package com.example.schembuilder;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

/**
 * Which horizontal layer of the schematic is being built.
 * Modes 0/1 follow the layer you are standing on (it updates when you move up or down a level).
 */
final class Layer {
    private Layer() {}

    static final String[] NAMES = {"Feet level", "Floor you stand on", "Whole build, bottom-up", "Whole build, nearest"};

    private static int stableFeet = Integer.MIN_VALUE;

    /** Remember the level we stand on (only updated on the ground so jumping doesn't change layers). */
    static void update(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p != null && p.isOnGround()) stableFeet = p.getBlockY();
    }

    static int mode() { return Math.max(0, Math.min(3, Config.I.layerMode)); }

    /** The Y of the layer to build, or null when building the whole schematic. */
    static Integer lockedY() {
        int m = mode();
        if (m > 1 || stableFeet == Integer.MIN_VALUE) return null;
        return m == 1 ? stableFeet - 1 : stableFeet;
    }

    static boolean bottomUp() { return mode() == 2; }

    static String label() { return NAMES[mode()]; }
}
