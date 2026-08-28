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
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (Task task : ACTIVE.values()) {
                task.abort();
            }
            ACTIVE.clear();
            if (worker != null) {
                worker.shutdownNow();
                worker = null;
            }
        });
    }

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
        Task task = new Task(source.getServer(), level.dimension(), source,
                source.getEntity() instanceof ServerPlayer player ? player.getUUID() : "console-biome",
                printable, candidates, count, origin, sampleYs,
                biomeSource, level.getChunkSource().randomState().sampler());

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
        final UUID playerId;

        final AtomicBoolean aborted = new AtomicBoolean();
        private volatile ServerBossEvent bossBar;
        private long lastProgressPush;

        Task(MinecraftServer server, ResourceKey<Level> dimension, CommandSourceStack source, Object key,
                String printable, Set<Holder<Biome>> candidates, int count, BlockPos origin, int[] sampleYs,
                BiomeSource biomeSource, Climate.Sampler sampler) {
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

        private List<Hit> search(long startNanos) {
            long radius = Config.biomeMaxDistanceBlocks();
            long radiusSqr = radius * radius;
            long separationSqr = (long) Config.biomeSeparationBlocks() * Config.biomeSeparationBlocks();
            long wallClockMs = Config.wallClockSeconds() * 1000L;
            int maxRing = (int) (radius / HORIZ_STEP) + 1;

            // Exact order over vanilla's own sample grid: rings feed a
            // priority queue, and a ring is only expanded once something in
            // it could rank at the head. Ring r's nearest column sits at
            // exactly r*32 blocks, so the bound is tight.
            PriorityQueue<Column> queue = new PriorityQueue<>(Comparator.comparingLong(Column::distSqr));
            int nextRing = 0;
            List<Hit> hits = new ArrayList<>();
            long columns = 0;

            while (!aborted.get() && hits.size() < count) {
                long head = queue.isEmpty() ? radiusSqr : Math.min(queue.peek().distSqr(), radiusSqr);
                while (nextRing <= maxRing
                        && square((long) nextRing * HORIZ_STEP) <= head) {
                    pushRing(nextRing++, queue);
                    head = queue.isEmpty() ? radiusSqr : Math.min(queue.peek().distSqr(), radiusSqr);
                }
                Column column = queue.poll();
                if (column == null || column.distSqr() > radiusSqr) {
                    break;
                }
                columns++;
                // Vanilla's column scan verbatim: y out from the player's
                // own height, first match wins the column.
                int noiseX = QuartPos.fromBlock(column.x());
                int noiseZ = QuartPos.fromBlock(column.z());
                for (int y : sampleYs) {
                    Holder<Biome> biome = biomeSource.getNoiseBiome(
                            noiseX, QuartPos.fromBlock(y), noiseZ, sampler);
                    if (!candidates.contains(biome)) {
                        continue;
                    }
                    if (farEnough(hits, column, separationSqr)) {
                        Hit hit = new Hit(new BlockPos(column.x(), y, column.z()), column.distSqr());
                        hits.add(hit);
                        streamHit(hits.size(), hit);
                    }
                    break;
                }
                if ((columns & 255) == 0) {
                    if ((System.nanoTime() - startNanos) / 1_000_000L > wallClockMs) {
                        break;
                    }
                    pushProgress(hits.size(), columns, startNanos);
                }
            }
            return hits;
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
