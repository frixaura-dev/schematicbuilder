package dev.schembuilder.build;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public final class Rotations {
	private Rotations() {}

	/** {yaw, pitch} looking from one point to another. */
	public static float[] toward(Vec3d from, Vec3d to) {
		double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
		double h = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, h));
		return new float[]{MathHelper.wrapDegrees(yaw), MathHelper.clamp(pitch, -90f, 90f)};
	}

	public static float angleTo(PlayerEntity p, float yaw, float pitch) {
		float dy = Math.abs(MathHelper.wrapDegrees(yaw - p.getYaw()));
		float dp = Math.abs(pitch - p.getPitch());
		return dy + dp;
	}

	/**
	 * Turns the player toward a rotation by at most maxStep degrees, with easing so it settles smoothly.
	 * Returns true once exactly on target. Yaw is kept continuous (no 360 jumps) so the camera never spins.
	 */
	public static boolean step(PlayerEntity p, float yaw, float pitch, float maxStep) {
		float dYaw = MathHelper.wrapDegrees(yaw - p.getYaw());
		float dPitch = pitch - p.getPitch();
		float dist = (float) Math.sqrt(dYaw * dYaw + dPitch * dPitch);
		if (dist < 0.75f) {
			p.setYaw(p.getYaw() + dYaw);
			p.setPitch(pitch);
			return true;
		}
		float eased = Math.min(maxStep, Math.max(2.5f, dist * 0.55f));
		float k = Math.min(1f, eased / dist);
		p.setYaw(p.getYaw() + dYaw * k);
		p.setPitch(MathHelper.clamp(p.getPitch() + dPitch * k, -90f, 90f));
		return false;
	}
}
