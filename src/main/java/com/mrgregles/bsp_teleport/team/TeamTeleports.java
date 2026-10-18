package com.mrgregles.bsp_teleport.team;

import com.mrgregles.bsp_teleport.compat.BspCore;
import com.mrgregles.bsp_teleport.data.Position;
import com.mrgregles.bsp_teleport.network.Messenger;
import com.mrgregles.bsp_teleport.teleport.TeleportKind;
import com.mrgregles.bsp_teleport.teleport.Teleports;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Team teleports (BSP-Core's T-1): a player goes to a teammate. Joining the team is the consent, so the teammate is
 * not asked; they get one line saying who is coming.
 *
 * <p>Every move runs through {@link Teleports#start}, so it has everything a bracelet teleport has: the cooldown,
 * the warm-up with its cancels, the access check, the zone flags and both raid rules (D91). A teammate on another
 * server is found the way {@code /tpto} finds a player: this server asks theirs for their position over Redis and
 * lands them with the normal cross-server move once the answer is in; without Redis, or without an answer in
 * {@link #ANSWER_SECONDS}, the player is told and nothing moves. BSP-Core's TELEPORT key and the band's GO both end
 * up in {@link #go(ServerPlayer, UUID)}.
 */
public final class TeamTeleports {
    /** How long a cross-server position ask may take before the player is told nobody answered. */
    public static final int ANSWER_SECONDS = 5;

    /** Cross-server asks waiting for the target's position, by the asker. */
    private static final Map<UUID, Ask> WAITING = new HashMap<>();

    private record Ask(String targetName, String targetServer, long expiresAt) {
    }

    /** A teammate as the band and the handler see them: {@code server} is a proxy name, "" when offline or unknown. */
    public record Mate(UUID uuid, String name, boolean leader, boolean onlineHere, String server, long seenMs) {
        public boolean online() {
            return onlineHere || !server.isEmpty();
        }
    }

    private TeamTeleports() {
    }

    /** Moves the player to a teammate, telling them why when it cannot. */
    public static boolean go(ServerPlayer player, UUID target) {
        Optional<BspCore.TeamInfo> team = BspCore.teamOf(player.server, player.getUUID());
        if (team.isEmpty()) {
            return fail(player, "team.not_in_team");
        }
        if (target.equals(player.getUUID())) {
            return fail(player, "team.self");
        }
        Mate mate = null;
        for (Mate m : BspCore.teamMembers(player.server, team.get().id())) {
            if (m.uuid().equals(target)) {
                mate = m;
                break;
            }
        }
        if (mate == null) {
            return fail(player, "team.not_teammate");
        }
        return go(player, mate);
    }

    public static boolean go(ServerPlayer player, Mate mate) {
        if (Teleports.isWaiting(player) || WAITING.containsKey(player.getUUID())) {
            return fail(player, "already_waiting");
        }
        ServerPlayer here = player.server.getPlayerList().getPlayer(mate.uuid());
        if (here != null) {
            boolean started = Teleports.start(player, Position.of(here).toDestination(""), here.getDisplayName(), TeleportKind.TEAM, null);
            if (started) {
                tell(here, "team.incoming", player.getDisplayName());
            }
            return started;
        }
        if (mate.server().isEmpty()) {
            return fail(player, "team.offline", mate.name());
        }
        if (!Messenger.enabled() || !Messenger.askPosition(player, mate.name(), "team")) {
            return fail(player, "team.unreachable", mate.name(), mate.server());
        }
        WAITING.put(player.getUUID(), new Ask(mate.name(), mate.server(), System.currentTimeMillis() + ANSWER_SECONDS * 1000L));
        tell(player, "team.asking", mate.name(), mate.server());
        return true;
    }

    /** The teammate's server answered a position ask: the rules run here, then the cross-server move. */
    public static void onPosition(ServerPlayer asker, String targetName, Position where) {
        Ask ask = WAITING.remove(asker.getUUID());
        if (ask == null || !ask.targetName().equalsIgnoreCase(targetName)) {
            return;
        }
        boolean started = Teleports.start(asker, where.toDestination(""), Component.literal(targetName), TeleportKind.TEAM, null);
        if (started) {
            Messenger.tell(targetName, Component.translatable("message.bsp_teleport.team.incoming", asker.getGameProfile().getName()).getString());
        }
    }

    /** Whether the player is waiting for a teammate's server to answer. */
    public static boolean isAsking(ServerPlayer player) {
        return WAITING.containsKey(player.getUUID());
    }

    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || WAITING.isEmpty() || event.getServer().getTickCount() % 20 != 0) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Ask>> it = WAITING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Ask> e = it.next();
            if (e.getValue().expiresAt() > now) {
                continue;
            }
            it.remove();
            ServerPlayer asker = event.getServer().getPlayerList().getPlayer(e.getKey());
            if (asker != null) {
                tell(asker, "team.no_answer", e.getValue().targetName());
            }
        }
    }

    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        WAITING.remove(event.getEntity().getUUID());
    }

    /** The teammate with this id in a member list, or null. */
    @Nullable
    public static Mate find(List<Mate> mates, UUID uuid) {
        for (Mate m : mates) {
            if (m.uuid().equals(uuid)) {
                return m;
            }
        }
        return null;
    }

    /** "just now", "5 min ago", "2 h ago" or "3 d ago" for the band's offline rows; "" when unknown. */
    public static String ago(long thenMs, long nowMs) {
        if (thenMs <= 0) {
            return "";
        }
        long seconds = Math.max(0, (nowMs - thenMs) / 1000);
        if (seconds < 60) {
            return "just now";
        }
        if (seconds < 3600) {
            return seconds / 60 + " min ago";
        }
        if (seconds < 86400) {
            return seconds / 3600 + " h ago";
        }
        return seconds / 86400 + " d ago";
    }

    private static boolean fail(ServerPlayer player, String key, Object... args) {
        tell(player, key, args);
        return false;
    }

    private static void tell(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(Component.translatable("message.bsp_teleport." + key, args), false);
    }
}
