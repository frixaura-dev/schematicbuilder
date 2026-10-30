package com.example.schembuilder;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.*;

/**
 * Walks the player to the next place where blocks can be built from.
 * It never teleports: it finds a route with A*, then presses the real movement keys
 * (forward / jump / sneak) and turns the camera smoothly, like a person would.
 */
public final class Navigator {
    private Navigator() {}

    private static final double EYE = 1.62;
    private static final int MAX_EXPANSIONS = 6000;
    private static final int GOALS = 12;

    record Cand(BlockPos pos, net.minecraft.block.BlockState want, double score) {}

    private static final class N {
        final int x, y, z; double g, f; N parent;
        N(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    }

    // ---- state
    private static List<BlockPos> path = null;
    private static int idx = 0;
    private static int searchCooldown = 0;
    private static int settle = 0;            // ticks spent standing still after arriving
    private static int arrivedFails = 0;
    private static int stuckStrikes = 0;
    private static int stuckTimer = 0;
    private static double lastX, lastZ;
    private static List<Cand> goals = List.of();
    private static final Map<Long, Long> blacklist = new HashMap<>();
    private static long tickNo = 0;
    private static boolean movingByUs = false, sneakingByUs = false;
    private static int remainingCache = -1;
    private static long remainingAt = -9999;
    private static String missingItem = null;

    // ================================================================ lifecycle
    public static void reset() {
        path = null; idx = 0; searchCooldown = 0; settle = 0; arrivedFails = 0;
        stuckStrikes = 0; stuckTimer = 0; goals = List.of(); blacklist.clear();
        remainingCache = -1; missingItem = null;
    }

    static void beginTick() { tickNo++; }

    /** The engine found something to place from where we stand - stop walking. */
    static void onPlanFound(MinecraftClient mc) {
        path = null; settle = 0; arrivedFails = 0; stuckStrikes = 0;
        releaseMove(mc);
    }

    // ================================================================ key control
    static void holdSneak(MinecraftClient mc, boolean on) {
        if (mc.options == null) return;
        if (on) { mc.options.sneakKey.setPressed(true); sneakingByUs = true; }
        else if (sneakingByUs) { mc.options.sneakKey.setPressed(false); sneakingByUs = false; }
    }

    /** Release forward / jump / sneak, but only keys we pressed ourselves (so manual input isn't cancelled). */
    static void releaseMove(MinecraftClient mc) {
        if (mc == null || mc.options == null) return;
        if (movingByUs) {
            mc.options.forwardKey.setPressed(false);
            mc.options.jumpKey.setPressed(false);
            movingByUs = false;
        }
        if (sneakingByUs) { mc.options.sneakKey.setPressed(false); sneakingByUs = false; }
    }

    public static void releaseAll(MinecraftClient mc) { releaseMove(mc); }

    // ================================================================ edge detection
    /** True if there is a drop of 3+ blocks within ~0.7 blocks of where the player stands. */
    static boolean isNearEdge(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        if (p == null || w == null || !p.isOnGround()) return false;
        int floorY = MathHelper.floor(p.getY() - 0.01);
        double[] o = {-0.7, 0.0, 0.7};
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (double dx : o)
            for (double dz : o) {
                if (dx == 0.0 && dz == 0.0) continue;
                int bx = MathHelper.floor(p.getX() + dx), bz = MathHelper.floor(p.getZ() + dz);
                boolean support = false;
                for (int k = 0; k < 3 && !support; k++) {
                    m.set(bx, floorY - k, bz);
                    BlockState s = w.getBlockState(m);
                    support = !s.getCollisionShape(w, m).isEmpty() || !s.getFluidState().isEmpty();
                }
                if (!support) return true;
            }
        return false;
    }

    // ================================================================ per-tick entry
    /** Called by the engine when nothing can be placed from the current position. */
    static void walkTick(MinecraftClient mc) {
        if (!Config.I.autoWalk) {
            releaseMove(mc);
            BuildEngine.mode = "Idle";
            BuildEngine.status = "Nothing in reach - walk along the build (or turn on Auto-walk)";
            return;
        }

        if (settle > 0) {                          // arrived: stand still a moment and let the engine re-plan
            releaseMove(mc);
            BuildEngine.mode = "Arriving";
            if (--settle == 0) {
                path = null;
                if (++arrivedFails >= 2) { blacklistGoals(); arrivedFails = 0; }
                searchCooldown = 2;
            }
            return;
        }

        if (path == null) {
            releaseMove(mc);
            if (searchCooldown > 0) { searchCooldown--; return; }
            search(mc);
            return;
        }
        followPath(mc);
    }

    // ================================================================ choosing where to go
    private static void search(MinecraftClient mc) {
        List<Cand> cands = pickCandidates(mc);

        if (cands.isEmpty()) {
            BuildEngine.mode = "Waiting";
            if (missingItem != null) {
                BuildEngine.status = "Missing: " + missingItem;
                searchCooldown = 40;
                return;
            }
            int remaining = remainingPlaceable(mc);
            if (remaining == 0) {
                ClientPlayerEntity p = mc.player;
                if (p != null) p.sendMessage(Text.literal("§a[Builder] Build complete! Placed " + BuildEngine.placedThisSession + " blocks."), false);
                BuildEngine.stop("Build complete!");
                return;
            }
            BuildEngine.status = remaining + " block(s) left but none can be built from here (no support yet, or too far - raise the search radius)";
            searchCooldown = 40;
            return;
        }

        goals = cands;
        List<BlockPos> found = astar(mc, cands);
        if (found == null) {
            // Can't reach the best spot - ignore those targets for a while and try the next ones.
            blacklistGoals();
            BuildEngine.mode = "Waiting";
            BuildEngine.status = "Can't find a safe route - trying another spot";
            searchCooldown = 10;
            return;
        }
        path = found;
        idx = 0;
        stuckStrikes = 0; stuckTimer = 0;
        ClientPlayerEntity p = mc.player;
        lastX = p.getX(); lastZ = p.getZ();
        BuildEngine.mode = "Walking";
        BuildEngine.status = "Walking to next spot (" + found.size() + " steps)";
        if (found.isEmpty()) settle = 8;
    }

    /** Nearest few placeable blocks (lowest layer first when layer-by-layer is on). */
    private static List<Cand> pickCandidates(MinecraftClient mc) {
        ClientWorld w = mc.world;
        ClientPlayerEntity p = mc.player;
        missingItem = null;
        int R = Config.I.searchRadius;
        int px = p.getBlockX(), py = p.getBlockY(), pz = p.getBlockZ();
        List<Cand> out = new ArrayList<>();
        BlockPos.Mutable m = new BlockPos.Mutable();

        for (LitematicaBridge.Region reg : LitematicaBridge.regions()) {
            int x0 = Math.max(reg.minX(), px - R), x1 = Math.min(reg.maxX(), px + R);
            int y0 = Math.max(reg.minY(), py - R), y1 = Math.min(reg.maxY(), py + R);
            int z0 = Math.max(reg.minZ(), pz - R), z1 = Math.min(reg.maxZ(), pz + R);
            for (int x = x0; x <= x1; x++)
                for (int y = y0; y <= y1; y++)
                    for (int z = z0; z <= z1; z++) {
                        m.set(x, y, z);
                        if (!w.getBlockState(m).isReplaceable()) continue;      // cheap test first
                        BlockState want = LitematicaBridge.wanted(m);
                        if (!BuildEngine.isCandidate(w, m, want)) continue;
                        if (isBlacklisted(m.asLong())) continue;
                        Item item = want.getBlock().asItem();
                        if (!BuildEngine.hasItem(mc, item)) {
                            if (missingItem == null) missingItem = item.getName().getString();
                            continue;
                        }
                        if (!BuildEngine.hasSupport(w, m)) continue;
                        double dx = x - px, dy = y - py, dz = z - pz;
                        double score = (Config.I.layerByLayer ? y * 100.0 : 0) + Math.sqrt(dx * dx + dy * dy + dz * dz);
                        out.add(new Cand(m.toImmutable(), want, score));
                    }
        }
        out.sort(Comparator.comparingDouble(Cand::score));
        return out.size() > GOALS ? new ArrayList<>(out.subList(0, GOALS)) : out;
    }

    /** How many placeable blocks remain anywhere in the schematic (throttled). */
    private static int remainingPlaceable(MinecraftClient mc) {
        if (remainingCache >= 0 && tickNo - remainingAt < 100) return remainingCache;
        ClientWorld w = mc.world;
        int count = 0;
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (LitematicaBridge.Region reg : LitematicaBridge.regions())
            for (int x = reg.minX(); x <= reg.maxX(); x++)
                for (int y = reg.minY(); y <= reg.maxY(); y++)
                    for (int z = reg.minZ(); z <= reg.maxZ(); z++) {
                        m.set(x, y, z);
                        if (!w.getBlockState(m).isReplaceable()) continue;
                        if (BuildEngine.isCandidate(w, m, LitematicaBridge.wanted(m))) count++;
                    }
        remainingCache = count;
        remainingAt = tickNo;
        return count;
    }

    private static void blacklistGoals() {
        for (Cand c : goals) blacklist.put(c.pos().asLong(), tickNo + 600);
        goals = List.of();
    }

    private static boolean isBlacklisted(long key) {
        Long until = blacklist.get(key);
        if (until == null) return false;
        if (tickNo > until) { blacklist.remove(key); return false; }
        return true;
    }

    // ================================================================ following the path
    private static void followPath(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        BuildEngine.mode = "Walking";

        if (idx >= path.size()) { releaseMove(mc); settle = 8; return; }

        BlockPos next = path.get(idx);
        double px = p.getX(), py = p.getY(), pz = p.getZ();
        double tx = next.getX() + 0.5, tz = next.getZ() + 0.5;
        double dx = tx - px, dz = tz - pz;
        double hd = Math.sqrt(dx * dx + dz * dz);

        // reached this node?
        if (hd < 0.42 && Math.abs(py - next.getY()) < 1.3) {
            idx++;
            stuckTimer = 0; stuckStrikes = 0; lastX = px; lastZ = pz;
            if (idx >= path.size()) { releaseMove(mc); settle = 8; return; }
            next = path.get(idx);
            tx = next.getX() + 0.5; tz = next.getZ() + 0.5;
            dx = tx - px; dz = tz - pz; hd = Math.sqrt(dx * dx + dz * dz);
        }

        // knocked off the route, fell, or the next step got blocked -> plan again
        if (hd > 6 || py < next.getY() - 3.5 || !standable(w, new BlockPos.Mutable(), next.getX(), next.getY(), next.getZ())) {
            replan(mc);
            return;
        }

        // turn toward the next node (gentle, human-ish)
        float ty = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
        float dyaw = MathHelper.wrapDegrees(ty - p.getYaw());
        float maxTurn = Config.I.turnSpeed * 1.3f;
        p.setYaw(p.getYaw() + MathHelper.clamp(dyaw * 0.4f, -maxTurn, maxTurn));
        float dp = 12.0f - p.getPitch();
        p.setPitch(p.getPitch() + MathHelper.clamp(dp * 0.2f, -6f, 6f));

        boolean forward = Math.abs(dyaw) < 35f;
        boolean stepUp = next.getY() > p.getBlockY();
        boolean jump = p.isOnGround() && (stepUp || p.horizontalCollision) && hd < 1.6;

        mc.options.forwardKey.setPressed(forward);
        mc.options.jumpKey.setPressed(jump);
        movingByUs = true;
        holdSneak(mc, Config.I.edgeSneak && isNearEdge(mc));

        // stuck detection
        if (++stuckTimer >= 25) {
            double moved = Math.hypot(px - lastX, pz - lastZ);
            lastX = px; lastZ = pz; stuckTimer = 0;
            if (moved < 0.25) {
                if (++stuckStrikes >= 3) { blacklistGoals(); stuckStrikes = 0; replan(mc); }
                else replan(mc);
            }
        }
    }

    private static void replan(MinecraftClient mc) {
        releaseMove(mc);
        path = null;
        searchCooldown = 3;
    }

    // ================================================================ A*
    private static boolean hazard(BlockState s) {
        return s.isOf(Blocks.LAVA) || s.isOf(Blocks.FIRE) || s.isOf(Blocks.SOUL_FIRE) || s.isOf(Blocks.MAGMA_BLOCK)
                || s.isOf(Blocks.CACTUS) || s.isOf(Blocks.SWEET_BERRY_BUSH) || s.isOf(Blocks.CAMPFIRE)
                || s.isOf(Blocks.SOUL_CAMPFIRE) || s.isOf(Blocks.WITHER_ROSE) || s.isOf(Blocks.POWDER_SNOW)
                || s.isOf(Blocks.COBWEB);
    }

    /** Body can occupy this cell. */
    private static boolean passable(ClientWorld w, BlockPos.Mutable m, int x, int y, int z) {
        m.set(x, y, z);
        BlockState s = w.getBlockState(m);
        if (!s.getFluidState().isEmpty() || hazard(s)) return false;
        return s.getCollisionShape(w, m).isEmpty();
    }

    /** A solid, safe, roughly-flat surface to stand on. */
    private static boolean floor(ClientWorld w, BlockPos.Mutable m, int x, int y, int z) {
        m.set(x, y, z);
        BlockState s = w.getBlockState(m);
        if (!s.getFluidState().isEmpty() || hazard(s)) return false;
        var shape = s.getCollisionShape(w, m);
        return !shape.isEmpty() && shape.getMax(Direction.Axis.Y) <= 1.0;
    }

    private static boolean standable(ClientWorld w, BlockPos.Mutable m, int x, int y, int z) {
        return passable(w, m, x, y, z) && passable(w, m, x, y + 1, z) && floor(w, m, x, y - 1, z);
    }

    /** A cell where the schematic still wants a block - we'd be standing in our own way. */
    private static boolean wantsBlockAt(BlockPos.Mutable m, int x, int y, int z) {
        m.set(x, y, z);
        return !LitematicaBridge.wanted(m).isAir();
    }

    private static boolean nearDrop(ClientWorld w, BlockPos.Mutable m, int x, int y, int z) {
        int[][] d = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] o : d) {
            boolean support = false;
            for (int k = 1; k <= 3 && !support; k++) {
                m.set(x + o[0], y - k, z + o[1]);
                BlockState s = w.getBlockState(m);
                support = !s.getCollisionShape(w, m).isEmpty() || !s.getFluidState().isEmpty();
            }
            if (!support) return true;
        }
        return false;
    }

    private static double heuristic(int x, int y, int z, List<Cand> cands, double reach) {
        double best = Double.MAX_VALUE;
        double ex = x + 0.5, ey = y + EYE, ez = z + 0.5;
        for (Cand c : cands) {
            double dx = c.pos().getX() + 0.5 - ex, dy = c.pos().getY() + 0.5 - ey, dz = c.pos().getZ() + 0.5 - ez;
            best = Math.min(best, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        return Math.max(0, best - reach);
    }

    /** Could we build at least one of the goals standing here? */
    private static boolean isGoal(MinecraftClient mc, BlockPos.Mutable m, int x, int y, int z, List<Cand> cands, double reach) {
        if (wantsBlockAt(m, x, y, z) || wantsBlockAt(m, x, y + 1, z)) return false;
        Vec3d eye = new Vec3d(x + 0.5, y + EYE, z + 0.5);
        for (Cand c : cands) {
            if (eye.squaredDistanceTo(Vec3d.ofCenter(c.pos())) > (reach + 0.9) * (reach + 0.9)) continue;
            if (BuildEngine.planFor(mc, c.pos(), c.want(), eye, reach) != null) return true;
        }
        return false;
    }

    private static List<BlockPos> astar(MinecraftClient mc, List<Cand> cands) {
        ClientWorld w = mc.world;
        ClientPlayerEntity p = mc.player;
        BlockPos.Mutable m = new BlockPos.Mutable();
        double reach = Math.max(2.0, Config.I.reach() - 0.5);   // keep a safety margin for where we actually end up standing
        int maxDrop = Config.I.allowDrops ? 3 : 1;
        int sx = p.getBlockX(), sy = p.getBlockY(), sz = p.getBlockZ();
        int limit = Config.I.searchRadius + 12;

        Map<Long, N> best = new HashMap<>();
        PriorityQueue<N> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        N start = new N(sx, sy, sz);
        start.g = 0; start.f = heuristic(sx, sy, sz, cands, reach);
        best.put(BlockPos.asLong(sx, sy, sz), start);
        open.add(start);

        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        int expansions = 0;

        while (!open.isEmpty() && expansions++ < MAX_EXPANSIONS) {
            N cur = open.poll();
            N known = best.get(BlockPos.asLong(cur.x, cur.y, cur.z));
            if (known != cur) continue;                         // stale queue entry

            if (isGoal(mc, m, cur.x, cur.y, cur.z, cands, reach)) {
                LinkedList<BlockPos> out = new LinkedList<>();
                for (N n = cur; n.parent != null; n = n.parent) out.addFirst(new BlockPos(n.x, n.y, n.z));
                return out;
            }

            for (int[] d : dirs) {
                boolean diag = d[0] != 0 && d[1] != 0;
                int nx = cur.x + d[0], nz = cur.z + d[1];
                if (Math.abs(nx - sx) > limit || Math.abs(nz - sz) > limit) continue;

                if (diag) {   // don't clip corners
                    if (!passable(w, m, cur.x + d[0], cur.y, cur.z) || !passable(w, m, cur.x + d[0], cur.y + 1, cur.z)) continue;
                    if (!passable(w, m, cur.x, cur.y, cur.z + d[1]) || !passable(w, m, cur.x, cur.y + 1, cur.z + d[1])) continue;
                }

                for (int dy = 1; dy >= -maxDrop; dy--) {
                    if (diag && (dy > 0 || dy < -1)) continue;
                    int ny = cur.y + dy;
                    if (!standable(w, m, nx, ny, nz)) continue;
                    if (dy > 0 && !passable(w, m, cur.x, cur.y + 2, cur.z)) continue;           // head room to jump
                    if (dy < 0) {                                                               // clear column to fall through
                        boolean clear = true;
                        for (int k = ny + 2; k <= cur.y + 1 && clear; k++) clear = passable(w, m, nx, k, nz);
                        if (!clear) continue;
                    }

                    double cost = diag ? 1.414 : 1.0;
                    if (dy > 0) cost += 0.6;
                    if (dy < 0) cost += 0.3 * -dy;
                    if (nearDrop(w, m, nx, ny, nz)) cost += 1.5;                                // prefer routes away from edges
                    if (wantsBlockAt(m, nx, ny, nz) || wantsBlockAt(m, nx, ny + 1, nz)) cost += 5; // don't stand where blocks go

                    long key = BlockPos.asLong(nx, ny, nz);
                    N old = best.get(key);
                    double g = cur.g + cost;
                    if (old != null && old.g <= g) continue;
                    N n = new N(nx, ny, nz);
                    n.g = g; n.parent = cur;
                    n.f = g + heuristic(nx, ny, nz, cands, reach);
                    best.put(key, n);
                    open.add(n);
                    break;                                                                      // take the highest valid level only
                }
            }
        }
        return null;
    }
}
