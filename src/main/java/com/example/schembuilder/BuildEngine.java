package com.example.schembuilder;

import net.minecraft.block.*;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Places blocks like a player would: picks something in reach, smoothly turns the camera toward a
 * visible face, waits a randomised delay, then right-clicks. When nothing is in reach it hands
 * over to the Navigator, which walks to the next spot. Crouches at ledges.
 */
public final class BuildEngine {
    private BuildEngine() {}

    public static boolean running = false;
    public static String status = "Idle";
    /** Idle / Placing / Walking / Arriving / Waiting - shown in the UI. */
    public static String mode = "Idle";
    public static int placedThisSession = 0;
    private static long startMs = 0, accumMs = 0;

    private static final Random RNG = new Random();

    record Plan(BlockPos target, BlockPos neighbor, Direction side, Vec3d aim, BlockState want) {}

    private static Plan plan;
    private static int cooldown = 0;
    /** How many times we tried to place at a position (gives up after a few so a server-rejected spot can't loop forever). */
    private static final Map<Long, Integer> attempts = new HashMap<>();

    // ---------------------------------------------------------------- control
    public static void start() {
        running = true; plan = null; cooldown = 0; status = "Building..."; mode = "Starting";
        placedThisSession = 0; accumMs = 0; startMs = System.currentTimeMillis();
        attempts.clear();
        Navigator.reset();
    }

    public static void stop(String why) {
        if (running) accumMs += System.currentTimeMillis() - startMs;
        running = false; plan = null; status = why; mode = "Idle";
        Navigator.releaseAll(MinecraftClient.getInstance());
        Navigator.reset();
    }

    public static void toggle() { if (running) stop("Stopped"); else start(); }

    public static long activeMs() { return accumMs + (running ? System.currentTimeMillis() - startMs : 0); }

    // ------------------------------------------------------------------- tick
    public static void tick(MinecraftClient mc) {
        if (!running) return;
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        if (p == null || w == null || mc.interactionManager == null) { stop("Not in a world"); return; }
        if (!p.isAlive()) { stop("Stopped - you died"); return; }
        if (mc.currentScreen != null) { Navigator.releaseAll(mc); return; } // paused while a menu is open

        Navigator.beginTick();
        if (LitematicaBridge.regions().isEmpty()) {
            Navigator.releaseAll(mc);
            mode = "Idle";
            status = LitematicaBridge.lastError != null ? LitematicaBridge.lastError : "No schematic placement found (load one in Litematica)";
            return;
        }

        // Re-validate current plan.
        if (plan != null) {
            boolean stillNeeded = w.getBlockState(plan.target).isReplaceable();
            boolean inReach = p.getEyePos().distanceTo(plan.aim) <= Config.I.reach() + 0.25;
            if (!stillNeeded || !inReach) plan = null;
        }

        if (plan == null) {
            plan = findPlan(mc);
            if (plan == null) { Navigator.walkTick(mc); return; } // nothing in reach -> walk somewhere useful
            Navigator.onPlanFound(mc);
            if (cooldown < 2) cooldown = 2 + RNG.nextInt(3);      // "reaction time" on a new spot
        }

        mode = "Placing";
        // Crouch if a slip would drop us off the build.
        Navigator.holdSneak(mc, Config.I.edgeSneak && Navigator.isNearEdge(mc));

        boolean aligned = turnToward(p, plan.aim);
        if (!aligned) { status = "Aiming..."; return; }
        if (cooldown > 0) { cooldown--; status = "Building..."; return; }

        Item item = plan.want.getBlock().asItem();
        if (!ensureItem(mc, item)) { status = "Missing: " + item.getName().getString(); plan = null; return; }

        BlockHitResult hit = new BlockHitResult(plan.aim, plan.side, plan.neighbor, false);
        ActionResult r = mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, hit);
        attempts.merge(plan.target.asLong(), 1, Integer::sum);
        if (r.isAccepted()) {
            p.swingHand(Hand.MAIN_HAND);
            placedThisSession++;
        }
        plan = null;
        int lo = Math.min(Config.I.minDelay, Config.I.maxDelay);
        int hi = Math.max(Config.I.minDelay, Config.I.maxDelay);
        cooldown = lo + RNG.nextInt(hi - lo + 1);
        status = "Building...";
    }

    // --------------------------------------------------------------- planning
    /** Best block to place from where the player stands right now, or null. */
    private static Plan findPlan(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        Vec3d eye = p.getEyePos();
        double reach = Config.I.reach();
        int r = (int) Math.ceil(reach) + 1;
        BlockPos pp = p.getBlockPos();

        Plan best = null;
        double bestScore = Double.MAX_VALUE;

        for (LitematicaBridge.Region reg : LitematicaBridge.regions()) {
            int x0 = Math.max(reg.minX(), pp.getX() - r), x1 = Math.min(reg.maxX(), pp.getX() + r);
            int y0 = Math.max(reg.minY(), pp.getY() - r), y1 = Math.min(reg.maxY(), pp.getY() + r);
            int z0 = Math.max(reg.minZ(), pp.getZ() - r), z1 = Math.min(reg.maxZ(), pp.getZ() + r);
            BlockPos.Mutable m = new BlockPos.Mutable();

            for (int x = x0; x <= x1; x++)
                for (int y = y0; y <= y1; y++)
                    for (int z = z0; z <= z1; z++) {
                        m.set(x, y, z);
                        if (!w.getBlockState(m).isReplaceable()) continue;      // cheap test first
                        BlockState want = LitematicaBridge.wanted(m);
                        if (!isCandidate(w, m, want)) continue;
                        if (attempts.getOrDefault(m.asLong(), 0) >= 4) continue;
                        if (!hasItem(mc, want.getBlock().asItem())) continue;
                        BlockPos pos = m.toImmutable();
                        if (!w.canPlace(want, pos, ShapeContext.of(p))) continue; // entity in the way

                        Plan pl = planFor(mc, pos, want, eye, reach);
                        if (pl == null) continue;
                        double score = (Config.I.layerByLayer ? y * 10000.0 : 0) + eye.squaredDistanceTo(pl.aim);
                        if (score < bestScore) { bestScore = score; best = pl; }
                    }
        }
        return best;
    }

    /** True if the schematic wants `want` at pos and the world still has a replaceable block there. */
    static boolean isCandidate(ClientWorld w, BlockPos pos, BlockState want) {
        if (want.isAir() || !want.getFluidState().isEmpty()) return false;
        if (want.getBlock().asItem() == Items.AIR) return false;
        if (isSecondHalf(want)) return false;
        BlockState have = w.getBlockState(pos);
        if (!have.isReplaceable()) return false;      // already built (or wrong block - we never break)
        return have.getBlock() != want.getBlock();
    }

    /** Is there at least one solid neighbour we could click against? */
    static boolean hasSupport(ClientWorld w, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockState ns = w.getBlockState(pos.offset(d));
            if (!ns.isReplaceable() && ns.getFluidState().isEmpty() && !isInteractable(ns)) return true;
        }
        return false;
    }

    /** Choose a face to click for `pos`, as seen from a (possibly hypothetical) eye position. */
    static Plan planFor(MinecraftClient mc, BlockPos pos, BlockState want, Vec3d eye, double reach) {
        ClientWorld w = mc.world;
        Plan best = null;
        double bestDist = Double.MAX_VALUE;
        Boolean top = wantsTop(want);

        for (Direction d : Direction.values()) {
            BlockPos nb = pos.offset(d);
            BlockState ns = w.getBlockState(nb);
            if (ns.isReplaceable() || !ns.getFluidState().isEmpty() || isInteractable(ns)) continue;

            Direction side = d.getOpposite(); // face of the neighbour we click
            if (want.contains(Properties.AXIS) && side.getAxis() != want.get(Properties.AXIS)) continue;
            if (top != null && side.getAxis() == Direction.Axis.Y && (side == Direction.DOWN) != top) continue;

            Vec3d c = Vec3d.ofCenter(nb);
            double jx = (RNG.nextDouble() - 0.5) * 0.5, jy = (RNG.nextDouble() - 0.5) * 0.5, jz = (RNG.nextDouble() - 0.5) * 0.5;
            double hx = c.x + side.getOffsetX() * 0.5 + (side.getAxis() == Direction.Axis.X ? 0 : jx);
            double hy = c.y + side.getOffsetY() * 0.5 + (side.getAxis() == Direction.Axis.Y ? 0 : jy);
            double hz = c.z + side.getOffsetZ() * 0.5 + (side.getAxis() == Direction.Axis.Z ? 0 : jz);
            if (top != null && side.getAxis() != Direction.Axis.Y) hy = nb.getY() + (top ? 0.75 : 0.25);
            Vec3d aim = new Vec3d(hx, hy, hz);

            double dist = eye.distanceTo(aim);
            if (dist > reach) continue;

            if (Config.I.lineOfSight) {
                BlockHitResult ray = w.raycast(new RaycastContext(eye, aim,
                        RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
                if (ray.getType() == HitResult.Type.BLOCK && !ray.getBlockPos().equals(nb)) continue;
            }
            if (dist < bestDist) { bestDist = dist; best = new Plan(pos, nb, side, aim, want); }
        }
        return best;
    }

    // ---------------------------------------------------------------- helpers
    private static boolean isSecondHalf(BlockState s) {
        if (s.contains(Properties.DOUBLE_BLOCK_HALF) && s.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return true;
        return s.contains(Properties.BED_PART) && s.get(Properties.BED_PART) == BedPart.HEAD;
    }

    private static Boolean wantsTop(BlockState s) {
        if (s.contains(Properties.BLOCK_HALF)) return s.get(Properties.BLOCK_HALF) == BlockHalf.TOP;
        if (s.contains(Properties.SLAB_TYPE)) {
            SlabType t = s.get(Properties.SLAB_TYPE);
            return t == SlabType.DOUBLE ? null : t == SlabType.TOP;
        }
        return null;
    }

    static boolean isInteractable(BlockState s) {
        Block b = s.getBlock();
        return b instanceof BlockEntityProvider || b instanceof DoorBlock || b instanceof TrapdoorBlock
                || b instanceof ButtonBlock || b instanceof LeverBlock || b instanceof FenceGateBlock
                || b instanceof CraftingTableBlock || b instanceof AnvilBlock || b instanceof BedBlock
                || b instanceof NoteBlock || b instanceof AbstractRedstoneGateBlock;
    }

    /** Smoothly rotate the camera. Returns true once close enough to the target. */
    private static boolean turnToward(ClientPlayerEntity p, Vec3d aim) {
        Vec3d d = aim.subtract(p.getEyePos());
        float ty = (float) (MathHelper.atan2(d.z, d.x) * 180.0 / Math.PI) - 90.0f;
        float tp = (float) -(MathHelper.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * 180.0 / Math.PI);
        float dy = MathHelper.wrapDegrees(ty - p.getYaw());
        float dp = tp - p.getPitch();

        float max = Config.I.turnSpeed * (0.6f + RNG.nextFloat() * 0.8f);
        float sy = MathHelper.clamp(dy * 0.45f, -max, max);
        float sp = MathHelper.clamp(dp * 0.45f, -max * 0.7f, max * 0.7f);
        p.setYaw(p.getYaw() + sy);
        p.setPitch(MathHelper.clamp(p.getPitch() + sp, -90f, 90f));
        return Math.abs(dy) < 4f && Math.abs(dp) < 4f;
    }

    public static boolean hasItem(MinecraftClient mc, Item item) {
        if (mc.player.isCreative() && Config.I.creativeFill) return true;
        return mc.player.getInventory().count(item) > 0;
    }

    /** Put the item into the selected hotbar slot (swap from inventory / creative-give if needed). */
    private static boolean ensureItem(MinecraftClient mc, Item item) {
        ClientPlayerEntity p = mc.player;
        var inv = p.getInventory();
        if (inv.getSelectedStack().isOf(item)) return true;

        for (int i = 0; i < 9; i++)
            if (inv.getStack(i).isOf(item)) { inv.setSelectedSlot(i); return true; }

        for (int i = 9; i < 36; i++)
            if (inv.getStack(i).isOf(item)) {
                mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, i, inv.getSelectedSlot(), SlotActionType.SWAP, p);
                return true;
            }

        if (p.isCreative() && Config.I.creativeFill) {
            mc.interactionManager.clickCreativeStack(new ItemStack(item), 36 + inv.getSelectedSlot());
            return true;
        }
        return false;
    }
}
