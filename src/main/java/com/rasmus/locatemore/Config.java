package com.rasmus.locatemore;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-admin knobs, loaded once at mod init from config/locatemore.json.
 * Missing file or missing keys fall back to the built-in defaults and the
 * file is rewritten complete, so upgrades add new keys automatically.
 */
public final class Config {

    private static final Logger LOGGER = LoggerFactory.getLogger("locatemore-config");

    /** Hard wall clock for one async search, seconds. */
    private static volatile int wallClockSeconds = 60;
    /** Search gives up past this many blocks from the origin. */
    private static volatile long maxDistanceBlocks = 1_000_000;
    /**
     * Upper bound for the count argument. Read once at command registration,
     * so a change applies after restart; it does not clamp API calls.
     */
    private static volatile int maxCount = 100;
    /** Concurrent searches; also sizes the worker pool. */
    private static volatile int maxActiveSearches = 2;
    /**
     * Allow generating candidate chunks (to their first stage) for structures
     * in ungenerated terrain. Off means such candidates are skipped and the
     * summary reports a partial result; the world save never grows.
     */
    private static volatile boolean allowProbeChunkGeneration = true;
    /**
     * Route vanilla's own nearest-structure search (plain /locate, eyes of
     * ender, explorer maps, trades, other mods) through the exact-order
     * engine, fixing MC-138887.
     */
    private static volatile boolean improveVanillaLocate = true;
    /**
     * Biome search gives up past this many blocks. Vanilla's radius is
     * 6400; the default doubles it, since the search is pure math off the
     * server thread and rare biomes are routinely farther than 6400.
     */
    private static volatile long biomeMaxDistanceBlocks = 12_800;
    /**
     * Two biome hits closer than this are the same place: within one
     * search, a hit this close to an accepted hit is suppressed, so a
     * count of N returns N distinct patches instead of N samples of the
     * nearest one.
     */
    private static volatile int biomeSeparationBlocks = 512;
    /** Dying auto-saves the "death" mark and links it in the respawn
     * message; servers that treat death coordinates as gameplay (hardcore
     * flavors) can turn it off here. */
    private static volatile boolean deathMark = true;

    private Config() {
    }

    // Read-only accessors: the fields are deliberately not public, so no
    // other mod on the classpath can silently rewrite operator settings
    // (Config.improveVanillaLocate = false used to be one statement away).

    public static int wallClockSeconds() {
        return wallClockSeconds;
    }

    public static long maxDistanceBlocks() {
        return maxDistanceBlocks;
    }

    public static int maxCount() {
        return maxCount;
    }

    public static int maxActiveSearches() {
        return maxActiveSearches;
    }

    public static boolean allowProbeChunkGeneration() {
        return allowProbeChunkGeneration;
    }

    public static boolean improveVanillaLocate() {
        return improveVanillaLocate;
    }

    public static long biomeMaxDistanceBlocks() {
        return biomeMaxDistanceBlocks;
    }

    public static boolean deathMark() {
        return deathMark;
    }

    public static int biomeSeparationBlocks() {
        return biomeSeparationBlocks;
    }

    public static void load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("locatemore.json");
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        if (Files.isRegularFile(path)) {
            try {
                JsonObject json = gson.fromJson(Files.readString(path, StandardCharsets.UTF_8), JsonObject.class);
                if (json != null) {
                    if (json.has("wallClockSeconds")) {
                        wallClockSeconds = Math.min(3_600, Math.max(5, json.get("wallClockSeconds").getAsInt()));
                    }
                    if (json.has("maxDistanceBlocks")) {
                        // Upper clamp at the world border: beyond it the squared
                        // distance overflows and every search silently fails.
                        maxDistanceBlocks = Math.min(30_000_000L,
                                Math.max(1_000, json.get("maxDistanceBlocks").getAsLong()));
                    }
                    if (json.has("maxCount")) {
                        maxCount = Math.max(1, Math.min(1_000, json.get("maxCount").getAsInt()));
                    }
                    if (json.has("maxActiveSearches")) {
                        maxActiveSearches = Math.max(1, Math.min(8, json.get("maxActiveSearches").getAsInt()));
                    }
                    if (json.has("allowProbeChunkGeneration")) {
                        allowProbeChunkGeneration = json.get("allowProbeChunkGeneration").getAsBoolean();
                    }
                    if (json.has("deathMark")) {
                        deathMark = json.get("deathMark").getAsBoolean();
                    }
                    if (json.has("improveVanillaLocate")) {
                        improveVanillaLocate = json.get("improveVanillaLocate").getAsBoolean();
                    }
                    if (json.has("biomeMaxDistanceBlocks")) {
                        biomeMaxDistanceBlocks = Math.min(1_000_000L,
                                Math.max(1_000, json.get("biomeMaxDistanceBlocks").getAsLong()));
                    }
                    if (json.has("biomeSeparationBlocks")) {
                        biomeSeparationBlocks = Math.max(32,
                                Math.min(16_384, json.get("biomeSeparationBlocks").getAsInt()));
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("Could not parse {}; using defaults", path, e);
            }
        }
        JsonObject out = new JsonObject();
        out.addProperty("wallClockSeconds", wallClockSeconds);
        out.addProperty("maxDistanceBlocks", maxDistanceBlocks);
        out.addProperty("maxCount", maxCount);
        out.addProperty("maxActiveSearches", maxActiveSearches);
        out.addProperty("allowProbeChunkGeneration", allowProbeChunkGeneration);
        out.addProperty("improveVanillaLocate", improveVanillaLocate);
        out.addProperty("biomeMaxDistanceBlocks", biomeMaxDistanceBlocks);
        out.addProperty("biomeSeparationBlocks", biomeSeparationBlocks);
        out.addProperty("deathMark", deathMark);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, gson.toJson(out), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Could not write {}", path, e);
        }
    }
}
