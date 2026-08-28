package com.rasmus.locatemore;

import com.rasmus.locatemore.api.LocateMoreApi;
import com.mojang.datafixers.DataFixer;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.ChunkScanAccess;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Off-thread search service. The command snapshots everything on the server
 * thread; a single daemon worker drives the candidate queue with a shadow
 * verification path (own disk scans + placement math, never touching
 * vanilla's thread-confined StructureCheck); candidates that genuinely need
 * a chunk are resolved on the server thread, budgeted per tick, through
 * {@code getChunkFuture}. Hits stream to chat in exact distance order.
 */
public final class AsyncLocate {

    private static final Logger LOGGER = LoggerFactory.getLogger("locatemore-async");

    /** Kill switch only; the deterministic bounds are distance + candidate caps. */
    /**
     * Waiting room for when every worker slot is busy: player searches admit
     * first (an API consumer must never make a player's /locate feel slow),
     * bounded so a runaway integrator fails fast instead of hoarding memory.
     */
    private static final java.util.ArrayDeque<Task> WAITING_PLAYERS = new java.util.ArrayDeque<>();
    private static final java.util.ArrayDeque<Task> WAITING_API = new java.util.ArrayDeque<>();
    private static final int MAX_WAITING = 64;
    private static final long SCAN_TIMEOUT_MS = 5_000;

    private static final int MAX_CHUNK_LOADS_IN_FLIGHT = 4;
    /** Bound on speculative pending loads per search (also bounds save growth). */
    private static final int MAX_PENDING_LOADS = 8;

    private static final Map<Object, Task> ACTIVE = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<PendingLoad> INCOMING_LOADS = new ConcurrentLinkedQueue<>();
    private static ExecutorService worker;
    private static int loadsInFlight;

    /**
     * Session memo of generation-point math verdicts, mirroring vanilla's
     * featureChecks. Shared by the math pool, so fully concurrent; deliberately
     * NOT persisted (datapacks can shift biome math without changing the seed).
     * Bounded, cleared on server stop and on datapack reload.
     *
     * Justifying measurement (seed 20260821, jungle_pyramid 20): repeat
     * searches drop from ~1.5 s to 274 ms with memoHits=1505. If memoHits
     * reads near zero in real use, delete the memo.
     */
    /** Structures can exist in several dimensions with different generators. */
    private record MemoKey(ResourceKey<Level> dimension, Structure structure) {
    }

    // Two generations instead of clear-all-at-cap: inserts go to the new
    // generation, old-generation hits are promoted, and hitting the cap
    // drops only the old generation. Any verdict touched within the last
    // half-cap of inserts survives (a 2-approximation of LRU), so the
    // measured warm speedup no longer vanishes at an arbitrary moment
    // mid-session. Verdicts are deterministic, so eviction policy can
    // never change results.
    private static volatile Map<MemoKey, ConcurrentHashMap<Long, Boolean>> MATH_MEMO_NEW = new ConcurrentHashMap<>();
    private static volatile Map<MemoKey, ConcurrentHashMap<Long, Boolean>> MATH_MEMO_OLD = new ConcurrentHashMap<>();
    private static final int MATH_MEMO_CAP = 250_000;
    private static final java.util.concurrent.atomic.AtomicInteger MATH_MEMO_SIZE =
            new java.util.concurrent.atomic.AtomicInteger();

    private static synchronized void rotateMemo() {
        if (MATH_MEMO_SIZE.get() <= MATH_MEMO_CAP) {
            return; // another thread already rotated
        }
        MATH_MEMO_OLD = MATH_MEMO_NEW;
        MATH_MEMO_NEW = new ConcurrentHashMap<>();
        MATH_MEMO_SIZE.set(0);
    }

    private static void clearMemo() {
        MATH_MEMO_NEW = new ConcurrentHashMap<>();
        MATH_MEMO_OLD = new ConcurrentHashMap<>();
        MATH_MEMO_SIZE.set(0);
    }

    /** The sampler stack is used concurrently by vanilla's own worldgen workers. */
    private static final int MATH_POOL_SIZE = Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors() / 2));
    private static final int MAX_PENDING_SHADOWS = 16;
    private static ExecutorService mathPool;

    /** The biome engine samples on the same pool; see BiomeLocate. */
    static ExecutorService sharedMathPool() {
        return mathPool();
    }

    /**
     * Lab-harness hook: true when no structure search is running, queued or
     * resolving chunks. The battery driver in the local lab mod awaits this
     * between commands instead of sleeping, which is what makes the release
     * battery signal-paced rather than time-paced. Server thread only.
     */
    public static boolean idle() {
        return ACTIVE.isEmpty() && INCOMING_LOADS.isEmpty() && loadsInFlight == 0
                && WAITING_PLAYERS.isEmpty() && WAITING_API.isEmpty();
    }

    private static synchronized ExecutorService mathPool() {
        if (mathPool == null || mathPool.isShutdown()) {
            java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
            mathPool = Executors.newFixedThreadPool(MATH_POOL_SIZE, r -> {
                Thread thread = new Thread(r, "LocateMore-Math-" + n.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
        }
        return mathPool;
    }

    private AsyncLocate() {
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(AsyncLocate::pumpLoads);
        // A departed player's search wastes bounded work at best; a datapack
        // reload can rebind the structures a search was resolved against.
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            Task task = ACTIVE.get(handler.getPlayer().getUUID());
            if (task != null) {
                task.abort();
                ACTIVE.remove(handler.getPlayer().getUUID(), task);
            }
        });
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
            for (Task task : ACTIVE.values()) {
                task.abort();
            }
            ACTIVE.clear();
            // Biome tags rebind on datapack reload and structure.biomes() is
            // tag-backed, so memoized math verdicts can go stale.
            clearMemo();
            SetDraw.onReload();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (Task task : ACTIVE.values()) {
                task.abort();
            }
            ACTIVE.clear();
            PendingLoad pending;
            while ((pending = INCOMING_LOADS.poll()) != null) {
                pending.result.complete(null);
            }
            // Reset the in-flight counter; completion callbacks on the dying
            // server executor may never run.
            loadsInFlight = 0;
            if (worker != null) {
                worker.shutdownNow();
                worker = null;
            }
            if (mathPool != null) {
                mathPool.shutdownNow();
                mathPool = null;
            }
            clearMemo();
        });
    }

    // ------------------------------------------------------------------
    // Entry (server thread)
    // ------------------------------------------------------------------

    public static int start(CommandSourceStack source, String printable, HolderSet<Structure> holders, int count) {
        Object key = source.getEntity() instanceof ServerPlayer player ? player.getUUID() : "console";
        Task task = buildTask(source, key, printable, holders, count);

        Task previous = ACTIVE.get(key);
        if (previous != null) {
            previous.abort();
            source.sendSuccess(() -> Component.literal("Previous search superseded.").withStyle(ChatFormatting.GRAY), false);
        }
        source.sendSuccess(() -> Component.literal(count == 1
                ? "Searching for the nearest " + printable + "…"
                : "Searching for the " + count + " nearest " + printable + "…")
                .withStyle(ChatFormatting.GRAY), false);
        if (source.getEntity() instanceof ServerPlayer player) {
            task.attachBossBar(player);
        }
        submit(task, true);
        return 1;
    }

    /**
     * API entry: the same engine and budgets behind a silent source. Each
     * call gets its own key, so API searches count against the cap but never
     * supersede a player's search or each other.
     */
    static CompletableFuture<LocateMoreApi.SearchResult> startForApi(ServerLevel level,
            HolderSet<Structure> holders, BlockPos origin, int count, LocateMoreApi.SearchOptions options) {
        CompletableFuture<LocateMoreApi.SearchResult> sink = new CompletableFuture<>();
        CommandSourceStack source = level.getServer().createCommandSourceStack()
                .withSource(CommandSource.NULL)
                .withLevel(level)
                .withPosition(Vec3.atBottomCenterOf(origin));
        Object key = new Object();
        Task task = buildTask(source, key, "structures (api)", holders, count);
        task.apiSink = sink;
        // Per-call overrides, server budgets as hard ceilings.
        task.maxDistBlocks = Math.min(task.maxDistBlocks, Math.max(1, options.maxDistanceBlocks()));
        task.wallClockMs = Math.min(task.wallClockMs, Math.max(1, options.maxMillis()));
        task.allowGeneration = task.allowGeneration && options.allowChunkGeneration();
        for (LocateMoreApi.StructureHit previous : options.excludePrevious()) {
            ChunkPos chunk = new ChunkPos(previous.pos().getX() >> 4, previous.pos().getZ() >> 4);
            task.preExcluded.add(new LocateMore.DedupKey(chunk.pack(), previous.structure().value()));
        }
        submit(task, false);
        return sink;
    }

    /** Admit into a free slot, or wait; player tasks always admit first. */
    private static void submit(Task task, boolean playerPriority) {
        synchronized (WAITING_PLAYERS) {
            if (ACTIVE.size() < Config.maxActiveSearches()) {
                ACTIVE.put(task.key, task);
                workerExecutor().execute(task::run);
                return;
            }
            java.util.ArrayDeque<Task> queue = playerPriority ? WAITING_PLAYERS : WAITING_API;
            if (WAITING_PLAYERS.size() + WAITING_API.size() >= MAX_WAITING) {
                task.failEarly(MAX_WAITING + " searches already waiting; try again shortly.");
                return;
            }
            queue.add(task);
            task.notifyQueued(WAITING_PLAYERS.size() + WAITING_API.size());
        }
    }

    /** Called when a slot frees; re-validates before running. */
    private static void admitNext() {
        Task next;
        synchronized (WAITING_PLAYERS) {
            if (ACTIVE.size() >= Config.maxActiveSearches()) {
                return;
            }
            next = WAITING_PLAYERS.poll();
            if (next == null) {
                next = WAITING_API.poll();
            }
            if (next == null) {
                return;
            }
            ACTIVE.put(next.key, next);
        }
        if (next.aborted.get() || next.server.getLevel(next.dimension) == null) {
            ACTIVE.remove(next.key, next);
            next.failEarly("Search aborted while waiting.");
            admitNext();
            return;
        }
        workerExecutor().execute(next::run);
    }

    private static Task buildTask(CommandSourceStack source, Object key, String printable,
            HolderSet<Structure> holders, int count) {
        MinecraftServer server = source.getServer();
        ServerLevel level = source.getLevel();
        ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
        BlockPos origin = BlockPos.containing(source.getPosition());

        // Server thread: forces ensureStructuresGenerated and joins the ring
        // futures here, never on the worker.
        Map<StructurePlacement, Set<Holder<Structure>>> byPlacement = new LinkedHashMap<>();
        for (Holder<Structure> holder : holders) {
            for (StructurePlacement placement : state.getPlacementsForStructure(holder)) {
                byPlacement.computeIfAbsent(placement, k -> new LinkedHashSet<>()).add(holder);
            }
        }
        List<LocateMore.Candidate> concentric = new ArrayList<>();
        long maxDistSqr = LocateMore.maxDistBlocks() * LocateMore.maxDistBlocks();
        for (Map.Entry<StructurePlacement, Set<Holder<Structure>>> entry : byPlacement.entrySet()) {
            if (entry.getKey() instanceof ConcentricRingsStructurePlacement rings) {
                List<ChunkPos> positions = state.getRingPositionsFor(rings);
                if (positions == null) {
                    continue;
                }
                for (ChunkPos pos : positions) {
                    long d = LocateMore.horizDistSqr(rings.getLocatePos(pos), origin);
                    if (d <= maxDistSqr) {
                        concentric.add(new LocateMore.Candidate(pos, rings, entry.getValue(), d, null));
                    }
                }
            }
        }

        // The set behind each placement: a one-member set has no weighted
        // draw (the math verdict is generation's verdict), and multi-member
        // sets need the whole set so the draw referee can predict the winner.
        Map<StructurePlacement, net.minecraft.core.Holder<net.minecraft.world.level.levelgen.structure.StructureSet>> setByPlacement =
                new java.util.IdentityHashMap<>();
        for (var setHolder : state.possibleStructureSets()) {
            setByPlacement.put(setHolder.value().placement(), setHolder);
        }

        Task built = new Task(server, level.dimension(), source, key, printable, holders, count, origin,
                state, setByPlacement,
                byPlacement, concentric,
                level.registryAccess(), level.getChunkSource().getGenerator(),
                level.getChunkSource().getGenerator().getBiomeSource(), level.getChunkSource().randomState(),
                level.getStructureManager(),
                LevelHeightAccessor.create(level.getMinY(), level.getHeight()),
                level.getChunkSource().chunkScanner(),
                server.getFixerUpper(), state.getLevelSeed());
        for (StructurePlacement placement : byPlacement.keySet()) {
            if (!(placement instanceof RandomSpreadStructurePlacement)
                    && !(placement instanceof ConcentricRingsStructurePlacement)) {
                // A modded placement type the candidate walk cannot
                // enumerate: the search proceeds over the placements it
                // understands, and the summary says so instead of letting
                // a silent miss masquerade as "not found".
                built.unsupportedPlacement = true;
            }
        }
        return built;
    }

    /**
     * Lazily answers whether a region file may hold chunks (the file exists
     * and is larger than a bare 8 KB header). A candidate whose region file
     * is absent cannot be on disk, so the scan is skipped - which also
     * sidesteps vanilla's scan path, whose RegionFile opens with CREATE and
     * would write an empty region file for every unexplored candidate.
     *
     * <p>This replaced a full directory enumeration that cost O(files in
     * the world) per search - on the server thread for the vanilla call
     * sites - with one memoized stat per region actually visited. It is
     * also evaluated when the candidate is examined rather than snapshotted
     * at search start, which shrinks the fresh-world window where spawn
     * regions are written mid-search from the whole search to one stat.
     * Conservative on errors: an unreadable file means "scan it".
     * One instance per search, confined to that search's thread.
     */
    static final class RegionCatalog {
        private final java.nio.file.Path regionDir;
        private final java.util.HashMap<Long, Boolean> cache = new java.util.HashMap<>();

        RegionCatalog(java.nio.file.Path regionDir) {
            this.regionDir = regionDir;
        }

        boolean mayHoldChunks(ChunkPos chunk) {
            int rx = chunk.x() >> 5;
            int rz = chunk.z() >> 5;
            return cache.computeIfAbsent(ChunkPos.pack(rx, rz), key -> {
                try {
                    return java.nio.file.Files.size(
                            regionDir.resolve("r." + rx + "." + rz + ".mca")) > 8192;
                } catch (java.nio.file.NoSuchFileException e) {
                    return false;
                } catch (java.io.IOException e) {
                    return true;
                }
            });
        }
    }

    /**
     * Server-thread math verdict for the sync path, sharing the session memo
     * with the async tasks. Mirrors Task.structureCanStart.
     */
    static boolean mathCanStart(ServerLevel level, Structure structure, ChunkPos pos) {
        return canStartMemoized(level.dimension(), structure, pos, null, () -> {
            HolderSet<net.minecraft.world.level.biome.Biome> biomes = structure.biomes();
            return new Structure.GenerationContext(
                    level.registryAccess(), level.getChunkSource().getGenerator(),
                    level.getChunkSource().getGenerator().getBiomeSource(), level.getChunkSource().randomState(),
                    level.getStructureManager(),
                    level.getChunkSource().getGeneratorState().getLevelSeed(), pos,
                    LevelHeightAccessor.create(level.getMinY(), level.getHeight()), biomes::contains);
        });
    }

    /**
     * The one memoized math verdict both engines share; the callers differ
     * only in how they assemble the generation context (the async worker
     * caches level-derived fields so it never touches the level off-thread).
     */
    private static boolean canStartMemoized(ResourceKey<Level> dimension, Structure structure, ChunkPos pos,
            LocateMore.Stats stats, java.util.function.Supplier<Structure.GenerationContext> context) {
        long pack = pos.pack();
        MemoKey key = new MemoKey(dimension, structure);
        ConcurrentHashMap<Long, Boolean> memo = MATH_MEMO_NEW.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        Boolean cached = memo.get(pack);
        if (cached == null) {
            ConcurrentHashMap<Long, Boolean> old = MATH_MEMO_OLD.get(key);
            if (old != null) {
                cached = old.get(pack);
                if (cached != null) {
                    // Promote: a touched verdict survives the next rotation.
                    memo.put(pack, cached);
                    MATH_MEMO_SIZE.incrementAndGet();
                }
            }
        }
        if (cached != null) {
            if (stats != null) {
                stats.memoHits++;
            }
            return cached;
        }
        Structure.GenerationContext ctx = context.get();
        boolean possible = !monumentCornersFail(structure, ctx)
                && structure.findValidGenerationPoint(ctx).isPresent();
        if (MATH_MEMO_SIZE.incrementAndGet() > MATH_MEMO_CAP) {
            rotateMemo();
            memo = MATH_MEMO_NEW.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        }
        memo.put(pack, possible);
        return possible;
    }


    /**
     * Monument's generation gate demands EVERY biome in a 29-block-radius
     * quart box (15x15x15 = 3375 samples) carry the ocean tag, and rejected
     * candidates pay the whole box. The 8 corners of that box are members
     * of vanilla's own sample set, so any corner failing the tag proves
     * vanilla's check fails: pure permissive rejection, 8 samples instead
     * of 3375 on the (common) miss path. Exact class match, so a modded
     * subclass with a different gate never takes the shortcut, and the tag
     * itself is read live, so datapack tag changes flow through. The
     * release battery's monument line gates this against both game
     * versions.
     */
    private static boolean monumentCornersFail(Structure structure, Structure.GenerationContext context) {
        if (structure.getClass()
                != net.minecraft.world.level.levelgen.structure.structures.OceanMonumentStructure.class) {
            return false;
        }
        int centerX = context.chunkPos().getBlockX(9);
        int centerZ = context.chunkPos().getBlockZ(9);
        int seaLevel = context.chunkGenerator().getSeaLevel();
        int[] xs = {net.minecraft.core.QuartPos.fromBlock(centerX - 29),
                net.minecraft.core.QuartPos.fromBlock(centerX + 29)};
        int[] ys = {net.minecraft.core.QuartPos.fromBlock(seaLevel - 29),
                net.minecraft.core.QuartPos.fromBlock(seaLevel + 29)};
        int[] zs = {net.minecraft.core.QuartPos.fromBlock(centerZ - 29),
                net.minecraft.core.QuartPos.fromBlock(centerZ + 29)};
        for (int x : xs) {
            for (int y : ys) {
                for (int z : zs) {
                    if (!context.biomeSource().getNoiseBiome(x, y, z, context.randomState().sampler())
                            .is(net.minecraft.tags.BiomeTags.REQUIRED_OCEAN_MONUMENT_SURROUNDING)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static synchronized ExecutorService workerExecutor() {
        if (worker == null || worker.isShutdown()) {
            java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
            // Sized to the active-search cap so a second search runs instead of
            // queuing behind the first with a frozen progress bar.
            worker = Executors.newFixedThreadPool(Config.maxActiveSearches(), r -> {
                Thread thread = new Thread(r, "LocateMore-Worker-" + n.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
        }
        return worker;
    }

    // ------------------------------------------------------------------
    // Server-thread chunk resolution, budgeted per tick
    // ------------------------------------------------------------------

    private static final class PendingLoad {
        final Task task;
        final LocateMore.Candidate candidate;
        final CompletableFuture<LocateMore.VerifyResult> result = new CompletableFuture<>();
        /** False when the load came from a failed scan: the chunk may already exist. */
        final boolean knownAbsent;
        /** The draw referee's predicted winner for multi-member sets, or null. */
        final Holder<Structure> predictedWinner;
        boolean retried;

        PendingLoad(Task task, LocateMore.Candidate candidate, boolean knownAbsent,
                Holder<Structure> predictedWinner) {
            this.task = task;
            this.candidate = candidate;
            this.knownAbsent = knownAbsent;
            this.predictedWinner = predictedWinner;
        }
    }

    /** Once per structure per session, loud enough that a bug report carries it. */
    private static final java.util.Set<String> REFEREE_WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void warnRefereeMiss(ServerLevel level, PendingLoad pending) {
        StringBuilder which = new StringBuilder();
        for (Holder<Structure> holder : pending.candidate.holders()) {
            if (which.length() > 0) {
                which.append(',');
            }
            which.append(holder.unwrapKey().map(k -> k.identifier().toString()).orElse("?"));
        }
        if (REFEREE_WARNED.add(which.toString())) {
            LOGGER.warn("Math referee disagreement: predicted {} at chunk {} in {} (seed {}), but generation "
                            + "produced no start. Results stay correct (the load was the authority), but please "
                            + "report this line: it means a vanilla behavior this mod replicates has drifted.",
                    which, pending.candidate.pos(), level.dimension().identifier(),
                    level.getChunkSource().getGeneratorState().getLevelSeed());
        }
    }

    private static void pumpLoads(MinecraftServer server) {
        while (loadsInFlight < MAX_CHUNK_LOADS_IN_FLIGHT) {
            PendingLoad pending = INCOMING_LOADS.poll();
            if (pending == null) {
                return;
            }
            if (pending.task.aborted.get() || pending.task.completed) {
                pending.result.complete(null);
                continue;
            }
            if (!pending.task.allowGeneration) {
                pending.task.unresolved.incrementAndGet();
                pending.result.complete(null);
                continue;
            }
            ServerLevel level = server.getLevel(pending.task.dimension);
            if (level == null) {
                pending.result.complete(null);
                continue;
            }
            ChunkPos pos = pending.candidate.pos();
            loadsInFlight++;
            // Acquired OFF the tick thread: on the main thread, vanilla's
            // getChunkFuture managed-blocks until generation completes, which
            // would invert the never-stalls-the-tick property (round-4 F1).
            final PendingLoad issued = pending;
            CompletableFuture
                    .supplyAsync(() -> level.getChunkSource().getChunkFuture(
                            pos.x(), pos.z(), ChunkStatus.STRUCTURE_STARTS, true), mathPool())
                    .thenCompose(f -> f)
                    .whenCompleteAsync((chunkResult, throwable) -> {
                        loadsInFlight--;
                        // Refill the freed slot immediately instead of waiting
                        // for the next tick boundary (round-4 LM-14).
                        pumpLoads(server);
                        if (pending.task.aborted.get() || pending.task.completed) {
                            pending.result.complete(null);
                            return;
                        }
                        ChunkAccess chunk = throwable == null && chunkResult != null ? chunkResult.orElse(null) : null;
                        if (chunk == null) {
                            // Ticket may have expired mid-generation; retry once.
                            if (!pending.retried) {
                                pending.retried = true;
                                INCOMING_LOADS.add(pending);
                            } else {
                                LOGGER.warn("Chunk resolution failed twice at {}; a candidate was dropped",
                                        pending.candidate.pos());
                                pending.task.unresolved.incrementAndGet();
                                pending.result.complete(null);
                            }
                            return;
                        }
                        // Chunk is resident and vanilla's cache warm via
                        // onStructureLoad, so the vanilla-exact check is cheap.
                        // Scratch stats: the worker already counted this load.
                        // An exception must never leave the future incomplete,
                        // or it would pin the ordering barrier forever.
                        LocateMore.VerifyResult found = null;
                        try {
                            found = LocateMore.verify(pending.candidate.holders(), level,
                                    level.structureManager(), pending.candidate.placement(),
                                    pending.candidate.pos(), false, new LocateMore.Stats());
                            if (pending.knownAbsent) {
                                // knownAbsent means this load exists only because the
                                // math said present: generation is the referee.
                                pending.task.mathLoads++;
                                if (found != null) {
                                    pending.task.mathHits++;
                                } else if (pending.predictedWinner == null) {
                                    warnRefereeMiss(level, pending);
                                }
                            }
                            if (pending.predictedWinner != null && pending.knownAbsent) {
                                // Draw referee: agree when the loaded chunk produced
                                // exactly the predicted member, or produced nothing
                                // for a predicted winner outside the requested set.
                                pending.task.drawLoads++;
                                boolean requested = false;
                                for (Holder<Structure> h : pending.candidate.holders()) {
                                    if (h.value() == pending.predictedWinner.value()) {
                                        requested = true;
                                        break;
                                    }
                                }
                                boolean agree = found != null
                                        ? found.holder().value() == pending.predictedWinner.value()
                                        : !requested;
                                if (agree) {
                                    pending.task.drawHits++;
                                } else {
                                    var badSet = pending.task.setByPlacement.get(pending.candidate.placement());
                                    SetDraw.distrust(badSet == null ? null : badSet.unwrapKey().orElse(null));
                                    LOGGER.warn("Draw referee disagreement at chunk {} in {}: predicted {}, "
                                                    + "generation produced {}. Draw trust is disabled for "
                                                    + "this placement for the rest of the session; multi-set "
                                                    + "candidates there fall back to chunk loads.",
                                            pending.candidate.pos(), level.dimension().identifier(),
                                            pending.predictedWinner.unwrapKey().map(k -> k.identifier().toString()).orElse("?"),
                                            found == null ? "nothing"
                                                    : found.holder().unwrapKey().map(k -> k.identifier().toString()).orElse("?"));
                                }
                            }
                        } catch (Throwable t) {
                            LOGGER.error("Chunk verification failed at {}", pos, t);
                            pending.task.unresolved.incrementAndGet();
                        } finally {
                            // Single completion point: found stays null on any
                            // failure, and nothing can pin the ordering barrier.
                            pending.result.complete(found);
                        }
                    }, server);
        }
    }

    // ------------------------------------------------------------------
    // The search task (worker thread except where noted)
    // ------------------------------------------------------------------

    private static final class Task {
        final MinecraftServer server;
        final ResourceKey<Level> dimension;
        final CommandSourceStack source;
        final Object key;
        final String printable;
        final HolderSet<Structure> holders;
        final int count;
        final BlockPos origin;
        /** Safe off-thread: every lazy init is forced by getPlacementsForStructure on the server thread. */
        final ChunkGeneratorStructureState state;
        final Map<StructurePlacement, net.minecraft.core.Holder<net.minecraft.world.level.levelgen.structure.StructureSet>> setByPlacement;
        final Map<StructurePlacement, Set<Holder<Structure>>> byPlacement;
        final List<LocateMore.Candidate> concentric;
        /** Pre-seeded dedup key for next-mode: the structure the player stands in. */
        final UUID playerId;

        // Captured for the shadow check; all safe off-thread per the audit.
        final RegistryAccess registryAccess;
        final ChunkGenerator generator;
        final BiomeSource biomeSource;
        final RandomState randomState;
        final StructureTemplateManager templateManager;
        final LevelHeightAccessor heightAccessor;
        final ChunkScanAccess scanAccess;
        final DataFixer fixer;
        final long seed;

        /** Per-task budgets; Config values are the defaults and the ceilings. */
        long maxDistBlocks = LocateMore.maxDistBlocks();
        long wallClockMs = Config.wallClockSeconds() * 1000L;
        boolean allowGeneration = Config.allowProbeChunkGeneration();
        final java.util.Set<LocateMore.DedupKey> preExcluded = new java.util.HashSet<>();

        final AtomicBoolean aborted = new AtomicBoolean();
        /** Candidates lost to resolution failure or the probe-generation switch. */
        final java.util.concurrent.atomic.AtomicInteger unresolved = new java.util.concurrent.atomic.AtomicInteger();
        /** Set when the search ends normally, so leftover pending loads are dropped. */
        volatile boolean completed;
        final LocateMore.Stats stats = new LocateMore.Stats();
        /** Math-vs-generation agreement: probe loads whose math verdict was present, and confirmations. */
        int mathLoads;
        int mathHits;
        /** Draw referee (multi-member sets): predictions judged by loads, and agreements. */
        int drawLoads;
        int drawHits;
        /** True when a budget (wall clock or load cap) cut the search short. */
        boolean budgetStopped;
        /** A requested structure sits behind a placement type the engine
         * cannot enumerate; the summary discloses the possible blind spot. */
        boolean unsupportedPlacement;
        /** When set, results complete this future instead of going to chat. */
        volatile CompletableFuture<LocateMoreApi.SearchResult> apiSink;
        /** Regions with chunk data on disk; null means unknown, scan everything. */
        private RegionCatalog regions;
        private volatile ServerBossEvent bossBar;
        private long lastProgressPush;

        Task(MinecraftServer server, ResourceKey<Level> dimension, CommandSourceStack source, Object key,
                String printable, HolderSet<Structure> holders, int count, BlockPos origin,
                ChunkGeneratorStructureState state, Map<StructurePlacement, net.minecraft.core.Holder<net.minecraft.world.level.levelgen.structure.StructureSet>> setByPlacement,
                Map<StructurePlacement, Set<Holder<Structure>>> byPlacement, List<LocateMore.Candidate> concentric,
                RegistryAccess registryAccess, ChunkGenerator generator, BiomeSource biomeSource,
                RandomState randomState, StructureTemplateManager templateManager, LevelHeightAccessor heightAccessor,
                ChunkScanAccess scanAccess, DataFixer fixer, long seed) {
            this.server = server;
            this.dimension = dimension;
            this.source = source;
            this.key = key;
            this.printable = printable;
            this.holders = holders;
            this.count = count;
            this.origin = origin;
            this.state = state;
            this.setByPlacement = setByPlacement;
            this.byPlacement = byPlacement;
            this.concentric = concentric;
            this.registryAccess = registryAccess;
            this.generator = generator;
            this.biomeSource = biomeSource;
            this.randomState = randomState;
            this.templateManager = templateManager;
            this.heightAccessor = heightAccessor;
            this.scanAccess = scanAccess;
            this.fixer = fixer;
            this.seed = seed;
            this.playerId = source.getEntity() instanceof ServerPlayer player ? player.getUUID() : null;
        }

        void attachBossBar(ServerPlayer player) {
            bossBar = new ServerBossEvent(UUID.randomUUID(), Component.literal("Locating " + printable),
                    BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS);
            bossBar.setProgress(0.0F);
            bossBar.addPlayer(player);
        }

        void failEarly(String reason) {
            if (apiSink != null) {
                apiSink.completeExceptionally(new IllegalStateException(reason));
            } else {
                server.execute(() -> {
                    if (stillDeliverable()) {
                        source.sendFailure(Component.literal(reason));
                    }
                });
            }
        }

        void notifyQueued(int position) {
            if (apiSink == null) {
                server.execute(() -> {
                    if (stillDeliverable()) {
                        source.sendSuccess(() -> Component.literal(
                                "All workers busy; queued at position " + position + ".")
                                .withStyle(ChatFormatting.GRAY), false);
                    }
                });
            }
        }

        void abort() {
            if (aborted.compareAndSet(false, true)) {
                if (apiSink != null) {
                    apiSink.completeExceptionally(
                            new java.util.concurrent.CancellationException("Search aborted"));
                }
                server.execute(this::removeBossBar);
            }
        }

        private void removeBossBar() {
            if (bossBar != null) {
                bossBar.removeAllPlayers();
                bossBar = null;
            }
        }

        void run() {
            long startNanos = System.nanoTime();
            try {
                List<LocateMore.Hit> hits = search(startNanos);
                if (!aborted.get()) {
                    finish(hits, startNanos, null);
                }
            } catch (Throwable t) {
                if (!aborted.get()) {
                    LOGGER.error("Async locate failed", t);
                    finish(List.of(), startNanos, t);
                }
            } finally {
                completed = true;
                ACTIVE.remove(key, this);
                server.execute(this::removeBossBar);
                admitNext();
            }
        }

        private List<LocateMore.Hit> search(long startNanos) throws InterruptedException {
            regions = new RegionCatalog(((com.rasmus.locatemore.mixin.MinecraftServerAccessor) server)
                    .locatemore$storageSource().getDimensionPath(dimension).resolve("region"));
            long maxDistSqr = maxDistBlocks * maxDistBlocks;
            ChunkPos originChunk = new ChunkPos(origin.getX() >> 4, origin.getZ() >> 4);
            PriorityQueue<LocateMore.Candidate> queue =
                    new PriorityQueue<>(Comparator.comparingLong(LocateMore.Candidate::distSqr));
            queue.addAll(concentric);
            List<LocateMore.SpreadSource> sources = new ArrayList<>();
            for (Map.Entry<StructurePlacement, Set<Holder<Structure>>> entry : byPlacement.entrySet()) {
                if (entry.getKey() instanceof RandomSpreadStructurePlacement spread) {
                    sources.add(new LocateMore.SpreadSource(spread, entry.getValue(), originChunk));
                }
            }

            List<LocateMore.Hit> hits = new ArrayList<>();
            Set<LocateMore.DedupKey> seen = new HashSet<>();
            seen.addAll(preExcluded); // API: caller's previous hits
            // Two pipelines share one ordering barrier: shadow verifications
            // (scan + math) fan out to the math pool, chunk loads to the
            // server-thread resolver. Verified hits buffer and are finalized
            // only once nothing in flight could still rank ahead of them.
            List<PendingShadow> shadows = new ArrayList<>();
            List<PendingLoad> pending = new ArrayList<>();
            PriorityQueue<LocateMore.Candidate> buffered =
                    new PriorityQueue<>(Comparator.comparingLong(LocateMore.Candidate::distSqr));
            int checked = 0;
            search:
            while (!aborted.get()) {
                // Harvest completed shadow verifications.
                for (var iterator = shadows.iterator(); iterator.hasNext(); ) {
                    PendingShadow shadow = iterator.next();
                    if (!shadow.future().isDone()) {
                        continue;
                    }
                    ShadowDone done;
                    try {
                        done = shadow.future().getNow(null);
                    } catch (RuntimeException e) {
                        // Exceptionally completed (modded placement or structure
                        // threw): drop the candidate, keep the search alive.
                        LOGGER.warn("Shadow verification failed at {}", shadow.candidate().pos(), e);
                        unresolved.incrementAndGet();
                        iterator.remove();
                        continue;
                    }
                    if (done != null && done.shadow().needsLoad() && pending.size() >= MAX_PENDING_LOADS) {
                        continue; // defer: keeps the pending bound hard
                    }
                    iterator.remove();
                    if (done == null) {
                        continue;
                    }
                    stats.merge(done.scratch());
                    LocateMore.Candidate candidate = shadow.candidate();
                    if (done.shadow().needsLoad()) {
                        stats.loads++;
                        PendingLoad load = new PendingLoad(this, candidate, done.shadow().knownAbsent(),
                                done.shadow().predictedWinner());
                        pending.add(load);
                        INCOMING_LOADS.add(load);
                        // Kick the pump now instead of waiting for the tick
                        // boundary: shaves up to one tick (50 ms) off
                        // time-to-first-hit whenever the nearest candidate
                        // needs generation.
                        server.execute(() -> pumpLoads(server));
                    } else if (done.shadow().result() != null) {
                        // Shadow results always come from the candidate chunk's
                        // own NBT, so no legacy-mismatch handling is needed here
                        // (that edge only exists on the chunk-load path above).
                        buffered.add(new LocateMore.Candidate(candidate.pos(), candidate.placement(),
                                candidate.holders(), candidate.distSqr(), done.shadow().result()));
                    }
                }
                // Harvest completed loads.
                for (var iterator = pending.iterator(); iterator.hasNext(); ) {
                    PendingLoad load = iterator.next();
                    if (!load.result.isDone()) {
                        continue;
                    }
                    iterator.remove();
                    LocateMore.VerifyResult resolved = load.result.getNow(null);
                    if (resolved != null) {
                        stats.loadHits++;
                        if (!resolved.startChunk().equals(load.candidate.pos())) {
                            // Datafixed legacy edge: the start lives outside its
                            // candidate chunk. Requeue at the corrected distance
                            // so it pops back out in its rightful place.
                            queue.add(new LocateMore.Candidate(resolved.startChunk(), load.candidate.placement(),
                                    load.candidate.holders(), LocateMore.horizDistSqr(resolved.pos(), origin), resolved));
                        } else {
                            buffered.add(new LocateMore.Candidate(load.candidate.pos(), load.candidate.placement(),
                                    load.candidate.holders(), load.candidate.distSqr(), resolved));
                        }
                    }
                }
                // Finalize buffered hits that nothing in flight can outrank.
                // The queue head is part of the barrier: dispatch order is
                // monotone, so it only ever bites after a legacy re-queue
                // inserted a corrected key below already-dispatched ones.
                long barrier = Long.MAX_VALUE;
                if (!queue.isEmpty()) {
                    barrier = queue.peek().distSqr();
                }
                for (PendingShadow shadow : shadows) {
                    barrier = Math.min(barrier, shadow.candidate().distSqr());
                }
                for (PendingLoad load : pending) {
                    barrier = Math.min(barrier, load.candidate.distSqr());
                }
                while (!buffered.isEmpty() && buffered.peek().distSqr() <= barrier && hits.size() < count) {
                    LocateMore.Candidate done = buffered.poll();
                    LocateMore.VerifyResult found = done.resolved();
                    if (seen.add(new LocateMore.DedupKey(found.startChunk().pack(), found.holder().value()))) {
                        LocateMore.Hit hit = new LocateMore.Hit(found.pos().immutable(), found.holder(),
                                LocateMore.horizDistSqr(found.pos(), origin));
                        hits.add(hit);
                        streamHit(hits.size(), hit);
                    }
                }
                if (hits.size() >= count) {
                    break;
                }
                if (overBudget(startNanos, checked)) {
                    budgetStopped = true;
                    break;
                }
                // Backpressure: bound speculation (and probe-chunk creation).
                if (shadows.size() >= MAX_PENDING_SHADOWS || pending.size() >= MAX_PENDING_LOADS) {
                    pushProgress(hits.size(), checked, startNanos);
                    Thread.sleep(5L);
                    continue;
                }
                boolean expanded = true;
                while (expanded) {
                    expanded = false;
                    long head = queue.isEmpty() ? maxDistSqr : Math.min(queue.peek().distSqr(), maxDistSqr);
                    for (LocateMore.SpreadSource src : sources) {
                        if (src.nextRingMinDistSqr() <= head) {
                            src.pushNextRing(seed, origin, queue);
                            expanded = true;
                        }
                    }
                    if (aborted.get()) {
                        break search;
                    }
                    if (overBudget(startNanos, checked)) {
                        budgetStopped = true;
                        break search;
                    }
                }
                if (queue.isEmpty() || queue.peek().distSqr() > maxDistSqr) {
                    if (shadows.isEmpty() && pending.isEmpty() && buffered.isEmpty()) {
                        break;
                    }
                    Thread.sleep(5L);
                    continue;
                }
                LocateMore.Candidate candidate = queue.poll();
                if (candidate.resolved() != null) {
                    buffered.add(candidate);
                    continue;
                }
                checked++;
                shadows.add(new PendingShadow(candidate, dispatchShadow(candidate)));
                pushProgress(hits.size(), checked, startNanos);
            }
            // Budget/count exits: verified hits that nothing still in flight
            // (or still queued) can outrank are results, not waste.
            long finalBarrier = Long.MAX_VALUE;
            if (!queue.isEmpty()) {
                finalBarrier = queue.peek().distSqr();
            }
            for (PendingShadow shadow : shadows) {
                finalBarrier = Math.min(finalBarrier, shadow.candidate().distSqr());
            }
            for (PendingLoad load : pending) {
                finalBarrier = Math.min(finalBarrier, load.candidate.distSqr());
            }
            while (!buffered.isEmpty() && buffered.peek().distSqr() <= finalBarrier && hits.size() < count
                    && !aborted.get()) {
                LocateMore.Candidate done = buffered.poll();
                LocateMore.VerifyResult found = done.resolved();
                if (seen.add(new LocateMore.DedupKey(found.startChunk().pack(), found.holder().value()))) {
                    LocateMore.Hit hit = new LocateMore.Hit(found.pos().immutable(), found.holder(),
                            LocateMore.horizDistSqr(found.pos(), origin));
                    hits.add(hit);
                    streamHit(hits.size(), hit);
                }
            }
            return hits;
        }

        private record PendingShadow(LocateMore.Candidate candidate, CompletableFuture<ShadowDone> future) {
        }

        private record ShadowDone(Shadow shadow, LocateMore.Stats scratch) {
        }

        private boolean overBudget(long startNanos, int checked) {
            return checked >= LocateMore.MAX_CANDIDATE_CHECKS
                    || (System.nanoTime() - startNanos) / 1_000_000L > wallClockMs;
        }

        private record Shadow(LocateMore.VerifyResult result, boolean needsLoad, boolean knownAbsent,
                Holder<Structure> predictedWinner) {
        }

        /** Chunk verified absent from disk; resolution will generate it. */
        private static final Shadow NEEDS_LOAD = new Shadow(null, true, true, null);
        /** Scan failed; the chunk may already exist, so a load is not a generation. */
        private static final Shadow NEEDS_LOAD_SCAN_FAILED = new Shadow(null, true, false, null);
        private static final Shadow ABSENT = new Shadow(null, false, false, null);

        /**
         * Shadow of vanilla's checkStart per candidate, without touching the
         * thread-confined StructureCheck, built as a non-blocking chain: the
         * IOWorker scan future feeds straight into a math-pool continuation,
         * so pool threads never park on disk waits (the IOWorker serializes
         * scans internally anyway; the win is overlap, not scan parallelism).
         */
        private CompletableFuture<ShadowDone> dispatchShadow(LocateMore.Candidate candidate) {
            LocateMore.Stats scratch = new LocateMore.Stats();
            ChunkPos pos = candidate.pos();
            if (!regions.mayHoldChunks(pos)) {
                // No region file at the moment this candidate is examined, so
                // the chunk cannot be on disk: straight to math. The math and
                // draw verdicts are trusted on this path precisely because
                // generation would run the same computation for an
                // ungenerated chunk (the referees earned that trust; see
                // SetDraw).
                return CompletableFuture.supplyAsync(
                        () -> new ShadowDone(decide(candidate, null, scratch), scratch), mathPool());
            }
            CollectFields collector = ShadowScan.newCollector();
            // The timeout also covers the IOWorker shutdown trap: futures are
            // never completed after close, but orTimeout fires regardless.
            return scanAccess.scanChunk(pos, collector)
                    .orTimeout(SCAN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .handle((ignored, failure) -> failure)
                    .thenApplyAsync(failure -> {
                        Object2IntMap<Structure> onDisk;
                        if (failure != null) {
                            if (!(failure instanceof java.util.concurrent.TimeoutException)) {
                                LOGGER.warn("Failed to read chunk {}", pos, failure);
                            }
                            onDisk = ShadowScan.SCAN_FAILED;
                        } else {
                            onDisk = ShadowScan.parse(collector, pos, dimension,
                                    generator.getTypeNameForDataFixer(), fixer, registryAccess);
                        }
                        return new ShadowDone(decide(candidate, onDisk, scratch), scratch);
                    }, mathPool());
        }

        /** Vanilla's exact per-structure decision order, given the scan outcome. */
        private Shadow decide(LocateMore.Candidate candidate, Object2IntMap<Structure> onDisk,
                LocateMore.Stats scratch) {
            ChunkPos pos = candidate.pos();
            if (onDisk == ShadowScan.SCAN_FAILED) {
                return NEEDS_LOAD_SCAN_FAILED;
            }
            if (onDisk != null) {
                sampleDrawOnDisk(candidate, onDisk, scratch);
            }
            for (Holder<Structure> holder : candidate.holders()) {
                if (onDisk != null) {
                    int references = onDisk.getOrDefault(holder.value(), -1);
                    if (references == -1) {
                        continue;
                    }
                    return new Shadow(new LocateMore.VerifyResult(
                            candidate.placement().getLocatePos(pos), holder, pos), false, false, null);
                }
                // Not on disk: vanilla's math path. isStructureChunk is
                // vanilla's own composition (placement, then frequency, then
                // exclusion zones - outposts refuse to spawn near village
                // candidates); calling it instead of hand-composing the two
                // internals keeps the filter on vanilla's intended entry
                // point. Its isPlacementChunk leg is redundant-true here,
                // because candidates come from getPotentialStructureChunk
                // and getRingPositionsFor.
                if (!candidate.placement().isStructureChunk(state, pos.x(), pos.z())) {
                    continue;
                }
                if (structureCanStart(holder.value(), pos, scratch)) {
                    var setHolder = setByPlacement.get(candidate.placement());
                    net.minecraft.world.level.levelgen.structure.StructureSet set =
                            setHolder == null ? null : setHolder.value();
                    if (set != null && set.structures().size() == 1) {
                        // Generation would run this exact math and nothing else
                        // (Structure.generate calls the same
                        // findValidGenerationPoint), so for a one-structure set
                        // the verdict is the outcome; the math=X/Y referee in
                        // the summary line measured 100% agreement across the
                        // single-set battery before this shortcut shipped.
                        // Multi-structure sets still load: the weighted draw is
                        // generation's call to make.
                        scratch.mathSkips++;
                        return new Shadow(new LocateMore.VerifyResult(
                                candidate.placement().getLocatePos(pos), holder, pos), false, false, null);
                    }
                    if (set != null && SetDraw.trusted(
                            setHolder.unwrapKey().orElse(null), set.structures().size())) {
                        // Multi-member set, trust earned: the draw replicates
                        // generation's weighted pick (427/427 referee-confirmed
                        // across 7 seeds before this shipped), so the first
                        // draw-ordered member whose generation point validates
                        // IS the chunk's winner. A requested winner is the
                        // verdict; any other winner means this chunk belongs to
                        // a different structure. The verify command still loads
                        // and compares, and any observed disagreement distrusts
                        // the placement for the session.
                        Holder<Structure> winner = SetDraw.winner(seed, pos, set,
                                member -> structureCanStart(member.value(), pos, scratch));
                        if (winner != null) {
                            for (Holder<Structure> requested : candidate.holders()) {
                                if (requested.value() == winner.value()) {
                                    scratch.drawSkips++;
                                    return new Shadow(new LocateMore.VerifyResult(
                                            candidate.placement().getLocatePos(pos), requested, pos),
                                            false, false, null);
                                }
                            }
                            scratch.drawSkips++;
                        }
                        return ABSENT;
                    }
                    // Distrusted or oversized set: referee mode, predict and
                    // let the load judge it (draw=hits/loads in the summary).
                    Holder<Structure> predicted = set == null ? null : SetDraw.winner(seed, pos, set,
                            member -> structureCanStart(member.value(), pos, scratch));
                    return new Shadow(null, true, true, predicted);
                }
            }
            return ABSENT;
        }



        /**
         * The standing sample that keeps the draw trust honest after the
         * flip: the load referee only ever saw ungenerated chunks, and
         * trust removed most of those, so this samples the opposite
         * population - multi-set candidates already generated on disk -
         * where the comparison is free of I/O and generation. One in eight
         * by chunk coordinate, deterministic. Log-only by design: a chunk
         * on disk may predate the current MC version or datapack, so a
         * mismatch here is a lead, not proof; auto-distrust stays with the
         * load referee, whose ground truth is generated now.
         */
        private void sampleDrawOnDisk(LocateMore.Candidate candidate, Object2IntMap<Structure> onDisk,
                LocateMore.Stats scratch) {
            var setHolder = setByPlacement.get(candidate.placement());
            if (setHolder == null || setHolder.value().structures().size() < 2) {
                return;
            }
            ChunkPos pos = candidate.pos();
            if (((pos.x() ^ pos.z()) & 7) != 0) {
                return;
            }
            if (!candidate.placement().isStructureChunk(state, pos.x(), pos.z())) {
                return;
            }
            LocateMore.Stats throwaway = new LocateMore.Stats();
            Holder<Structure> winner = SetDraw.winner(seed, pos, setHolder.value(),
                    member -> structureCanStart(member.value(), pos, throwaway));
            boolean agree;
            if (winner != null) {
                agree = onDisk.getOrDefault(winner.value(), -1) != -1;
            } else {
                agree = true;
                for (var entry : setHolder.value().structures()) {
                    if (onDisk.getOrDefault(entry.structure().value(), -1) != -1) {
                        agree = false;
                        break;
                    }
                }
            }
            scratch.drawSeen++;
            if (agree) {
                scratch.drawSeenHits++;
            } else {
                LOGGER.warn("On-disk draw sample disagreement at chunk {} in {}: predicted {}. The chunk "
                                + "may predate the current version or datapack, so this does not distrust "
                                + "the draw by itself; investigate if it repeats on freshly generated chunks.",
                        pos, dimension.identifier(),
                        winner == null ? "no member"
                                : winner.unwrapKey().map(k -> k.identifier().toString()).orElse("?"));
            }
        }

        private boolean structureCanStart(Structure structure, ChunkPos pos, LocateMore.Stats scratch) {
            return canStartMemoized(dimension, structure, pos, scratch, () -> {
                HolderSet<net.minecraft.world.level.biome.Biome> biomes = structure.biomes();
                return new Structure.GenerationContext(
                        registryAccess, generator, biomeSource, randomState, templateManager,
                        seed, pos, heightAccessor, biomes::contains);
            });
        }

        // ------------------------------------------------------------------
        // Output (always via server thread hops with re-validation)
        // ------------------------------------------------------------------

        private void streamHit(int number, LocateMore.Hit hit) {
            if (apiSink != null) {
                return;
            }
            server.execute(() -> {
                if (aborted.get() || !stillDeliverable()) {
                    return;
                }
                source.sendSuccess(() -> LocateMore.hitLine(number, hit, printable), false);
            });
        }

        private void pushProgress(int found, int checked, long startNanos) {
            long now = System.currentTimeMillis();
            if (bossBar == null || now - lastProgressPush < 500L) {
                return;
            }
            lastProgressPush = now;
            long elapsed = (System.nanoTime() - startNanos) / 1_000_000_000L;
            float progress = Math.min(1.0F, found / (float) count);
            server.execute(() -> {
                if (bossBar != null && !aborted.get()) {
                    bossBar.setProgress(progress);
                    bossBar.setName(Component.literal("Locating " + printable + ": " + found + "/" + count
                            + " found, " + checked + " checked, " + elapsed + " s"));
                }
            });
        }

        private void finish(List<LocateMore.Hit> hits, long startNanos, Throwable error) {
            long tookMs = (System.nanoTime() - startNanos) / 1_000_000L;
            if (stats.drawSeen > 0) {
                // The standing sample is invisible in a quiet chat summary
                // (full agreement is deliberately not "interesting"), so the
                // evidence lands in the log where the operator collects it.
                LOGGER.info("On-disk draw sample: {}/{} predictions matched already-generated chunks ({}).",
                        stats.drawSeenHits, stats.drawSeen, printable);
            }
            // API futures complete directly: routing them through the server
            // executor could drop the completion on a stopping server, and a
            // caller awaiting the future would hang forever. Completing off
            // the server thread is safe; the contract's "completes on the
            // server thread" holds on every path except server shutdown,
            // where completing at all is the contract that matters.
            if (apiSink != null) {
                server.execute(this::removeBossBar);
                if (error != null) {
                    apiSink.completeExceptionally(error);
                    return;
                }
                List<LocateMoreApi.StructureHit> out = new ArrayList<>(hits.size());
                for (LocateMore.Hit hit : hits) {
                    out.add(new LocateMoreApi.StructureHit(hit.pos(), hit.holder(),
                            Math.sqrt((double) hit.horizDistSqr())));
                }
                CompletableFuture<LocateMoreApi.SearchResult> sink = apiSink;
                LocateMoreApi.SearchResult result = new LocateMoreApi.SearchResult(List.copyOf(out),
                        unresolved.get() == 0, hits.size() >= count || !budgetStopped, tookMs);
                server.execute(() -> sink.complete(result));
                // Backstop: a stopping server can drop the scheduled hop, and
                // complete() is first-wins, so a delayed off-thread completion
                // guarantees the caller never hangs without ever changing the
                // value delivered on the normal path.
                CompletableFuture.delayedExecutor(5, TimeUnit.SECONDS)
                        .execute(() -> sink.complete(result));
                return;
            }
            server.execute(() -> {
                removeBossBar();
                if (!stillDeliverable()) {
                    return;
                }
                if (error != null) {
                    source.sendFailure(Component.literal("Search failed: " + error.getClass().getSimpleName()
                            + " (see log)"));
                    return;
                }
                if (hits.isEmpty()) {
                    source.sendFailure(Component.literal("No " + printable + " found within "
                            + maxDistBlocks + " blocks."));
                    return;
                }
                // A clean search earns a quiet line; the counters only appear when
                // they explain something (chunk work, partial results, or a
                // referee disagreement).
                boolean interesting = stats.loads > 0 || unresolved.get() > 0 || hits.size() < count
                        || mathHits < mathLoads || stats.drawSeenHits < stats.drawSeen
                        || unsupportedPlacement;
                String detail = "";
                if (interesting) {
                    String baseNote = hits.size() < count
                            ? " - only " + hits.size() + " of " + count + " within range/budget" : "";
                    String note = unresolved.get() > 0
                            ? baseNote + " - " + unresolved.get() + " candidates unresolved; ordering not guaranteed"
                            : baseNote;
                    if (unsupportedPlacement) {
                        note += " - a placement type this engine cannot enumerate was skipped;"
                                + " vanilla /locate (gamerule locatemore:exact_locate false) still covers it";
                    }
                    String probeNote = mathLoads > 0
                            ? " - generated " + mathLoads + " probe chunks" : "";
                    String mathNote = (mathLoads > 0 ? " math=" + mathHits + "/" + mathLoads : "")
                            + (drawLoads > 0 ? " draw=" + drawHits + "/" + drawLoads : "")
                            + (stats.drawSeen > 0 ? " drawSeen=" + stats.drawSeenHits + "/" + stats.drawSeen : "");
                    String avoided = stats.mathSkips + stats.drawSkips > 0
                            ? " avoided=" + (stats.mathSkips + stats.drawSkips) : "";
                    detail = " [loads=" + stats.loads + " loadHits=" + stats.loadHits
                            + " memoHits=" + stats.memoHits + avoided + "]" + mathNote + note + probeNote;
                }
                final String line = (count == 1 && hits.size() == 1
                        ? "Nearest " + printable
                        : hits.size() + " nearest " + printable)
                        + " (" + tookMs + " ms" + detail + ")";
                source.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
            });
        }

        private boolean stillDeliverable() {
            if (!server.isRunning() || server.getLevel(dimension) == null) {
                return false;
            }
            return playerId == null || server.getPlayerList().getPlayer(playerId) != null;
        }
    }
}
