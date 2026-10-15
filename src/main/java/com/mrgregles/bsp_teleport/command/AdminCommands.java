package com.mrgregles.bsp_teleport.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.api.BspTeleportApi;
import com.mrgregles.bsp_teleport.network.Messenger;
import com.mrgregles.bsp_teleport.network.PlayerDirectory;
import com.mrgregles.bsp_teleport.perm.Permissions;
import com.mrgregles.bsp_teleport.spike.SpikeCommands;
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
                .then(Commands.literal("players").executes(AdminCommands::players))
                .then(Commands.literal("send")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("server", StringArgumentType.word())
                                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(PlayerDirectory.servers(), b))
                                        .executes(AdminCommands::send))))
                .then(SpikeCommands.tree()));
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        src.sendSuccess(() -> Component.literal("This server: " + BspTeleportApi.get().serverName()), false);
        src.sendSuccess(() -> Component.literal("Storage: " + Storage.status()), false);
        src.sendSuccess(() -> Component.literal("Messaging: " + Messenger.status()), false);
        return 1;
    }

    private static int players(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.literal("Other servers: " + PlayerDirectory.describe()), false);
        return 1;
    }

    private static int send(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
        String server = StringArgumentType.getString(ctx, "server");
        boolean ok = BspTeleportApi.get().sendToServer(player, server);
        ctx.getSource().sendSuccess(() -> Component.literal((ok ? "Sent " : "Could not send ") + player.getGameProfile().getName() + " to " + server), true);
        return ok ? 1 : 0;
    }
}
