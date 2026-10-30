package com.example.schembuilder;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.util.*;

/** Counts what the schematic needs vs. what is already built. Run on demand (can be heavy for huge builds). */
public final class MaterialScanner {
    private MaterialScanner() {}

    public static final class Entry {
        public final Item item;
        public int total;
        public int missing;
        Entry(Item i) { item = i; }
    }

    public static List<Entry> entries = new ArrayList<>();
    public static int totalBlocks = 0;
    public static int builtBlocks = 0;

    public static long lastScanMs = 0;

    /** Number of block positions a scan has to look at. */
    public static long volume() {
        long v = 0;
        for (LitematicaBridge.Region r : LitematicaBridge.regions())
            v += (long) (r.maxX() - r.minX() + 1) * (r.maxY() - r.minY() + 1) * (r.maxZ() - r.minZ() + 1);
        return v;
    }

    public static void scan() {
        MinecraftClient mc = MinecraftClient.getInstance();
        lastScanMs = System.currentTimeMillis();
        if (mc.world == null) return;
        Map<Item, Entry> map = new HashMap<>();
        int total = 0, built = 0;

        for (LitematicaBridge.Region r : LitematicaBridge.regions()) {
            BlockPos.Mutable pos = new BlockPos.Mutable();
            for (int x = r.minX(); x <= r.maxX(); x++)
                for (int y = r.minY(); y <= r.maxY(); y++)
                    for (int z = r.minZ(); z <= r.maxZ(); z++) {
                        pos.set(x, y, z);
                        BlockState want = LitematicaBridge.wanted(pos);
                        if (want.isAir() || !want.getFluidState().isEmpty()) continue;
                        Item item = want.getBlock().asItem();
                        if (item == Items.AIR) continue;
                        Entry e = map.computeIfAbsent(item, Entry::new);
                        e.total++;
                        total++;
                        if (mc.world.getBlockState(pos).getBlock() == want.getBlock()) built++;
                        else e.missing++;
                    }
        }
        List<Entry> list = new ArrayList<>(map.values());
        list.sort((a, b) -> b.missing != a.missing ? Integer.compare(b.missing, a.missing) : Integer.compare(b.total, a.total));
        entries = list;
        totalBlocks = total;
        builtBlocks = built;
    }
}
