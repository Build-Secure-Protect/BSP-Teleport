package com.mrgregles.bsp_teleport.spike;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.compat.BspCore;
import io.netty.buffer.Unpooled;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.server.permission.PermissionAPI;
import net.minecraftforge.server.permission.events.PermissionGatherEvent;
import net.minecraftforge.server.permission.nodes.PermissionNode;
import net.minecraftforge.server.permission.nodes.PermissionTypes;

import java.lang.reflect.Method;

/**
 * Throwaway test commands for the two things that must be proven on the real network (Velocity, Mohist,
 * Proxy Compatible Forge) before the design is fixed. Delete once both are answered.
 *
 * <ul>
 *   <li>{@code /bsptp spike connect <server>}: ask the proxy to move the player.</li>
 *   <li>{@code /bsptp spike perm <node>}: read a LuckPerms permission and say which route answered.</li>
 * </ul>
 */
public final class SpikeCommands {
    /** The proxy plugin-message channel. Velocity answers it while {@code bungee-plugin-message-channel} is on. */
    private static final ResourceLocation PROXY_CHANNEL = new ResourceLocation("bungeecord", "main");

    /** A node registered with Forge, to see whether LuckPerms on Mohist answers Forge's permission API at all. */
    private static final PermissionNode<Boolean> FORGE_NODE = new PermissionNode<>(
            BSPTeleport.MODID, "spike", PermissionTypes.BOOLEAN, (player, uuid, context) -> false);

    private SpikeCommands() {
    }

    public static void onPermissionNodes(PermissionGatherEvent.Nodes event) {
        event.addNodes(FORGE_NODE);
    }

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("bsptp")
                .then(Commands.literal("spike")
                        .requires(SpikeCommands::allowed)
                        .then(Commands.literal("connect")
                                .then(Commands.argument("server", StringArgumentType.word())
                                        .executes(SpikeCommands::connect)))
                        .then(Commands.literal("perm")
                                .then(Commands.argument("node", StringArgumentType.word())
                                        .executes(SpikeCommands::perm)))));
    }

    private static boolean allowed(CommandSourceStack source) {
        return source.getEntity() instanceof Player player ? BspCore.isAdmin(player) : source.hasPermission(2);
    }

    private static int connect(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String server = StringArgumentType.getString(context, "server");

        // The proxy protocol uses Java's DataOutput strings, not Minecraft's length-prefixed ones.
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("Connect");
        out.writeUTF(server);
        player.connection.send(new ClientboundCustomPayloadPacket(
                PROXY_CHANNEL, new FriendlyByteBuf(Unpooled.wrappedBuffer(out.toByteArray()))));

        BSPTeleport.LOGGER.info("[spike] Sent Connect '{}' for {}", server, player.getGameProfile().getName());
        context.getSource().sendSuccess(() -> Component.literal(
                "Asked the proxy to move you to '" + server + "'. If nothing happens in a few seconds, it failed."), false);
        return 1;
    }

    private static int perm(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String node = StringArgumentType.getString(context, "node");

        say(context, "Bukkit, from the player: " + bukkitFromPlayer(player, node));
        say(context, "Bukkit, by lookup: " + bukkitByLookup(player, node));
        say(context, "Forge API (" + FORGE_NODE.getNodeName() + "): " + forgeApi(player));
        return 1;
    }

    private static void say(CommandContext<CommandSourceStack> context, String line) {
        BSPTeleport.LOGGER.info("[spike] {}", line);
        context.getSource().sendSuccess(() -> Component.literal(line), false);
    }

    /** Mohist adds {@code getBukkitEntity()} to every entity, so no Bukkit class has to be found by name. */
    private static String bukkitFromPlayer(ServerPlayer player, String node) {
        try {
            Object bukkitPlayer = player.getClass().getMethod("getBukkitEntity").invoke(player);
            return node + " = " + hasPermission(bukkitPlayer, node);
        } catch (Throwable t) {
            return "failed (" + t + ")";
        }
    }

    private static String bukkitByLookup(ServerPlayer player, String node) {
        try {
            Class<?> bukkit = Class.forName("org.bukkit.Bukkit", true, player.getClass().getClassLoader());
            Object bukkitPlayer = bukkit.getMethod("getPlayer", java.util.UUID.class).invoke(null, player.getUUID());
            if (bukkitPlayer == null) {
                return "failed (Bukkit does not know this player)";
            }
            return node + " = " + hasPermission(bukkitPlayer, node);
        } catch (Throwable t) {
            return "failed (" + t + ")";
        }
    }

    private static Object hasPermission(Object bukkitPlayer, String node) throws ReflectiveOperationException {
        Method method = bukkitPlayer.getClass().getMethod("hasPermission", String.class);
        method.setAccessible(true);
        return method.invoke(bukkitPlayer, node);
    }

    private static String forgeApi(ServerPlayer player) {
        try {
            return String.valueOf(PermissionAPI.getPermission(player, FORGE_NODE));
        } catch (Throwable t) {
            return "failed (" + t + ")";
        }
    }
}
