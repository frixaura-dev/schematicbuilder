package dev.schembuilder.build;

import dev.schembuilder.mixin.BlockItemInvoker;
import net.minecraft.block.BlockState;
import net.minecraft.block.FallingBlock;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Works out how to click so the game itself produces the right block state.
 *
 * Rather than hard-coding rules for every block, it asks the item's own placement logic:
 * for each clickable face, hit point and look direction it simulates the placement
 * (temporarily setting the player's rotation, sending nothing) and keeps the first
 * combination whose result matches the schematic. That covers stairs, slabs, logs,
 * doors and hinges, trapdoors, observers, buttons, signs with 16 rotations, wall torches, etc.
 */
public final class PlacementSolver {
	private PlacementSolver() {}

	public record Plan(BlockPos target, BlockHitResult hit, float yaw, float pitch) {}

	private record Click(BlockPos clicked, Direction side, Vec3d point) {}

	private static final float[] CARDINAL_YAWS = {0f, 90f, 180f, -90f};
	private static final float[] PITCHES = {0f, 72f, -72f};

	@Nullable
	public static Plan solve(ClientPlayerEntity player, ClientWorld world, BlockPos target, BlockState want,
							 ItemStack stack, double reach, boolean anyRotationOk) {
		if (!(stack.getItem() instanceof BlockItem blockItem)) return null;
		if (want.getBlock() instanceof FallingBlock) {
			BlockState below = world.getBlockState(target.down());
			if (below.isAir() || below.isReplaceable()) return null;
		}

		Vec3d eye = player.getEyePos();
		double reachSq = reach * reach;
		List<Click> clicks = clicks(world, target, eye, reachSq);
		if (clicks.isEmpty()) return null;

		List<float[]> sectors = new ArrayList<>();
		if (want.contains(Properties.ROTATION)) {
			for (int i = 0; i < 16; i++) sectors.add(new float[]{i * 22.5f - 180f, 0f});
		} else {
			for (float y : CARDINAL_YAWS) for (float p : PITCHES) sectors.add(new float[]{y, p});
		}
		sectors.sort(Comparator.comparingDouble(r -> Rotations.angleTo(player, r[0], r[1])));

		float oldYaw = player.getYaw(), oldPitch = player.getPitch();
		try {
			// Pass 1: keep the current view (no turning needed), then look straight at the hit point.
			for (Click c : clicks) {
				Plan plan = trial(player, blockItem, stack, c, target, want, oldYaw, oldPitch, anyRotationOk);
				if (plan != null) return plan;
				float[] aim = Rotations.toward(eye, c.point);
				plan = trial(player, blockItem, stack, c, target, want, aim[0], aim[1], anyRotationOk);
				if (plan != null) return plan;
			}
			// Pass 2: whole look-direction sectors, nearest to where the camera already points first.
			for (float[] r : sectors) {
				for (Click c : clicks) {
					Plan plan = trial(player, blockItem, stack, c, target, want, r[0], r[1], anyRotationOk);
					if (plan != null) return plan;
				}
			}
		} finally {
			player.setYaw(oldYaw);
			player.setPitch(oldPitch);
		}
		return null;
	}

	@Nullable
	private static Plan trial(ClientPlayerEntity player, BlockItem item, ItemStack stack, Click c, BlockPos target,
							  BlockState want, float yaw, float pitch, boolean anyRotationOk) {
		player.setYaw(yaw);
		player.setPitch(pitch);
		BlockHitResult hit = new BlockHitResult(c.point, c.side, c.clicked, false);
		ItemPlacementContext ctx = new ItemPlacementContext(player, Hand.MAIN_HAND, stack, hit);
		if (!ctx.canPlace() || !ctx.getBlockPos().equals(target)) return null;
		BlockState result = ((BlockItemInvoker) item).schembuilder$placementState(ctx);
		if (result == null || result.getBlock() != want.getBlock()) return null;
		if (!anyRotationOk && !StateMatcher.placementCompatible(result, want)) return null;
		return new Plan(target, hit, yaw, pitch);
	}

	/** Faces we could click to place into target, with a few hit points each, nearest first. */
	private static List<Click> clicks(ClientWorld world, BlockPos target, Vec3d eye, double reachSq) {
		List<Click> out = new ArrayList<>();
		BlockState existing = world.getBlockState(target);
		boolean partial = !existing.isAir() && !existing.isReplaceable();

		for (Direction d : Direction.values()) {
			BlockPos clicked;
			Direction side;
			if (partial) {
				// Adding to a block already there (second slab half, extra candle...): click the block itself.
				clicked = target;
				side = d;
			} else {
				clicked = target.offset(d);
				side = d.getOpposite();
				BlockState n = world.getBlockState(clicked);
				if (n.isAir() || n.isReplaceable() || !n.getFluidState().isEmpty()) continue;
				if (n.getOutlineShape(world, clicked).isEmpty()) continue;
			}
			Vec3d faceCenter = Vec3d.ofCenter(clicked).add(side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5);
			for (Vec3d p : facePoints(faceCenter, side)) {
				if (eye.squaredDistanceTo(p) <= reachSq) out.add(new Click(clicked, side, p));
			}
		}
		out.sort(Comparator.comparingDouble(c -> eye.squaredDistanceTo(c.point)));
		return out;
	}

	private static List<Vec3d> facePoints(Vec3d center, Direction side) {
		List<Vec3d> pts = new ArrayList<>(5);
		pts.add(center);
		double o = 0.25;
		switch (side.getAxis()) {
			case Y -> {
				pts.add(center.add(-o, 0, -o)); pts.add(center.add(o, 0, -o));
				pts.add(center.add(-o, 0, o)); pts.add(center.add(o, 0, o));
			}
			case X -> {
				pts.add(center.add(0, -o, -o)); pts.add(center.add(0, -o, o));
				pts.add(center.add(0, o, -o)); pts.add(center.add(0, o, o));
			}
			case Z -> {
				pts.add(center.add(-o, -o, 0)); pts.add(center.add(o, -o, 0));
				pts.add(center.add(-o, o, 0)); pts.add(center.add(o, o, 0));
			}
		}
		return pts;
	}

	/** Face of a block to click for breaking or right-click fixes: the one facing the eye. */
	public static BlockHitResult faceToward(BlockPos pos, Vec3d eye) {
		Vec3d c = Vec3d.ofCenter(pos);
		Direction side = Direction.getFacing(eye.x - c.x, eye.y - c.y, eye.z - c.z);
		Vec3d point = c.add(side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5);
		return new BlockHitResult(point, side, pos, false);
	}
}
