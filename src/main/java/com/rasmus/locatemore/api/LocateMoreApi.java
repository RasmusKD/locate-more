package com.rasmus.locatemore.api;

import com.rasmus.locatemore.LocateMore;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * Public entry point for other mods; everything outside this package is
 * internal and may change without notice. One method: the async engine
 * behind {@code /locate}, exposed as a future. Plain
 * {@code ChunkGenerator.findNearestMapStructure} calls are already routed
 * through the engine when improveVanillaLocate is on; use this when you need
 * more than one result or a non-blocking handoff.
 */
public final class LocateMoreApi {

    /** Bumped only on breaking changes to this package. */
    public static final int API_VERSION = 2;

    /** Absolute count ceiling, independent of the server's chat-facing maxCount knob. */
    public static final int MAX_COUNT = 10_000;

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
     * A completed search, in exact distance order.
     *
     * <p>{@code orderingGuaranteed} is false when some candidates could not
     * be resolved (probe generation disabled or chunk failures): the
     * returned positions are correct, but a nearer structure may be missing.
     *
     * <p>{@code complete} is false when a budget (wall clock or per-task
     * load cap) stopped the search before the space within
     * maxDistanceBlocks was exhausted. It is the difference between "there
     * are only three within range" (complete, three hits) and "we ran out
     * of time after three" (incomplete): only the former justifies telling
     * a player that no further structure exists.
     */
    public record SearchResult(List<StructureHit> hits, boolean orderingGuaranteed, boolean complete,
            long tookMillis) {
    }

    private LocateMoreApi() {
    }

    /**
     * Finds the {@code count} nearest structures from {@code structures}
     * around {@code origin} without blocking the tick.
     *
     * <p>Call on the server thread; the future also completes on the server
     * thread. The server's locatemore.json budgets apply (wall clock, max
     * distance, active-search cap); {@code count} is bounded only by
     * {@link #MAX_COUNT}, not by the chat-facing maxCount knob. Completes
     * exceptionally with CancellationException when the search is aborted by
     * a datapack reload or server stop.
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
        if (count < 1 || count > MAX_COUNT) {
            throw new IllegalArgumentException("count must be within 1.." + MAX_COUNT);
        }
        return LocateMore.apiStart(level, structures, origin, count, options);
    }
}
