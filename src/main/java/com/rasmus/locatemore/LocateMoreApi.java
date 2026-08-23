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

    /** Bumped only on breaking changes to this class. */
    public static final int API_VERSION = 1;

    /**
     * Per-call overrides. Server budgets still apply as hard ceilings.
     *
     * @param maxDistanceBlocks   search radius cap, clamped to the server's
     * @param allowChunkGeneration whether multi-set candidates may generate
     *                             probe chunks; false means such candidates
     *                             stay unresolved and the result is flagged
     *                             with orderingGuaranteed=false instead of
     *                             writing to the world
     * @param maxMillis           wall-clock budget, clamped to the server's
     * @param excludePrevious     hits from earlier searches to skip, so
     *                            "find the next ones" is one call
     */
    public record SearchOptions(long maxDistanceBlocks, boolean allowChunkGeneration, long maxMillis,
            java.util.Collection<StructureHit> excludePrevious) {

        /** Instant and exact where provable, partial and flagged elsewhere. Never writes to the world. */
        public static SearchOptions mathOnly() {
            return new SearchOptions(Long.MAX_VALUE, false, 5_000L, java.util.List.of());
        }

        public static SearchOptions defaults() {
            return new SearchOptions(Long.MAX_VALUE, true, Long.MAX_VALUE, java.util.List.of());
        }

        public SearchOptions excluding(java.util.Collection<StructureHit> previous) {
            return new SearchOptions(maxDistanceBlocks, allowChunkGeneration, maxMillis, previous);
        }
    }

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
        return findNearest(level, structures, origin, count, SearchOptions.defaults());
    }

    /**
     * As above, with per-call options. When the active-search cap is full the
     * request queues (bounded) instead of failing; the future completes
     * exceptionally only when the queue itself is full or the search is
     * aborted by a reload or server stop.
     */
    public static CompletableFuture<SearchResult> findNearest(ServerLevel level, HolderSet<Structure> structures,
            BlockPos origin, int count, SearchOptions options) {
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("LocateMoreApi.findNearest must be called on the server thread");
        }
        if (count < 1) {
            throw new IllegalArgumentException("count must be at least 1");
        }
        return AsyncLocate.startForApi(level, structures, origin, Math.min(count, Config.maxCount), options);
    }
}
