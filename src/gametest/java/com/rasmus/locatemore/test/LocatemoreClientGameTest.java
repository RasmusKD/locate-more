package com.rasmus.locatemore.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * Opens a singleplayer world and sends /locate structure, biome and poi through the integrated server.
 * Each answer must arrive in the player's chat with a coordinate triple, and none may report a failure.
 */
public class LocatemoreClientGameTest implements FabricClientGameTest {
    private static final Pattern COORDS = Pattern.compile("\\[-?\\d+, (~|-?\\d+), -?\\d+\\]");
    private static final int ANSWER_TIMEOUT_TICKS = 20 * 240;

    private final List<String> chat = new CopyOnWriteArrayList<>();

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) chat.add(message.getString());
        });
        try (TestSingleplayerContext world = context.worldBuilder()
                .setUseConsistentSettings(false)
                .adjustSettings(state -> {
                    // Default (not flat) terrain on a fixed seed, so every structure and biome exists.
                    state.setAllowCommands(true);
                    state.setSeed("20260821");
                    state.setWorldType(state.getNormalPresetList().get(0));
                })
                .create()) {
            world.getServer().runCommand("difficulty peaceful");
            context.waitTicks(40);
            locate(context, "locate structure #minecraft:village 3", 3);
            locate(context, "locate structure minecraft:pillager_outpost", 1);
            locate(context, "locate biome minecraft:desert", 1);
            locate(context, "locate biome minecraft:lush_caves", 1);
            locate(context, "locate poi #minecraft:village 3", 3);
            context.takeScreenshot("locatemore-chat");
        }
    }

    private void locate(ClientGameTestContext context, String command, int expectedAnswers) {
        chat.clear();
        context.runOnClient(client -> client.player.connection.sendCommand(command));
        context.waitFor(client -> countAnswers() >= expectedAnswers || failure() != null, ANSWER_TIMEOUT_TICKS);
        List<String> seen = new ArrayList<>(chat);
        String failure = failure();
        if (failure != null) throw new AssertionError("/" + command + " failed: " + failure + " (chat: " + seen + ")");
        int answers = countAnswers();
        if (answers < expectedAnswers) {
            throw new AssertionError("/" + command + " gave " + answers + " answers, expected " + expectedAnswers
                    + " (chat: " + seen + ")");
        }
        System.out.println("LOCATEMORE-GAMETEST ok /" + command + " -> " + seen);
        context.takeScreenshot("locatemore-" + command.replaceAll("[^a-z0-9]+", "-"));
    }

    private int countAnswers() {
        int n = 0;
        for (String line : chat) if (COORDS.matcher(line).find()) n++;
        return n;
    }

    private String failure() {
        for (String line : chat) {
            String l = line.toLowerCase();
            if (l.startsWith("no ") || l.contains("could not find") || l.contains("unknown") || l.contains("incorrect")
                    || l.contains("error") || l.contains("no permission") || l.contains("failed")) return line;
        }
        return null;
    }
}
