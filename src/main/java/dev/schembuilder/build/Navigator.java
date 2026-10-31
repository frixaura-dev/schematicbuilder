package dev.schembuilder.build;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CampfireBlock;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.WallBlock;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.Predicate;

/** Small A* over standable block positions: walk, step up one, drop up to three. */
public final class Navigator {
	private Navigator() {}

	private static final class Node implements Comparable<Node> {
		final BlockPos pos;
		final Node parent;
		final double g, f;

		Node(BlockPos pos, Node parent, double g, double h) {
			this.pos = pos;
			this.parent = parent;
			this.g = g;
			this.f = g + h;
		}

		@Override
		public int compareTo(Node o) {
			return Double.compare(f, o.f);
		}
	}

	@Nullable
	public static List<BlockPos> find(ClientWorld w, BlockPos start, BlockPos hint, Predicate<BlockPos> goal,
									  LongSet discouraged, int maxNodes) {
		PriorityQueue<Node> open = new PriorityQueue<>();
		Long2ObjectOpenHashMap<Node> best = new Long2ObjectOpenHashMap<>();
		Node first = new Node(start, null, 0, h(start, hint));
		open.add(first);
		best.put(start.asLong(), first);
		int expanded = 0;

		while (!open.isEmpty() && expanded < maxNodes) {
			Node n = open.poll();
			Node recorded = best.get(n.pos.asLong());
			if (recorded != null && recorded.g < n.g) continue;
			if (goal.test(n.pos)) return rebuild(n);
			expanded++;

			for (Direction d : Direction.Type.HORIZONTAL) {
				BlockPos q = n.pos.offset(d);
				if (Math.abs(q.getX() - start.getX()) > 64 || Math.abs(q.getZ() - start.getZ()) > 64) continue;
				BlockPos next = null;
				double cost = 1;
				if (canStand(w, q)) {
					next = q;
				} else if (canStand(w, q.up()) && passable(w, n.pos.up(2))) {
					next = q.up();
					cost = 2;
				} else if (passable(w, q) && passable(w, q.up())) {
					for (int k = 1; k <= 3; k++) {
						BlockPos r = q.down(k);
						if (canStand(w, r)) {
							next = r;
							cost = 1 + k * 0.5;
							break;
						}
						if (!passable(w, r)) break;
					}
				}
				if (next == null) continue;
				if (discouraged.contains(next.asLong()) || discouraged.contains(next.up().asLong())) cost += 6;
				double g = n.g + cost;
				Node old = best.get(next.asLong());
				if (old != null && old.g <= g) continue;
				Node nn = new Node(next, n, g, h(next, hint));
				best.put(next.asLong(), nn);
				open.add(nn);
			}
		}
		return null;
	}

	private static double h(BlockPos a, BlockPos b) {
		return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY()) + Math.abs(a.getZ() - b.getZ());
	}

	private static List<BlockPos> rebuild(Node n) {
		List<BlockPos> path = new ArrayList<>();
		for (Node c = n; c != null; c = c.parent) path.add(c.pos);
		Collections.reverse(path);
		return path;
	}

	public static boolean passable(ClientWorld w, BlockPos p) {
		BlockState s = w.getBlockState(p);
		if (!s.getCollisionShape(w, p).isEmpty()) return false;
		if (s.getFluidState().isIn(FluidTags.LAVA)) return false;
		if (s.getBlock() instanceof AbstractFireBlock) return false;
		return !s.isOf(Blocks.SWEET_BERRY_BUSH) && !s.isOf(Blocks.POWDER_SNOW) && !s.isOf(Blocks.COBWEB);
	}

	/** Feet at p: feet and head clear, something solid to stand on. Low blocks (slabs, carpet) in the feet slot are fine. */
	public static boolean canStand(ClientWorld w, BlockPos p) {
		BlockState feet = w.getBlockState(p);
		VoxelShape feetShape = feet.getCollisionShape(w, p);
		if (!feetShape.isEmpty()) {
			if (feetShape.getMax(Direction.Axis.Y) > 0.5) return false;
			if (feet.getBlock() instanceof FenceBlock || feet.getBlock() instanceof WallBlock) return false;
			return passable(w, p.up()) && passable(w, p.up(2));
		}
		if (!passable(w, p) || !passable(w, p.up())) return false;
		BlockPos below = p.down();
		BlockState b = w.getBlockState(below);
		if (b.getCollisionShape(w, below).isEmpty()) return false;
		if (b.isOf(Blocks.MAGMA_BLOCK) || b.isOf(Blocks.CACTUS) || b.getBlock() instanceof CampfireBlock) return false;
		return !(b.getBlock() instanceof FenceBlock) && !(b.getBlock() instanceof WallBlock) && !(b.getBlock() instanceof FenceGateBlock);
	}
}
