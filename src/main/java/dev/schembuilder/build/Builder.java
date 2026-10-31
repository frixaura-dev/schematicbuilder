package dev.schembuilder.build;

import dev.schembuilder.config.SBConfig;
import dev.schembuilder.mixin.SignEditScreenAccessor;
import dev.schembuilder.schematic.Placement;
import dev.schembuilder.schematic.SchematicBlock;
import dev.schembuilder.ui.Toasts;
import dev.schembuilder.ui.UiSound;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.AbstractSignBlock;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/**
 * The build loop. Drives the player with the normal movement keys and camera, and
 * places/breaks through the standard interaction manager, one action at a time.
 */
public final class Builder {
	public enum Status { STOPPED, RUNNING, PAUSED, DONE }

	public enum Phase {
		IDLE("Thinking"), MOVING("Walking"), AIMING("Aiming"), PLACING("Placing"), VERIFYING("Checking"),
		FIXING("Adjusting"), BREAKING("Breaking"), PILLARING("Climbing"), WAITING("Waiting");
		public final String label;

		Phase(String label) {
			this.label = label;
		}
	}

	private final MinecraftClient mc = MinecraftClient.getInstance();

	private Status status = Status.STOPPED;
	private Phase phase = Phase.IDLE;
	private Placement placement;
	private final List<Target> targets = new ArrayList<>();
	private int total, done;
	private int roundRobin;
	private long tick;

	@Nullable private Target current;
	@Nullable private PlacementSolver.Plan plan;
	private Phase afterAim = Phase.PLACING;
	private int wait, verifyTicks, phaseTicks, fixClicksLeft;
	private BlockHitResult actionHit;

	private List<BlockPos> path;
	private int pathIndex;
	private Vec3d lastPos = Vec3d.ZERO;
	private int stillTicks, failedPaths, idleNoWork;

	@Nullable private Target pendingSign;
	private int pendingSignTicks;

	private final Deque<Long> placeTimes = new ArrayDeque<>();
	private String detail = "";

	// ---------------------------------------------------------------- public API

	public Status status() { return status; }
	public Phase phase() { return phase; }
	public int total() { return total; }
	public int done() { return done; }
	public float progress() { return total == 0 ? 0f : done / (float) total; }
	public String detail() { return detail; }
	@Nullable public Target current() { return current; }
	@Nullable public Placement placement() { return placement; }
	public List<Target> targets() { return targets; }
	public boolean active() { return status == Status.RUNNING || status == Status.PAUSED; }

	/** Blocks per minute over the last minute. */
	public float rate() {
		long now = System.currentTimeMillis();
		while (!placeTimes.isEmpty() && now - placeTimes.peekFirst() > 60_000) placeTimes.pollFirst();
		if (placeTimes.size() < 2) return 0f;
		float span = Math.max(5_000, now - placeTimes.peekFirst()) / 60_000f;
		return placeTimes.size() / span;
	}

	/** Seconds left at the current rate, or -1 if unknown. */
	public int etaSeconds() {
		float r = rate();
		if (r <= 0.1f) return -1;
		return (int) ((total - done) / r * 60f);
	}

	public boolean start(Placement p) {
		ClientPlayerEntity player = mc.player;
		if (player == null || mc.world == null) return false;
		BlockPos standingOn = player.getBlockPos().down();
		BlockPos max = p.max();
		boolean inside = p.containsXZ(standingOn)
			&& standingOn.getY() >= p.origin.getY() - 1 && standingOn.getY() <= max.getY()
			&& !mc.world.getBlockState(standingOn).isAir();
		if (!inside) {
			Toasts.show("Can't start here", "Stand on a block inside the schematic", Toasts.Kind.ERROR);
			return false;
		}

		placement = p;
		targets.clear();
		SBConfig cfg = SBConfig.get();
		for (SchematicBlock b : p.schematic.blocks) {
			BlockState state = p.toWorld(b.state());
			if (automatic(state)) continue;
			Item item = state.getBlock().asItem();
			if (item == Items.AIR || !(item instanceof BlockItem)) continue;
			targets.add(new Target(p.toWorld(b.pos()), state, b.nbt(), item, false));
		}
		if (cfg.clearAir) {
			for (int i = 0; i < p.schematic.air.size(); i++) {
				BlockPos rel = BlockPos.fromLong(p.schematic.air.getLong(i));
				targets.add(new Target(p.toWorld(rel), net.minecraft.block.Blocks.AIR.getDefaultState(), null, Items.AIR, true));
			}
		}
		// Bottom-up, so supports exist before what sits on them.
		targets.sort(Comparator.comparingInt((Target t) -> t.pos.getY()).thenComparingInt(t -> t.pos.getX()).thenComparingInt(t -> t.pos.getZ()));
		total = targets.size();
		done = 0;
		placeTimes.clear();
		recountAll();
		resetAction();
		failedPaths = 0;
		idleNoWork = 0;
		status = Status.RUNNING;
		UiSound.start();
		Toasts.show("Building " + p.schematic.name, String.format("%,d blocks to check", total), Toasts.Kind.INFO);
		return true;
	}

	public void pause() {
		if (status != Status.RUNNING) return;
		status = Status.PAUSED;
		releaseInputs();
		cancelBreaking();
		phase = Phase.IDLE;
		UiSound.pause();
	}

	public void resume() {
		if (status != Status.PAUSED) return;
		status = Status.RUNNING;
		resetAction();
		failedPaths = 0;
		idleNoWork = 0;
		UiSound.start();
	}

	public void togglePause() {
		if (status == Status.RUNNING) pause();
		else if (status == Status.PAUSED) resume();
	}

	public void stop() {
		if (status == Status.STOPPED) return;
		releaseInputs();
		cancelBreaking();
		status = Status.STOPPED;
		phase = Phase.IDLE;
		current = null;
		pendingSign = null;
	}

	/** Blocks the game places automatically alongside another (door tops, bed heads) or that can't be placed. */
	public static boolean automatic(BlockState s) {
		if (s.isAir()) return true;
		if (s.getBlock() instanceof FluidBlock) return true;
		if (s.contains(Properties.DOUBLE_BLOCK_HALF) && s.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return true;
		return s.getBlock() instanceof BedBlock && s.get(BedBlock.PART) == BedPart.HEAD;
	}

	// ---------------------------------------------------------------- tick

	public void tick() {
		if (status != Status.RUNNING) return;
		ClientPlayerEntity p = mc.player;
		ClientWorld w = mc.world;
		if (p == null || w == null || mc.interactionManager == null) {
			stop();
			return;
		}
		if (!p.isAlive()) {
			pause();
			Toasts.show("Paused", "You died", Toasts.Kind.WARN);
			return;
		}
		tick++;

		if (mc.currentScreen != null) {
			if (mc.currentScreen instanceof AbstractSignEditScreen sign && pendingSign != null) {
				fillSign(sign);
			}
			releaseInputs();
			return;
		}
		if (pendingSign != null && ++pendingSignTicks > 20) pendingSign = null;

		recheck(w, 384);
		if (done >= total) {
			finish();
			return;
		}

		if (wait > 0) {
			wait--;
			return;
		}
		phaseTicks++;

		switch (phase) {
			case IDLE -> decide(p, w);
			case MOVING -> move(p, w);
			case AIMING -> aim(p);
			case PLACING -> place(p, w);
			case VERIFYING -> verify(w);
			case FIXING -> fix(p, w);
			case BREAKING -> breakBlock(p, w);
			case PILLARING -> pillar(p, w);
			case WAITING -> {
				if (phaseTicks > 30) setPhase(Phase.IDLE);
			}
		}
	}

	private void finish() {
		releaseInputs();
		status = Status.DONE;
		phase = Phase.IDLE;
		current = null;
		long failed = targets.stream().filter(t -> t.failed && !t.done).count();
		if (failed > 0) {
			Toasts.show("Build finished", failed + " blocks couldn't be placed", Toasts.Kind.WARN);
		} else {
			Toasts.show("Build complete", placement.schematic.name + " is done", Toasts.Kind.SUCCESS);
		}
	}

	// ---------------------------------------------------------------- progress

	private boolean satisfied(ClientWorld w, Target t) {
		BlockState actual = w.getBlockState(t.pos);
		if (t.clear) return actual.isAir() || actual.isReplaceable();
		return StateMatcher.matches(actual, t.state);
	}

	private void refresh(ClientWorld w, Target t) {
		boolean was = t.done;
		BlockState actual = w.getBlockState(t.pos);
		t.done = satisfied(w, t);
		t.wrong = !t.done && !actual.isAir() && !actual.isReplaceable();
		if (t.done && !was) done++;
		else if (!t.done && was) done--;
	}

	private void recountAll() {
		ClientWorld w = mc.world;
		done = 0;
		for (Target t : targets) {
			t.done = false;
			refresh(w, t);
		}
	}

	private void recheck(ClientWorld w, int count) {
		if (targets.isEmpty()) return;
		for (int i = 0; i < count; i++) {
			roundRobin = (roundRobin + 1) % targets.size();
			refresh(w, targets.get(roundRobin));
		}
	}

	// ---------------------------------------------------------------- decisions

	private double reach(ClientPlayerEntity p) {
		return p.getBlockInteractionRange() - 0.25;
	}

	private void decide(ClientPlayerEntity p, ClientWorld w) {
		SBConfig cfg = SBConfig.get();
		Vec3d eye = p.getEyePos();
		double reach = reach(p);
		double reachSq = (reach + 0.9) * (reach + 0.9);

		// Everything pending within arm's length, lowest layer first, then nearest.
		List<Target> near = new ArrayList<>();
		for (Target t : targets) {
			if (t.done || t.failed || t.deferUntil > tick) continue;
			if (eye.squaredDistanceTo(Vec3d.ofCenter(t.pos)) <= reachSq) near.add(t);
		}
		near.sort(Comparator.comparingInt((Target t) -> t.pos.getY())
			.thenComparingDouble(t -> eye.squaredDistanceTo(Vec3d.ofCenter(t.pos))));

		Target pillarCandidate = null;
		Item missing = null;
		int sims = 0;
		for (Target t : near) {
			refresh(w, t);
			if (t.done) continue;
			BlockState actual = w.getBlockState(t.pos);

			if (t.clear) {
				if (canBreak(actual)) {
					startBreak(p, t);
					return;
				}
				t.failed = true;
				continue;
			}

			StateMatcher.Diff diff = StateMatcher.diff(actual, t.state);
			boolean replaceable = actual.isAir() || actual.isReplaceable();

			if (!replaceable && (diff == StateMatcher.Diff.WRONG_BLOCK || diff == StateMatcher.Diff.MISMATCH)) {
				if (cfg.fixWrongBlocks && canBreak(actual) && t.breakAttempts < 3) {
					startBreak(p, t);
					return;
				}
				t.failed = true;
				continue;
			}
			if (diff == StateMatcher.Diff.FIXABLE) {
				startFix(p, w, t);
				return;
			}

			// Needs placing (fresh or adding to a partial block).
			if (intersectsPlayer(p, t.pos)) {
				if (pillarCandidate == null && t.pos.equals(p.getBlockPos()) && actual.isAir()
					&& t.state.isFullCube(w, t.pos) && !w.getBlockState(t.pos.down()).isAir()) {
					pillarCandidate = t;
				}
				continue;
			}
			if (!Inventory.has(p, t.item)) {
				if (missing == null) missing = t.item;
				continue;
			}
			if (sims++ >= 6) break;
			ItemStack stack = p.isCreative() ? Inventory.creativeStack(t, cfg.creativeNbt) : new ItemStack(t.item);
			boolean anyRotation = p.isCreative() && cfg.creativeNbt;
			PlacementSolver.Plan pl = PlacementSolver.solve(p, w, t.pos, t.state, stack, reach, anyRotation);
			if (pl != null) {
				startPlace(p, t, pl);
				return;
			}
			t.deferUntil = tick + 40;
		}

		if (pillarCandidate != null && Inventory.has(p, pillarCandidate.item)) {
			current = pillarCandidate;
			detail = "Climbing on " + name(pillarCandidate);
			setPhase(Phase.PILLARING);
			return;
		}

		// Nothing reachable: walk to the next block that needs work.
		Target goal = null;
		double bestScore = Double.MAX_VALUE;
		for (Target t : targets) {
			if (t.done || t.failed || t.deferUntil > tick) continue;
			if (!t.clear && !Inventory.has(p, t.item)) {
				if (missing == null) missing = t.item;
				continue;
			}
			double score = (t.pos.getY() - p.getBlockY()) * 6.0 + Math.sqrt(p.getBlockPos().getSquaredDistance(t.pos));
			if (score < bestScore) {
				bestScore = score;
				goal = t;
			}
		}

		if (goal == null) {
			if (missing != null) {
				pause();
				Toasts.show("Out of materials", "Need " + new ItemStack(missing).getName().getString(), Toasts.Kind.ERROR);
				return;
			}
			// Only deferred blocks remain (waiting for support). Give them a moment, then retry.
			if (++idleNoWork > 12) {
				for (Target t : targets) t.deferUntil = 0;
				idleNoWork = 0;
				if (++failedPaths > 6) {
					pause();
					Toasts.show("Stuck", "Remaining blocks have nothing to attach to", Toasts.Kind.WARN);
					return;
				}
			}
			detail = "Waiting for supports";
			setPhase(Phase.WAITING);
			return;
		}
		idleNoWork = 0;

		final BlockPos goalPos = goal.pos;
		final double reachLimit = reach - 0.4;
		LongOpenHashSet pending = new LongOpenHashSet();
		for (Target t : targets) if (!t.done && !t.clear) pending.add(t.pos.asLong());

		List<BlockPos> found = Navigator.find(w, p.getBlockPos(), goalPos, pos -> {
			if (pos.equals(goalPos) || pos.up().equals(goalPos)) return false;
			Vec3d e = Vec3d.ofBottomCenter(pos).add(0, 1.5, 0);
			return e.squaredDistanceTo(Vec3d.ofCenter(goalPos)) <= reachLimit * reachLimit;
		}, pending, 6000);

		if (found == null || found.size() <= 1) {
			goal.deferUntil = tick + 100;
			if (++failedPaths > 30) {
				pause();
				Toasts.show("Can't reach", "Walk closer to the remaining blocks, then resume", Toasts.Kind.WARN);
			}
			return;
		}
		failedPaths = 0;
		path = found;
		pathIndex = 1;
		stillTicks = 0;
		lastPos = p.getPos();
		current = goal;
		detail = "Heading to " + name(goal);
		setPhase(Phase.MOVING);
	}

	private boolean canBreak(BlockState s) {
		return !s.isAir() && s.getHardness(mc.world, BlockPos.ORIGIN) >= 0 && s.getFluidState().isEmpty();
	}

	private static boolean intersectsPlayer(ClientPlayerEntity p, BlockPos pos) {
		return p.getBoundingBox().intersects(new Box(pos));
	}

	private static String name(Target t) {
		return t.clear ? "air" : new ItemStack(t.item).getName().getString();
	}

	// ---------------------------------------------------------------- actions

	private void setPhase(Phase ph) {
		phase = ph;
		phaseTicks = 0;
	}

	private void resetAction() {
		releaseInputs();
		current = null;
		plan = null;
		path = null;
		setPhase(Phase.IDLE);
	}

	private void startPlace(ClientPlayerEntity p, Target t, PlacementSolver.Plan pl) {
		current = t;
		plan = pl;
		actionHit = pl.hit();
		afterAim = Phase.PLACING;
		detail = "Placing " + name(t);
		setPhase(Phase.AIMING);
	}

	private void startBreak(ClientPlayerEntity p, Target t) {
		current = t;
		actionHit = PlacementSolver.faceToward(t.pos, p.getEyePos());
		float[] r = Rotations.toward(p.getEyePos(), actionHit.getPos());
		plan = new PlacementSolver.Plan(t.pos, actionHit, r[0], r[1]);
		afterAim = Phase.BREAKING;
		detail = "Removing wrong block";
		Inventory.selectTool(p, mc.world.getBlockState(t.pos));
		setPhase(Phase.AIMING);
	}

	private void startFix(ClientPlayerEntity p, ClientWorld w, Target t) {
		current = t;
		fixClicksLeft = StateMatcher.fixClicks(w.getBlockState(t.pos), t.state);
		if (fixClicksLeft <= 0) {
			t.failed = true;
			resetAction();
			return;
		}
		actionHit = PlacementSolver.faceToward(t.pos, p.getEyePos());
		float[] r = Rotations.toward(p.getEyePos(), actionHit.getPos());
		plan = new PlacementSolver.Plan(t.pos, actionHit, r[0], r[1]);
		afterAim = Phase.FIXING;
		detail = "Adjusting " + name(t);
		setPhase(Phase.AIMING);
	}

	private void aim(ClientPlayerEntity p) {
		GameOptions o = mc.options;
		releaseMovement();
		// Sneak while placing so clicking chests/doors/etc. places against them instead of opening them.
		o.sneakKey.setPressed(afterAim == Phase.PLACING && !p.getAbilities().flying);
		if (current == null || plan == null) {
			resetAction();
			return;
		}
		if (Rotations.step(p, plan.yaw(), plan.pitch(), SBConfig.get().rotationSpeed)) {
			// Rotation goes to the server with next tick's movement packet; act on the tick after.
			setPhase(afterAim);
		} else if (phaseTicks > 60) {
			resetAction();
		}
	}

	private void place(ClientPlayerEntity p, ClientWorld w) {
		Target t = current;
		if (t == null || plan == null) {
			resetAction();
			return;
		}
		if (!p.isSneaking() && !p.getAbilities().flying) {
			mc.options.sneakKey.setPressed(true);
			if (phaseTicks > 10) resetAction();
			return;
		}
		if (!Inventory.select(mc, t, SBConfig.get().creativeNbt)) {
			if (!Inventory.has(p, t.item) || phaseTicks > 10) resetAction();
			return;
		}
		// Re-validate with the exact rotation we're holding, in case the world changed.
		ItemStack stack = p.getMainHandStack();
		boolean anyRotation = p.isCreative() && SBConfig.get().creativeNbt;
		PlacementSolver.Plan check = PlacementSolver.solve(p, w, t.pos, t.state, stack, reach(p), anyRotation);
		if (check == null) {
			t.deferUntil = tick + 40;
			resetAction();
			return;
		}
		if (Math.abs(MathHelper.wrapDegrees(check.yaw() - p.getYaw())) > 0.01f || Math.abs(check.pitch() - p.getPitch()) > 0.01f) {
			plan = check;
			setPhase(Phase.AIMING);
			return;
		}

		ActionResult result = mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, check.hit());
		if (result.isAccepted()) p.swingHand(Hand.MAIN_HAND);
		t.attempts++;
		if (t.state.getBlock() instanceof AbstractSignBlock && SBConfig.get().signText && !(p.isCreative() && SBConfig.get().creativeNbt && t.nbt != null)) {
			pendingSign = t;
			pendingSignTicks = 0;
		}
		verifyTicks = 3;
		setPhase(Phase.VERIFYING);
	}

	private void verify(ClientWorld w) {
		Target t = current;
		if (t == null) {
			resetAction();
			return;
		}
		if (--verifyTicks > 0) return;
		refresh(w, t);
		mc.options.sneakKey.setPressed(false);
		if (t.done) {
			placeTimes.addLast(System.currentTimeMillis());
			t.attempts = 0;
			resetAction();
			wait = SBConfig.get().actionDelay;
			return;
		}
		BlockState actual = w.getBlockState(t.pos);
		StateMatcher.Diff diff = StateMatcher.diff(actual, t.state);
		if (diff == StateMatcher.Diff.COUNT || diff == StateMatcher.Diff.FIXABLE) {
			resetAction();
			return;
		}
		if (t.attempts >= 4) {
			t.failed = true;
			Toasts.show("Skipped a block", name(t) + " at " + t.pos.toShortString(), Toasts.Kind.WARN);
		} else if (actual.isAir() || actual.isReplaceable()) {
			t.deferUntil = tick + 20;
		}
		resetAction();
	}

	private void fix(ClientPlayerEntity p, ClientWorld w) {
		Target t = current;
		if (t == null) {
			resetAction();
			return;
		}
		mc.options.sneakKey.setPressed(false);
		if (p.isSneaking()) return;
		if (phaseTicks % 3 != 0) return;
		if (fixClicksLeft > 0) {
			ActionResult r = mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, actionHit);
			if (r.isAccepted()) p.swingHand(Hand.MAIN_HAND);
			fixClicksLeft--;
			return;
		}
		verifyTicks = 3;
		t.attempts++;
		setPhase(Phase.VERIFYING);
	}

	private void breakBlock(ClientPlayerEntity p, ClientWorld w) {
		Target t = current;
		if (t == null) {
			resetAction();
			return;
		}
		mc.options.sneakKey.setPressed(false);
		BlockState actual = w.getBlockState(t.pos);
		if (actual.isAir() || actual.isReplaceable()) {
			cancelBreaking();
			t.breakAttempts++;
			refresh(w, t);
			resetAction();
			wait = SBConfig.get().actionDelay;
			return;
		}
		if (phaseTicks > 300) {
			cancelBreaking();
			t.breakAttempts++;
			if (t.breakAttempts >= 3) t.failed = true;
			resetAction();
			return;
		}
		mc.interactionManager.updateBlockBreakingProgress(t.pos, actionHit.getSide());
		p.swingHand(Hand.MAIN_HAND);
	}

	private void pillar(ClientPlayerEntity p, ClientWorld w) {
		Target t = current;
		if (t == null || !Inventory.has(p, t.item)) {
			resetAction();
			return;
		}
		GameOptions o = mc.options;
		releaseMovement();
		Rotations.step(p, p.getYaw(), 90f, SBConfig.get().rotationSpeed);
		if (p.getPitch() < 89f) return;
		if (!Inventory.select(mc, t, SBConfig.get().creativeNbt)) return;

		if (p.isOnGround() && p.getY() < t.pos.getY() + 0.5) {
			o.jumpKey.setPressed(true);
			return;
		}
		o.jumpKey.setPressed(false);
		if (p.getY() >= t.pos.getY() + 1.0) {
			ItemStack stack = p.getMainHandStack();
			PlacementSolver.Plan pl = PlacementSolver.solve(p, w, t.pos, t.state, stack, reach(p), p.isCreative() && SBConfig.get().creativeNbt);
			if (pl != null && Math.abs(MathHelper.wrapDegrees(pl.yaw() - p.getYaw())) < 0.01f && Math.abs(pl.pitch() - p.getPitch()) < 0.01f) {
				ActionResult r = mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, pl.hit());
				if (r.isAccepted()) p.swingHand(Hand.MAIN_HAND);
				t.attempts++;
				current = t;
				verifyTicks = 4;
				setPhase(Phase.VERIFYING);
				return;
			}
		}
		if (phaseTicks > 40) {
			t.deferUntil = tick + 100;
			resetAction();
		}
	}

	private void move(ClientPlayerEntity p, ClientWorld w) {
		GameOptions o = mc.options;
		o.sneakKey.setPressed(false);
		if (path == null || pathIndex >= path.size()) {
			resetAction();
			return;
		}
		// Arrived early? Stop as soon as the goal block is within reach.
		if (current != null && p.isOnGround()
			&& p.getEyePos().squaredDistanceTo(Vec3d.ofCenter(current.pos)) <= Math.pow(reach(p) - 0.3, 2)
			&& !intersectsPlayer(p, current.pos)) {
			resetAction();
			return;
		}

		BlockPos node = path.get(pathIndex);
		Vec3d target = Vec3d.ofBottomCenter(node);
		double dx = target.x - p.getX(), dz = target.z - p.getZ();
		double horiz = Math.sqrt(dx * dx + dz * dz);
		if (horiz < 0.35 && Math.abs(p.getY() - node.getY()) < 1.1) {
			pathIndex++;
			return;
		}

		float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
		Rotations.step(p, yaw, 12f, SBConfig.get().rotationSpeed);
		float off = Math.abs(MathHelper.wrapDegrees(yaw - p.getYaw()));
		o.forwardKey.setPressed(off < 35f);
		o.backKey.setPressed(false);
		o.leftKey.setPressed(false);
		o.rightKey.setPressed(false);
		o.sprintKey.setPressed(false);
		boolean higher = node.getY() > p.getBlockY();
		o.jumpKey.setPressed(p.isOnGround() && (higher || p.horizontalCollision));

		if (p.getPos().squaredDistanceTo(lastPos) < 0.0025) {
			if (++stillTicks > 40) {
				resetAction();
				return;
			}
		} else {
			stillTicks = 0;
			lastPos = p.getPos();
		}
		if (phaseTicks > 20 * 60) resetAction();
	}

	// ---------------------------------------------------------------- signs

	private void fillSign(AbstractSignEditScreen screen) {
		Target t = pendingSign;
		pendingSign = null;
		if (t == null || t.nbt == null) {
			screen.close();
			return;
		}
		SignEditScreenAccessor acc = (SignEditScreenAccessor) screen;
		String[] lines = signLines(t.nbt, acc.schembuilder$front());
		String[] msgs = acc.schembuilder$messages();
		for (int i = 0; i < msgs.length && i < lines.length; i++) msgs[i] = lines[i];
		screen.close();
	}

	private String[] signLines(NbtCompound nbt, boolean front) {
		String[] out = {"", "", "", ""};
		String key = front ? "front_text" : "back_text";
		if (nbt.contains(key, NbtElement.COMPOUND_TYPE)) {
			NbtList list = nbt.getCompound(key).getList("messages", NbtElement.STRING_TYPE);
			for (int i = 0; i < 4 && i < list.size(); i++) out[i] = plain(list.getString(i));
		} else if (front) {
			for (int i = 0; i < 4; i++) out[i] = plain(nbt.getString("Text" + (i + 1)));
		}
		return out;
	}

	private String plain(String json) {
		if (json == null || json.isEmpty()) return "";
		try {
			Text text = Text.Serialization.fromJson(json, mc.world.getRegistryManager());
			return text == null ? "" : text.getString();
		} catch (Exception e) {
			return json.startsWith("\"") && json.endsWith("\"") && json.length() >= 2 ? json.substring(1, json.length() - 1) : json;
		}
	}

	// ---------------------------------------------------------------- inputs

	private void releaseMovement() {
		GameOptions o = mc.options;
		o.forwardKey.setPressed(false);
		o.backKey.setPressed(false);
		o.leftKey.setPressed(false);
		o.rightKey.setPressed(false);
		o.jumpKey.setPressed(false);
		o.sprintKey.setPressed(false);
	}

	public void releaseInputs() {
		releaseMovement();
		mc.options.sneakKey.setPressed(false);
	}

	private void cancelBreaking() {
		if (mc.interactionManager != null) mc.interactionManager.cancelBlockBreaking();
	}
}
