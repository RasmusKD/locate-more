package com.rasmus.locatemore;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.serialization.Codec;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;

/**
 * Named bookmarks: navigation without teleport. A mark is one GlobalPos
 * per name per player, persisted through the Fabric attachment API
 * (survives restarts, copies through death), and the payoff is the
 * buttons: every mark line carries the same [track] and [compass] the
 * search results do. This is deliberately not a homes mod: nothing
 * teleports, and per the mod's state rule the marks feed buttons only,
 * never a search result.
 */
final class Marks {

    private static final int MAX_MARKS = 32;

    private static final AttachmentType<Map<String, GlobalPos>> MARKS = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath("locatemore", "marks"),
            builder -> builder
                    .persistent(Codec.unboundedMap(Codec.STRING, GlobalPos.CODEC))
                    .copyOnDeath());

    private Marks() {
    }

    /** Forces attachment registration during mod init, before any player
     * data with persisted marks could load. */
    static void init() {
    }

    static int set(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.literal("Marks belong to a player."));
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name");
        Map<String, GlobalPos> marks = new HashMap<>(player.getAttachedOrElse(MARKS, Map.of()));
        if (!marks.containsKey(name) && marks.size() >= MAX_MARKS) {
            ctx.getSource().sendFailure(Component.literal(
                    MAX_MARKS + " marks is the cap; remove one first."));
            return 0;
        }
        GlobalPos pos = GlobalPos.of(player.level().dimension(), player.blockPosition());
        marks.put(name, pos);
        player.setAttached(MARKS, Map.copyOf(marks));
        ctx.getSource().sendSuccess(() -> markLine(name, pos, player), false);
        return 1;
    }

    static int list(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.literal("Marks belong to a player."));
            return 0;
        }
        Map<String, GlobalPos> marks = new TreeMap<>(player.getAttachedOrElse(MARKS, Map.of()));
        if (marks.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "No marks yet; mark <name> saves where you stand.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        for (Map.Entry<String, GlobalPos> entry : marks.entrySet()) {
            ctx.getSource().sendSuccess(() -> markLine(entry.getKey(), entry.getValue(), player), false);
        }
        return marks.size();
    }

    static int remove(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.literal("Marks belong to a player."));
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name");
        Map<String, GlobalPos> marks = new HashMap<>(player.getAttachedOrElse(MARKS, Map.of()));
        if (marks.remove(name) == null) {
            ctx.getSource().sendFailure(Component.literal("No mark named " + name + "."));
            return 0;
        }
        player.setAttached(MARKS, Map.copyOf(marks));
        ctx.getSource().sendSuccess(() -> Component.literal("Mark " + name + " removed.")
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static Component markLine(String name, GlobalPos pos, ServerPlayer player) {
        BlockPos p = pos.pos();
        String where;
        if (player.level().dimension() == pos.dimension()) {
            long distSqr = LocateMore.horizDistSqr(p, player.blockPosition());
            int distance = Mth.floor(Mth.sqrt((float) distSqr));
            where = distance >= 16
                    ? distance + " blocks " + HitPresentation.octant(
                            p.getX() - player.getBlockX(), p.getZ() - player.getBlockZ())
                    : distance + " blocks away";
        } else {
            where = pos.dimension().identifier().toString();
        }
        return Component.literal(name + ": ")
                .append(Component.literal("[" + p.getX() + ", " + p.getY() + ", " + p.getZ() + "]")
                        .withStyle(ChatFormatting.GREEN))
                .append(Component.literal(" (" + where + ") "))
                .append(HitPresentation.trackButton(p.getX(), p.getY(), p.getZ(), name))
                .append(Component.literal(" "))
                .append(HitPresentation.compassButton(p.getX(), p.getY(), p.getZ(), name));
    }
}
