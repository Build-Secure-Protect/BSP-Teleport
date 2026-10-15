package com.mrgregles.bsp_teleport.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mrgregles.bsp_teleport.api.BspTeleportApi;
import com.mrgregles.bsp_teleport.api.Destination;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.compat.BspCore;
import com.mrgregles.bsp_teleport.data.Home;
import com.mrgregles.bsp_teleport.data.HomeLimits;
import com.mrgregles.bsp_teleport.data.Position;
import com.mrgregles.bsp_teleport.data.TeleportData;
import com.mrgregles.bsp_teleport.data.Warp;
import com.mrgregles.bsp_teleport.network.Messenger;
import com.mrgregles.bsp_teleport.network.PlayerDirectory;
import com.mrgregles.bsp_teleport.perm.Permissions;
import com.mrgregles.bsp_teleport.storage.Storage;
import com.mrgregles.bsp_teleport.request.TeleportRequests;
import com.mrgregles.bsp_teleport.teleport.AdminActions;
import com.mrgregles.bsp_teleport.teleport.TeleportActions;
import com.mrgregles.bsp_teleport.teleport.Rules;
import com.mrgregles.bsp_teleport.teleport.TeleportKind;
import com.mrgregles.bsp_teleport.teleport.Teleports;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;

import java.util.List;
import java.util.stream.Collectors;

/** The player and admin commands. Every message is a lang key written by {@code tools/gen_lang.py}. */
public final class TeleportCommands {
    private static final SuggestionProvider<CommandSourceStack> HOMES = (ctx, builder) -> {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player != null) {
            TeleportData.get(ctx.getSource().getServer()).homesOf(player.getUUID()).forEach(h -> builder.suggest(h.name()));
        }
        return builder.buildFuture();
    };
    private static final SuggestionProvider<CommandSourceStack> WARPS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(TeleportData.get(ctx.getSource().getServer()).warps().stream().map(Warp::name), builder);
    private static final SuggestionProvider<CommandSourceStack> PLAYERS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(TeleportRequests.suggestions(ctx.getSource().getServer()), builder);
    private static final SuggestionProvider<CommandSourceStack> SERVERS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(PlayerDirectory.servers(), builder);

    private TeleportCommands() {
    }

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();

        d.register(player("home").executes(ctx -> home(ctx, null))
                .then(Commands.argument("name", StringArgumentType.word()).suggests(HOMES)
                        .executes(ctx -> home(ctx, StringArgumentType.getString(ctx, "name")))));
        d.register(player("homes").executes(TeleportCommands::homes));
        d.register(player("sethome").executes(ctx -> setHome(ctx, "home"))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> setHome(ctx, StringArgumentType.getString(ctx, "name")))));
        d.register(player("delhome")
                .then(Commands.literal("all").then(Commands.literal("confirm").executes(TeleportCommands::delAllHomes)))
                .then(Commands.argument("name", StringArgumentType.word()).suggests(HOMES)
                        .executes(ctx -> delHome(ctx, StringArgumentType.getString(ctx, "name")))));
        d.register(player("edithome")
                .then(Commands.argument("name", StringArgumentType.word()).suggests(HOMES)
                        .executes(ctx -> editHome(ctx, "show", ""))
                        .then(Commands.literal("rename").then(Commands.argument("new", StringArgumentType.word())
                                .executes(ctx -> editHome(ctx, "rename", StringArgumentType.getString(ctx, "new")))))
                        .then(Commands.literal("relocate").executes(ctx -> editHome(ctx, "relocate", "")))
                        .then(Commands.literal("icon").executes(ctx -> editHome(ctx, "icon", "")))
                        .then(Commands.literal("description").then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(ctx -> editHome(ctx, "description", StringArgumentType.getString(ctx, "text")))))
                        .then(Commands.literal("public").then(Commands.argument("public", BoolArgumentType.bool())
                                .executes(ctx -> editHome(ctx, "public", Boolean.toString(BoolArgumentType.getBool(ctx, "public"))))))));
        d.register(player("phome")
                .then(Commands.argument("owner", StringArgumentType.word()).suggests(PLAYERS)
                        .executes(ctx -> publicHome(ctx, StringArgumentType.getString(ctx, "owner"), null))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> publicHome(ctx, StringArgumentType.getString(ctx, "owner"), StringArgumentType.getString(ctx, "name"))))));
        d.register(player("phomes").executes(TeleportCommands::publicHomes));

        d.register(player("warp").executes(TeleportCommands::warps)
                .then(Commands.argument("name", StringArgumentType.word()).suggests(WARPS)
                        .executes(ctx -> warp(ctx, StringArgumentType.getString(ctx, "name")))));
        d.register(player("warps").executes(TeleportCommands::warps));
        d.register(admin("setwarp")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> setWarp(ctx, 0, false))
                        .then(Commands.argument("cost", IntegerArgumentType.integer(0))
                                .executes(ctx -> setWarp(ctx, IntegerArgumentType.getInteger(ctx, "cost"), false))
                                .then(Commands.argument("restricted", BoolArgumentType.bool())
                                        .executes(ctx -> setWarp(ctx, IntegerArgumentType.getInteger(ctx, "cost"), BoolArgumentType.getBool(ctx, "restricted")))))));
        d.register(admin("delwarp")
                .then(Commands.argument("name", StringArgumentType.word()).suggests(WARPS)
                        .executes(ctx -> delWarp(ctx, StringArgumentType.getString(ctx, "name")))));

        d.register(player("spawn").executes(TeleportCommands::spawn));
        d.register(admin("setspawn").executes(TeleportCommands::setSpawn));
        d.register(player("back").executes(TeleportCommands::back));
        d.register(player("rtp").executes(ctx -> rtp(ctx, null))
                .then(Commands.argument("server", StringArgumentType.word()).suggests(SERVERS)
                        .executes(ctx -> rtp(ctx, StringArgumentType.getString(ctx, "server")))));

        d.register(player("tpa").then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS)
                .executes(ctx -> request(ctx, false))));
        d.register(player("tpahere").then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS)
                .executes(ctx -> request(ctx, true))));
        d.register(player("tpaccept").executes(ctx -> {
            TeleportRequests.accept(ctx.getSource().getPlayerOrException());
            return 1;
        }));
        d.register(player("tpdeny").executes(ctx -> {
            TeleportRequests.deny(ctx.getSource().getPlayerOrException());
            return 1;
        }));
        d.register(player("tpcancel").executes(ctx -> Teleports.cancel(ctx.getSource().getPlayerOrException(), "cancelled") ? 1 : 0));
        d.register(player("tpaall").executes(ctx -> {
            TeleportRequests.sendToAll(ctx.getSource().getPlayerOrException());
            return 1;
        }));
        d.register(player("tpignore").executes(ctx -> TeleportRequests.toggleIgnore(ctx.getSource().getPlayerOrException()) ? 1 : 0));
        d.register(player("tpnow").executes(ctx -> Teleports.payAndRetry(ctx.getSource().getPlayerOrException()) ? 1 : 0));

        // Admin, forced: no warm-up, no cooldown, /back still works for the moved player.
        d.register(admin("tpall").executes(ctx -> tpAll(ctx, "here"))
                .then(Commands.literal("all").executes(ctx -> tpAll(ctx, "all")))
                .then(Commands.literal("from").then(Commands.argument("server", StringArgumentType.word()).suggests(SERVERS)
                        .executes(ctx -> tpAll(ctx, StringArgumentType.getString(ctx, "server"))))));
        d.register(admin("tphere").then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS)
                .executes(TeleportCommands::tpHere)));
        d.register(admin("tpto").then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS)
                .executes(TeleportCommands::tpTo)));
        d.register(admin("tppos").then(Commands.argument("pos", Vec3Argument.vec3())
                .executes(ctx -> tpPos(ctx, null, null))
                .then(Commands.argument("world", StringArgumentType.string())
                        .executes(ctx -> tpPos(ctx, StringArgumentType.getString(ctx, "world"), null))
                        .then(Commands.argument("server", StringArgumentType.word()).suggests(SERVERS)
                                .executes(ctx -> tpPos(ctx, StringArgumentType.getString(ctx, "world"), StringArgumentType.getString(ctx, "server")))))));
        d.register(admin("tpoffline").then(Commands.argument("player", StringArgumentType.word())
                .executes(TeleportCommands::tpOffline)));
    }

    // Public homes and editing

    private static int editHome(CommandContext<CommandSourceStack> ctx, String action, String value) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = TeleportData.normalise(StringArgumentType.getString(ctx, "name"));
        TeleportData data = TeleportData.get(player.server);
        Home home = data.home(player.getUUID(), name);
        if (home == null) {
            return fail(player, "home.unknown", name);
        }
        Home edited;
        switch (action) {
            case "rename" -> {
                String newName = TeleportData.normalise(value);
                if (!TeleportData.validName(newName)) {
                    return fail(player, "home.bad_name");
                }
                if (data.home(player.getUUID(), newName) != null) {
                    return fail(player, "home.exists", newName);
                }
                edited = home.withName(newName);
                data.deleteHome(player.getUUID(), name);
                Storage.homeDeleted(player.getUUID(), name);
            }
            case "relocate" -> {
                if (!Rules.canSetHome(player.serverLevel(), player.blockPosition(), player)) {
                    return fail(player, "home.blocked_here");
                }
                edited = home.withPosition(Position.of(player));
            }
            case "description" -> edited = home.withDescription(value.length() > Home.MAX_DESCRIPTION ? value.substring(0, Home.MAX_DESCRIPTION) : value);
            case "public" -> edited = home.withPublic(Boolean.parseBoolean(value));
            case "icon" -> edited = home.withIcon(com.mrgregles.bsp_teleport.util.Icons.heldItemId(player));
            default -> {
                tell(player, "home.info", home.name(), home.position().describe(), home.isPublic() ? "public" : "private",
                        home.description().isEmpty() ? "-" : home.description());
                return 1;
            }
        }
        data.putHome(player.getUUID(), name, edited);
        Storage.homeSaved(player.getUUID(), edited);
        tell(player, "home.edited", edited.name());
        return 1;
    }

    private static int delAllHomes(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        TeleportData data = TeleportData.get(player.server);
        int count = 0;
        for (Home home : data.homesOf(player.getUUID())) {
            data.deleteHome(player.getUUID(), home.name());
            Storage.homeDeleted(player.getUUID(), home.name());
            count++;
        }
        tell(player, "home.deleted_all", count);
        return count;
    }

    private static int publicHome(CommandContext<CommandSourceStack> ctx, String ownerName, String homeName) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (Storage.enabled()) {
            Storage.lookup(ownerName, found -> {
                if (found == null) {
                    fail(player, "home.owner_unknown", ownerName);
                    return;
                }
                goPublic(player, found.name(), found.homes(), homeName);
            });
            return 1;
        }
        TeleportData data = TeleportData.get(player.server);
        UUID owner = data.findByName(ownerName);
        if (owner == null) {
            return fail(player, "home.owner_unknown", ownerName);
        }
        goPublic(player, data.nameOf(owner), data.homesOf(owner), homeName);
        return 1;
    }

    private static void goPublic(ServerPlayer player, String ownerName, List<Home> homes, String homeName) {
        List<Home> open = homes.stream().filter(Home::isPublic).collect(Collectors.toList());
        if (open.isEmpty()) {
            fail(player, "home.none_public", ownerName);
            return;
        }
        Home home;
        if (homeName == null) {
            if (open.size() > 1) {
                tell(player, "home.public_list_of", ownerName, open.stream().map(Home::name).collect(Collectors.joining(", ")));
                return;
            }
            home = open.get(0);
        } else {
            String key = TeleportData.normalise(homeName);
            home = open.stream().filter(h -> h.name().equals(key)).findFirst().orElse(null);
            if (home == null) {
                fail(player, "home.none_public_named", ownerName, key);
                return;
            }
        }
        Teleports.start(player, home.position().toDestination(""), Component.literal(ownerName + "'s " + home.name()), TeleportKind.HOME, null);
    }

    private static int publicHomes(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (Storage.enabled()) {
            Storage.publicHomes(list -> {
                if (list.isEmpty()) {
                    fail(player, "home.no_public");
                } else {
                    tell(player, "home.public_list", list.stream().map(p -> p.ownerName() + "." + p.home().name()).collect(Collectors.joining(", ")));
                }
            });
            return 1;
        }
        TeleportData data = TeleportData.get(player.server);
        List<java.util.Map.Entry<UUID, Home>> list = data.publicHomes();
        if (list.isEmpty()) {
            return fail(player, "home.no_public");
        }
        tell(player, "home.public_list", list.stream().map(e -> String.valueOf(data.nameOf(e.getKey())) + "." + e.getValue().name()).collect(Collectors.joining(", ")));
        return list.size();
    }

    // Admin moves

    private static int tpAll(CommandContext<CommandSourceStack> ctx, String scope) throws CommandSyntaxException {
        return AdminActions.bringAll(ctx.getSource().getPlayerOrException(), scope) ? 1 : 0;
    }

    private static int tpHere(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return AdminActions.bringPlayer(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "player")) ? 1 : 0;
    }

    private static int tpTo(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return AdminActions.goToPlayer(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "player")) ? 1 : 0;
    }

    private static int tpPos(CommandContext<CommandSourceStack> ctx, String world, String server) throws CommandSyntaxException {
        Vec3 pos = Vec3Argument.getVec3(ctx, "pos");
        return AdminActions.goToPosition(ctx.getSource().getPlayerOrException(), pos.x, pos.y, pos.z, world, server) ? 1 : 0;
    }

    private static int tpOffline(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return AdminActions.goToOffline(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "player")) ? 1 : 0;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> player(String name) {
        return Commands.literal(name).requires(src -> src.getEntity() instanceof ServerPlayer);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> admin(String name) {
        return Commands.literal(name).requires(src -> src.getEntity() instanceof ServerPlayer p ? Permissions.isAdmin(p) : src.hasPermission(2));
    }

    // Homes

    private static int home(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        return TeleportActions.home(ctx.getSource().getPlayerOrException(), name) ? 1 : 0;
    }

    private static int homes(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        TeleportActions.listHomes(ctx.getSource().getPlayerOrException());
        return 1;
    }

    private static int setHome(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        return TeleportActions.setHome(ctx.getSource().getPlayerOrException(), name) ? 1 : 0;
    }

    private static int delHome(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        return TeleportActions.delHome(ctx.getSource().getPlayerOrException(), name) ? 1 : 0;
    }

    // Warps

    private static int warps(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        TeleportActions.listWarps(ctx.getSource().getPlayerOrException());
        return 1;
    }

    private static int warp(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        return TeleportActions.warp(ctx.getSource().getPlayerOrException(), name) ? 1 : 0;
    }

    private static int setWarp(CommandContext<CommandSourceStack> ctx, int cost, boolean restricted) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        if (!TeleportData.validName(name)) {
            return fail(player, "warp.bad_name");
        }
        Warp warp = new Warp(TeleportData.normalise(name), Position.of(player), cost, restricted);
        TeleportData.get(player.server).setWarp(warp);
        Storage.warpSaved(warp);
        tell(player, "warp.set", warp.name(), restricted ? Permissions.warpNode(warp.name()) : "-", cost);
        return 1;
    }

    private static int delWarp(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!TeleportData.get(player.server).deleteWarp(name)) {
            return fail(player, "warp.unknown", name);
        }
        Storage.warpDeleted(TeleportData.normalise(name));
        tell(player, "warp.deleted", TeleportData.normalise(name));
        return 1;
    }

    // Spawn, back, random

    private static int spawn(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return TeleportActions.spawn(ctx.getSource().getPlayerOrException()) ? 1 : 0;
    }

    private static int setSpawn(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Position spawn = Position.of(player);
        TeleportData.get(player.server).setSpawn(spawn);
        Storage.spawnSaved(spawn);
        tell(player, "spawn.set");
        return 1;
    }

    private static int back(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return TeleportActions.back(ctx.getSource().getPlayerOrException()) ? 1 : 0;
    }

    private static int rtp(CommandContext<CommandSourceStack> ctx, String server) throws CommandSyntaxException {
        return TeleportActions.rtp(ctx.getSource().getPlayerOrException(), server) ? 1 : 0;
    }

    // Requests

    private static int request(CommandContext<CommandSourceStack> ctx, boolean here) throws CommandSyntaxException {
        ServerPlayer from = ctx.getSource().getPlayerOrException();
        TeleportRequests.send(from, StringArgumentType.getString(ctx, "player"), here);
        return 1;
    }

    private static void tell(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(Component.translatable("message.bsp_teleport." + key, args), false);
    }

    private static int fail(ServerPlayer player, String key, Object... args) {
        tell(player, key, args);
        return 0;
    }
}
