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
 * start identity.
 */
public class LocateMore implements ModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("locatemore");

    /** Test-harness hook: the local lab mod sets this while measuring
     * unmodified vanilla, so the mixin steps aside. Volatile boolean instead
     * of a ThreadLocal: the flag is only ever toggled on the server thread,
     * and this read is on vanilla's own locate path. */
    private static volatile boolean labBypass = false;

    /** Read by the mixin: lab-only escape hatch back to unmodified vanilla. */
    public static boolean labBypass() {
        return labBypass;
    }

    /**
     * Lab hook. Outside a development environment the toggle only works when
     * the local lab mod is actually present, so the shipped jar carries no
     * public kill switch with a friendlier name than the config.
     */
    public static void setLabBypass(boolean value) {
        var loader = net.fabricmc.loader.api.FabricLoader.getInstance();
        if (!loader.isDevelopmentEnvironment() && !loader.isModLoaded("locatemorelab")) {
            LOGGER.warn("setLabBypass ignored: lab mod not present");
            return;
        }
        labBypass = value;
    }

    private static final DynamicCommandExceptionType ERROR_STRUCTURE_INVALID = new DynamicCommandExceptionType(
            id -> Component.translatableEscape("commands.locate.structure.invalid", id));
    private static final DynamicCommandExceptionType ERROR_STRUCTURE_NOT_FOUND = new DynamicCommandExceptionType(
            id -> Component.translatableEscape("commands.locate.structure.not_found", id));


    /** Smart mode gives up past this many blocks out (config: maxDistanceBlocks). */
    static long maxDistBlocks() {
        return Config.maxDistanceBlocks();
    }
    /** Safety valves for pathological placements (checked inside expansion too). */
    static final long SMART_TIME_BUDGET_MS = 15_000;
    static final int MAX_CANDIDATE_CHECKS = 50_000;

    @Override
    public void onInitialize() {
        Config.load();
        LocateMoreGameRules.init();
        AsyncLocate.init();
        CommandRegistrationCallback.EVENT.register((dispatcher, ctx, env) -> graft(dispatcher));
        LOGGER.info("Vanilla /locate, eyes of ender, and other mods now return the true nearest "
                + "structure (MC-138887). Per world: /gamerule locatemore:exact_locate false. "
                + "Server-wide kill switch: improveVanillaLocate in config/locatemore.json.");
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
                RequiredArgumentBuilder.<CommandSourceStack, Integer>argument("count", IntegerArgumentType.integer(1, Config.maxCount()))
                        .executes(LocateMore::locateAsync)
                        .build());
    }

    /**
     * Operator tooling: verify (the NBT-parse drift tripwire) and prune.
     * Prune deletes empty region files from vanilla's CREATE-on-scan path,
     * and that path is not only upstream (MC-311323): this mod's own
     * explorer-map route deliberately keeps vanilla's checkStructurePresence
     * under skipKnown, so map searches litter too. Prune stays load-bearing
     * even after Mojang fixes their side.
     */
    private static void registerDebugCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal("locatemore")
                .requires(net.minecraft.commands.Commands.hasPermission(net.minecraft.commands.Commands.LEVEL_GAMEMASTERS))
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
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("verify")
                        .then(RequiredArgumentBuilder.<CommandSourceStack, ResourceOrTagKeyArgument.Result<Structure>>argument(
                                        "structure", ResourceOrTagKeyArgument.resourceOrTagKey(Registries.STRUCTURE))
                                .executes(ctx -> verifyShadow(ctx, 20)))));
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
        final String line = "Shadow verify " + printable + ": " + agree + " agree, "
                + shadowMissing + " unflushed-or-DRIFT of " + hits.size()
                + " (run right after a save; nonzero after /save-all means parser drift)";
        source.sendSuccess(() -> Component.literal(line), false);
        return agree;
    }

    private static int locateAsync(CommandContext<CommandSourceStack> ctx)
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
        return AsyncLocate.start(source, printable, holders,
                IntegerArgumentType.getInteger(ctx, "count"));
    }

    /** One confirmed structure, in the order it was found (= distance order in smart mode). */
    public record Hit(BlockPos pos, Holder<Structure> holder, long horizDistSqr) {
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
    public static final class Stats {
        int loads;
        int loadHits;
        int mathSkips;
        /** Multi-set loads avoided because the trusted draw named the winner. */
        int drawSkips;
        /** On-disk draw sample (see AsyncLocate.sampleDrawOnDisk): observed, and agreements. */
        int drawSeen;
        int drawSeenHits;
        int memoHits;

        void merge(Stats other) {
            loads += other.loads;
            loadHits += other.loadHits;
            mathSkips += other.mathSkips;
            drawSkips += other.drawSkips;
            drawSeen += other.drawSeen;
            drawSeenHits += other.drawSeenHits;
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
            StructureManager structureManager, StructurePlacement placement, ChunkPos chunkTarget,
            boolean skipKnown, Stats stats) {
        for (Holder<Structure> structure : structures) {
            // skipKnown is vanilla's explorer-map filter verbatim: a start
            // with references reads as NOT_PRESENT (checkStructureInfo).
            StructureCheckResult check = structureManager.checkStructurePresence(
                    chunkTarget, structure.value(), placement, skipKnown);
            if (check == StructureCheckResult.START_NOT_PRESENT) {
                continue;
            }
            if (check == StructureCheckResult.START_PRESENT && !skipKnown) {
                return new VerifyResult(placement.getLocatePos(chunkTarget), structure, chunkTarget);
            }
            // skipKnown: even a PRESENT verdict needs the real start loaded,
            // because taking the reference is the point of the call.
            stats.loads++;
            if (!Config.allowProbeChunkGeneration()) {
                continue; // admin forbade probe generation; skip like the async path
            }
            ChunkAccess chunk = level.getChunk(chunkTarget.x(), chunkTarget.z(), ChunkStatus.STRUCTURE_STARTS);
            StructureStart start = structureManager.getStartForStructure(
                    SectionPos.bottomOf(chunk), structure.value(), chunk);
            if (start != null && start.isValid()) {
                if (skipKnown) {
                    if (!start.canBeReferenced()) {
                        continue; // vanilla decides referencability, not us
                    }
                    structureManager.addReference(start);
                }
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
            HolderSet<Structure> holders, BlockPos origin, int ringRadius, boolean skipExistingChunks,
            boolean[] gaveUp) {
        long startNanos = System.nanoTime();
        int[] checked = new int[1];
        List<Hit> hits = smartLocate(level, holders, origin, 1, startNanos, checked, new Stats(),
                ringRadius, gaveUp, true, skipExistingChunks);
        if (gaveUp[0]) {
            // Measurement for the sync budget decision: how often real play
            // hits the 15 s wall (a give-up costs vanilla's own full search
            // ON TOP of the budget, so this should be near zero). Counted
            // always, warned once per dimension+structures per session.
            GAVE_UP_COUNT++;
            String structures = holderNames(holders);
            String key = level.dimension().identifier() + "|" + structures;
            if (GAVE_UP_WARNED.add(key)) {
                LOGGER.warn("Sync search gave up after {} ms / {} candidates (structures [{}], "
                                + "radius {}, skipKnown {}, dim {}); vanilla's own search runs "
                                + "instead. Give-up {} this session.",
                        (System.nanoTime() - startNanos) / 1_000_000L, checked[0], structures,
                        ringRadius, skipExistingChunks, level.dimension().identifier(),
                        GAVE_UP_COUNT);
            }
        }
        if (hits.isEmpty()) {
            return null;
        }
        Hit hit = hits.get(0);
        return Pair.of(hit.pos(), hit.holder());
    }

    private static int GAVE_UP_COUNT;
    private static final Set<String> GAVE_UP_WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static String holderNames(HolderSet<Structure> holders) {
        StringBuilder out = new StringBuilder();
        for (Holder<Structure> holder : holders) {
            if (out.length() > 60) {
                out.append(", ...");
                break;
            }
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(holder.unwrapKey().map(k -> k.identifier().toString()).orElse("?"));
        }
        return out.toString();
    }

    /**
     * Internal bridge for {@link com.rasmus.locatemore.api.LocateMoreApi};
     * not API, do not call. Exists because the public surface lives in its
     * own package and the engine entry it delegates to is package-private.
     */
    public static java.util.concurrent.CompletableFuture<com.rasmus.locatemore.api.LocateMoreApi.SearchResult> apiStart(
            ServerLevel level, HolderSet<Structure> structures, BlockPos origin, int count,
            com.rasmus.locatemore.api.LocateMoreApi.SearchOptions options) {
        return AsyncLocate.startForApi(level, structures, origin, count, options);
    }

    /**
     * Test-harness hook for the local lab mod: the sync engine with the
     * generation-backed path (trustMath=false), so a differential run can
     * compare the trusted engine against real generation.
     */
    public static List<Hit> labLocate(ServerLevel level, HolderSet<Structure> holders,
            BlockPos origin, int count, int[] checkedOut, Stats stats) {
        return smartLocate(level, holders, origin, count, System.nanoTime(), checkedOut, stats,
                Integer.MAX_VALUE, new boolean[1], false, false);
    }

    private static List<Hit> smartLocate(ServerLevel level, HolderSet<Structure> holders,
            BlockPos origin, int count, long startNanos, int[] checkedOut, Stats stats) {
        return smartLocate(level, holders, origin, count, startNanos, checkedOut, stats,
                Integer.MAX_VALUE, new boolean[1], false, false);
    }

    private static List<Hit> smartLocate(ServerLevel level, HolderSet<Structure> holders,
            BlockPos origin, int count, long startNanos, int[] checkedOut, Stats stats,
            int ringCap, boolean[] gaveUp, boolean trustMath, boolean skipKnown) {
        ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
        StructureManager structureManager = level.structureManager();
        long seed = state.getLevelSeed();
        ChunkPos originChunk = new ChunkPos(origin.getX() >> 4, origin.getZ() >> 4);

        // trustMath: the vanilla call sites (plain /locate, eyes of ender,
        // other mods via the mixin) get the same single-set math trust as the
        // async engine. The lab modes pass false and keep real generation as
        // their referee.
        Map<StructurePlacement, Holder<net.minecraft.world.level.levelgen.structure.StructureSet>> trustSets = null;
        AsyncLocate.RegionCatalog trustCatalog = null;
        if (trustMath) {
            trustSets = new java.util.IdentityHashMap<>();
            for (var setHolder : state.possibleStructureSets()) {
                trustSets.put(setHolder.value().placement(), setHolder);
            }
            trustCatalog = new AsyncLocate.RegionCatalog(
                    ((com.rasmus.locatemore.mixin.MinecraftServerAccessor) level.getServer())
                            .locatemore$storageSource().getDimensionPath(level.dimension()).resolve("region"));
        }

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
                // Generation's own placement filter, called through vanilla's
                // composed entry point (isPlacementChunk is redundant-true for
                // our candidates; frequency and exclusion zones do the work).
                if (!candidate.placement().isStructureChunk(
                        state, candidate.pos().x(), candidate.pos().z())) {
                    continue;
                }
                checkedOut[0]++;
                // INVARIANT: this shortcut may only ever be PERMISSIVE. Under
                // skipKnown the decision is always vanilla's (the filter and
                // canBeReferenced evaluate per candidate); a too-restrictive
                // shortcut here would skip a valid nearer candidate and break
                // exactness. Math-absent still prunes for free either way.
                Holder<net.minecraft.world.level.levelgen.structure.StructureSet> trustSetHolder =
                        trustSets == null ? null : trustSets.get(candidate.placement());
                net.minecraft.world.level.levelgen.structure.StructureSet trustSet =
                        trustSetHolder == null ? null : trustSetHolder.value();
                if (trustCatalog != null && !skipKnown && trustSet != null
                        && (trustSet.structures().size() == 1
                                || SetDraw.trusted(trustSetHolder.unwrapKey().orElse(null),
                                        trustSet.structures().size()))
                        && !trustCatalog.mayHoldChunks(candidate.pos())) {
                    // Region-absent candidate: generation would run exactly
                    // this math (findValidGenerationPoint per member, in draw
                    // order), so the first member that validates is the chunk's
                    // winner and the verdict is the answer. For one-member sets
                    // this degenerates to the original single-set shortcut; for
                    // multi-member sets the draw replication carries it (427/427
                    // referee-confirmed before trust shipped). This also keeps
                    // vanilla's StructureCheck scan away from ungenerated
                    // regions, where it would create empty files.
                    found = null;
                    Holder<Structure> winner = SetDraw.winner(seed, candidate.pos(), trustSet,
                            member -> AsyncLocate.mathCanStart(level, member.value(), candidate.pos()));
                    boolean requested = false;
                    if (winner != null) {
                        for (Holder<Structure> holder : candidate.holders()) {
                            if (holder.value() == winner.value()) {
                                requested = true;
                                break;
                            }
                        }
                    }
                    if (requested) {
                        if (trustSet.structures().size() == 1) {
                            stats.mathSkips++;
                        } else {
                            stats.drawSkips++;
                        }
                        found = new VerifyResult(candidate.placement().getLocatePos(candidate.pos()),
                                winner, candidate.pos());
                    } else {
                        if (winner != null) {
                            stats.drawSkips++;
                        }
                    }
                } else {
                    found = verify(candidate.holders(), level, structureManager,
                            candidate.placement(), candidate.pos(), skipKnown, stats);
                }
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


}
