package com.rasmus.locatemore;

import com.mojang.serialization.Dynamic;
import com.rasmus.locatemore.mixin.MinecraftServerAccessor;
import com.rasmus.locatemore.mixin.SectionStorageAccessor;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.ResourceOrTagArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The poi sibling: /locate poi with count and min_distance, off the server
 * thread, over ALL explored terrain instead of vanilla's 256-block cap.
 *
 * <p>POIs are world state, not seed math, so the truth model is layered:
 * columns marked dirty in the PoiManager are answered from an in-memory
 * snapshot taken on the server thread at command time, and every other
 * column is read through the poi storage's own IOWorker, whose FIFO
 * executor guarantees a read sees every write that cleared a dirty flag
 * before it. Raw region-file reads would miss the worker's pending writes
 * and can see torn files, so they are never used. Parsing mirrors
 * vanilla's PackedChunk.parse exactly: same datafix entry (default
 * DataVersion 1945), same codec, same drop-the-section-on-error policy,
 * same build-height section window.
 *
 * <p>Ordering is vanilla's: full 3D distance, the same comparator
 * findClosestWithType uses, so the count-1 mixin route returns exactly the
 * poi vanilla would have found whenever it lies within vanilla's radius.
 * min_distance filters on horizontal distance like its siblings. Results
 * print at completion, after a server-thread referee pass re-checks every
 * hit whose column is resident in memory, so mid-scan edits (a bell broken
 * while searching) are corrected instead of reported.
 */
public final class PoiLocate {

    private static final Logger LOGGER = LoggerFactory.getLogger("locatemore-poi");

    private static final Map<Object, Task> ACTIVE = new ConcurrentHashMap<>();
    private static ExecutorService worker;

    /**
     * Session cache of clean-column parse results, keyed by PoiManager
     * instance (weakly, so it dies with its level). Provably valid: every
     * poi mutation passes setDirty, whose mixin drops the column here at
     * the moment of mutation, and the per-search dirty snapshot overrides
     * whatever is cached. Cleared on datapack reload (poi type holders
     * rebind). Empty columns share one immutable list, so a big world
     * costs map entries, not lists.
     */
    private static final Map<Object, ConcurrentHashMap<Long, List<PoiRecord.Packed>>> CLEAN_CACHE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static final List<PoiRecord.Packed> EMPTY = List.of();

    /** Called by SectionStorageMixin on every setDirty; non-poi storages
     * miss the map and cost one lookup. */
    public static void invalidate(Object sectionStorage, long sectionPos) {
        ConcurrentHashMap<Long, List<PoiRecord.Packed>> cache = CLEAN_CACHE.get(sectionStorage);
        if (cache != null) {
            cache.remove(ChunkPos.pack(SectionPos.x(sectionPos), SectionPos.z(sectionPos)));
        }
    }

    private PoiLocate() {
    }

    /** Lab-harness hook, folded into AsyncLocate.idle(). */
    public static boolean idle() {
        return ACTIVE.isEmpty();
    }

    public static void init() {
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            Task task = ACTIVE.get(handler.getPlayer().getUUID());
            if (task != null) {
                task.abort();
                ACTIVE.remove(handler.getPlayer().getUUID(), task);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (Task task : ACTIVE.values()) {
                task.abort();
            }
            ACTIVE.clear();
            CLEAN_CACHE.clear();
            if (worker != null) {
                worker.shutdownNow();
                worker = null;
            }
        });
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) ->
                CLEAN_CACHE.clear());
    }

    private static synchronized ExecutorService workerExecutor() {
        if (worker == null || worker.isShutdown()) {
            java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
            worker = Executors.newFixedThreadPool(Config.maxActiveSearches(), r -> {
                Thread thread = new Thread(r, "LocateMore-Poi-" + n.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
        }
        return worker;
    }

    public static int start(CommandSourceStack source, ResourceOrTagArgument.Result<PoiType> target,
            int count, int minDistanceBlocks) {
        ServerLevel level = source.getLevel();
        String printable = target.asPrintable();
        BlockPos origin = BlockPos.containing(source.getPosition());
        Object poiManager = level.getPoiManager();
        SectionStorageAccessor access = (SectionStorageAccessor) poiManager;
        SimpleRegionStorage storage = access.locatemore$simpleRegionStorage();
        ConcurrentHashMap<Long, List<PoiRecord.Packed>> cache =
                CLEAN_CACHE.computeIfAbsent(poiManager, k -> new ConcurrentHashMap<>());
        Path poiDir = ((MinecraftServerAccessor) source.getServer())
                .locatemore$storageSource().getDimensionPath(level.dimension()).resolve("poi");

        // Server-thread snapshot of every dirty column: for these, memory is
        // ahead of what the IOWorker can serve, and packing them here is the
        // only way a just-placed bell is findable.
        Long2ObjectMap<List<PoiRecord.Packed>> dirty = new Long2ObjectOpenHashMap<>();
        for (LongIterator it = access.locatemore$dirtyChunks().iterator(); it.hasNext(); ) {
            long chunk = it.nextLong();
            ChunkPos pos = ChunkPos.unpack(chunk);
            List<PoiRecord.Packed> records = new ArrayList<>();
            for (int sy = level.getMinSectionY(); sy <= level.getMaxSectionY(); sy++) {
                Optional<Object> section = access.locatemore$get(SectionPos.asLong(pos.x(), sy, pos.z()));
                if (section != null && section.isPresent()) {
                    records.addAll(((PoiSection) section.get()).pack().records());
                }
            }
            dirty.put(chunk, records);
        }

        AtomicBoolean abortFlag = new AtomicBoolean();
        SearchSession session = new SearchSession(source.getServer(), level.dimension(), source,
                printable, abortFlag::get);
        Task task = new Task(source.getServer(), level.dimension(), session, abortFlag,
                source.getEntity() instanceof ServerPlayer player ? player.getUUID() : "console-poi",
                target, count, (long) minDistanceBlocks * minDistanceBlocks, origin,
                level.getMinSectionY(), level.getMaxSectionY(),
                storage, new AsyncLocate.RegionCatalog(poiDir), poiDir, dirty, cache,
                level.registryAccess().createSerializationContext(NbtOps.INSTANCE), access);

        Task previous = ACTIVE.get(task.key);
        if (previous != null) {
            previous.abort();
            source.sendSuccess(() -> Component.literal("Previous search superseded.")
                    .withStyle(ChatFormatting.GRAY), false);
        }
        source.sendSuccess(() -> Component.literal(count == 1
                ? "Searching for the nearest " + printable + "…"
                : "Searching for the " + count + " nearest " + printable + "…")
                .withStyle(ChatFormatting.GRAY), false);
        if (source.getEntity() instanceof ServerPlayer player) {
            session.attachBossBar(player);
        }
        ACTIVE.put(task.key, task);
        workerExecutor().execute(task::run);
        return 1;
    }

    record Hit(BlockPos pos, long dist3DSqr, long horizSqr) {
    }

    /**
     * Union-find over record positions with orthogonal adjacency: portal
     * frames are connected block sets, so a whole portal collapses to one
     * cluster. Each cluster keeps its nearest member as representative,
     * folded on union, so representative lookup never walks members.
     */
    private static final class Clusters {
        private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap byPos =
                new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        private final it.unimi.dsi.fastutil.ints.IntArrayList parent =
                new it.unimi.dsi.fastutil.ints.IntArrayList();
        private final List<Hit> best = new ArrayList<>();

        Clusters() {
            byPos.defaultReturnValue(-1);
        }

        private int find(int i) {
            while (parent.getInt(i) != i) {
                parent.set(i, parent.getInt(parent.getInt(i)));
                i = parent.getInt(i);
            }
            return i;
        }

        void add(BlockPos pos, Hit hit) {
            int root = -1;
            long[] neighbors = {
                    BlockPos.asLong(pos.getX() + 1, pos.getY(), pos.getZ()),
                    BlockPos.asLong(pos.getX() - 1, pos.getY(), pos.getZ()),
                    BlockPos.asLong(pos.getX(), pos.getY() + 1, pos.getZ()),
                    BlockPos.asLong(pos.getX(), pos.getY() - 1, pos.getZ()),
                    BlockPos.asLong(pos.getX(), pos.getY(), pos.getZ() + 1),
                    BlockPos.asLong(pos.getX(), pos.getY(), pos.getZ() - 1)};
            for (long neighbor : neighbors) {
                int id = byPos.get(neighbor);
                if (id < 0) {
                    continue;
                }
                int r = find(id);
                if (root == -1) {
                    root = r;
                } else if (r != root) {
                    parent.set(r, root);
                    if (best.get(r).dist3DSqr() < best.get(root).dist3DSqr()) {
                        best.set(root, best.get(r));
                    }
                }
            }
            if (root == -1) {
                root = parent.size();
                parent.add(root);
                best.add(hit);
            } else if (hit.dist3DSqr() < best.get(root).dist3DSqr()) {
                best.set(root, hit);
            }
            byPos.put(pos.asLong(), root);
        }

        int clusterCount() {
            int n = 0;
            for (int i = 0; i < parent.size(); i++) {
                if (find(i) == i) {
                    n++;
                }
            }
            return n;
        }

        List<Hit> topRepresentatives(int count) {
            List<Hit> roots = new ArrayList<>();
            for (int i = 0; i < parent.size(); i++) {
                if (find(i) == i) {
                    roots.add(best.get(i));
                }
            }
            roots.sort(Comparator.comparingLong(Hit::dist3DSqr));
            if (roots.size() > count) {
                roots.subList(count, roots.size()).clear();
            }
            return roots;
        }
    }

    private static final class Task {
        final MinecraftServer server;
        final ResourceKey<Level> dimension;
        final SearchSession session;
        final AtomicBoolean aborted;
        final Object key;
        final ResourceOrTagArgument.Result<PoiType> target;
        final int count;
        final long minDistSqr;
        final BlockPos origin;
        final int minSectionY;
        final int maxSectionY;
        final SimpleRegionStorage storage;
        final AsyncLocate.RegionCatalog catalog;
        final Path poiDir;
        final Long2ObjectMap<List<PoiRecord.Packed>> dirty;
        final ConcurrentHashMap<Long, List<PoiRecord.Packed>> cache;
        final com.mojang.serialization.DynamicOps<Tag> ops;
        final SectionStorageAccessor access;
        boolean budgetStopped;

        Task(MinecraftServer server, ResourceKey<Level> dimension, SearchSession session,
                AtomicBoolean aborted, Object key, ResourceOrTagArgument.Result<PoiType> target,
                int count, long minDistSqr, BlockPos origin, int minSectionY, int maxSectionY,
                SimpleRegionStorage storage, AsyncLocate.RegionCatalog catalog, Path poiDir,
                Long2ObjectMap<List<PoiRecord.Packed>> dirty,
                ConcurrentHashMap<Long, List<PoiRecord.Packed>> cache,
                com.mojang.serialization.DynamicOps<Tag> ops, SectionStorageAccessor access) {
            this.server = server;
            this.dimension = dimension;
            this.session = session;
            this.aborted = aborted;
            this.key = key;
            this.target = target;
            this.count = count;
            this.minDistSqr = minDistSqr;
            this.origin = origin;
            this.minSectionY = minSectionY;
            this.maxSectionY = maxSectionY;
            this.storage = storage;
            this.catalog = catalog;
            this.poiDir = poiDir;
            this.dirty = dirty;
            this.cache = cache;
            this.ops = ops;
            this.access = access;
        }

        void abort() {
            if (aborted.compareAndSet(false, true)) {
                session.closeBossBar();
            }
        }

        void run() {
            long startNanos = System.nanoTime();
            try {
                List<Hit> hits = search(startNanos);
                if (!aborted.get()) {
                    finish(hits, startNanos, null);
                }
            } catch (Throwable t) {
                if (!aborted.get()) {
                    LOGGER.error("Async poi locate failed", t);
                    finish(List.of(), startNanos, t);
                }
            } finally {
                ACTIVE.remove(key, this);
                session.closeBossBar();
            }
        }

        /**
         * Explored terrain = the union of poi region files; their extent
         * bounds the sweep. Chunk rings walk outward and the loop stops as
         * soon as no unscanned ring can beat the count-th best place: a
         * ring-k chunk's nearest point is at least (k-1)*16 blocks out, and
         * the y term only adds, so the horizontal bound is a valid 3D bound.
         *
         * <p>N results are N places, not N records: vanilla indexes one poi
         * record per block, so a single nether portal is many records.
         * Orthogonally adjacent matching records merge into one cluster
         * whose representative is its nearest block; a min_distance record
         * filter runs first, so the representative is the nearest
         * QUALIFYING block.
         */
        private List<Hit> search(long startNanos) {
            int maxRing = maxRegionRing();
            long wallClockMs = Config.wallClockSeconds() * 1000L;
            ChunkPos originChunk = ChunkPos.containing(origin);
            Clusters clusters = new Clusters();
            long chunksScanned = 0;
            for (int ring = 0; ring <= maxRing && !aborted.get(); ring++) {
                long ringFloor = Math.max(0, (long) (ring - 1) * 16);
                List<Hit> top = clusters.topRepresentatives(count);
                if (top.size() >= count
                        && top.get(count - 1).dist3DSqr() < ringFloor * ringFloor) {
                    break;
                }
                // Instant sources first (dirty snapshot, session cache),
                // then every remaining read in this ring is issued at once:
                // the IOWorker pipeline swallows a batch far faster than
                // one join per column, and clustering is order-independent
                // within a ring.
                List<ChunkPos> toRead = new ArrayList<>();
                for (int dx = -ring; dx <= ring; dx++) {
                    for (int dz = -ring; dz <= ring; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                            continue;
                        }
                        ChunkPos chunk = new ChunkPos(originChunk.x() + dx, originChunk.z() + dz);
                        List<PoiRecord.Packed> records = dirty.get(chunk.pack());
                        if (records == null) {
                            records = cache.get(chunk.pack());
                        }
                        if (records != null) {
                            chunksScanned++;
                            accept(records, clusters);
                            continue;
                        }
                        if (!catalog.mayHoldChunks(chunk)) {
                            continue;
                        }
                        toRead.add(chunk);
                    }
                }
                List<java.util.concurrent.CompletableFuture<Optional<net.minecraft.nbt.CompoundTag>>> reads =
                        new ArrayList<>(toRead.size());
                for (ChunkPos chunk : toRead) {
                    reads.add(storage.read(chunk));
                }
                for (int i = 0; i < toRead.size(); i++) {
                    Optional<net.minecraft.nbt.CompoundTag> tag;
                    try {
                        tag = reads.get(i).join();
                    } catch (Exception e) {
                        // Transient failure: nothing cached, nothing added.
                        LOGGER.warn("Poi read failed at {}", toRead.get(i), e);
                        continue;
                    }
                    List<PoiRecord.Packed> records = parseColumn(tag);
                    cache.put(toRead.get(i).pack(), records.isEmpty() ? EMPTY : records);
                    chunksScanned++;
                    accept(records, clusters);
                }
                if ((System.nanoTime() - startNanos) / 1_000_000L > wallClockMs) {
                    budgetStopped = true;
                    break;
                }
                final long scanned = chunksScanned;
                final int found = Math.min(clusters.clusterCount(), count);
                session.progress(Math.min(0.95F, ring / (float) Math.max(1, maxRing)),
                        () -> found + " found, " + scanned + " chunks scanned");
            }
            return clusters.topRepresentatives(count);
        }

        private void accept(List<PoiRecord.Packed> records, Clusters clusters) {
            for (PoiRecord.Packed record : records) {
                if (!target.test(record.poiType())) {
                    continue;
                }
                long horiz = LocateMore.horizDistSqr(record.pos(), origin);
                if (horiz < minDistSqr) {
                    continue;
                }
                long dy = record.pos().getY() - origin.getY();
                clusters.add(record.pos(), new Hit(record.pos(), horiz + dy * dy, horiz));
            }
        }

        /**
         * Vanilla's PackedChunk.parse, mirrored: the tag came through the
         * poi IOWorker (pending writes visible, no torn files); datafix
         * from default DataVersion 1945, parse each in-range section with
         * the vanilla codec, and drop a section on parse error exactly as
         * vanilla drops it.
         */
        private List<PoiRecord.Packed> parseColumn(Optional<net.minecraft.nbt.CompoundTag> tag) {
            if (tag.isEmpty()) {
                return List.of();
            }
            List<PoiRecord.Packed> records = new ArrayList<>();
            Dynamic<Tag> fixed = new Dynamic<>(ops, storage.upgradeChunkTag(tag.get(), 1945));
            var sections = fixed.get("Sections");
            for (int sy = minSectionY; sy <= maxSectionY; sy++) {
                sections.get(Integer.toString(sy)).result()
                        .flatMap(data -> PoiSection.Packed.CODEC.parse(data)
                                .resultOrPartial(LOGGER::error))
                        .ifPresent(packed -> records.addAll(packed.records()));
            }
            return records;
        }

        private void finish(List<Hit> hits, long startNanos, Throwable error) {
            long tookMs = (System.nanoTime() - startNanos) / 1_000_000L;
            session.closeBossBar();
            if (error != null) {
                session.fail(Component.literal("Search failed: "
                        + error.getClass().getSimpleName() + " (see log)"));
                return;
            }
            server.execute(() -> {
                // Referee: a column resident in memory is fresher than any
                // snapshot taken before the scan; hits it no longer backs
                // are dropped and disclosed, never silently reported.
                List<Hit> confirmed = new ArrayList<>(hits.size());
                int dropped = 0;
                for (Hit hit : hits) {
                    if (stillPresent(hit)) {
                        confirmed.add(hit);
                    } else {
                        dropped++;
                    }
                }
                if (confirmed.isEmpty()) {
                    session.fail(Component.literal("No " + session.printable
                            + " found in explored terrain"
                            + (budgetStopped ? " within the time budget" : "") + "."));
                    return;
                }
                for (int i = 0; i < confirmed.size(); i++) {
                    final int number = i + 1;
                    final Hit hit = confirmed.get(i);
                    session.chat(() -> hitLine(number, hit));
                }
                String note = (confirmed.size() < count
                        ? " - only " + confirmed.size() + " of " + count + " in explored terrain"
                        + (budgetStopped ? "/budget" : "") : "")
                        + (dropped > 0 ? " - " + dropped + " removed while searching" : "");
                final String line = (count == 1 && confirmed.size() == 1
                        ? "Nearest " + session.printable
                        : confirmed.size() + " nearest " + session.printable)
                        + " (" + tookMs + " ms" + note + ")";
                session.chat(() -> Component.literal(line).withStyle(ChatFormatting.GRAY));
            });
        }

        /** Server thread. Never getOrLoad: residency check only. */
        private boolean stillPresent(Hit hit) {
            long sectionPos = SectionPos.asLong(SectionPos.blockToSectionCoord(hit.pos().getX()),
                    SectionPos.blockToSectionCoord(hit.pos().getY()),
                    SectionPos.blockToSectionCoord(hit.pos().getZ()));
            Optional<Object> section = access.locatemore$get(sectionPos);
            if (section == null) {
                return true;
            }
            if (section.isEmpty()) {
                return false;
            }
            for (PoiRecord.Packed record : ((PoiSection) section.get()).pack().records()) {
                if (record.pos().equals(hit.pos()) && target.test(record.poiType())) {
                    return true;
                }
            }
            return false;
        }

        private int maxRegionRing() {
            int max = 0;
            ChunkPos originChunk = ChunkPos.containing(origin);
            try (var stream = java.nio.file.Files.newDirectoryStream(poiDir, "r.*.mca")) {
                for (Path file : stream) {
                    String[] parts = file.getFileName().toString().split("\\.");
                    int rx = Integer.parseInt(parts[1]);
                    int rz = Integer.parseInt(parts[2]);
                    // The farthest chunk corner of this region, Chebyshev.
                    int cheb = Math.max(
                            Math.max(Math.abs(rx * 32 - originChunk.x()), Math.abs(rx * 32 + 31 - originChunk.x())),
                            Math.max(Math.abs(rz * 32 - originChunk.z()), Math.abs(rz * 32 + 31 - originChunk.z())));
                    max = Math.max(max, cheb);
                }
            } catch (java.io.IOException | NumberFormatException e) {
                LOGGER.warn("Could not enumerate poi regions in {}", poiDir, e);
                return 64;
            }
            return max;
        }

        private Component hitLine(int number, Hit hit) {
            int distance = Mth.floor(Mth.sqrt((float) hit.horizSqr()));
            String heading = distance >= 16
                    ? HitPresentation.octant(hit.pos().getX() - origin.getX(), hit.pos().getZ() - origin.getZ())
                    : "away";
            String name = HitPresentation.trackName(session.printable, number);
            return Component.literal(number + ". ")
                    .append(HitPresentation.coordinates(hit.pos(), true, session.viewer))
                    .append(Component.literal(" (" + distance + " blocks " + heading + ")"))
                    .append(HitPresentation.buttons(hit.pos().getX(), hit.pos().getY(), hit.pos().getY(),
                            hit.pos().getZ(), name, session.viewer));
        }
    }
}
