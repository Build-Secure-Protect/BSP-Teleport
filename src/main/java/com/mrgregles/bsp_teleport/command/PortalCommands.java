package com.mrgregles.bsp_teleport.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.block.PortalCoreBlockEntity;
import com.mrgregles.bsp_teleport.data.PortalRow;
import com.mrgregles.bsp_teleport.data.TeleportData;
import com.mrgregles.bsp_teleport.data.Warp;
import com.mrgregles.bsp_teleport.network.PlayerDirectory;
import com.mrgregles.bsp_teleport.portal.PortalActions;
import com.mrgregles.bsp_teleport.portal.PortalColour;
import com.mrgregles.bsp_teleport.portal.Portals;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;

import java.util.Arrays;
import java.util.stream.Stream;

/**
 * {@code /bsptp portal ...} acts on the Portal Core the admin is looking at; {@code /bsptp portalwarp ...} keeps the
 * portal warps (D55). Both are the command face of {@link PortalActions}; the core screen (T56) is the other.
 */
public final class PortalCommands {
    private static final SuggestionProvider<CommandSourceStack> WARPS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(TeleportData.get(ctx.getSource().getServer()).warps().stream().map(Warp::name), builder);
    private static final SuggestionProvider<CommandSourceStack> PORTAL_WARPS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(TeleportData.get(ctx.getSource().getServer()).warps().stream().filter(Warp::portal).map(Warp::name), builder);
    private static final SuggestionProvider<CommandSourceStack> SERVERS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(Stream.concat(Stream.of(TeleportConfig.serverName()), PlayerDirectory.servers().stream()).distinct(), builder);
    private static final SuggestionProvider<CommandSourceStack> PORTAL_NAMES = (ctx, builder) ->
            SharedSuggestionProvider.suggest(Portals.rows().stream().map(r -> r.name().contains(" ") ? "\"" + r.name() + "\"" : r.name()).distinct(), builder);
    private static final SuggestionProvider<CommandSourceStack> COLOURS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(Arrays.stream(PortalColour.values()).map(PortalColour::getSerializedName), builder);

    private PortalCommands() {
    }

    private interface CoreAction {
        boolean run(ServerPlayer player, PortalCoreBlockEntity core, CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException;
    }

    private interface RowAction {
        boolean run(ServerPlayer player, PortalRow row) throws CommandSyntaxException;
    }

    /** {@code /bsptp portal <name> ...}: the named portal anywhere on the network, this server's first. */
    private static int withRow(CommandContext<CommandSourceStack> ctx, RowAction action) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "portal");
        PortalRow row = Portals.rowByName(name);
        if (row == null) {
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.bsp_teleport.portal.unknown", name), false);
            return 0;
        }
        return action.run(player, row) ? 1 : 0;
    }

    private static int withCore(CommandContext<CommandSourceStack> ctx, CoreAction action) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        PortalCoreBlockEntity core = PortalActions.lookedAt(player);
        return core != null && action.run(player, core, ctx) ? 1 : 0;
    }

    /** {@code dest add <kind> <target> [label]} for the kinds that take a name. */
    private static LiteralArgumentBuilder<CommandSourceStack> destAdd(String kind, String arg, com.mojang.brigadier.arguments.ArgumentType<String> type, SuggestionProvider<CommandSourceStack> suggest) {
        return Commands.literal(kind)
                .then(Commands.argument(arg, type).suggests(suggest)
                        .executes(ctx -> withCore(ctx, (p, core, c) -> PortalActions.addDestination(p, core, PortalActions.resolve(p, core, kind, StringArgumentType.getString(c, arg)), "")))
                        .then(Commands.argument("label", StringArgumentType.greedyString())
                                .executes(ctx -> withCore(ctx, (p, core, c) -> PortalActions.addDestination(p, core, PortalActions.resolve(p, core, kind, StringArgumentType.getString(c, arg)), StringArgumentType.getString(c, "label"))))));
    }

    public static LiteralArgumentBuilder<CommandSourceStack> portal() {
        return Commands.literal("portal")
                .then(Commands.literal("info").executes(ctx -> withCore(ctx, (p, core, c) -> {
                    core.describe(p, true);
                    return true;
                })))
                .then(Commands.literal("list").executes(ctx -> {
                    PortalActions.listPortals(ctx.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(Commands.literal("dest")
                        .then(Commands.literal("warp").then(Commands.argument("name", StringArgumentType.word()).suggests(WARPS)
                                .executes(ctx -> withCore(ctx, (p, core, c) -> PortalActions.setWarp(p, core, StringArgumentType.getString(c, "name"))))))
                        .then(Commands.literal("random").executes(ctx -> withCore(ctx, (p, core, c) -> PortalActions.setRandom(p, core, "")))
                                .then(Commands.argument("server", StringArgumentType.word()).suggests(SERVERS)
                                        .executes(ctx -> withCore(ctx, (p, core, c) -> PortalActions.setRandom(p, core, StringArgumentType.getString(c, "server"))))))
                        .then(Commands.literal("here").executes(ctx -> withCore(ctx, (p, core, c) -> PortalActions.setHere(p, core))))
                        .then(Commands.literal("none").executes(ctx -> withCore(ctx, (p, core, c) -> PortalActions.clearDestination(p, core))))
                        // The list (D94, D104): add keeps what is there; the forms above replace the list with one entry.
                        .then(Commands.literal("add")
                                .then(destAdd("warp", "name", StringArgumentType.word(), WARPS))
                                .then(destAdd("portalwarp", "name", StringArgumentType.word(), PORTAL_WARPS))
                                .then(destAdd("portal", "name", StringArgumentType.string(), PORTAL_NAMES))
                                .then(Commands.literal("here").executes(ctx -> withCore(ctx, (p, core, c) -> PortalActions.addDestination(p, core, PortalActions.resolve(p, core, "POSITION", ""), "")))
                                        .then(Commands.argument("label", StringArgumentType.greedyString())
                                                .executes(ctx -> withCore(ctx, (p, core, c) -> PortalActions.addDestination(p, core, PortalActions.resolve(p, core, "POSITION", ""), StringArgumentType.getString(c, "label"))))))
                                // D116: back, each player's own /back spot.
                                .then(Commands.literal("back").executes(ctx -> withCore(ctx, (p, core, c) -> PortalActions.addDestination(p, core, PortalActions.resolve(p, core, "BACK", ""), "")))
                                        .then(Commands.argument("label", StringArgumentType.greedyString())
                                                .executes(ctx -> withCore(ctx, (p, core, c) -> PortalActions.addDestination(p, core, PortalActions.resolve(p, core, "BACK", ""), StringArgumentType.getString(c, "label"))))))
                                .then(Commands.literal("random").executes(ctx -> withCore(ctx, (p, core, c) -> PortalActions.addDestination(p, core, PortalActions.resolve(p, core, "RANDOM", ""), "")))
                                        .then(Commands.argument("server", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(com.mrgregles.bsp_teleport.network.PlayerDirectory.servers(), b))
                                                .executes(ctx -> withCore(ctx, (p, core, c) -> PortalActions.addDestination(p, core, PortalActions.resolve(p, core, "RANDOM", StringArgumentType.getString(c, "server")), "")))
                                                .then(Commands.argument("label", StringArgumentType.greedyString())
                                                        .executes(ctx -> withCore(ctx, (p, core, c) -> PortalActions.addDestination(p, core, PortalActions.resolve(p, core, "RANDOM", StringArgumentType.getString(c, "server")), StringArgumentType.getString(c, "label"))))))))
                        .then(Commands.literal("del").then(Commands.argument("n", IntegerArgumentType.integer(1))
                                .executes(ctx -> withCore(ctx, (p, core, c) -> PortalActions.removeDestination(p, core, IntegerArgumentType.getInteger(c, "n") - 1)))))
                        .then(Commands.literal("label").then(Commands.argument("n", IntegerArgumentType.integer(1)).then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(ctx -> withCore(ctx, (p, core, c) -> PortalActions.labelDestination(p, core, IntegerArgumentType.getInteger(c, "n") - 1, StringArgumentType.getString(c, "text")))))))
                        .then(Commands.literal("clear").executes(ctx -> withCore(ctx, (p, core, c) -> PortalActions.clearDestinations(p, core))))
                        .then(Commands.literal("list").executes(ctx -> withCore(ctx, (p, core, c) -> PortalActions.listDestinations(p, core)))))
                .then(Commands.literal("name").then(Commands.argument("name", StringArgumentType.greedyString())
                        .executes(ctx -> withCore(ctx, (p, core, c) -> PortalActions.setName(p, core, StringArgumentType.getString(c, "name"))))))
                .then(Commands.literal("colour").then(Commands.argument("colour", StringArgumentType.word()).suggests(COLOURS)
                        .executes(ctx -> withCore(ctx, (p, core, c) -> PortalActions.setColour(p, core, PortalColour.byName(StringArgumentType.getString(c, "colour")))))))
                .then(Commands.literal("arriveonly").then(Commands.argument("on", BoolArgumentType.bool())
                        .executes(ctx -> withCore(ctx, (p, core, c) -> PortalActions.setArriveOnly(p, core, BoolArgumentType.getBool(c, "on"))))))
                .then(Commands.literal("closewhendown").then(Commands.argument("on", BoolArgumentType.bool())
                        .executes(ctx -> withCore(ctx, (p, core, c) -> PortalActions.setCloseWhenDown(p, core, BoolArgumentType.getBool(c, "on"))))))
                .then(Commands.literal("cooldown").then(Commands.argument("seconds", IntegerArgumentType.integer(0, 86400))
                        .executes(ctx -> withCore(ctx, (p, core, c) -> PortalActions.setCooldown(p, core, IntegerArgumentType.getInteger(c, "seconds"))))))
                .then(Commands.literal("warmup").then(Commands.argument("seconds", IntegerArgumentType.integer(0, 30))
                        .executes(ctx -> withCore(ctx, (p, core, c) -> PortalActions.setWarmup(p, core, IntegerArgumentType.getInteger(c, "seconds"))))))
                .then(Commands.literal("open").executes(ctx -> withCore(ctx, (p, core, c) -> PortalActions.setOpen(p, core, true))))
                .then(Commands.literal("close").executes(ctx -> withCore(ctx, (p, core, c) -> PortalActions.setOpen(p, core, false))))
                .then(Commands.literal("lock").then(Commands.argument("on", BoolArgumentType.bool())
                        .executes(ctx -> withCore(ctx, (p, core, c) -> PortalActions.setLocked(p, core, BoolArgumentType.getBool(c, "on"))))))
                .then(Commands.literal("mode")
                        .then(Commands.literal("manual").executes(ctx -> withCore(ctx, (p, core, c) -> PortalActions.setManual(p, core))))
                        .then(Commands.literal("redstone").executes(ctx -> withCore(ctx, (p, core, c) -> PortalActions.setRedstone(p, core, true)))
                                .then(Commands.argument("openWhenPowered", BoolArgumentType.bool())
                                        .executes(ctx -> withCore(ctx, (p, core, c) -> PortalActions.setRedstone(p, core, BoolArgumentType.getBool(c, "openWhenPowered"))))))
                        .then(Commands.literal("realclock").then(Commands.argument("open", StringArgumentType.word()).then(Commands.argument("close", StringArgumentType.word())
                                .executes(ctx -> withCore(ctx, (p, core, c) -> PortalActions.setRealClock(p, core, StringArgumentType.getString(c, "open"), StringArgumentType.getString(c, "close")))))))
                        .then(Commands.literal("gameclock").then(Commands.argument("open", IntegerArgumentType.integer(0, 24000)).then(Commands.argument("close", IntegerArgumentType.integer(0, 24000))
                                .executes(ctx -> withCore(ctx, (p, core, c) -> PortalActions.setGameClock(p, core, IntegerArgumentType.getInteger(c, "open"), IntegerArgumentType.getInteger(c, "close"))))))))
                .then(Commands.literal("doors")
                        .then(Commands.literal("blocks").executes(ctx -> withCore(ctx, (p, core, c) -> PortalActions.setDoorStyle(p, core, PortalCoreBlockEntity.DoorStyle.BLOCKS))))
                        .then(Commands.literal("rendered").executes(ctx -> withCore(ctx, (p, core, c) -> PortalActions.setDoorStyle(p, core, PortalCoreBlockEntity.DoorStyle.RENDERED)))))
                .then(Commands.argument("portal", StringArgumentType.string()).suggests(PORTAL_NAMES)
                        .then(Commands.literal("open").executes(ctx -> withRow(ctx, (p, row) -> PortalActions.command(p, row, "open"))))
                        .then(Commands.literal("close").executes(ctx -> withRow(ctx, (p, row) -> PortalActions.command(p, row, "close"))))
                        .then(Commands.literal("lock").executes(ctx -> withRow(ctx, (p, row) -> PortalActions.command(p, row, "lock"))))
                        .then(Commands.literal("unlock").executes(ctx -> withRow(ctx, (p, row) -> PortalActions.command(p, row, "unlock"))))
                        .then(Commands.literal("tp").executes(ctx -> withRow(ctx, PortalActions::goTo)))
                        .then(Commands.literal("info").executes(ctx -> withRow(ctx, (p, row) -> {
                            p.displayClientMessage(net.minecraft.network.chat.Component.literal(row.name() + " [" + row.server() + " " + row.world() + " " + row.x() + "," + row.y() + "," + row.z() + "] "
                                    + row.destination().describe() + (row.arriveOnly() ? " (arrive only)" : "") + ", " + row.status().toLowerCase(java.util.Locale.ROOT)
                                    + (row.doors().isEmpty() ? "" : ", doors " + row.doors())), false);
                            return true;
                        }))))
                .then(Commands.literal("arrivalline").executes(ctx -> withCore(ctx, (p, core, c) -> PortalActions.setArrivalLine(p, core, "")))
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(ctx -> withCore(ctx, (p, core, c) -> PortalActions.setArrivalLine(p, core, StringArgumentType.getString(c, "text"))))));
    }

    public static LiteralArgumentBuilder<CommandSourceStack> portalWarp() {
        return Commands.literal("portalwarp")
                .then(Commands.literal("set").then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> PortalActions.setPortalWarp(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "name")) ? 1 : 0)))
                .then(Commands.literal("del").then(Commands.argument("name", StringArgumentType.word()).suggests(PORTAL_WARPS)
                        .executes(ctx -> PortalActions.delPortalWarp(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "name")) ? 1 : 0)))
                .then(Commands.literal("list").executes(ctx -> {
                    PortalActions.listPortalWarps(ctx.getSource().getPlayerOrException());
                    return 1;
                }));
    }
}
