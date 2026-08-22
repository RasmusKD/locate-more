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
    public static volatile int wallClockSeconds = 60;
    /** Search gives up past this many blocks from the origin. */
    public static volatile long maxDistanceBlocks = 1_000_000;
    /** Upper bound for the count argument. */
    public static volatile int maxCount = 100;
    /** Concurrent searches; also sizes the worker pool. */
    public static volatile int maxActiveSearches = 2;
    /**
     * Allow generating candidate chunks (to their first stage) for structures
     * in ungenerated terrain. Off means such candidates are skipped and the
     * summary reports a partial result; the world save never grows.
     */
    public static volatile boolean allowProbeChunkGeneration = true;
    /** Persist the negative index across restarts. */
    public static volatile boolean persistentIndex = true;
    /** Expose the synchronous benchmark modes (sync/vanilla) to admins. */
    public static volatile boolean enableBenchmarkModes = false;

    private Config() {
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
                    if (json.has("persistentIndex")) {
                        persistentIndex = json.get("persistentIndex").getAsBoolean();
                    }
                    if (json.has("enableBenchmarkModes")) {
                        enableBenchmarkModes = json.get("enableBenchmarkModes").getAsBoolean();
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
        out.addProperty("persistentIndex", persistentIndex);
        out.addProperty("enableBenchmarkModes", enableBenchmarkModes);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, gson.toJson(out), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Could not write {}", path, e);
        }
    }
}
