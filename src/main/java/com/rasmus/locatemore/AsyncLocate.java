package com.rasmus.locatemore;

import com.mojang.datafixers.DataFixer;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
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
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
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
    private static long wallClockLimitMs() {
        return Config.wallClockSeconds * 1000L;
    }
    private static final long SCAN_TIMEOUT_MS = 5_000;

    private static final int MAX_CHUNK_LOADS_IN_FLIGHT = 4;
    /** Bound on speculative pending loads per search (also bounds save growth). */
    private static final int MAX_PENDING_LOADS = 8;

    private static final Map<Object, Task> ACTIVE = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<PendingLoad> INCOMING_LOADS = new ConcurrentLinkedQueue<>();
    /** Worker-side observations; applied to the index on the server thread only. */
    private record IndexMutation(StructureIndex index, long chunk, boolean absent) {
    }

    private static final ConcurrentLinkedQueue<IndexMutation> INDEX_MUTATIONS = new ConcurrentLinkedQueue<>();
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

    private static final Map<MemoKey, ConcurrentHashMap<Long, Boolean>> MATH_MEMO = new ConcurrentHashMap<>();
    private static final int MATH_MEMO_CAP = 500_000;
    private static final java.util.concurrent.atomic.AtomicInteger MATH_MEMO_SIZE =
            new java.util.concurrent.atomic.AtomicInteger();

    /** The sampler stack is used concurrently by vanilla's own worldgen workers. */
    private static final int MATH_POOL_SIZE = Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors() / 2));
    private static final int MAX_PENDING_SHADOWS = 16;
    private static ExecutorService mathPool;

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
            MATH_MEMO.clear();
            MATH_MEMO_SIZE.set(0);
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
            // Apply outstanding index observations so the final world save
            // carries them, and reset the in-flight counter (completion
            // callbacks on the dying server executor may never run).
            drainIndexMutations();
            loadsInFlight = 0;
            if (worker != null) {
                worker.shutdownNow();
                worker = null;
            }
            if (mathPool != null) {
                mathPool.shutdownNow();
                mathPool = null;
            }
            MATH_MEMO.clear();
            MATH_MEMO_SIZE.set(0);
        });
    }

    // ------------------------------------------------------------------
    // Entry (server thread)
    // ------------------------------------------------------------------

    public static int start(CommandSourceStack source, String printable, HolderSet<Structure> holders, int count) {
        return start(source, printable, holders, count, null);
    }

    public static int start(CommandSourceStack source, String printable, HolderSet<Structure> holders, int count,
            LocateMore.DedupKey excluded) {
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

        StructureIndex index = level.getDataStorage().computeIfAbsent(StructureIndex.TYPE);
        index.validate(state.getLevelSeed(), configFingerprint(level));

        Object key = source.getEntity() instanceof ServerPlayer player ? player.getUUID() : "console";
        Task task = new Task(server, level.dimension(), source, key, printable, holders, count, origin,
                excluded,
                byPlacement, concentric,
                level.registryAccess(), level.getChunkSource().getGenerator(),
                level.getChunkSource().getGenerator().getBiomeSource(), level.getChunkSource().randomState(),
                level.getStructureManager(),
                LevelHeightAccessor.create(level.getMinY(), level.getHeight()),
                level.getChunkSource().chunkScanner(),
                server.getFixerUpper(), state.getLevelSeed(), index);

        Task previous = ACTIVE.get(key);
        if (previous == null && ACTIVE.size() >= Config.maxActiveSearches) {
            source.sendFailure(Component.literal(Config.maxActiveSearches + " searches are already running; try again shortly."));
            return 0;
        }
        ACTIVE.put(key, task);
        if (previous != null) {
            previous.abort();
            source.sendSuccess(() -> Component.literal("Previous search superseded.").withStyle(ChatFormatting.GRAY), false);
        }
        source.sendSuccess(() -> Component.literal(
                "Searching for the " + count + " nearest " + printable + "…").withStyle(ChatFormatting.GRAY), false);
        if (source.getEntity() instanceof ServerPlayer player) {
            task.attachBossBar(player);
        }
        workerExecutor().execute(task::run);
        return 1;
    }

    /**
     * Fingerprint of the generation config the index depends on: the
     * structure-set registry (ids, placement class, spacing/separation) and
     * the game data version. Salt/frequency are not readable through public
     * API, so a pure parameter tweak inside an existing set is not covered;
     * the README documents /locatemore index clear for that case.
     */
    private static int configFingerprint(ServerLevel level) {
        int hash = net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version();
        // The math path reads biomes and noise settings, so those registries
        // must invalidate the index too (round-4 consensus finding).
        for (var regKey : List.of(Registries.BIOME, Registries.NOISE_SETTINGS)) {
            List<String> ids = new ArrayList<>();
            for (var e : level.registryAccess().lookupOrThrow(regKey).entrySet()) {
                ids.add(e.getKey().identifier().toString());
            }
            java.util.Collections.sort(ids);
            hash = 31 * hash + ids.hashCode();
        }
        var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET);
        List<String> parts = new ArrayList<>();
        for (var entry : registry.entrySet()) {
            StructurePlacement placement = entry.getValue().placement();
            StringBuilder part = new StringBuilder(entry.getKey().identifier().toString())
                    .append('|').append(placement.getClass().getSimpleName());
            if (placement instanceof RandomSpreadStructurePlacement spread) {
                part.append('|').append(spread.spacing()).append(':').append(spread.separation());
            }
            parts.add(part.toString());
        }
        java.util.Collections.sort(parts);
        return 31 * hash + parts.hashCode();
    }

    /** Admin-facing invalidation for the cache no fingerprint can see into. */
    static void clearMathMemo() {
        MATH_MEMO.clear();
        MATH_MEMO_SIZE.set(0);
    }

    private static synchronized ExecutorService workerExecutor() {
        if (worker == null || worker.isShutdown()) {
            java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
            // Sized to the active-search cap so a second search runs instead of
            // queuing behind the first with a frozen progress bar.
            worker = Executors.newFixedThreadPool(Config.maxActiveSearches, r -> {
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
        boolean retried;

        PendingLoad(Task task, LocateMore.Candidate candidate, boolean knownAbsent) {
            this.task = task;
            this.candidate = candidate;
            this.knownAbsent = knownAbsent;
        }
    }

    private static void drainIndexMutations() {
        IndexMutation mutation;
        Set<StructureIndex> mutated = new HashSet<>();
        while ((mutation = INDEX_MUTATIONS.poll()) != null) {
            if (mutation.index().apply(mutation.chunk(), mutation.absent())) {
                mutated.add(mutation.index());
            }
        }
        for (StructureIndex index : mutated) {
            index.setDirty();
        }
    }

    private static void pumpLoads(MinecraftServer server) {
        drainIndexMutations();
        while (loadsInFlight < MAX_CHUNK_LOADS_IN_FLIGHT) {
            PendingLoad pending = INCOMING_LOADS.poll();
            if (pending == null) {
                return;
            }
            if (pending.task.aborted.get() || pending.task.completed) {
                pending.result.complete(null);
                continue;
            }
            if (!Config.allowProbeChunkGeneration) {
                pending.task.unresolved++;
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
                                pending.task.unresolved++;
                                pending.result.complete(null);
                            }
                            return;
                        }
                        if (pending.knownAbsent) {
                            pending.task.chunksGenerated++;
                        }
                        // The chunk now exists (it reaches disk on save); a stale entry
                        // would only cost a redundant load, but keep it honest.
                        INDEX_MUTATIONS.add(new IndexMutation(pending.task.index, pos.pack(), false));
                        // Chunk is resident and vanilla's cache warm via
                        // onStructureLoad, so the vanilla-exact check is cheap.
                        // Scratch stats: the worker already counted this load.
                        // An exception must never leave the future incomplete,
                        // or it would pin the ordering barrier forever.
                        try {
                            pending.result.complete(LocateMore.verify(pending.candidate.holders(), level,
                                    level.structureManager(), pending.candidate.placement(),
                                    pending.candidate.pos(), new LocateMore.Stats()));
                        } catch (Throwable t) {
                            LOGGER.error("Chunk verification failed at {}", pos, t);
                            pending.task.unresolved++;
                        } finally {
                            pending.result.complete(null);
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
        final Map<StructurePlacement, Set<Holder<Structure>>> byPlacement;
        final List<LocateMore.Candidate> concentric;
        /** Pre-seeded dedup key for next-mode: the structure the player stands in. */
        final LocateMore.DedupKey excluded;
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
        final StructureIndex index;

        final AtomicBoolean aborted = new AtomicBoolean();
        /** Candidates lost to resolution failure or the probe-generation switch. */
        volatile int unresolved;
        /** Set when the search ends normally, so leftover pending loads are dropped. */
        volatile boolean completed;
        final LocateMore.Stats stats = new LocateMore.Stats();
        int chunksGenerated;
        private volatile ServerBossEvent bossBar;
        private long lastProgressPush;

        Task(MinecraftServer server, ResourceKey<Level> dimension, CommandSourceStack source, Object key,
                String printable, HolderSet<Structure> holders, int count, BlockPos origin,
                LocateMore.DedupKey excluded,
                Map<StructurePlacement, Set<Holder<Structure>>> byPlacement, List<LocateMore.Candidate> concentric,
                RegistryAccess registryAccess, ChunkGenerator generator, BiomeSource biomeSource,
                RandomState randomState, StructureTemplateManager templateManager, LevelHeightAccessor heightAccessor,
                ChunkScanAccess scanAccess, DataFixer fixer, long seed, StructureIndex index) {
            this.server = server;
            this.dimension = dimension;
            this.source = source;
            this.key = key;
            this.printable = printable;
            this.holders = holders;
            this.count = count;
            this.origin = origin;
            this.byPlacement = byPlacement;
            this.concentric = concentric;
            this.excluded = excluded;
            this.registryAccess = registryAccess;
            this.generator = generator;
            this.biomeSource = biomeSource;
            this.randomState = randomState;
            this.templateManager = templateManager;
            this.heightAccessor = heightAccessor;
            this.scanAccess = scanAccess;
            this.fixer = fixer;
            this.seed = seed;
            this.index = index;
            this.playerId = source.getEntity() instanceof ServerPlayer player ? player.getUUID() : null;
        }

        void attachBossBar(ServerPlayer player) {
            bossBar = new ServerBossEvent(UUID.randomUUID(), Component.literal("Locating " + printable),
                    BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS);
            bossBar.setProgress(0.0F);
            bossBar.addPlayer(player);
        }

        void abort() {
            if (aborted.compareAndSet(false, true)) {
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
            }
        }

        private List<LocateMore.Hit> search(long startNanos) throws InterruptedException {
            long maxDistSqr = LocateMore.maxDistBlocks() * LocateMore.maxDistBlocks();
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
            if (excluded != null) {
                seen.add(excluded); // next-mode: skip the structure the player stands in
            }
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
                        unresolved++;
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
                        PendingLoad load = new PendingLoad(this, candidate, done.shadow().knownAbsent());
                        pending.add(load);
                        INCOMING_LOADS.add(load);
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
                    if (aborted.get() || overBudget(startNanos, checked)) {
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
                    || (System.nanoTime() - startNanos) / 1_000_000L > wallClockLimitMs();
        }

        private record Shadow(LocateMore.VerifyResult result, boolean needsLoad, boolean knownAbsent) {
        }

        /** Chunk verified absent from disk; resolution will generate it. */
        private static final Shadow NEEDS_LOAD = new Shadow(null, true, true);
        /** Scan failed; the chunk may already exist, so a load is not a generation. */
        private static final Shadow NEEDS_LOAD_SCAN_FAILED = new Shadow(null, true, false);
        private static final Shadow ABSENT = new Shadow(null, false, false);

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
            if (Config.persistentIndex && index.isKnownAbsentFromDisk(pos.pack())) {
                // Authoritative negative: skip the disk scan, straight to math.
                scratch.indexHits++;
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
                            onDisk = SCAN_FAILED;
                        } else {
                            onDisk = parseStarts(pos, collector);
                            if (onDisk == null && Config.persistentIndex) {
                                INDEX_MUTATIONS.add(new IndexMutation(index, pos.pack(), true));
                            }
                        }
                        return new ShadowDone(decide(candidate, onDisk, scratch), scratch);
                    }, mathPool());
        }

        /** Vanilla's exact per-structure decision order, given the scan outcome. */
        private Shadow decide(LocateMore.Candidate candidate, Object2IntMap<Structure> onDisk,
                LocateMore.Stats scratch) {
            ChunkPos pos = candidate.pos();
            if (onDisk == SCAN_FAILED) {
                return NEEDS_LOAD_SCAN_FAILED;
            }
            for (Holder<Structure> holder : candidate.holders()) {
                if (onDisk != null) {
                    int references = onDisk.getOrDefault(holder.value(), -1);
                    if (references == -1) {
                        scratch.absent++;
                        continue;
                    }
                    scratch.present++;
                    return new Shadow(new LocateMore.VerifyResult(
                            candidate.placement().getLocatePos(pos), holder, pos), false, false);
                }
                // Not on disk: vanilla's math path.
                if (!candidate.placement().applyAdditionalChunkRestrictions(pos.x(), pos.z(), seed)) {
                    scratch.absent++;
                    continue;
                }
                if (structureCanStart(holder.value(), pos, scratch)) {
                    return NEEDS_LOAD;
                }
                scratch.absent++;
            }
            return ABSENT;
        }

        private static final Object2IntMap<Structure> SCAN_FAILED =
                it.unimi.dsi.fastutil.objects.Object2IntMaps.unmodifiable(new Object2IntOpenHashMap<>());

        private Object2IntMap<Structure> parseStarts(ChunkPos pos, CollectFields collector) {
            Object2IntMap<Structure> parsed = ShadowScan.parse(collector, pos, dimension,
                    generator.getTypeNameForDataFixer(), fixer, registryAccess);
            return parsed == ShadowScan.SCAN_FAILED ? SCAN_FAILED : parsed;
        }


        private boolean structureCanStart(Structure structure, ChunkPos pos, LocateMore.Stats scratch) {
            long pack = pos.pack();
            MemoKey key = new MemoKey(dimension, structure);
            ConcurrentHashMap<Long, Boolean> memo = MATH_MEMO.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
            Boolean cached = memo.get(pack);
            if (cached != null) {
                scratch.memoHits++;
                return cached;
            }
            HolderSet<net.minecraft.world.level.biome.Biome> biomes = structure.biomes();
            boolean possible = structure.findValidGenerationPoint(new Structure.GenerationContext(
                    registryAccess, generator, biomeSource, randomState, templateManager,
                    seed, pos, heightAccessor, biomes::contains)).isPresent();
            if (MATH_MEMO_SIZE.incrementAndGet() > MATH_MEMO_CAP) {
                MATH_MEMO.clear();
                MATH_MEMO_SIZE.set(0);
                memo = MATH_MEMO.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
            }
            memo.put(pack, possible);
            return possible;
        }

        // ------------------------------------------------------------------
        // Output (always via server thread hops with re-validation)
        // ------------------------------------------------------------------

        private void streamHit(int number, LocateMore.Hit hit) {
            server.execute(() -> {
                if (aborted.get() || !stillDeliverable()) {
                    return;
                }
                source.sendSuccess(() -> LocateMore.hitLine(number, hit), false);
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
                            + LocateMore.maxDistBlocks() + " blocks."));
                    return;
                }
                String baseNote = hits.size() < count ? " - only " + hits.size() + " of " + count + " within range/budget" : "";
                final String note = unresolved > 0
                        ? baseNote + " - " + unresolved + " candidates unresolved; ordering not guaranteed"
                        : baseNote;
                String probeNote = chunksGenerated > 0
                        ? " - generated " + chunksGenerated + " probe chunks" : "";
                source.sendSuccess(() -> Component.literal(
                        hits.size() + " nearest " + printable + " (" + tookMs + " ms async"
                                + " [present=" + stats.present + " absent=" + stats.absent
                                + " loads=" + stats.loads + " loadHits=" + stats.loadHits
                                + " indexHits=" + stats.indexHits + " memoHits=" + stats.memoHits + "]"
                                + note + probeNote + ")").withStyle(ChatFormatting.GRAY), false);
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
