package com.example.schembuilder;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * All Litematica access lives here and uses reflection, so this mod compiles without the
 * Litematica jar and tolerates small API changes (e.g. malilib package moves).
 * If Litematica renames something, only this file needs a tweak.
 */
public final class LitematicaBridge {
    private LitematicaBridge() {}

    public record Region(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, String name) {}

    public static String lastError = null;

    private static Method getWorldM;

    // ------------------------------------------------------------ placements
    public static List<Region> regions() {
        List<Region> out = new ArrayList<>();
        try {
            Object mgr = Class.forName("fi.dy.masa.litematica.data.DataManager")
                    .getMethod("getSchematicPlacementManager").invoke(null);
            if (mgr == null) return out;

            List<Object> placements = new ArrayList<>();
            Object sel = call(mgr, "getSelectedSchematicPlacement");
            if (sel != null) placements.add(sel);
            else {
                Object all = call(mgr, "getAllSchematicsPlacements");
                if (all instanceof Iterable<?> it)
                    for (Object p : it) {
                        Object en = tryCall(p, "isEnabled");
                        if (en == null || Boolean.TRUE.equals(en)) placements.add(p);
                    }
            }

            for (Object p : placements) {
                Object box = call(p, "getEclosingBox"); // (sic) - Litematica's spelling
                if (box == null) continue;
                int[] b = readBox(box);
                Object name = tryCall(p, "getName");
                out.add(new Region(b[0], b[1], b[2], b[3], b[4], b[5], name == null ? "placement" : name.toString()));
            }
            lastError = null;
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
            lastError = "Litematica API mismatch: " + t;
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

    /** Accepts a box exposing public minX..maxZ fields, getMinX()..getMaxZ() getters, or getPos1()/getPos2(). */
    private static int[] readBox(Object b) throws Exception {
        Class<?> c = b.getClass();
        String[] f = {"minX", "minY", "minZ", "maxX", "maxY", "maxZ"};
        try {
            int[] r = new int[6];
            for (int i = 0; i < 6; i++) r[i] = c.getField(f[i]).getInt(b);
            return r;
        } catch (NoSuchFieldException ignored) {}
        try {
            int[] r = new int[6];
            for (int i = 0; i < 6; i++) {
                String g = "get" + Character.toUpperCase(f[i].charAt(0)) + f[i].substring(1);
                r[i] = ((Number) call(b, g)).intValue();
            }
            return r;
        } catch (NoSuchMethodException ignored) {}
        BlockPos a = (BlockPos) call(b, "getPos1"), d = (BlockPos) call(b, "getPos2");
        return new int[]{Math.min(a.getX(), d.getX()), Math.min(a.getY(), d.getY()), Math.min(a.getZ(), d.getZ()),
                Math.max(a.getX(), d.getX()), Math.max(a.getY(), d.getY()), Math.max(a.getZ(), d.getZ())};
    }
}
