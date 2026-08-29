package com.rasmus.locatemore;

import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.Level;

/**
 * The presentation half of one search, shared by the structure and biome
 * engines: boss bar lifecycle, throttled progress pushes, and the chat
 * hops. Every outbound line re-validates on the server thread (the search
 * may have been aborted, the player may be gone, the server may be
 * stopping), and that discipline used to be copy-pasted between the two
 * Task classes - a bug fixed in one silently survived in the other.
 * Engines keep their own search state and abort flag; the session only
 * ever reads it.
 */
final class SearchSession {

    private static final long PROGRESS_THROTTLE_MS = 500;

    final MinecraftServer server;
    final ResourceKey<Level> dimension;
    final CommandSourceStack source;
    final String printable;
    final UUID playerId;
    final HitPresentation.Viewer viewer;
    private final BooleanSupplier aborted;
    private volatile ServerBossEvent bossBar;
    private long lastProgressPush;

    SearchSession(MinecraftServer server, ResourceKey<Level> dimension, CommandSourceStack source,
            String printable, BooleanSupplier aborted) {
        this.server = server;
        this.dimension = dimension;
        this.source = source;
        this.printable = printable;
        this.aborted = aborted;
        this.playerId = source.getEntity() instanceof ServerPlayer player ? player.getUUID() : null;
        this.viewer = HitPresentation.Viewer.of(source);
    }

    void attachBossBar(ServerPlayer player) {
        bossBar = new ServerBossEvent(UUID.randomUUID(), Component.literal("Locating " + printable),
                BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS);
        bossBar.setProgress(0.0F);
        bossBar.addPlayer(player);
    }

    /** Server-thread hop; safe from any thread, idempotent. */
    void closeBossBar() {
        server.execute(this::removeBossBar);
    }

    private void removeBossBar() {
        if (bossBar != null) {
            bossBar.removeAllPlayers();
            bossBar = null;
        }
    }

    boolean stillDeliverable() {
        if (!server.isRunning() || server.getLevel(dimension) == null) {
            return false;
        }
        return playerId == null || server.getPlayerList().getPlayer(playerId) != null;
    }

    /** A success line, re-validated on the server thread. */
    void chat(Supplier<Component> line) {
        server.execute(() -> {
            if (aborted.getAsBoolean() || !stillDeliverable()) {
                return;
            }
            source.sendSuccess(line, false);
        });
    }

    /** A failure line, re-validated on the server thread. */
    void fail(Component line) {
        server.execute(() -> {
            if (stillDeliverable()) {
                source.sendFailure(line);
            }
        });
    }

    /**
     * Throttled boss bar update: at most one push per half second, name
     * built lazily so quiet ticks cost nothing.
     */
    void progress(float fraction, Supplier<String> name) {
        long now = System.currentTimeMillis();
        if (bossBar == null || now - lastProgressPush < PROGRESS_THROTTLE_MS) {
            return;
        }
        lastProgressPush = now;
        String built = name.get();
        server.execute(() -> {
            if (bossBar != null && !aborted.getAsBoolean()) {
                bossBar.setProgress(fraction);
                bossBar.setName(Component.literal(built));
            }
        });
    }
}
