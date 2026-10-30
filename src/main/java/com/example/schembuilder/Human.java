package com.example.schembuilder;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;

import java.util.Random;

/**
 * Natural variation so the builder doesn't act like a metronome: uneven delays, small pauses,
 * the odd short break, and a gentle camera drift while idle. Strength is the "Human-ness" setting.
 * (This makes it behave more like a relaxed player; it is not a way around server rules.)
 */
final class Human {
    private Human() {}

    static final Random R = new Random();

    static String idleMode = "Pause";
    static String idleText = "";

    private static int placementsUntilPause = 30;
    private static int pauseTicks = 0;
    private static long breakUntil = 0, nextBreakAt = 0;

    static double level() { return MathHelper.clamp(Config.I.humanLevel, 0, 100) / 100.0; }

    static void reset() {
        placementsUntilPause = 12 + R.nextInt(40);
        pauseTicks = 0;
        breakUntil = 0;
        nextBreakAt = System.currentTimeMillis() + (6 + R.nextInt(9)) * 60_000L;
    }

    /** Ticks to wait before the next placement: right-skewed, so mostly near the setting but sometimes slower. */
    static int delay(int lo, int hi) {
        int base = lo + (hi > lo ? R.nextInt(hi - lo + 1) : 0);
        double lv = level();
        if (lv <= 0) return base;
        double v = base * Math.exp(R.nextGaussian() * 0.38 * lv);
        if (R.nextDouble() < 0.04 * lv) v += 6 + R.nextInt(16);   // brief hesitation
        return (int) Math.max(1, Math.min(80, Math.round(Math.max(lo * 0.7, v))));
    }

    static void afterPlace() {
        if (level() <= 0) return;
        if (--placementsUntilPause <= 0) {
            pauseTicks = Math.max(10, (int) ((15 + R.nextInt(60)) * level()));
            placementsUntilPause = 12 + R.nextInt(45);
        }
    }

    /** True while we should stand still (short pause or longer break). Call once per tick. */
    static boolean idling() {
        if (level() <= 0) return false;
        long now = System.currentTimeMillis();
        if (Config.I.takeBreaks) {
            if (now >= nextBreakAt && breakUntil <= now) {
                breakUntil = now + (20 + R.nextInt(60)) * 1000L;
                nextBreakAt = breakUntil + (6 + R.nextInt(10)) * 60_000L;
            }
            if (breakUntil > now) {
                idleMode = "Break";
                idleText = "Taking a short break (" + ((breakUntil - now) / 1000 + 1) + "s)";
                return true;
            }
        }
        if (pauseTicks > 0) {
            pauseTicks--;
            idleMode = "Pause";
            idleText = "Pausing for a moment...";
            return true;
        }
        return false;
    }

    /** Tiny smooth camera sway, like a hand resting on the mouse. */
    static void drift(ClientPlayerEntity p) {
        double lv = level();
        if (lv <= 0) return;
        double t = System.nanoTime() / 1e9;
        float dyaw = (float) (lv * 0.10 * (Math.sin(t * 1.1) + 0.6 * Math.sin(t * 2.3 + 1.7)));
        float dpit = (float) (lv * 0.07 * (Math.sin(t * 0.9 + 0.4) + 0.5 * Math.sin(t * 2.9)));
        p.setYaw(p.getYaw() + dyaw);
        p.setPitch(MathHelper.clamp(p.getPitch() + dpit, -90f, 90f));
    }

    /** Slow wandering offset in degrees (for the walking direction). */
    static double wander(double scale) {
        double t = System.nanoTime() / 1e9;
        return scale * level() * (0.6 * Math.sin(t * 0.8) + 0.4 * Math.sin(t * 1.9 + 2));
    }
}
