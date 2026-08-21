package com.rasmus.locatemore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local dev bridge for measurement automation, RCON-style but file-based:
 * drop lines into {@code <gameDir>/locatemore-bridge/commands.txt} and they
 * run as full-permission server commands within ~half a second; all command
 * output (including async search lines) appends to {@code output.log} in the
 * same folder. Inert unless the folder exists, touches only local files, and
 * must be stripped or gated before any public release.
 */
public final class DevBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("locatemore-bridge");
    private static final int POLL_TICKS = 10;

    private static int cooldown;
    private static Path dir;

    private DevBridge() {
    }

    public static void init() {
        dir = FabricLoader.getInstance().getGameDir().resolve("locatemore-bridge");
        ServerTickEvents.END_SERVER_TICK.register(DevBridge::tick);
    }

    private static void tick(MinecraftServer server) {
        if (++cooldown < POLL_TICKS) {
            return;
        }
        cooldown = 0;
        // Security boundary: on a dedicated server (shared hosting, multiple
        // writers to the game dir) the bridge is dead unless the operator
        // explicitly enabled it via JVM flag. Local singleplayer keeps the
        // folder-based convenience: anyone able to create the folder already
        // owns the machine.
        if (server.isDedicatedServer() && !Boolean.getBoolean("locatemore.bridge")) {
            return;
        }
        Path commands = dir.resolve("commands.txt");
        if (!Files.isRegularFile(commands)) {
            return;
        }
        List<String> lines;
        try {
            // Claim atomically first: a failed delete must never replay a batch.
            Path claimed = dir.resolve("commands.processing");
            Files.move(commands, claimed, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            lines = Files.readAllLines(claimed, StandardCharsets.UTF_8);
            Files.delete(claimed);
        } catch (IOException e) {
            LOGGER.warn("Bridge read failed", e);
            return;
        }
        for (String line : lines) {
            String command = line.strip();
            if (command.isEmpty() || command.startsWith("#")) {
                continue;
            }
            log(server, "> " + command);
            CommandSourceStack source = server.createCommandSourceStack().withSource(new Sink(server));
            try {
                server.getCommands().performPrefixedCommand(source, command);
            } catch (Exception e) {
                log(server, "! " + e);
            }
        }
    }

    private static void log(MinecraftServer server, String message) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("output.log"),
                    "[" + LocalTime.now().withNano(0) + " t" + server.getTickCount() + "] " + message + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.warn("Bridge write failed", e);
        }
    }

    private record Sink(MinecraftServer server) implements CommandSource {
        @Override
        public void sendSystemMessage(Component component) {
            log(server, component.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    }
}
