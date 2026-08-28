package com.rasmus.locatemore;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

/**
 * Self-clearing navigation for vanilla clients: clicking [track] on a
 * result line shows live distance and a direction arrow in the action bar,
 * updated twice a second, until the player arrives (or tracks something
 * else, changes dimension, or turns it off). Nothing persists: no
 * waypoints to clean up afterwards, which is the whole point.
 */
public final class TravelTracker {

    private record Target(ResourceKey<Level> dimension, BlockPos pos, String name) {
    }

    private static final Map<UUID, Target> TRACKING = new ConcurrentHashMap<>();
    private static final int ARRIVE_DISTANCE = 16;
    /** Relative bearing to arrow, 45 degree sectors starting at ahead. */
    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

    private TravelTracker() {
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(TravelTracker::tick);
        // Static state outlives the integrated server: without this, a
        // tracker armed in one singleplayer world kept pointing at its
        // coordinates in the NEXT world opened in the same session (same
        // player UUID, interned dimension key - every check passed).
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING
                .register(server -> TRACKING.clear());
    }

    public static void track(ServerPlayer player, BlockPos pos, String name) {
        TRACKING.put(player.getUUID(), new Target(player.level().dimension(), pos, name));
    }

    /** @return true when something was actually being tracked. */
    public static boolean stop(ServerPlayer player) {
        return TRACKING.remove(player.getUUID()) != null;
    }

    private static void tick(MinecraftServer server) {
        if (TRACKING.isEmpty() || server.getTickCount() % 10 != 0) {
            return;
        }
        for (var iterator = TRACKING.entrySet().iterator(); iterator.hasNext(); ) {
            var entry = iterator.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                iterator.remove();
                continue;
            }
            Target target = entry.getValue();
            if (player.level().dimension() != target.dimension()) {
                iterator.remove();
                continue;
            }
            double dx = target.pos().getX() + 0.5 - player.getX();
            double dz = target.pos().getZ() + 0.5 - player.getZ();
            int distance = Mth.floor(Math.sqrt(dx * dx + dz * dz));
            if (distance <= ARRIVE_DISTANCE) {
                sendActionBar(player, Component.literal("Arrived: " + target.name())
                        .withStyle(ChatFormatting.GREEN));
                iterator.remove();
                continue;
            }
            // Bearing relative to where the player is looking, mapped to one
            // of eight arrows; vanilla yaw 0 faces +z and grows clockwise.
            float bearing = (float) (Mth.atan2(-dx, dz) * (180.0 / Math.PI));
            float relative = Mth.wrapDegrees(bearing - player.getYRot());
            String arrow = ARROWS[Math.floorMod(Math.round(relative / 45.0F), 8)];
            sendActionBar(player, Component.literal(target.name() + ": " + distance + " m ")
                    .withStyle(ChatFormatting.AQUA)
                    .append(Component.literal(arrow).withStyle(ChatFormatting.YELLOW)));
        }
    }

    private static void sendActionBar(ServerPlayer player, Component message) {
        player.connection.send(
                new net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket(message));
    }
}
