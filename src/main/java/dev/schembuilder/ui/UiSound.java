package dev.schembuilder.ui;

import dev.schembuilder.config.SBConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;

/** Interface sounds, built from vanilla sounds so no audio files are needed. */
public final class UiSound {
	private UiSound() {}

	private static long lastHover;

	public static void play(SoundEvent sound, float pitch, float volume) {
		if (!SBConfig.get().sounds) return;
		MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(sound, pitch, volume));
	}

	public static void play(RegistryEntry<SoundEvent> sound, float pitch, float volume) {
		play(sound.value(), pitch, volume);
	}

	public static void click() { play(SoundEvents.UI_BUTTON_CLICK, 1.35f, 0.35f); }

	public static void hover() {
		long now = System.currentTimeMillis();
		if (now - lastHover < 45) return;
		lastHover = now;
		play(SoundEvents.BLOCK_NOTE_BLOCK_HAT, 2.0f, 0.12f);
	}

	public static void toggle(boolean on) {
		play(SoundEvents.BLOCK_NOTE_BLOCK_PLING, on ? 1.9f : 1.3f, 0.3f);
	}

	public static void open() { play(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1.3f, 0.9f); }

	public static void close() { play(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 0.85f, 0.5f); }

	public static void select() { play(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.6f, 0.25f); }

	public static void start() {
		play(SoundEvents.BLOCK_NOTE_BLOCK_PLING, 1.2f, 0.5f);
		play(SoundEvents.BLOCK_NOTE_BLOCK_PLING, 1.8f, 0.35f);
	}

	public static void pause() { play(SoundEvents.BLOCK_NOTE_BLOCK_PLING, 0.9f, 0.4f); }

	public static void done() { play(SoundEvents.ENTITY_PLAYER_LEVELUP, 1.2f, 0.6f); }

	public static void error() { play(SoundEvents.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.6f); }

	public static void tick() { play(SoundEvents.BLOCK_NOTE_BLOCK_HAT, 1.6f, 0.2f); }
}
