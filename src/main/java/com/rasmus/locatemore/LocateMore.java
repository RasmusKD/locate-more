package com.rasmus.locatemore;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.datafixers.util.Pair;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.Comparator;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.ResourceOrTagKeyArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheckResult;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Grafts a count onto the vanilla command:
 * /locate structure minecraft:ancient_city 20
 * finds the N nearest structures instead of just the closest.
 *
 * Default mode enumerates the seed's placement candidates in exact distance
 * order (priority queue keyed on each candidate's reported locate position)
 * and verifies each with the same public checks vanilla's own locate uses,
 * so cost scales with hits rather than area. Each region has exactly one
 * candidate chunk; the residual duplicate routes (one structure in several
 * structure sets, legacy re-queues) are collapsed by a dedup set keyed on
 * start identity. Appending "vanilla" runs the naive lab mode instead
 * (a grid of unmodified vanilla nearest-searches, deduped) so the two can
 * be timed against each other.
 */
public class LocateMore implements ModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("locatemore");

    /** Set while lab mode measures unmodified vanilla, so the mixin steps aside. */
    public static final ThreadLocal<Boolean> VANILLA_BYPASS = ThreadLocal.withInitial(() -> false);

    private static final DynamicCommandExceptionType ERROR_STRUCTURE_INVALID = new DynamicCommandExceptionType(
            id -> Component.translatableEscape("commands.locate.structure.invalid", id));
    private static final DynamicCommandExceptionType ERROR_STRUCTURE_NOT_FOUND = new DynamicCommandExceptionType(
            id -> Component.translatableEscape("commands.locate.structure.not_found", id));

    /** Same search radius as the vanilla command, in chunks (lab mode). */
    private static final int VANILLA_RADIUS_CHUNKS = 100;
    /** Distance between lab-mode probe points; vanilla's radius is 1600 blocks, so this overlaps. */
    private static final int PROBE_STEP = 1024;
    /** Lab mode: widen until the count is met or this runs out. */
    private static final long PROBE_TIME_BUDGET_MS = 10_000;
    private static final int PROBE_MAX_RING = 16;

    /** Smart mode gives up past this many blocks out (config: maxDistanceBlocks). */
    static long maxDistBlocks() {
        return Config.maxDistanceBlocks;
    }
    /** Safety valves for pathological placements (checked inside expansion too). */
    static final long SMART_TIME_BUDGET_MS = 15_000;
    static final int MAX_CANDIDATE_CHECKS = 50_000;

    @Override
    public void onInitialize() {
        Config.load();
        AsyncLocate.init();
        DevBridge.init();
        CommandRegistrationCallback.EVENT.register((dispatcher, ctx, env) -> graft(dispatcher));
    }

    private static void graft(CommandDispatcher<CommandSourceStack> dispatcher) {
        registerDebugCommand(dispatcher);
        CommandNode<CommandSourceStack> locate = dispatcher.getRoot().getChild("locate");
        CommandNode<CommandSourceStack> structureLiteral = locate == null ? null : locate.getChild("structure");
        CommandNode<CommandSourceStack> structureArg = structureLiteral == null ? null : structureLiteral.getChild("structure");
        if (structureArg == null) {
            LOGGER.warn("Could not find the vanilla /locate structure <structure> node; count argument not registered");
            return;
        }
        if (structureArg.getChild("count") != null) {
            // Brigadier merges same-named children but keeps the existing node's
            // argument type, which would break our executor at runtime.
            LOGGER.warn("Another mod already registered a 'count' argument on /locate structure; skipping graft");
            return;
        }
        structureArg.addChild(
                RequiredArgumentBuilder.<CommandSourceStack, Integer>argument("count", IntegerArgumentType.integer(1, Config.maxCount))
                        .executes(ctx -> locateAsync(ctx, false))
                        .then(LiteralArgumentBuilder.<CommandSourceStack>literal("next")
                                .executes(ctx -> locateAsync(ctx, true)))
                        .then(LiteralArgumentBuilder.<CommandSourceStack>literal("sync")
                                .requires(net.minecraft.commands.Commands.hasPermission(net.minecraft.commands.Commands.LEVEL_ADMINS))
                                .requires(src -> Config.enableBenchmarkModes)
                                .executes(ctx -> locateMany(ctx, false)))
                        .then(LiteralArgumentBuilder.<CommandSourceStack>literal("vanilla")
                                .requires(net.minecraft.commands.Commands.hasPermission(net.minecraft.commands.Commands.LEVEL_ADMINS))
                                .requires(src -> Config.enableBenchmarkModes)
                                .executes(ctx -> locateMany(ctx, true)))
                        .build());
    }

    /**
     * Measurement tooling: /locatemore cache stats|clear inspects and clears
     * vanilla's in-memory StructureCheck caches (via accessor mixin), so
     * cold/warm timings can be decomposed in a single session instead of
     * relying on rejoin experiments.
     */
    private static void registerDebugCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal("locatemore")
                .requires(net.minecraft.commands.Commands.hasPermission(net.minecraft.commands.Commands.LEVEL_GAMEMASTERS))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("cache")
                        .then(LiteralArgumentBuilder.<CommandSourceStack>literal("stats").executes(ctx -> {
                            ServerLevel level = ctx.getSource().getLevel();
                            var check = (com.rasmus.locatemore.mixin.StructureCheckAccessor)
                                    ((com.rasmus.locatemore.mixin.ServerLevelStructureAccessor) level).locatemore$structureCheck();
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "StructureCheck: " + check.locatemore$loadedChunks().size() + " chunk entries, "
                                            + check.locatemore$featureChecks().size() + " feature maps."), false);
                            return 1;
                        }))
                        .then(LiteralArgumentBuilder.<CommandSourceStack>literal("clear").executes(ctx -> {
                            ServerLevel level = ctx.getSource().getLevel();
                            var check = (com.rasmus.locatemore.mixin.StructureCheckAccessor)
                                    ((com.rasmus.locatemore.mixin.ServerLevelStructureAccessor) level).locatemore$structureCheck();
                            int chunks = check.locatemore$loadedChunks().size();
                            check.locatemore$loadedChunks().clear();
                            check.locatemore$featureChecks().clear();
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "Cleared vanilla StructureCheck caches (" + chunks + " chunk entries)."), false);
                            return 1;
                        })))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("prune").executes(ctx -> {
                    ServerLevel level = ctx.getSource().getLevel();
                    java.nio.file.Path dir = ((com.rasmus.locatemore.mixin.MinecraftServerAccessor) level.getServer())
                            .locatemore$storageSource().getDimensionPath(level.dimension()).resolve("region");
                    int removed = 0;
                    int held = 0;
                    try (var stream = java.nio.file.Files.newDirectoryStream(dir, "r.*.mca")) {
                        for (java.nio.file.Path file : stream) {
                            try {
                                // Only files with literally nothing in them; a region
                                // holding any chunk is past the 8 KB header.
                                if (java.nio.file.Files.size(file) == 0) {
                                    java.nio.file.Files.delete(file);
                                    removed++;
                                }
                            } catch (java.io.IOException e) {
                                held++;
                            }
                        }
                    } catch (java.io.IOException ignored) {
                    }
                    final String line = "Removed " + removed + " empty region files in this dimension"
                            + (held > 0 ? " (" + held + " still held open, run again after a restart)" : "") + ".";
                    ctx.getSource().sendSuccess(() -> Component.literal(line), false);
                    return removed;
                }))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("apitest")
                        .then(RequiredArgumentBuilder.<CommandSourceStack, ResourceOrTagKeyArgument.Result<Structure>>argument(
                                        "structure", ResourceOrTagKeyArgument.resourceOrTagKey(Registries.STRUCTURE))
                                .then(RequiredArgumentBuilder.<CommandSourceStack, Integer>argument(
                                                "count", IntegerArgumentType.integer(1, 100))
                                        .executes(LocateMore::apiSmokeTest))))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("verify")
                        .then(RequiredArgumentBuilder.<CommandSourceStack, ResourceOrTagKeyArgument.Result<Structure>>argument(
                                        "structure", ResourceOrTagKeyArgument.resourceOrTagKey(Registries.STRUCTURE))
                                .executes(ctx -> verifyShadow(ctx, 20))))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("memo")
                        .then(LiteralArgumentBuilder.<CommandSourceStack>literal("clear").executes(ctx -> {
                            AsyncLocate.clearMathMemo();
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "Cleared the math memo."), false);
                            return 1;
                        }))));
    }

    /** Exercises the public API surface end to end from in game. */
    private static int apiSmokeTest(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ResourceOrTagKeyArgument.Result<Structure> result = ResourceOrTagKeyArgument.getResourceOrTagKey(
                ctx, "structure", Registries.STRUCTURE, ERROR_STRUCTURE_INVALID);
        CommandSourceStack source = ctx.getSource();
        Registry<Structure> registry = source.getLevel().registryAccess().lookupOrThrow(Registries.STRUCTURE);
        String printable = result.unwrap().map(
                key -> key.identifier().toString(),
                tag -> "#" + tag.location());
        HolderSet<Structure> holders = result.unwrap().map(
                key -> registry.get(key).map(holder -> (HolderSet<Structure>) HolderSet.direct(holder)),
                registry::get
        ).orElseThrow(() -> ERROR_STRUCTURE_INVALID.create(printable));
        int count = IntegerArgumentType.getInteger(ctx, "count");
        LocateMoreApi.findNearest(source.getLevel(), holders, BlockPos.containing(source.getPosition()), count)
                .whenComplete((search, failure) -> {
                    if (failure != null) {
                        source.sendFailure(Component.literal("API failed: " + failure));
                        return;
                    }
                    StringBuilder line = new StringBuilder("API: " + search.hits().size() + " hits in "
                            + search.tookMillis() + " ms, ordering "
                            + (search.orderingGuaranteed() ? "guaranteed" : "NOT guaranteed"));
                    for (LocateMoreApi.StructureHit hit : search.hits()) {
                        line.append(" [").append(hit.pos().getX()).append(',').append(hit.pos().getZ())
                                .append(" d=").append((int) hit.distance()).append(']');
                    }
                    source.sendSuccess(() -> Component.literal(line.toString()), false);
                });
        return 1;
    }

    /**
     * Drift tripwire: compares the replicated shadow parse against vanilla's
     * independent checkStructurePresence over the nearest candidates, so a
     * vanilla NBT-layout change surfaces as a report instead of silent drift.
     */
    private static int verifyShadow(CommandContext<CommandSourceStack> ctx, int samples)
            throws CommandSyntaxException {
        ResourceOrTagKeyArgument.Result<Structure> result = ResourceOrTagKeyArgument.getResourceOrTagKey(
                ctx, "structure", Registries.STRUCTURE, ERROR_STRUCTURE_INVALID);
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        String printable = result.unwrap().map(
                key -> key.identifier().toString(),
                tag -> "#" + tag.location());
        HolderSet<Structure> holders = result.unwrap().map(
                key -> registry.get(key).map(holder -> (HolderSet<Structure>) HolderSet.direct(holder)),
                registry::get
        ).orElseThrow(() -> ERROR_STRUCTURE_INVALID.create(printable));
        BlockPos origin = BlockPos.containing(source.getPosition());
        List<Hit> hits = smartLocate(level, holders, origin, samples,
                System.nanoTime(), new int[1], new Stats());
        int agree = 0;
        int shadowMissing = 0;
        int divergent = 0;
        for (Hit hit : hits) {
            ChunkPos chunk = new ChunkPos(hit.pos().getX() >> 4, hit.pos().getZ() >> 4);
            var shadow = ShadowScan.scanBlocking(level, chunk);
            if (shadow == ShadowScan.SCAN_FAILED) {
                continue;
            }
            boolean shadowPresent = shadow != null && shadow.containsKey(hit.holder().value());
            if (shadowPresent) {
                agree++;
            } else {
                // Vanilla found it; the shadow parse did not. A resident chunk
                // not yet flushed to disk is benign; anything else is drift.
                shadowMissing++;
            }
        }
        divergent = 0;
        final String line = "Shadow verify " + printable + ": " + agree + " agree, "
                + shadowMissing + " unflushed-or-DRIFT of " + hits.size()
                + " (run right after a save; nonzero after /save-all means parser drift)";
        source.sendSuccess(() -> Component.literal(line), false);
        return agree;
    }

    private static int locateAsync(CommandContext<CommandSourceStack> ctx, boolean skipCurrent)
            throws CommandSyntaxException {
        ResourceOrTagKeyArgument.Result<Structure> result = ResourceOrTagKeyArgument.getResourceOrTagKey(
                ctx, "structure", Registries.STRUCTURE, ERROR_STRUCTURE_INVALID);
        CommandSourceStack source = ctx.getSource();
        Registry<Structure> registry = source.getLevel().registryAccess().lookupOrThrow(Registries.STRUCTURE);
        String printable = result.unwrap().map(
                key -> key.identifier().toString(),
                tag -> "#" + tag.location());
        HolderSet<Structure> holders = result.unwrap().map(
                key -> registry.get(key).map(holder -> (HolderSet<Structure>) HolderSet.direct(holder)),
                registry::get
        ).orElseThrow(() -> ERROR_STRUCTURE_INVALID.create(printable));
        DedupKey excluded = null;
        if (skipCurrent) {
            // The structure the player stands in; its chunk is loaded, so this
            // is a cheap main-thread lookup.
            StructureStart current = source.getLevel().structureManager()
                    .getStructureWithPieceAt(BlockPos.containing(source.getPosition()), holders);
            if (current != null && current.isValid()) {
                excluded = new DedupKey(current.getChunkPos().pack(), current.getStructure());
            }
        }
        return AsyncLocate.start(source, printable, holders,
                IntegerArgumentType.getInteger(ctx, "count"), excluded);
    }

    /** One confirmed structure, in the order it was found (= distance order in smart mode). */
    record Hit(BlockPos pos, Holder<Structure> holder, long horizDistSqr) {
    }

    private static int locateMany(CommandContext<CommandSourceStack> ctx, boolean vanillaLab) throws CommandSyntaxException {
        ResourceOrTagKeyArgument.Result<Structure> result = ResourceOrTagKeyArgument.getResourceOrTagKey(
                ctx, "structure", Registries.STRUCTURE, ERROR_STRUCTURE_INVALID);
        int count = IntegerArgumentType.getInteger(ctx, "count");
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);

        String printable = result.unwrap().map(
                key -> key.identifier().toString(),
                tag -> "#" + tag.location());
        HolderSet<Structure> holders = result.unwrap().map(
                key -> registry.get(key).map(holder -> (HolderSet<Structure>) HolderSet.direct(holder)),
                registry::get
        ).orElseThrow(() -> ERROR_STRUCTURE_INVALID.create(printable));

        BlockPos origin = BlockPos.containing(source.getPosition());
        long startNanos = System.nanoTime();

        List<Hit> hits;
        int checked;
        String mode;
        String statsNote = "";
        if (vanillaLab) {
            int[] probes = new int[1];
            hits = probeVanilla(level, holders, origin, count, startNanos, probes);
            checked = probes[0];
            mode = "vanilla probes";
        } else {
            int[] candidates = new int[1];
            Stats stats = new Stats();
            hits = smartLocate(level, holders, origin, count, startNanos, candidates, stats);
            checked = candidates[0];
            mode = "candidates checked";
            // Outcome buckets make the cost mix a measurement instead of a caveat:
            // cache/disk-present, rejected, chunk loads forced, loads that held a start.
            statsNote = " [present=" + stats.present + " absent=" + stats.absent
                    + " loads=" + stats.loads + " loadHits=" + stats.loadHits + "]";
        }

        long tookMs = (System.nanoTime() - startNanos) / 1_000_000L;
        if (hits.isEmpty()) {
            throw ERROR_STRUCTURE_NOT_FOUND.create(printable);
        }

        int shown = Math.min(count, hits.size());
        final int checkedFinal = checked;
        String note = shown < count ? " - only " + shown + " of " + count + " within range/budget" : "";
        final String statsFinal = statsNote;
        source.sendSuccess(() -> Component.literal(
                shown + " nearest " + printable + " (" + checkedFinal + " " + mode + ", " + tookMs + " ms"
                        + statsFinal + note + "):"), false);
        for (int i = 0; i < shown; i++) {
            Hit hit = hits.get(i);
            final int number = i + 1;
            source.sendSuccess(() -> hitLine(number, hit), false);
        }
        return shown;
    }

    /**
     * Built from vanilla client lang keys only ("chat.coordinates"), so
     * unmodded clients on a dedicated server render it correctly.
     */
    static Component hitLine(int number, Hit hit) {
        int distance = Mth.floor(Mth.sqrt((float) hit.horizDistSqr()));
        Component coordinates = ComponentUtils.wrapInSquareBrackets(Component.translatable("chat.coordinates",
                        hit.pos().getX(), "~", hit.pos().getZ()))
                .withStyle(style -> style.withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent.SuggestCommand(
                                "/tp @s " + hit.pos().getX() + " ~ " + hit.pos().getZ()))
                        .withHoverEvent(new HoverEvent.ShowText(Component.translatable("chat.coordinates.tooltip"))));
        return Component.literal(number + ". ")
                .append(coordinates)
                .append(Component.literal(" (" + distance + " blocks away)"));
    }

    static long horizDistSqr(BlockPos a, BlockPos b) {
        long dx = a.getX() - b.getX();
        long dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    // ------------------------------------------------------------------
    // Smart mode: walk the seed's placement candidates outward in exact
    // distance order and verify with vanilla's own public checks.
    // ------------------------------------------------------------------

    /** Verification outcome buckets, reported in the header for measurements. */
    static final class Stats {
        int present;
        int absent;
        int loads;
        int loadHits;
        int regionSkips;
        int mathSkips;
        int memoHits;

        void merge(Stats other) {
            present += other.present;
            absent += other.absent;
            loads += other.loads;
            loadHits += other.loadHits;
            regionSkips += other.regionSkips;
            mathSkips += other.mathSkips;
            memoHits += other.memoHits;
        }
    }

    record VerifyResult(BlockPos pos, Holder<Structure> holder, ChunkPos startChunk) {
    }

    /**
     * Reimplementation of vanilla's private ChunkGenerator.getStructureGeneratingAt
     * (createReference=false paths only), against public API, preserving its
     * structure iteration order and the CHUNK_LOAD_NEEDED chunk load.
     */
    static VerifyResult verify(Iterable<Holder<Structure>> structures, ServerLevel level,
            StructureManager structureManager, StructurePlacement placement, ChunkPos chunkTarget, Stats stats) {
        for (Holder<Structure> structure : structures) {
            StructureCheckResult check = structureManager.checkStructurePresence(
                    chunkTarget, structure.value(), placement, false);
            if (check == StructureCheckResult.START_NOT_PRESENT) {
                stats.absent++;
                continue;
            }
            if (check == StructureCheckResult.START_PRESENT) {
                stats.present++;
                return new VerifyResult(placement.getLocatePos(chunkTarget), structure, chunkTarget);
            }
            stats.loads++;
            if (!Config.allowProbeChunkGeneration) {
                continue; // admin forbade probe generation; skip like the async path
            }
            ChunkAccess chunk = level.getChunk(chunkTarget.x(), chunkTarget.z(), ChunkStatus.STRUCTURE_STARTS);
            StructureStart start = structureManager.getStartForStructure(
                    SectionPos.bottomOf(chunk), structure.value(), chunk);
            if (start != null && start.isValid()) {
                stats.loadHits++;
                return new VerifyResult(placement.getLocatePos(start.getChunkPos()), structure, start.getChunkPos());
            }
        }
        return null;
    }

    /**
     * Keyed on the candidate's reported locate position, so queue order equals
     * result order and the first N verified hits are provably the N nearest.
     * {@code resolved} is set when a verified hit was re-queued at its corrected
     * distance (legacy start living outside its candidate chunk).
     */
    record Candidate(ChunkPos pos, StructurePlacement placement, Set<Holder<Structure>> holders, long distSqr,
            VerifyResult resolved) {
    }

    record DedupKey(long chunk, Structure structure) {
    }

    /** One random-spread placement's lazily expanding region rings. */
    static final class SpreadSource {
        final RandomSpreadStructurePlacement placement;
        final Set<Holder<Structure>> holders;
        final int originRx;
        final int originRz;
        final int maxRing;
        int nextRing = 0;

        SpreadSource(RandomSpreadStructurePlacement placement, Set<Holder<Structure>> holders, ChunkPos originChunk) {
            this(placement, holders, originChunk, Integer.MAX_VALUE);
        }

        SpreadSource(RandomSpreadStructurePlacement placement, Set<Holder<Structure>> holders, ChunkPos originChunk,
                int ringCap) {
            this.placement = placement;
            this.holders = holders;
            this.originRx = Math.floorDiv(originChunk.x(), placement.spacing());
            this.originRz = Math.floorDiv(originChunk.z(), placement.spacing());
            this.maxRing = Math.min(Math.min(
                    (int) (maxDistBlocks() / ((long) placement.spacing() * 16)) + 2, 4096), ringCap);
        }

        /**
         * Lower bound on the distance of anything in the not-yet-pushed rings;
         * the extra 16 covers a locate offset pulling the reported position
         * outside its region.
         */
        long nextRingMinDistSqr() {
            if (nextRing > maxRing) {
                return Long.MAX_VALUE;
            }
            long d = Math.max(0, (long) Math.max(0, nextRing - 1) * placement.spacing() * 16 - 16);
            return d * d;
        }

        void pushNextRing(long seed, BlockPos origin, PriorityQueue<Candidate> queue) {
            int ring = nextRing++;
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    // getPotentialStructureChunk expects a CHUNK coordinate and
                    // floorDivs by spacing itself; feed it any chunk inside the
                    // target region, never the region index (double division).
                    ChunkPos pos = placement.getPotentialStructureChunk(seed,
                            (originRx + dx) * placement.spacing(),
                            (originRz + dz) * placement.spacing());
                    long distSqr = horizDistSqr(placement.getLocatePos(pos), origin);
                    if (distSqr <= maxDistBlocks() * maxDistBlocks()) {
                        queue.add(new Candidate(pos, placement, holders, distSqr, null));
                    }
                }
            }
        }
    }

    /**
     * Vanilla-replacement entry: the exact-order engine as a synchronous
     * nearest-one search honoring the caller's ring radius. gaveUp[0] is set
     * when a budget stopped the search before the space was exhausted, in
     * which case the caller must fall back to vanilla rather than trust null.
     */
    public static Pair<BlockPos, Holder<Structure>> findNearestExact(ServerLevel level,
            HolderSet<Structure> holders, BlockPos origin, int ringRadius, boolean[] gaveUp) {
        long startNanos = System.nanoTime();
        int[] checked = new int[1];
        List<Hit> hits = smartLocate(level, holders, origin, 1, startNanos, checked, new Stats(), ringRadius, gaveUp);
        if (hits.isEmpty()) {
            return null;
        }
        Hit hit = hits.get(0);
        return Pair.of(hit.pos(), hit.holder());
    }

    private static List<Hit> smartLocate(ServerLevel level, HolderSet<Structure> holders,
            BlockPos origin, int count, long startNanos, int[] checkedOut, Stats stats) {
        return smartLocate(level, holders, origin, count, startNanos, checkedOut, stats,
                Integer.MAX_VALUE, new boolean[1]);
    }

    private static List<Hit> smartLocate(ServerLevel level, HolderSet<Structure> holders,
            BlockPos origin, int count, long startNanos, int[] checkedOut, Stats stats,
            int ringCap, boolean[] gaveUp) {
        ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
        StructureManager structureManager = level.structureManager();
        long seed = state.getLevelSeed();
        ChunkPos originChunk = new ChunkPos(origin.getX() >> 4, origin.getZ() >> 4);

        // LinkedHash* everywhere: vanilla verifies structures in insertion
        // order and reports the first present one, so order must be stable.
        Map<StructurePlacement, Set<Holder<Structure>>> byPlacement = new LinkedHashMap<>();
        for (Holder<Structure> holder : holders) {
            for (StructurePlacement placement : state.getPlacementsForStructure(holder)) {
                byPlacement.computeIfAbsent(placement, k -> new LinkedHashSet<>()).add(holder);
            }
        }

        long maxDistSqr = maxDistBlocks() * maxDistBlocks();
        PriorityQueue<Candidate> queue = new PriorityQueue<>(Comparator.comparingLong(Candidate::distSqr));
        List<SpreadSource> sources = new ArrayList<>();
        for (Map.Entry<StructurePlacement, Set<Holder<Structure>>> entry : byPlacement.entrySet()) {
            if (entry.getKey() instanceof RandomSpreadStructurePlacement spread) {
                sources.add(new SpreadSource(spread, entry.getValue(), originChunk, ringCap));
            } else if (entry.getKey() instanceof ConcentricRingsStructurePlacement rings) {
                // Strongholds: the full (finite) position list is precomputed by
                // the game. Nullable when the placement has no registered rings.
                List<ChunkPos> ringPositions = state.getRingPositionsFor(rings);
                if (ringPositions == null) {
                    LOGGER.warn("No ring positions registered for a concentric-rings placement; skipping it");
                    continue;
                }
                for (ChunkPos pos : ringPositions) {
                    long distSqr = horizDistSqr(rings.getLocatePos(pos), origin);
                    if (distSqr <= maxDistSqr) {
                        queue.add(new Candidate(pos, rings, entry.getValue(), distSqr, null));
                    }
                }
            }
        }

        List<Hit> hits = new ArrayList<>();
        Set<DedupKey> seen = new HashSet<>();
        search:
        while (hits.size() < count) {
            // Keep every source expanded far enough that the queue head is
            // globally nearest. Budget-checked: a placement that never
            // verifies must not spin expansion forever.
            boolean expanded = true;
            while (expanded) {
                expanded = false;
                long head = queue.isEmpty() ? maxDistSqr : Math.min(queue.peek().distSqr(), maxDistSqr);
                for (SpreadSource src : sources) {
                    if (src.nextRingMinDistSqr() <= head) {
                        src.pushNextRing(seed, origin, queue);
                        expanded = true;
                    }
                }
                if (syncOverBudget(startNanos, checkedOut[0])) {
                    gaveUp[0] = true;
                    break search;
                }
            }
            if (queue.isEmpty()) {
                break; // every placement exhausted out to the cap
            }
            Candidate candidate = queue.poll();
            if (candidate.distSqr() > maxDistSqr) {
                break;
            }
            VerifyResult found;
            if (candidate.resolved() != null) {
                found = candidate.resolved();
            } else {
                // Generation's own placement filter: frequency, then exclusion
                // zones. Skipping these probed chunks generation would refuse.
                if (!candidate.placement().applyAdditionalChunkRestrictions(
                        candidate.pos().x(), candidate.pos().z(), seed)
                        || !candidate.placement().applyInteractionsWithOtherStructures(
                                state, candidate.pos().x(), candidate.pos().z())) {
                    stats.absent++;
                    continue;
                }
                checkedOut[0]++;
                found = verify(candidate.holders(), level, structureManager,
                        candidate.placement(), candidate.pos(), stats);
            }
            if (found != null) {
                if (candidate.resolved() == null && !found.startChunk().equals(candidate.pos())) {
                    // Datafixed legacy edge: the start lives outside its candidate
                    // chunk, so its true distance differs from the queued key.
                    // Re-queue at the corrected distance instead of accepting out
                    // of order; it pops back out in its rightful place.
                    queue.add(new Candidate(found.startChunk(), candidate.placement(), candidate.holders(),
                            horizDistSqr(found.pos(), origin), found));
                } else if (seen.add(new DedupKey(found.startChunk().pack(), found.holder().value()))) {
                    hits.add(new Hit(found.pos().immutable(), found.holder(),
                            horizDistSqr(found.pos(), origin)));
                }
            }
            if (syncOverBudget(startNanos, checkedOut[0])) {
                gaveUp[0] = true;
                break;
            }
        }
        return hits;
    }

    private static boolean syncOverBudget(long startNanos, int checked) {
        return checked >= MAX_CANDIDATE_CHECKS
                || (System.nanoTime() - startNanos) / 1_000_000L > SMART_TIME_BUDGET_MS;
    }

    // ------------------------------------------------------------------
    // Lab mode: unmodified vanilla nearest-searches from a probe grid.
    // ------------------------------------------------------------------

    private static List<Hit> probeVanilla(ServerLevel level, HolderSet<Structure> holders,
            BlockPos origin, int count, long startNanos, int[] probesOut) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        Map<BlockPos, Holder<Structure>> found = new LinkedHashMap<>();
        outer:
        for (int ring = 0; ring <= PROBE_MAX_RING; ring++) {
            for (BlockPos probe : ringProbes(origin, ring)) {
                probesOut[0]++;
                Pair<BlockPos, Holder<Structure>> hit;
                VANILLA_BYPASS.set(true);
                try {
                    hit = generator.findNearestMapStructure(
                            level, holders, probe, VANILLA_RADIUS_CHUNKS, false);
                } finally {
                    VANILLA_BYPASS.set(false);
                }
                if (hit != null) {
                    found.putIfAbsent(hit.getFirst().immutable(), hit.getSecond());
                }
                long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
                if (found.size() >= count || elapsedMs > PROBE_TIME_BUDGET_MS) {
                    break outer;
                }
            }
        }
        List<Hit> hits = new ArrayList<>();
        for (Map.Entry<BlockPos, Holder<Structure>> entry : found.entrySet()) {
            hits.add(new Hit(entry.getKey(), entry.getValue(), horizDistSqr(entry.getKey(), origin)));
        }
        hits.sort(Comparator.comparingLong(Hit::horizDistSqr));
        return hits;
    }

    private static List<BlockPos> ringProbes(BlockPos origin, int ring) {
        if (ring == 0) {
            return List.of(origin);
        }
        List<BlockPos> out = new ArrayList<>();
        for (int dx = -ring; dx <= ring; dx++) {
            for (int dz = -ring; dz <= ring; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) == ring) {
                    out.add(origin.offset(dx * PROBE_STEP, 0, dz * PROBE_STEP));
                }
            }
        }
        return out;
    }
}
