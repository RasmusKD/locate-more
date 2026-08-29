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
 * engines: A streams from the structure engine's API path in escalating
 * batches (excluding what earlier rungs already ruled out), and every B
 * check is a bounded probe, either a radius-capped structure search from
 * the A hit or a pure-math biome disc scan. Every budget, referee and
 * dedup rule applies unchanged, and the probes introduce no new trust
 * surface: the biome probe takes only plain full samples, the structure
 * probe is the ordinary engine.
 */
final class NearSearch {

    /** Batch sizes per rung: most pairs resolve in the first handful of A
     * hits, and each rung excludes everything already probed. */
    private static final int[] LADDER = {8, 24, 64};
    /** Battery pacing: AsyncLocate.idle() includes this, so the gaps
     * between a run's inner searches never look idle to the lab driver. */
    private static final AtomicInteger RUNNING = new AtomicInteger();

    private final CommandSourceStack source;
    private final ServerLevel level;
    private final BlockPos origin;
    private final SearchSession session;
    private final HolderSet<Structure> aHolders;
    private final String printableA;
    private final String printableB;
    private final int radius;
    private final Function<BlockPos, CompletableFuture<BlockPos>> probe;
    private final List<LocateMoreApi.StructureHit> excluded = new ArrayList<>();
    private final long deadlineNanos;
    private final AtomicBoolean finished = new AtomicBoolean();
    private int probed;

    private NearSearch(CommandSourceStack source, HolderSet<Structure> aHolders, String printableA,
            String printableB, int radius, Function<BlockPos, CompletableFuture<BlockPos>> probe) {
        this.source = source;
        this.level = source.getLevel();
        this.origin = BlockPos.containing(source.getPosition());
        this.aHolders = aHolders;
        this.printableA = printableA;
        this.printableB = printableB;
        this.radius = radius;
        this.probe = probe;
        // Two engines compose here, so the budget is twice a single
        // search's; each inner call gets the remainder as its ceiling.
        this.deadlineNanos = System.nanoTime() + Config.wallClockSeconds() * 2_000_000_000L;
        // finished is completion bookkeeping, NOT an abort signal: handing
        // it to the session would suppress the very result messages that
        // completion is about to send.
        this.session = new SearchSession(source.getServer(), level.dimension(), source,
                printableA + " near " + printableB, () -> false);
    }

    static boolean idle() {
        return RUNNING.get() == 0;
    }

    static int structureNearStructure(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        var a = ResourceOrTagKeyArgument.getResourceOrTagKey(
                ctx, "structure", Registries.STRUCTURE, LocateMore.ERROR_STRUCTURE_INVALID);
        var b = ResourceOrTagKeyArgument.getResourceOrTagKey(
                ctx, "other", Registries.STRUCTURE, LocateMore.ERROR_STRUCTURE_INVALID);
        int radius = IntegerArgumentType.getInteger(ctx, "radius");
        HolderSet<Structure> aHolders = LocateMore.resolveStructures(source, a);
        HolderSet<Structure> bHolders = LocateMore.resolveStructures(source, b);
        ServerLevel level = source.getLevel();
        NearSearch run = new NearSearch(source, aHolders, LocateMore.structurePrintable(a),
                LocateMore.structurePrintable(b), radius, center ->
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
        int radius = IntegerArgumentType.getInteger(ctx, "radius");
        HolderSet<Structure> aHolders = LocateMore.resolveStructures(source, a);
        ServerLevel level = source.getLevel();
        // Probe context captured on the server thread; the scan itself is
        // pure math and runs on the shared math pool.
        BiomeSource biomeSource = level.getChunkSource().getGenerator().getBiomeSource();
        Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
        Set<Holder<Biome>> targets = biomeSource.possibleBiomes().stream()
                .filter(b).collect(Collectors.toUnmodifiableSet());
        if (targets.isEmpty()) {
            source.sendFailure(Component.literal("This dimension cannot generate " + b.asPrintable() + "."));
            return 0;
        }
        NearSearch run = new NearSearch(source, aHolders, LocateMore.structurePrintable(a),
                b.asPrintable(), radius, center -> {
                    int[] sampleYs = Mth.outFromOrigin(center.getY(),
                            level.getMinY() + 1, level.getMaxY() + 1, 64).toArray();
                    return CompletableFuture.supplyAsync(
                            () -> BiomeLocate.anyWithin(biomeSource, sampler, sampleYs, targets,
                                    center, radius),
                            AsyncLocate.sharedMathPool());
                });
        return run.begin();
    }

    private int begin() {
        source.sendSuccess(() -> Component.literal("Searching for the nearest " + printableA
                        + " with " + printableB + " within " + radius + " blocks…")
                .withStyle(ChatFormatting.GRAY), false);
        RUNNING.incrementAndGet();
        try {
            step(0);
        } catch (Throwable t) {
            fail("Search failed: " + t.getMessage());
        }
        return 1;
    }

    private void step(int rung) {
        long remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
        if (rung >= LADDER.length || remainingMs <= 0) {
            fail("No " + printableA + " with " + printableB + " within " + radius
                    + " blocks found (checked " + probed + " candidates).");
            return;
        }
        AsyncLocate.startForApi(level, aHolders, origin, LADDER[rung],
                        new LocateMoreApi.SearchOptions(LocateMore.maxDistBlocks(), true,
                                remainingMs, List.copyOf(excluded)))
                .whenComplete((result, error) -> {
                    try {
                        if (error != null) {
                            fail("Search failed: " + error.getMessage());
                            return;
                        }
                        probeNext(result, 0, rung);
                    } catch (Throwable t) {
                        fail("Search failed: " + t.getMessage());
                    }
                });
    }

    private void probeNext(LocateMoreApi.SearchResult result, int index, int rung) {
        if (index >= result.hits().size()) {
            excluded.addAll(result.hits());
            if (result.complete() && result.hits().size() < LADDER[rung]) {
                // The engine exhausted the search area: there is no
                // unprobed A left anywhere within the distance ceiling.
                fail("No " + printableA + " with " + printableB + " within " + radius
                        + " blocks found (checked " + probed + " candidates).");
            } else {
                step(rung + 1);
            }
            return;
        }
        LocateMoreApi.StructureHit hit = result.hits().get(index);
        probed++;
        probe.apply(hit.pos()).whenCompleteAsync((bPos, error) -> {
            try {
                if (error == null && bPos != null) {
                    succeed(hit, bPos);
                } else {
                    probeNext(result, index + 1, rung);
                }
            } catch (Throwable t) {
                fail("Search failed: " + t.getMessage());
            }
        }, source.getServer());
    }

    private void succeed(LocateMoreApi.StructureHit hit, BlockPos bPos) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        RUNNING.decrementAndGet();
        LocateMore.Hit aHit = new LocateMore.Hit(hit.pos(), hit.structure(),
                LocateMore.horizDistSqr(hit.pos(), origin));
        long dx = bPos.getX() - hit.pos().getX();
        long dz = bPos.getZ() - hit.pos().getZ();
        int apart = Mth.floor(Math.sqrt(dx * dx + dz * dz));
        session.chat(() -> Component.literal("Nearest " + printableA + " with " + printableB
                + " nearby:").withStyle(ChatFormatting.GRAY));
        session.chat(() -> LocateMore.hitLine(1, aHit, printableA, origin));
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
