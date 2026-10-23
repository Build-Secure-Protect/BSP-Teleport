package com.mrgregles.bsp_teleport.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.api.BspTeleportApi;
import com.mrgregles.bsp_teleport.network.Messenger;
import com.mrgregles.bsp_teleport.network.PlayerDirectory;
import com.mrgregles.bsp_teleport.perm.Permissions;
import com.mrgregles.bsp_teleport.storage.Storage;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.server.permission.events.PermissionGatherEvent;
import net.minecraftforge.server.permission.nodes.PermissionNode;
import net.minecraftforge.server.permission.nodes.PermissionTypes;

/** {@code /bsptp}: admin tools and the network status. Plain text, no lang keys: these are for the owner. */
public final class AdminCommands {
    /** Registered with Forge so permission mods that use its API see the node; LuckPerms on Mohist is asked through Bukkit. */
    private static final PermissionNode<Boolean> ADMIN_NODE = new PermissionNode<>(
            BSPTeleport.MODID, "admin", PermissionTypes.BOOLEAN, (player, uuid, context) -> false);

    private AdminCommands() {
    }

    public static void onPermissionNodes(PermissionGatherEvent.Nodes event) {
        event.addNodes(ADMIN_NODE);
    }

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(AccessCommands.mainServer());
        event.getDispatcher().register(Commands.literal("bsptp")
                .requires(src -> src.getEntity() instanceof ServerPlayer p ? com.mrgregles.bsp_teleport.admin.AdminService.mayOpen(p) : src.hasPermission(2))
                .then(Commands.literal("admin").executes(ctx -> {
                    com.mrgregles.bsp_teleport.admin.AdminService.open(ctx.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(Commands.literal("reload").executes(ctx -> {
                    com.mrgregles.bsp_teleport.TeleportConfig.reload(ctx.getSource().getServer());
                    ctx.getSource().sendSuccess(() -> Component.translatable("message.bsp_teleport.admin.reloaded"), true);
                    return 1;
                }))
                .then(Commands.literal("status").executes(AdminCommands::status))
                .then(Commands.literal("setnetworkspawn").executes(AdminCommands::setNetworkSpawn))
                .then(PortalCommands.portal())
                .then(AccessCommands.access())
                .then(PortalCommands.portalWarp())
                .then(Commands.literal("players").executes(AdminCommands::players))
                .then(Commands.literal("send")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("server", StringArgumentType.word())
                                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(PlayerDirectory.servers(), b))
                                        .executes(AdminCommands::send)))));
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        src.sendSuccess(() -> Component.literal("This server: " + BspTeleportApi.get().serverName()), false);
        src.sendSuccess(() -> Component.literal("Storage: " + Storage.status(src.getServer())), false);
        src.sendSuccess(() -> Component.literal("Messaging: " + Messenger.status()), false);
        return 1;
    }

    private static int players(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.literal("Other servers: " + PlayerDirectory.describe()), false);
        return 1;
    }

    /** D140, D144: saves the network spawn warp where the admin stands; warns when this server needs access. */
    private static int setNetworkSpawn(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = com.mrgregles.bsp_teleport.data.TeleportData.normalise(com.mrgregles.bsp_teleport.teleport.NetworkSpawn.warpName());
        com.mrgregles.bsp_teleport.data.Warp old = com.mrgregles.bsp_teleport.data.TeleportData.get(player.server).warp(name);
        com.mrgregles.bsp_teleport.data.Warp warp = old == null ? new com.mrgregles.bsp_teleport.data.Warp(name, com.mrgregles.bsp_teleport.data.Position.of(player), 0, false)
                : old.withPosition(com.mrgregles.bsp_teleport.data.Position.of(player));
        com.mrgregles.bsp_teleport.data.TeleportData.get(player.server).setWarp(warp);
        com.mrgregles.bsp_teleport.storage.Storage.warpSaved(warp);
        ctx.getSource().sendSuccess(() -> Component.translatable("message.bsp_teleport.networkspawn.set", name), true);
        if (com.mrgregles.bsp_teleport.access.AccessService.enabled()
                && com.mrgregles.bsp_teleport.access.AccessService.restricted(player.server).stream().anyMatch(s -> s.equalsIgnoreCase(com.mrgregles.bsp_teleport.TeleportConfig.serverName()))) {
            ctx.getSource().sendSuccess(() -> Component.translatable("message.bsp_teleport.networkspawn.restricted"), false);
        }
        return 1;
    }

    private static int send(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
        String server = StringArgumentType.getString(ctx, "server");
        if (com.mrgregles.bsp_teleport.TeleportConfig.singlePlayer()) {
            ctx.getSource().sendFailure(Component.translatable("message.bsp_teleport.single_player"));
            return 0;
        }
        boolean ok = BspTeleportApi.get().sendToServer(player, server);
        ctx.getSource().sendSuccess(() -> Component.literal((ok ? "Sent " : "Could not send ") + player.getGameProfile().getName() + " to " + server), true);
        return ok ? 1 : 0;
    }
}
