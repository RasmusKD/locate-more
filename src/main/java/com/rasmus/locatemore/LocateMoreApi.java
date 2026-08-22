package com.rasmus.locatemore;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * Public entry point for other mods. One method: the async engine behind
 * {@code /locate}, exposed as a future. Plain
 * {@code ChunkGenerator.findNearestMapStructure} calls are already routed
 * through the engine when improveVanillaLocate is on; use this when you need
 * more than one result or a non-blocking handoff.
 */
public final class LocateMoreApi {

    /** One found structure: position, which structure, horizontal distance in blocks. */
    public record StructureHit(BlockPos pos, Holder<Structure> structure, double distance) {
    }

    /**
     * A completed search, in exact distance order. When orderingGuaranteed is
     * false, some candidates could not be resolved (probe generation disabled
     * or chunk failures): the returned positions are correct, but a nearer
     * structure may be missing.
     */
    public record SearchResult(List<StructureHit> hits, boolean orderingGuaranteed, long tookMillis) {
    }

    private LocateMoreApi() {
    }

    /**
     * Finds the {@code count} nearest structures from {@code structures}
     * around {@code origin} without blocking the tick.
     *
     * <p>Call on the server thread; the future also completes on the server
     * thread. The server's locatemore.json budgets apply (wall clock, max
     * distance, active-search cap; count is clamped to maxCount). Completes
     * exceptionally with IllegalStateException when the active-search cap is
     * full, and with CancellationException when the search is aborted by a
     * datapack reload or server stop.
     */
    public static CompletableFuture<SearchResult> findNearest(ServerLevel level, HolderSet<Structure> structures,
            BlockPos origin, int count) {
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("LocateMoreApi.findNearest must be called on the server thread");
        }
        if (count < 1) {
            throw new IllegalArgumentException("count must be at least 1");
        }
        return AsyncLocate.startForApi(level, structures, origin, Math.min(count, Config.maxCount));
    }
}
