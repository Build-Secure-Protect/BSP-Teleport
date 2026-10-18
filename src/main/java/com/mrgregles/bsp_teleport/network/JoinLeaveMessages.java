package com.mrgregles.bsp_teleport.network;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.TeleportConfig;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Hides this server's own "X joined the game" and "X left the game" lines when {@code network.suppressJoinLeaveMessages}
 * is on (request VA-T1 from BSP-Velocity Additions: the proxy announces network joins and leaves once, so a server switch
 * must not print a leave on one backend and a join on the other).
 *
 * <p>How: the mixins in {@code mixin/} mark the start and end of {@code PlayerList.placeNewPlayer} and
 * {@code ServerGamePacketListenerImpl.onDisconnect}, the two methods that broadcast those lines on Forge and on Mohist.
 * While one of them runs for a player, {@link #shouldDrop(Component)} drops a broadcast whose plain text is exactly that
 * player's vanilla join or leave line ({@link JoinLeaveMatcher}). Every other line, including other mods' and plugins'
 * messages during login, passes. Nothing else about login changes. With the switch off nothing is touched.
 *
 * <p>Logins and disconnects run on the server thread, one at a time, so a single "current player" is enough; the end
 * hook clears it. A login that throws leaves its name set until the next login starts, which only matters if a line
 * with exactly that player's join text were broadcast meanwhile.
 */
public final class JoinLeaveMessages {
    private static ServerPlayer current;
    private static int dropped;

    private JoinLeaveMessages() {
    }

    /** Whether the switch is on. */
    public static boolean enabled() {
        return TeleportConfig.getOr(TeleportConfig.SUPPRESS_JOIN_LEAVE, false);
    }

    /** Called at the start of {@code placeNewPlayer} and {@code onDisconnect}. */
    public static void begin(ServerPlayer player) {
        current = player;
    }

    /** Called when those methods return. */
    public static void end() {
        current = null;
    }

    /**
     * Whether a system broadcast is the current player's vanilla join or leave line and the switch is on. Called by the
     * mixin at the head of {@code PlayerList.broadcastSystemMessage}.
     */
    public static boolean shouldDrop(Component message) {
        ServerPlayer player = current;
        if (player == null || message == null || !enabled()) {
            return false;
        }
        List<String> names = new ArrayList<>(2);
        names.add(player.getGameProfile().getName());
        Component display = player.getDisplayName();
        if (display != null) {
            names.add(display.getString());
        }
        Language lang = Language.getInstance();
        boolean drop = JoinLeaveMatcher.matches(message.getString(), names,
                lang.getOrDefault("multiplayer.player.joined"),
                lang.getOrDefault("multiplayer.player.joined.renamed"),
                lang.getOrDefault("multiplayer.player.left"));
        if (drop) {
            dropped++;
            BSPTeleport.LOGGER.debug("Hidden join/leave line: {}", message.getString());
        }
        return drop;
    }

    /** How many lines have been hidden since start (for tests and diagnostics). */
    public static int dropped() {
        return dropped;
    }
}
