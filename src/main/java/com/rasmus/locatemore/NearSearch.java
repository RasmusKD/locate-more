package com.rasmus.locatemore;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.rasmus.locatemore.api.LocateMoreApi;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.ResourceOrTagArgument;
import net.minecraft.commands.arguments.ResourceOrTagKeyArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * Constellation search: the nearest A that has a B within a radius, e.g. a
 * village with a desert next to it. Pure composition over the existing
 * engines: A streams as a deterministic distance-ordered prefix that grows
 * in escalating rungs (the structure engine excludes what earlier rungs
 * returned; the biome engine simply re-derives the prefix and the probe
 * index skips what was already checked), and every B check is a bounded
 * probe, either a radius-capped structure search from the A hit or a
 * pure-math biome disc scan. Every budget, referee and dedup rule applies
 * unchanged, and the probes introduce no new trust surface: the biome
 * probe takes only plain full samples, the structure probe is the
 * ordinary engine.
 */
final class NearSearch {

    /** First rung: most pairs resolve in the first handful of A hits. The
     * ladder then escalates without a ceiling; only A-exhaustion or the
     * time budget ends a search, so a rare pair (a village against a
     * climate it barely borders) is found if it exists at all. */
    private static final int FIRST_RUNG = 8;
    private static final int MAX_RUNG = 8192;
    /** Battery pacing: AsyncLocate.idle() includes this, so the gaps
     * between a run's inner searches never look idle to the lab driver. */
    private static final AtomicInteger RUNNING = new AtomicInteger();

    /** Radius when the argument is left off: close enough to feel "next
     * to", far enough that a village and its biome neighbor pair up. */
    private static final int DEFAULT_RADIUS = 512;

    /** Completes with the full distance-ordered prefix of A positions. */
    private interface Batcher {
        CompletableFuture<List<BlockPos>> upTo(int count);
    }

    private final CommandSourceStack source;
    private final BlockPos origin;
    private final SearchSession session;
    private final String printableA;
    private final String printableB;
    private final int radius;
    private final Batcher batcher;
    private final Function<BlockPos, CompletableFuture<BlockPos>> probe;
    private final long deadlineNanos;
    private final AtomicBoolean finished = new AtomicBoolean();
    private int probedIndex;

    private NearSearch(CommandSourceStack source, String printableA, String printableB, int radius,
            Function<NearSearch, Batcher> batcherFactory,
            Function<BlockPos, CompletableFuture<BlockPos>> probe) {
        this.source = source;
        this.origin = BlockPos.containing(source.getPosition());
        this.printableA = printableA;
        this.printableB = printableB;
        this.radius = radius;
        this.batcher = batcherFactory.apply(this);
        this.probe = probe;
        // Two engines compose here, so the budget is twice a single
        // search's; each inner call gets the remainder as its ceiling.
        this.deadlineNanos = System.nanoTime() + Config.wallClockSeconds() * 2_000_000_000L;
        // finished is completion bookkeeping, NOT an abort signal: handing
        // it to the session would suppress the very result messages that
        // completion is about to send.
        this.session = new SearchSession(source.getServer(), source.getLevel().dimension(), source,
                printableA + " near " + printableB, () -> false);
    }

    static boolean idle() {
        return RUNNING.get() == 0;
    }

    private static int radiusArg(CommandContext<CommandSourceStack> ctx) {
        try {
            return IntegerArgumentType.getInteger(ctx, "radius");
        } catch (IllegalArgumentException e) {
            return DEFAULT_RADIUS;
        }
    }

    static int structureNearStructure(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        var a = ResourceOrTagKeyArgument.getResourceOrTagKey(
                ctx, "structure", Registries.STRUCTURE, LocateMore.ERROR_STRUCTURE_INVALID);
        var b = ResourceOrTagKeyArgument.getResourceOrTagKey(
                ctx, "other", Registries.STRUCTURE, LocateMore.ERROR_STRUCTURE_INVALID);
        int radius = radiusArg(ctx);
        HolderSet<Structure> aHolders = LocateMore.resolveStructures(source, a);
        HolderSet<Structure> bHolders = LocateMore.resolveStructures(source, b);
        ServerLevel level = source.getLevel();
        NearSearch run = new NearSearch(source, LocateMore.structurePrintable(a),
                LocateMore.structurePrintable(b), radius,
                self -> self.structureBatcher(level, aHolders), center ->
                        AsyncLocate.startForApi(level, bHolders, center, 1,
                                        new LocateMoreApi.SearchOptions(radius, true, 10_000, List.of()))
                                .thenApply(result -> result.hits().stream()
                                        .filter(hit -> hit.distance() <= radius)
                                        .findFirst().map(LocateMoreApi.StructureHit::pos).orElse(null)));
        return run.begin();
    }

    static int structureNearBiome(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        var a = ResourceOrTagKeyArgument.getResourceOrTagKey(
                ctx, "structure", Registries.STRUCTURE, LocateMore.ERROR_STRUCTURE_INVALID);
        ResourceOrTagArgument.Result<Biome> b = ResourceOrTagArgument.getResourceOrTag(
                ctx, "biome", Registries.BIOME);
        int radius = radiusArg(ctx);
        HolderSet<Structure> aHolders = LocateMore.resolveStructures(source, a);
        ServerLevel level = source.getLevel();
        Set<Holder<Biome>> targets = resolveBiomes(source, b);
        if (targets == null) {
            return 0;
        }
        NearSearch run = new NearSearch(source, LocateMore.structurePrintable(a),
                b.asPrintable(), radius,
                self -> self.structureBatcher(level, aHolders), biomeProbe(level, targets, radius));
        return run.begin();
    }

    static int biomeNearBiome(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ResourceOrTagArgument.Result<Biome> a = ResourceOrTagArgument.getResourceOrTag(
                ctx, "biome", Registries.BIOME);
        ResourceOrTagArgument.Result<Biome> b = ResourceOrTagArgument.getResourceOrTag(
                ctx, "other", Registries.BIOME);
        int radius = radiusArg(ctx);
        Set<Holder<Biome>> aTargets = resolveBiomes(source, a);
        Set<Holder<Biome>> bTargets = aTargets == null ? null : resolveBiomes(source, b);
        if (aTargets == null || bTargets == null) {
            return 0;
        }
        ServerLevel level = source.getLevel();
        String printableA = a.asPrintable();
        NearSearch run = new NearSearch(source, printableA, b.asPrintable(), radius,
                self -> count -> BiomeLocate.startForNear(source, printableA, aTargets, count),
                biomeProbe(level, bTargets, radius));
        return run.begin();
    }

    /** Null means the failure message was already sent. */
    private static Set<Holder<Biome>> resolveBiomes(CommandSourceStack source,
            ResourceOrTagArgument.Result<Biome> result) {
        BiomeSource biomeSource = source.getLevel().getChunkSource().getGenerator().getBiomeSource();
        Set<Holder<Biome>> targets = biomeSource.possibleBiomes().stream()
                .filter(result).collect(Collectors.toUnmodifiableSet());
        if (targets.isEmpty()) {
            source.sendFailure(Component.literal(
                    "This dimension cannot generate " + result.asPrintable() + "."));
            return null;
        }
        return targets;
    }

    /** Probe context captured on the server thread; the scan itself is
     * pure math and runs on the shared math pool. */
    private static Function<BlockPos, CompletableFuture<BlockPos>> biomeProbe(
            ServerLevel level, Set<Holder<Biome>> targets, int radius) {
        BiomeSource biomeSource = level.getChunkSource().getGenerator().getBiomeSource();
        Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
        int minY = level.getMinY() + 1;
        int maxY = level.getMaxY() + 1;
        return center -> {
            int[] sampleYs = Mth.outFromOrigin(center.getY(), minY, maxY, 64).toArray();
            return CompletableFuture.supplyAsync(
                    () -> BiomeLocate.anyWithin(biomeSource, sampler, sampleYs, targets,
                            center, radius),
                    AsyncLocate.sharedMathPool());
        };
    }

    /**
     * Grows a distance-ordered prefix across rungs by excluding what
     * earlier rungs returned; the API contract keeps the ordering, so
     * appending preserves the global prefix.
     */
    private Batcher structureBatcher(ServerLevel level, HolderSet<Structure> holders) {
        List<LocateMoreApi.StructureHit> got = new ArrayList<>();
        return count -> {
            if (got.size() >= count) {
                return CompletableFuture.completedFuture(
                        got.stream().map(LocateMoreApi.StructureHit::pos).toList());
            }
            long remainingMs = Math.max(1, (deadlineNanos - System.nanoTime()) / 1_000_000L);
            return AsyncLocate.startForApi(level, holders, origin, count - got.size(),
                            new LocateMoreApi.SearchOptions(LocateMore.maxDistBlocks(), true,
                                    remainingMs, List.copyOf(got)))
                    .thenApply(result -> {
                        got.addAll(result.hits());
                        return got.stream().map(LocateMoreApi.StructureHit::pos).toList();
                    });
        };
    }

    private int begin() {
        source.sendSuccess(() -> Component.literal("Searching for the nearest " + printableA
                        + " with " + printableB + " within " + radius + " blocks…")
                .withStyle(ChatFormatting.GRAY), false);
        RUNNING.incrementAndGet();
        try {
            step(FIRST_RUNG);
        } catch (Throwable t) {
            fail("Search failed: " + t.getMessage());
        }
        return 1;
    }

    private void step(int target) {
        if (target > MAX_RUNG || System.nanoTime() > deadlineNanos) {
            fail("No " + printableA + " with " + printableB + " within " + radius
                    + " blocks found (checked " + probedIndex
                    + " candidates before the time budget ran out).");
            return;
        }
        batcher.upTo(target).whenCompleteAsync((prefix, error) -> {
            try {
                if (error != null) {
                    fail("Search failed: " + error.getMessage());
                    return;
                }
                probeNext(prefix, target);
            } catch (Throwable t) {
                fail("Search failed: " + t.getMessage());
            }
        }, source.getServer());
    }

    private void probeNext(List<BlockPos> prefix, int target) {
        if (probedIndex >= prefix.size()) {
            if (prefix.size() < target) {
                // The engine could not produce a longer prefix: there is
                // no unprobed A left within its distance and budget.
                fail("No " + printableA + " with " + printableB + " within " + radius
                        + " blocks found (checked every candidate, " + probedIndex + ").");
            } else {
                step(target * 3);
            }
            return;
        }
        if (System.nanoTime() > deadlineNanos) {
            fail("No " + printableA + " with " + printableB + " within " + radius
                    + " blocks found (checked " + probedIndex
                    + " candidates before the time budget ran out).");
            return;
        }
        BlockPos aPos = prefix.get(probedIndex++);
        probe.apply(aPos).whenCompleteAsync((bPos, error) -> {
            try {
                if (error == null && bPos != null) {
                    succeed(aPos, bPos);
                } else {
                    probeNext(prefix, target);
                }
            } catch (Throwable t) {
                fail("Search failed: " + t.getMessage());
            }
        }, source.getServer());
    }

    private void succeed(BlockPos aPos, BlockPos bPos) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        RUNNING.decrementAndGet();
        // hitLine reads only the position and distance, so the holder slot
        // of the record is free to be null for biome As.
        LocateMore.Hit aHit = new LocateMore.Hit(aPos, null, LocateMore.horizDistSqr(aPos, origin));
        long dx = bPos.getX() - aPos.getX();
        long dz = bPos.getZ() - aPos.getZ();
        int apart = Mth.floor(Math.sqrt(dx * dx + dz * dz));
        session.chat(() -> Component.literal("Nearest " + printableA + " with " + printableB
                + " nearby:").withStyle(ChatFormatting.GRAY));
        session.chat(() -> HitPresentation.hitLine(1, aHit, printableA, origin, session.viewer));
        session.chat(() -> Component.literal("   " + printableB + " at [" + bPos.getX() + ", "
                + bPos.getZ() + "] (" + apart + " blocks from it)").withStyle(ChatFormatting.GRAY));
    }

    private void fail(String message) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        RUNNING.decrementAndGet();
        session.fail(Component.literal(message));
    }
}
