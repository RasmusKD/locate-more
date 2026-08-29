package com.rasmus.locatemore;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.util.Mth;

/**
 * How a hit reaches the player: the numbered chat line with clickable
 * coordinates and heading, the [track] and [compass] buttons, and the
 * compass item those buttons hand out. Built from vanilla client lang
 * keys only ("chat.coordinates"), so unmodded clients on a dedicated
 * server render everything correctly.
 */
final class HitPresentation {

    private HitPresentation() {
    }

    /**
     * The [track] button arms the action-bar travel tracker: distance and
     * a direction arrow while walking there, self-clearing on arrival, so
     * nothing needs cleaning up afterwards.
     */
    static Component hitLine(int number, LocateMore.Hit hit, String printable, BlockPos origin) {
        int distance = Mth.floor(Mth.sqrt((float) hit.horizDistSqr()));
        // Direction at a glance: "1945 blocks W" reads as a place, not a
        // number; very close hits keep the plain "away".
        String heading = distance >= 16
                ? octant(hit.pos().getX() - origin.getX(), hit.pos().getZ() - origin.getZ())
                : "away";
        Component coordinates = ComponentUtils.wrapInSquareBrackets(Component.translatable("chat.coordinates",
                        hit.pos().getX(), "~", hit.pos().getZ()))
                .withStyle(style -> style.withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent.SuggestCommand(
                                "/tp @s " + hit.pos().getX() + " ~ " + hit.pos().getZ()))
                        .withHoverEvent(new HoverEvent.ShowText(Component.translatable("chat.coordinates.tooltip"))));
        String name = trackName(printable, number);
        return Component.literal(number + ". ")
                .append(coordinates)
                .append(Component.literal(" (" + distance + " blocks " + heading + ") "))
                .append(trackButton(hit.pos().getX(), 0, hit.pos().getZ(), name))
                .append(Component.literal(" "))
                .append(compassButton(hit.pos().getX(), 64, hit.pos().getZ(), name));
    }

    /**
     * A vanilla compass whose needle points at the result: the lodestone
     * tracker component with tracked=false needs no lodestone block and
     * survives entirely client-side rendering-wise, so vanilla clients get
     * a physical needle with zero client mod. Self-serve cleanup: it is an
     * ordinary item, toss it when done.
     */
    static int giveCompass(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
            return 0;
        }
        giveCompass(player, new BlockPos(
                        IntegerArgumentType.getInteger(ctx, "x"),
                        IntegerArgumentType.getInteger(ctx, "y"),
                        IntegerArgumentType.getInteger(ctx, "z")),
                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name"));
        return 1;
    }

    static void giveCompass(net.minecraft.server.level.ServerPlayer player, BlockPos pos, String name) {
        net.minecraft.world.item.ItemStack compass =
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COMPASS);
        compass.set(net.minecraft.core.component.DataComponents.LODESTONE_TRACKER,
                new net.minecraft.world.item.component.LodestoneTracker(
                        java.util.Optional.of(net.minecraft.core.GlobalPos.of(
                                player.level().dimension(), pos)),
                        false));
        compass.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                Component.literal(name).withStyle(ChatFormatting.AQUA));
        if (!player.getInventory().add(compass)) {
            player.drop(compass, false);
        }
    }

    /** Eight-way compass direction from origin toward a target. */
    static String octant(int dx, int dz) {
        // Vanilla map convention: north is -z, east is +x.
        double angle = Math.toDegrees(Math.atan2(dx, -dz));
        String[] names = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return names[Math.floorMod((int) Math.round(angle / 45.0), 8)];
    }

    static Component compassButton(int x, int y, int z, String name) {
        return Component.literal("[compass]").withStyle(style -> style.withColor(ChatFormatting.GOLD)
                .withClickEvent(new ClickEvent.RunCommand(
                        "/locatemore compass " + x + " " + y + " " + z + " " + name))
                .withHoverEvent(new HoverEvent.ShowText(
                        Component.literal("A compass whose needle points at this result"))));
    }

    static Component trackButton(int x, int y, int z, String name) {
        return Component.literal("[track]").withStyle(style -> style.withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent.RunCommand(
                        "/locatemore track " + x + " " + y + " " + z + " " + name))
                .withHoverEvent(new HoverEvent.ShowText(
                        Component.literal("Live distance and direction in the action bar"))));
    }

    static String trackName(String printable, int number) {
        int colon = printable.lastIndexOf(':');
        return (colon >= 0 ? printable.substring(colon + 1) : printable) + " #" + number;
    }
}
