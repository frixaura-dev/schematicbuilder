package com.example.schembuilder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

public class Config {
    public static Config I = new Config();

    public int minDelay = 3;          // ticks between placements (min)
    public int maxDelay = 8;          // ticks between placements (max)
    public int reachTenths = 45;      // reach in tenths of a block
    public int turnSpeed = 18;        // max degrees per tick when turning
    public boolean layerByLayer = true;
    public boolean creativeFill = true;
    public boolean lineOfSight = true;

    public double reach() { return reachTenths / 10.0; }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path path() { return FabricLoader.getInstance().getConfigDir().resolve("schembuilder.json"); }

    public static void load() {
        try {
            if (Files.exists(path())) I = GSON.fromJson(Files.readString(path()), Config.class);
            if (I == null) I = new Config();
        } catch (Exception e) { I = new Config(); }
    }

    public static void save() {
        try { Files.writeString(path(), GSON.toJson(I)); } catch (Exception ignored) {}
    }
}
