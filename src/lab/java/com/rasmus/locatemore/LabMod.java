package com.rasmus.locatemore;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.datafixers.util.Pair;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceOrTagKeyArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Driver for the differential run (test/run.sh), a dev-only mod that is never
 * packaged. It holds what used to be the local locatemore-lab mod:
 * <ul>
 * <li>{@code lmlab sync <structure> <count>}: the generation-backed engine, real
 * chunk generation instead of the math verdicts, so it is an independent check
 * of the async engine's answers.</li>
 * <li>{@code lmlab map <structure>}: two skipKnown calls through vanilla's
 * explorer-map entry point (the second returns the next nearest).</li>
 * <li>{@code lmlab bypass on|off}: steps every LocateMore mixin aside, so plain
 * {@code /locate} answers are unmodified vanilla (the oracle).</li>
 * <li>{@code lmlab ranges}: checks the climate bounds the biome engine's certified
 * shortcuts rely on against sampled values (a bound that is not a bound makes
 * a proof unsound).</li>
 * <li>{@code lmlab pause <ticks>}: holds the battery for that many ticks (chunk
 * generation from forceload runs off the engines' idleness signal).</li>
 * <li>{@code lmlab battery <file>}: runs a command list from the game directory
 * one command at a time, waiting for engine idleness between commands.</li>
 * </ul>
 */
public final class LabMod implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("locatemorelab");
    private static final DynamicCommandExceptionType STRUCTURE_INVALID = new DynamicCommandExceptionType(
            id -> Component.literal("Invalid structure " + id));

    private static final ArrayDeque<String> PENDING = new ArrayDeque<>();
    private static boolean batteryActive;
    /** Consecutive idle ticks seen since the last command; two means output has drained. */
    private static int idleTicks;
    private static boolean commandInFlight;
    private static int pauseRemaining;

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> register(dispatcher));
        ServerTickEvents.END_SERVER_TICK.register(LabMod::tick);
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("lmlab")
                .then(Commands.literal("sync")
                        .then(Commands.argument("structure", ResourceOrTagKeyArgument.resourceOrTagKey(Registries.STRUCTURE))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 100))
                                        .executes(LabMod::sync))))
                .then(Commands.literal("map")
                        .then(Commands.argument("structure", ResourceOrTagKeyArgument.resourceOrTagKey(Registries.STRUCTURE))
                                .executes(LabMod::map)))
                .then(Commands.literal("bypass")
                        .then(Commands.literal("on").executes(ctx -> bypass(ctx, true)))
                        .then(Commands.literal("off").executes(ctx -> bypass(ctx, false))))
                .then(Commands.literal("ranges").executes(LabMod::ranges))
                .then(Commands.literal("pause")
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 100000))
                                .executes(ctx -> {
                                    pauseRemaining = IntegerArgumentType.getInteger(ctx, "ticks");
                                    return pauseRemaining;
                                })))
                .then(Commands.literal("battery")
                        .then(Commands.argument("file", StringArgumentType.string()).executes(LabMod::battery))));
    }

    private static int bypass(CommandContext<CommandSourceStack> ctx, boolean on) {
        LocateMore.setLabBypass(on);
        ctx.getSource().sendSuccess(() -> Component.literal("labBypass=" + on), false);
        return 1;
    }

    private static HolderSet<Structure> holders(CommandContext<CommandSourceStack> ctx, String[] printableOut)
            throws CommandSyntaxException {
        ResourceOrTagKeyArgument.Result<Structure> result = ResourceOrTagKeyArgument.getResourceOrTagKey(
                ctx, "structure", Registries.STRUCTURE, STRUCTURE_INVALID);
        Registry<Structure> registry = ctx.getSource().getLevel().registryAccess().lookupOrThrow(Registries.STRUCTURE);
        printableOut[0] = result.unwrap().map(key -> key.identifier().toString(), tag -> "#" + tag.location());
        return result.unwrap().map(
                key -> registry.get(key).map(holder -> (HolderSet<Structure>) HolderSet.direct(holder)),
                registry::get
        ).orElseThrow(() -> STRUCTURE_INVALID.create(printableOut[0]));
    }

    private static Component line(int number, BlockPos pos, String tail) {
        return Component.literal(number + ". ")
                .append(ComponentUtils.wrapInSquareBrackets(Component.translatable("chat.coordinates",
                        pos.getX(), "~", pos.getZ())).withStyle(ChatFormatting.GREEN))
                .append(Component.literal(" (" + tail + ")"));
    }

    private static int sync(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String[] printable = new String[1];
        HolderSet<Structure> holders = holders(ctx, printable);
        int count = IntegerArgumentType.getInteger(ctx, "count");
        CommandSourceStack source = ctx.getSource();
        BlockPos origin = BlockPos.containing(source.getPosition());
        List<LocateMore.Hit> hits = LocateMore.labLocate(source.getLevel(), holders, origin, count,
                new int[1], new LocateMore.Stats());
        if (hits.isEmpty()) {
            source.sendFailure(Component.literal("Could not find " + printable[0]));
            return 0;
        }
        int shown = Math.min(count, hits.size());
        for (int i = 0; i < shown; i++) {
            LocateMore.Hit hit = hits.get(i);
            int distance = Mth.floor(Mth.sqrt((float) hit.horizDistSqr()));
            int number = i + 1;
            source.sendSuccess(() -> line(number, hit.pos(), distance + " blocks away"), false);
        }
        return shown;
    }

    private static int map(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String[] printable = new String[1];
        HolderSet<Structure> holders = holders(ctx, printable);
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        BlockPos origin = BlockPos.containing(source.getPosition());
        for (int i = 1; i <= 2; i++) {
            long start = System.nanoTime();
            Pair<BlockPos, Holder<Structure>> hit = level.getChunkSource().getGenerator()
                    .findNearestMapStructure(level, holders, origin, 100, true);
            long ms = (System.nanoTime() - start) / 1_000_000L;
            int number = i;
            if (hit == null) {
                source.sendFailure(Component.literal("Could not find " + printable[0]));
                return 0;
            }
            source.sendSuccess(() -> line(number, hit.getFirst(), ms + " ms, skipKnown"), false);
        }
        return 2;
    }

    private static int ranges(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        ClimateAccess climate = ClimateAccess.of(level);
        String[] names = {"temperature", "humidity", "continentalness", "erosion", "weirdness", "depth"};
        int outside = 0;
        for (int dim = 0; dim < 6; dim++) {
            double lo = Double.POSITIVE_INFINITY;
            double hi = Double.NEGATIVE_INFINITY;
            int below = 0;
            int above = 0;
            long samples = 0;
            for (int x = -40000; x <= 40000; x += 1000) {
                for (int z = -40000; z <= 40000; z += 1000) {
                    for (int y = level.getMinY(); y <= level.getMaxY(); y += 48) {
                        float v = climate.flat(dim, x + (y & 15) * 4, y, z + (y & 31) * 4);
                        lo = Math.min(lo, v);
                        hi = Math.max(hi, v);
                        below += v < climate.min(dim) ? 1 : 0;
                        above += v > climate.max(dim) ? 1 : 0;
                        samples++;
                    }
                }
            }
            outside += below + above;
            final String line = names[dim] + " bound [" + climate.min(dim) + ", " + climate.max(dim)
                    + "] observed [" + lo + ", " + hi + "] over " + samples + " samples, outside "
                    + below + "/" + above;
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        final int total = outside;
        ctx.getSource().sendSuccess(() -> Component.literal("RANGES outside=" + total), false);
        return outside;
    }

    private static int battery(CommandContext<CommandSourceStack> ctx) {
        String file = StringArgumentType.getString(ctx, "file");
        Path path = FabricLoader.getInstance().getGameDir().resolve(file);
        try {
            for (String line : Files.readAllLines(path)) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    PENDING.add(trimmed);
                }
            }
        } catch (IOException e) {
            ctx.getSource().sendFailure(Component.literal("Cannot read " + path + ": " + e));
            return 0;
        }
        batteryActive = true;
        idleTicks = 0;
        commandInFlight = false;
        LOGGER.info("BATTERY START {} commands", PENDING.size());
        return PENDING.size();
    }

    private static boolean engineIdle() {
        return AsyncLocate.idle() && BiomeLocate.idle();
    }

    /** One step per tick: wait until the last command's engines drained, then issue the next. */
    private static void tick(MinecraftServer server) {
        if (!batteryActive) {
            return;
        }
        if (pauseRemaining > 0) {
            pauseRemaining--;
            return;
        }
        if (commandInFlight) {
            if (!engineIdle()) {
                idleTicks = 0;
                return;
            }
            if (++idleTicks < 2) {
                return;
            }
            commandInFlight = false;
        }
        String next = PENDING.poll();
        if (next == null) {
            batteryActive = false;
            LOGGER.info("BATTERY DONE");
            return;
        }
        LOGGER.info("BATTERY> {}", next);
        commandInFlight = true;
        idleTicks = 0;
        try {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), next);
        } catch (RuntimeException e) {
            LOGGER.error("BATTERY command failed: {}", next, e);
        }
    }
}
