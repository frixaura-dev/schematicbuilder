package com.example.schembuilder;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * All Litematica access lives here and uses reflection, so this mod compiles without the
 * Litematica jar and tolerates small API changes. lastError always explains why nothing was found.
 */
public final class LitematicaBridge {
    private LitematicaBridge() {}

    public record Region(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, String name) {}

    public static String lastError = null;
    private static Method getWorldM;

    private static final String[] BOX_METHODS = {"getEclosingBox", "getEnclosingBox", "getEnclosingBoundingBox", "getBoundingBox"};

    // ------------------------------------------------------------ placements
    public static List<Region> regions() {
        List<Region> out = new ArrayList<>();
        try {
            Object mgr = Class.forName("fi.dy.masa.litematica.data.DataManager")
                    .getMethod("getSchematicPlacementManager").invoke(null);
            if (mgr == null) { lastError = "Litematica placement manager not ready"; return out; }

            List<Object> placements = new ArrayList<>();
            Object sel = tryCall(mgr, "getSelectedSchematicPlacement");
            if (sel != null) placements.add(sel);
            Object all = tryCall(mgr, "getAllSchematicsPlacements");
            if (all instanceof Iterable<?> it)
                for (Object p : it) if (!placements.contains(p)) {
                    Object en = tryCall(p, "isEnabled");
                    if (en == null || Boolean.TRUE.equals(en)) placements.add(p);
                }

            if (placements.isEmpty()) {
                lastError = "No placement found. In Litematica: M > Load Schematics > Load, then place it (enable 'Create placement').";
                return out;
            }

            String why = "";
            for (Object p : placements) {
                int[] b = null;
                try { b = boxOf(p); } catch (Throwable t) { why = t.toString(); }
                if (b == null) continue;
                Object name = tryCall(p, "getName");
                out.add(new Region(b[0], b[1], b[2], b[3], b[4], b[5], name == null ? "placement" : name.toString()));
            }
            lastError = out.isEmpty() ? "Found " + placements.size() + " placement(s) but couldn't read their size. " + why : null;
        } catch (ClassNotFoundException e) {
            lastError = "Litematica not found - is it installed for 1.21.11?";
        } catch (Throwable t) {
            lastError = "Litematica API mismatch: " + t;
        }
        return out;
    }

    /** The block the schematic wants at pos (air if outside / not loaded). */
    public static BlockState wanted(BlockPos pos) {
        try {
            if (getWorldM == null)
                getWorldM = Class.forName("fi.dy.masa.litematica.world.SchematicWorldHandler").getMethod("getSchematicWorld");
            Object w = getWorldM.invoke(null);
            if (w instanceof BlockView bv) return bv.getBlockState(pos);
        } catch (Throwable t) {
            lastError = "Litematica API mismatch (schematic world): " + t;
        }
        return Blocks.AIR.getDefaultState();
    }

    public static String placementName() {
        List<Region> r = regions();
        return r.isEmpty() ? "none" : r.get(0).name();
    }

    // --------------------------------------------------------------- helpers
    private static Object call(Object o, String name) throws Exception {
        return o.getClass().getMethod(name).invoke(o);
    }

    private static Object tryCall(Object o, String name) {
        try { return call(o, name); } catch (Throwable t) { return null; }
    }

    /** Try several known method names for the enclosing box, then fall back to the sub-region boxes. */
    private static int[] boxOf(Object placement) throws Exception {
        for (String m : BOX_METHODS) {
            Object box = tryCall(placement, m);
            if (box != null) { int[] r = readBox(box); if (r != null) return r; }
        }
        // Fallback: union of all sub-region boxes
        for (Method m : placement.getClass().getMethods()) {
            if (!m.getName().equals("getSubRegionBoxes")) continue;
            Object[] args = new Object[m.getParameterCount()];
            if (args.length == 1 && m.getParameterTypes()[0].isEnum()) {
                Object[] consts = m.getParameterTypes()[0].getEnumConstants();
                args[0] = consts[0];
                for (Object c : consts) if (c.toString().equals("ANY")) args[0] = c;
            } else if (args.length != 0) continue;
            Object res = m.invoke(placement, args);
            Collection<?> boxes = res instanceof Map<?, ?> mp ? mp.values() : res instanceof Collection<?> c ? c : null;
            if (boxes == null) continue;
            int[] u = null;
            for (Object b : boxes) {
                int[] r = readBox(b);
                if (r == null) continue;
                if (u == null) u = r;
                else for (int i = 0; i < 3; i++) { u[i] = Math.min(u[i], r[i]); u[i + 3] = Math.max(u[i + 3], r[i + 3]); }
            }
            if (u != null) return u;
        }
        throw new NoSuchMethodException("no box method found on " + placement.getClass().getSimpleName());
    }

    /** Accepts a box exposing minX..maxZ fields, getMinX()..getMaxZ() getters, or getPos1()/getPos2(). */
    private static int[] readBox(Object b) {
        Class<?> c = b.getClass();
        String[] f = {"minX", "minY", "minZ", "maxX", "maxY", "maxZ"};
        try {
            int[] r = new int[6];
            for (int i = 0; i < 6; i++) r[i] = c.getField(f[i]).getInt(b);
            return r;
        } catch (Throwable ignored) {}
        try {
            int[] r = new int[6];
            for (int i = 0; i < 6; i++)
                r[i] = ((Number) call(b, "get" + Character.toUpperCase(f[i].charAt(0)) + f[i].substring(1))).intValue();
            return r;
        } catch (Throwable ignored) {}
        try {
            BlockPos a = (BlockPos) call(b, "getPos1"), d = (BlockPos) call(b, "getPos2");
            if (a == null || d == null) return null;
            return new int[]{Math.min(a.getX(), d.getX()), Math.min(a.getY(), d.getY()), Math.min(a.getZ(), d.getZ()),
                    Math.max(a.getX(), d.getX()), Math.max(a.getY(), d.getY()), Math.max(a.getZ(), d.getZ())};
        } catch (Throwable ignored) {}
        return null;
    }
}
