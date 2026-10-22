package com.mrgregles.bsp_teleport.compat;

import com.mrgregles.bsp_teleport.BSPTeleport;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * LuckPerms through its own API (T70, D108): the Forge edition (the BSP build on plain Forge servers, or the Forge mod
 * on Mohist) puts {@code net.luckperms.api} on our class path, so any node can be asked, including
 * {@code bsp_teleport.warp.<name>} and {@code group.<name>}. The Bukkit plugin on Mohist lives in its own class loader;
 * {@code perm/Permissions} still reaches that one through Bukkit (D109). Compiled against the API only; the nested
 * {@code Impl} is the only class naming LuckPerms types and is loaded only once the API answered.
 */
public final class LuckPerms {
    private static final String PROVIDER = "net.luckperms.api.LuckPermsProvider";

    /** null until the first check; then whether the Forge edition's API answers. */
    private static Boolean present;

    private LuckPerms() {
    }

    public static boolean isPresent() {
        if (present == null) {
            present = detect();
            BSPTeleport.LOGGER.info(present ? "Permissions are answered by LuckPerms (Forge edition)"
                    : "LuckPerms' Forge edition is not on this server; permission nodes go through Bukkit when it is there");
        }
        return present;
    }

    private static boolean detect() {
        try {
            Class.forName(PROVIDER, false, LuckPerms.class.getClassLoader());
            return Impl.ready();
        } catch (ClassNotFoundException | LinkageError | IllegalStateException e) {
            return false;
        }
    }

    /**
     * LuckPerms' answer for an online player: true or false when the node is set (directly, by a group or a wildcard),
     * false when it is not set at all, empty when LuckPerms is absent or does not know the player yet.
     */
    public static Optional<Boolean> check(ServerPlayer player, String node) {
        if (!isPresent()) {
            return Optional.empty();
        }
        try {
            return Impl.check(player, node);
        } catch (RuntimeException | LinkageError e) {
            BSPTeleport.LOGGER.warn("LuckPerms permission check for {} failed: {}", node, e.toString());
            return Optional.empty();
        }
    }

    /** Forgets the cached answer, for tests. */
    public static void reset() {
        present = null;
    }

    private static final class Impl {
        static boolean ready() {
            net.luckperms.api.LuckPermsProvider.get();
            return true;
        }

        static Optional<Boolean> check(ServerPlayer player, String node) {
            net.luckperms.api.model.user.User user = net.luckperms.api.LuckPermsProvider.get().getUserManager().getUser(player.getUUID());
            if (user == null) {
                return Optional.empty();
            }
            net.luckperms.api.util.Tristate answer = user.getCachedData().getPermissionData().checkPermission(node);
            if (answer == net.luckperms.api.util.Tristate.UNDEFINED && node.startsWith("group.")) {
                // A rank rule (AccessChecks asks group.<name>): the player's groups, inherited ones included.
                String group = node.substring("group.".length());
                if (user.getPrimaryGroup().equalsIgnoreCase(group)) {
                    return Optional.of(true);
                }
                return Optional.of(user.getInheritedGroups(user.getQueryOptions()).stream().anyMatch(g -> g.getName().equalsIgnoreCase(group)));
            }
            return Optional.of(answer.asBoolean());
        }
    }
}
