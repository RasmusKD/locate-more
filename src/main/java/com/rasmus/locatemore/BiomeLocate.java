package com.rasmus.locatemore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.ResourceOrTagArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The biome sibling of the structure engine: /locate biome with an optional
 * count, off the server thread. Vanilla's findClosestBiome3d blocks a tick
 * while it walks a square spiral in 32-block steps and returns the first
 * match, which is only approximately nearest (a ring's corner is farther
 * than the next ring's edge). This walks the same sample grid in exact
 * distance order instead - ring expansion into a priority queue, the
 * structure engine's pattern - and streams the N nearest matches to chat.
 *
 * <p>Biomes are pure seed math through the climate sampler, so unlike the
 * structure engine there is no disk, no chunk loads and no draw to
 * replicate: every verdict is the same getNoiseBiome call generation would
 * make. Off-thread sampling is safe for the same reason the structure
 * engine's math pool is: the sampler stack is used concurrently by
 * vanilla's own worldgen workers, and this mod has sampled it from worker
 * threads since the shadow path shipped.
 *
 * <p>Multiple results only make sense as distinct places: one swamp would
 * otherwise fill the whole list, sampled every 32 blocks. A hit within
 * {@code biomeSeparationBlocks} of an already accepted hit is suppressed,
 * so N results are N patches (or distant shores of a very large one).
 */
public final class BiomeLocate {

    private static final Logger LOGGER = LoggerFactory.getLogger("locatemore-biome");

    /** Vanilla's own sample resolutions (blocks): keep parity, results land
     * on the same grid vanilla would report. */
    private static final int HORIZ_STEP = 32;
    private static final int VERT_STEP = 64;

    private static final Map<Object, Task> ACTIVE = new ConcurrentHashMap<>();
    private static ExecutorService worker;

    private BiomeLocate() {
    }

    public static void init() {
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            Task task = ACTIVE.get(handler.getPlayer().getUUID());
            if (task != null) {
                task.abort();
                ACTIVE.remove(handler.getPlayer().getUUID(), task);
            }
        });
        // Biome tags rebind on datapack reload; the snapshotted candidate
        // holders would go stale.
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
            for (Task task : ACTIVE.values()) {
                task.abort();
            }
            ACTIVE.clear();
            CLIMATE_TRUSTED.set(true);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (Task task : ACTIVE.values()) {
                task.abort();
            }
            ACTIVE.clear();
            CLIMATE_TRUSTED.set(true);
            if (worker != null) {
                worker.shutdownNow();
                worker = null;
            }
        });
    }

    /**
     * Session trust for the column-constant climate shortcut, revoked by the
     * standing referee on any disagreement (the SetDraw pattern). Reset on
     * datapack reload and server stop, since both can change the router.
     */
    private static final AtomicBoolean CLIMATE_TRUSTED = new AtomicBoolean(true);
    private static final AtomicBoolean CLIMATE_WARNED = new AtomicBoolean();

    /**
     * Sized to the shared active-search cap: biome searches are pure math
     * and finish in well under a second on the common path, so they get
     * their own small pool rather than competing with structure searches
     * for the chunk-capable workers.
     */
    private static synchronized ExecutorService workerExecutor() {
        if (worker == null || worker.isShutdown()) {
            java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
            worker = Executors.newFixedThreadPool(Config.maxActiveSearches(), r -> {
                Thread thread = new Thread(r, "LocateMore-Biome-" + n.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
        }
        return worker;
    }

    /**
     * Entry for both routes: the grafted count argument and the vanilla
     * one-result executor taken async by the mixin (count 1). Everything
     * the worker needs is snapshotted here on the server thread.
     */
    public static int start(CommandSourceStack source, ResourceOrTagArgument.Result<Biome> target, int count) {
        ServerLevel level = source.getLevel();
        BiomeSource biomeSource = level.getChunkSource().getGenerator().getBiomeSource();
        String printable = target.asPrintable();
        Set<Holder<Biome>> candidates = biomeSource.possibleBiomes().stream()
                .filter(target).collect(Collectors.toUnmodifiableSet());
        if (candidates.isEmpty()) {
            // This generator cannot produce the biome at all; vanilla's
            // radius-exhausted error is also its cannot-exist error.
            source.sendFailure(Component.translatableEscape("commands.locate.biome.not_found", printable));
            return 0;
        }
        BlockPos origin = BlockPos.containing(source.getPosition());
        int[] sampleYs = Mth.outFromOrigin(origin.getY(), level.getMinY() + 1, level.getMaxY() + 1, VERT_STEP)
                .toArray();
        Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
        MultiNoiseBiomeSource multiNoise =
                biomeSource instanceof MultiNoiseBiomeSource mn
                        && columnConstantClimate(sampler, origin, level.getMinY() + 1, level.getMaxY())
                        ? mn : null;
        Task task = new Task(source.getServer(), level.dimension(), source,
                source.getEntity() instanceof ServerPlayer player ? player.getUUID() : "console-biome",
                printable, candidates, count, origin, sampleYs,
                biomeSource, sampler, multiNoise);

        LOGGER.debug("Biome search {} ({}): column-constant climate shortcut {}", printable,
                level.dimension().identifier(), multiNoise != null ? "active" : "off");
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
            task.attachBossBar(player);
        }
        ACTIVE.put(task.key, task);
        workerExecutor().execute(task::run);
        return 1;
    }

    /**
     * The seed-finder observation behind the fast column scan: in vanilla's
     * router, five of the six climate functions (temperature, humidity,
     * continentalness, erosion, weirdness) are 2D - only depth varies with
     * y - so a column's five values need computing once, not once per y
     * sample. Datapacks can register 3D climate functions, so the shortcut
     * must be earned per world: this probes a few columns at the extreme
     * heights and requires bit-identical values before the shortcut is
     * allowed, and a standing 1-in-64 referee keeps comparing full samples
     * during searches (any disagreement revokes the shortcut for the
     * session). Non-multinoise sources always take the plain path.
     */
    private static boolean columnConstantClimate(Climate.Sampler sampler, BlockPos origin,
            int yLow, int yHigh) {
        DensityFunction[] flat = {sampler.temperature(), sampler.humidity(),
                sampler.continentalness(), sampler.erosion(), sampler.weirdness()};
        for (int i = 0; i < 4; i++) {
            int x = QuartPos.toBlock(QuartPos.fromBlock(origin.getX() + i * 1027));
            int z = QuartPos.toBlock(QuartPos.fromBlock(origin.getZ() - i * 913));
            DensityFunction.SinglePointContext low = new DensityFunction.SinglePointContext(
                    x, QuartPos.toBlock(QuartPos.fromBlock(yLow)), z);
            DensityFunction.SinglePointContext high = new DensityFunction.SinglePointContext(
                    x, QuartPos.toBlock(QuartPos.fromBlock(yHigh)), z);
            for (DensityFunction fn : flat) {
                if (fn.compute(low) != fn.compute(high)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** One sample column, keyed on its exact horizontal distance. */
    private record Column(int x, int z, long distSqr) {
    }

    private static final class Task {
        final MinecraftServer server;
        final ResourceKey<Level> dimension;
        final CommandSourceStack source;
        final Object key;
        final String printable;
        final Set<Holder<Biome>> candidates;
        final int count;
        final BlockPos origin;
        final int[] sampleYs;
        final BiomeSource biomeSource;
        final Climate.Sampler sampler;
        /** Non-null only when the column-constant climate probe passed. */
        final MultiNoiseBiomeSource multiNoise;
        final UUID playerId;

        final AtomicBoolean aborted = new AtomicBoolean();
        private volatile ServerBossEvent bossBar;
        private long lastProgressPush;

        Task(MinecraftServer server, ResourceKey<Level> dimension, CommandSourceStack source, Object key,
                String printable, Set<Holder<Biome>> candidates, int count, BlockPos origin, int[] sampleYs,
                BiomeSource biomeSource, Climate.Sampler sampler, MultiNoiseBiomeSource multiNoise) {
            this.server = server;
            this.dimension = dimension;
            this.source = source;
            this.key = key;
            this.printable = printable;
            this.candidates = candidates;
            this.count = count;
            this.origin = origin;
            this.sampleYs = sampleYs;
            this.biomeSource = biomeSource;
            this.sampler = sampler;
            this.multiNoise = multiNoise;
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
                List<Hit> hits = search(startNanos);
                if (!aborted.get()) {
                    finish(hits, startNanos, null);
                }
            } catch (Throwable t) {
                if (!aborted.get()) {
                    LOGGER.error("Async biome locate failed", t);
                    finish(List.of(), startNanos, t);
                }
            } finally {
                ACTIVE.remove(key, this);
                server.execute(this::removeBossBar);
            }
        }

        private record Hit(BlockPos pos, long distSqr) {
        }

        /** Columns per parallel batch: large enough to keep the math pool
         * saturated, small enough that a hit near the front does not pay
         * for a whole far batch before the next command can supersede. */
        private static final int BATCH = 1024;

        private List<Hit> search(long startNanos) throws InterruptedException {
            long radius = Config.biomeMaxDistanceBlocks();
            long radiusSqr = radius * radius;
            long separationSqr = (long) Config.biomeSeparationBlocks() * Config.biomeSeparationBlocks();
            long wallClockMs = Config.wallClockSeconds() * 1000L;
            int maxRing = (int) (radius / HORIZ_STEP) + 1;

            // Exact order over vanilla's own sample grid, sampled in
            // parallel: rings feed a priority queue, batches of the nearest
            // not-yet-sampled columns fan out across the structure engine's
            // math pool, and the results are read back in the batch's own
            // ascending-distance order. A column may only enter a batch when
            // every unpushed ring is provably farther (ring r's nearest
            // column sits at exactly r*32 blocks), so parallelism never
            // reorders anything: acceptance, separation and streaming all
            // happen sequentially over ascending distances, exactly as the
            // single-threaded loop did - the pool only computes the samples.
            PriorityQueue<Column> queue = new PriorityQueue<>(Comparator.comparingLong(Column::distSqr));
            int nextRing = 0;
            List<Hit> hits = new ArrayList<>();
            long columns = 0;
            List<Column> batch = new ArrayList<>(BATCH);
            List<java.util.concurrent.Callable<BlockPos>> samplers = new ArrayList<>(BATCH);

            while (!aborted.get() && hits.size() < count) {
                batch.clear();
                samplers.clear();
                while (batch.size() < BATCH) {
                    long pushedBound = square((long) nextRing * HORIZ_STEP);
                    // >= : at an exact tie the ring is pushed before the
                    // column is pulled, same order as the pre-parallel loop,
                    // so the blessed golden files stay comparable.
                    if (queue.isEmpty() || (nextRing <= maxRing && queue.peek().distSqr() >= pushedBound)) {
                        if (nextRing > maxRing) {
                            break;
                        }
                        pushRing(nextRing++, queue);
                        continue;
                    }
                    if (queue.peek().distSqr() > radiusSqr) {
                        break;
                    }
                    batch.add(queue.poll());
                }
                if (batch.isEmpty()) {
                    break;
                }
                for (Column column : batch) {
                    samplers.add(() -> sampleColumn(column));
                }
                List<java.util.concurrent.Future<BlockPos>> results =
                        AsyncLocate.sharedMathPool().invokeAll(samplers);
                columns += batch.size();
                for (int i = 0; i < batch.size() && hits.size() < count; i++) {
                    BlockPos found;
                    try {
                        found = results.get(i).get();
                    } catch (java.util.concurrent.ExecutionException e) {
                        LOGGER.warn("Biome sample failed at {}", batch.get(i), e.getCause());
                        continue;
                    }
                    if (found != null && farEnough(hits, batch.get(i), separationSqr)) {
                        Hit hit = new Hit(found, batch.get(i).distSqr());
                        hits.add(hit);
                        streamHit(hits.size(), hit);
                    }
                }
                if ((System.nanoTime() - startNanos) / 1_000_000L > wallClockMs) {
                    break;
                }
                pushProgress(hits.size(), columns, startNanos);
            }
            return hits;
        }

        /** Vanilla's column scan: y out from the player's own height, first
         * match wins the column. Runs on the math pool. When the climate
         * probe passed, the five 2D climate values are computed once and
         * only depth per y (see columnConstantClimate); coordinates are
         * quart-aligned exactly as Sampler.sample would, so the shortcut is
         * bit-identical to the plain path wherever the referee looks. */
        private BlockPos sampleColumn(Column column) {
            int noiseX = QuartPos.fromBlock(column.x());
            int noiseZ = QuartPos.fromBlock(column.z());
            if (multiNoise == null || !CLIMATE_TRUSTED.get()) {
                return sampleColumnPlain(column, noiseX, noiseZ);
            }
            int blockX = QuartPos.toBlock(noiseX);
            int blockZ = QuartPos.toBlock(noiseZ);
            DensityFunction.SinglePointContext flat = new DensityFunction.SinglePointContext(
                    blockX, QuartPos.toBlock(QuartPos.fromBlock(sampleYs[0])), blockZ);
            float temperature = (float) sampler.temperature().compute(flat);
            float humidity = (float) sampler.humidity().compute(flat);
            float continentalness = (float) sampler.continentalness().compute(flat);
            float erosion = (float) sampler.erosion().compute(flat);
            float weirdness = (float) sampler.weirdness().compute(flat);
            // Deterministic 1-in-64 standing referee: these columns also run
            // the plain sampler and must agree, or the shortcut is revoked.
            boolean referee = ((noiseX ^ noiseZ) & 63) == 0;
            for (int y : sampleYs) {
                int blockY = QuartPos.toBlock(QuartPos.fromBlock(y));
                float depth = (float) sampler.depth().compute(
                        new DensityFunction.SinglePointContext(blockX, blockY, blockZ));
                Holder<Biome> biome = multiNoise.getNoiseBiome(Climate.target(
                        temperature, humidity, continentalness, erosion, depth, weirdness));
                if (referee) {
                    Holder<Biome> plain = biomeSource.getNoiseBiome(
                            noiseX, QuartPos.fromBlock(y), noiseZ, sampler);
                    if (plain != biome) {
                        CLIMATE_TRUSTED.set(false);
                        if (CLIMATE_WARNED.compareAndSet(false, true)) {
                            LOGGER.warn("Climate referee disagreement at column {},{} y {} in {}: the "
                                            + "column-constant shortcut predicted {} but the full sampler "
                                            + "says {}. The shortcut is disabled for this session; results "
                                            + "stay correct, but please report this line.",
                                    column.x(), column.z(), y, dimension.identifier(), biome, plain);
                        }
                        return sampleColumnPlain(column, noiseX, noiseZ);
                    }
                }
                if (candidates.contains(biome)) {
                    return new BlockPos(column.x(), y, column.z());
                }
            }
            return null;
        }

        private BlockPos sampleColumnPlain(Column column, int noiseX, int noiseZ) {
            for (int y : sampleYs) {
                Holder<Biome> biome = biomeSource.getNoiseBiome(
                        noiseX, QuartPos.fromBlock(y), noiseZ, sampler);
                if (candidates.contains(biome)) {
                    return new BlockPos(column.x(), y, column.z());
                }
            }
            return null;
        }

        private void pushRing(int ring, PriorityQueue<Column> queue) {
            if (ring == 0) {
                queue.add(new Column(origin.getX(), origin.getZ(), 0L));
                return;
            }
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    long ox = (long) dx * HORIZ_STEP;
                    long oz = (long) dz * HORIZ_STEP;
                    queue.add(new Column(origin.getX() + (int) ox, origin.getZ() + (int) oz,
                            ox * ox + oz * oz));
                }
            }
        }

        private static boolean farEnough(List<Hit> hits, Column column, long separationSqr) {
            for (Hit hit : hits) {
                long dx = hit.pos().getX() - column.x();
                long dz = hit.pos().getZ() - column.z();
                if (dx * dx + dz * dz < separationSqr) {
                    return false;
                }
            }
            return true;
        }

        private static long square(long v) {
            return v * v;
        }

        private void streamHit(int number, Hit hit) {
            server.execute(() -> {
                if (aborted.get() || !stillDeliverable()) {
                    return;
                }
                source.sendSuccess(() -> hitLine(number, hit.pos(), hit.distSqr()), false);
            });
        }

        /**
         * Same vanilla client lang keys as the structure lines, with the y
         * included: unlike structures, the biome result's height is the
         * point (a deep dark at -40 is not "here, but lower").
         */
        private static Component hitLine(int number, BlockPos pos, long distSqr) {
            int distance = Mth.floor(Mth.sqrt((float) distSqr));
            Component coordinates = ComponentUtils.wrapInSquareBrackets(Component.translatable("chat.coordinates",
                            pos.getX(), pos.getY(), pos.getZ()))
                    .withStyle(style -> style.withColor(ChatFormatting.GREEN)
                            .withClickEvent(new ClickEvent.SuggestCommand(
                                    "/tp @s " + pos.getX() + " " + pos.getY() + " " + pos.getZ()))
                            .withHoverEvent(new HoverEvent.ShowText(
                                    Component.translatable("chat.coordinates.tooltip"))));
            return Component.literal(number + ". ")
                    .append(coordinates)
                    .append(Component.literal(" (" + distance + " blocks away)"));
        }

        private void pushProgress(int found, long columns, long startNanos) {
            long now = System.currentTimeMillis();
            if (bossBar == null || now - lastProgressPush < 500L) {
                return;
            }
            lastProgressPush = now;
            long elapsed = (System.nanoTime() - startNanos) / 1_000_000_000L;
            float progress = Math.min(1.0F, found / (float) count);
            final long sampled = columns;
            server.execute(() -> {
                if (bossBar != null && !aborted.get()) {
                    bossBar.setProgress(progress);
                    bossBar.setName(Component.literal("Locating " + printable + ": " + found + "/" + count
                            + " found, " + sampled + " columns, " + elapsed + " s"));
                }
            });
        }

        private void finish(List<Hit> hits, long startNanos, Throwable error) {
            long tookMs = (System.nanoTime() - startNanos) / 1_000_000L;
            server.execute(() -> {
                removeBossBar();
                if (!stillDeliverable()) {
                    return;
                }
                if (error != null) {
                    source.sendFailure(Component.literal("Search failed: "
                            + error.getClass().getSimpleName() + " (see log)"));
                    return;
                }
                if (hits.isEmpty()) {
                    source.sendFailure(Component.translatableEscape("commands.locate.biome.not_found", printable));
                    return;
                }
                String note = hits.size() < count
                        ? " - only " + hits.size() + " of " + count + " within "
                                + Config.biomeMaxDistanceBlocks() + " blocks/budget" : "";
                final String line = (count == 1 && hits.size() == 1
                        ? "Nearest " + printable
                        : hits.size() + " nearest " + printable)
                        + " (" + tookMs + " ms" + note + ")";
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
