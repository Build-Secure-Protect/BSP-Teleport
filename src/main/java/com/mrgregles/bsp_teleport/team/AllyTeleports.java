package com.mrgregles.bsp_teleport.team;

import com.mrgregles.bsp_teleport.api.Destination;
import com.mrgregles.bsp_teleport.compat.BspCore;
import com.mrgregles.bsp_teleport.network.Messenger;
import com.mrgregles.bsp_teleport.request.TeleportRequests;
import com.mrgregles.bsp_teleport.teleport.TeleportKind;
import com.mrgregles.bsp_teleport.teleport.Teleports;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ally teleports (BSP-Core's A-1, D167 to D170): a player goes to a player of an allied team. Allies trust each other
 * less than teammates, so the ally is asked first, through the {@code /tpa} request (D168) with a fixed
 * {@link #ASK_SECONDS} (D169); a player who ignores requests is not asked.
 *
 * <p>After an accept it is a team teleport ({@link TeleportKind#ALLY} gets the team rules in {@link Teleports#start}):
 * cooldown, warm-up, access, zone flags, the thief block and no landing near any running steal, cross-server through
 * the request's position answer. No price. Neither player may be running a steal, and BSP-Core's TeamEvents only fire
 * on the server where the alliance changed, so {@link #allowed} runs again on the accept and when the warm-up ends.
 */
public final class AllyTeleports {
    /** How long the ally has to accept (D169). */
    public static final int ASK_SECONDS = 30;

    /** Ally moves in their warm-up, by the asker: the ally they go to. */
    private static final Map<UUID, UUID> MOVING = new HashMap<>();
    /** Audit: asks still waiting for an answer, by the asker, so pressing ASK again does not ask the same ally again. */
    private static final Map<UUID, Asked> ASKED = new HashMap<>();

    private record Asked(UUID ally, long until) {
    }

    /**
     * An ally as the band and the handler see them: {@code server} is a proxy name, "" when offline; {@code dimension}
     * is "" until their first position was shared; {@code pos} is null when their team does not share coordinates.
     */
    public record Ally(UUID uuid, String name, String teamName, int colour, boolean onlineHere, String server, String dimension,
                       @Nullable BlockPos pos, long seenMs) {
        public boolean online() {
            return onlineHere || !server.isEmpty();
        }
    }

    private AllyTeleports() {
    }

    /** BSP-Core's TELEPORT on an ALLIES row, or the band's ASK: sends the ally the question, or says why not. */
    public static boolean ask(ServerPlayer player, UUID target) {
        MinecraftServer server = player.server;
        if (target.equals(player.getUUID())) {
            return fail(player, "team.self");
        }
        Ally ally = find(BspCore.allies(server, player.getUUID()), target);
        if (ally == null || !BspCore.allied(server, player.getUUID(), target)) {
            return fail(player, "ally.not_ally");
        }
        long now = System.currentTimeMillis();
        Asked asked = ASKED.get(player.getUUID());
        if (asked != null && asked.ally().equals(target) && asked.until() > now) {
            return fail(player, "ally.already_asked", ally.name());
        }
        String refusal = refusal(server, player.getUUID(), target);
        if (refusal != null) {
            return fail(player, refusal, ally.name());
        }
        if (Teleports.isWaiting(player) || TeamTeleports.isAsking(player)) {
            return fail(player, "already_waiting");
        }
        if (!ally.online()) {
            return fail(player, "team.offline", ally.name());
        }
        if (!ally.onlineHere() && !Messenger.enabled()) {
            return fail(player, "team.unreachable", ally.name(), ally.server());
        }
        String team = BspCore.teamOf(server, player.getUUID()).map(BspCore.TeamInfo::name).orElse("");
        if (!TeleportRequests.sendAlly(player, ally.name(), team)) {
            return false;
        }
        ASKED.put(player.getUUID(), new Asked(target, now + ASK_SECONDS * 1000L));
        return true;
    }

    /** The asker's ask was answered (accepted, denied or refused): they may ask again. */
    public static void answered(UUID asker) {
        ASKED.remove(asker);
    }

    /**
     * Why an ally move between these two may not happen now, as a message key taking the ally's name, or null:
     * not allied any more, or either of them running a steal.
     */
    @Nullable
    public static String refusal(MinecraftServer server, UUID asker, UUID ally) {
        if (!BspCore.allied(server, asker, ally)) {
            return "ally.ended";
        }
        if (BspCore.isStealing(server, asker)) {
            return "ally.you_stealing";
        }
        if (BspCore.isStealing(server, ally)) {
            return "ally.they_stealing";
        }
        return null;
    }

    /** Whether the two may still make the move; tells the asker why not. */
    private static boolean allowed(ServerPlayer asker, UUID ally, String allyName) {
        String refusal = refusal(asker.server, asker.getUUID(), ally);
        if (refusal != null) {
            tell(asker, refusal, allyName);
            return false;
        }
        return true;
    }

    /**
     * The ally accepted: the asker goes to {@code destination} (where the ally stood when they accepted) with the team
     * rules, and the alliance is checked again here and once more when the warm-up ends.
     */
    public static boolean start(ServerPlayer asker, UUID ally, String allyName, Destination destination) {
        answered(asker.getUUID());
        if (!allowed(asker, ally, allyName)) {
            return false;
        }
        UUID id = asker.getUUID();
        Teleports.BeforeMove again = then -> {
            MOVING.remove(id);
            then.accept(allowed(asker, ally, allyName)
                    ? new com.mrgregles.bsp_teleport.access.AccessChecks.Paid(id, "Ally teleport", 0, 0, List.of()) : null);
        };
        boolean started = Teleports.start(asker, destination, Component.literal(allyName), TeleportKind.ALLY, again);
        if (started && Teleports.waitingKind(asker) == TeleportKind.ALLY) {
            MOVING.put(id, ally);
        }
        return started;
    }

    /**
     * An alliance changed on this server: warm-ups and waiting asks between players who are no longer allies end.
     * The same check runs on accept and at the end of every warm-up, which also covers changes made on other servers.
     */
    public static void recheck(MinecraftServer server) {
        Iterator<Map.Entry<UUID, UUID>> it = MOVING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, UUID> e = it.next();
            ServerPlayer asker = server.getPlayerList().getPlayer(e.getKey());
            if (asker == null || Teleports.waitingKind(asker) != TeleportKind.ALLY) {
                it.remove();
                continue;
            }
            if (!BspCore.allied(server, e.getKey(), e.getValue())) {
                it.remove();
                Teleports.cancel(asker, "ally.ended_cancel");
            }
        }
        TeleportRequests.dropAllyRequests(server);
    }

    @Nullable
    public static Ally find(List<Ally> allies, UUID uuid) {
        for (Ally a : allies) {
            if (a.uuid().equals(uuid)) {
                return a;
            }
        }
        return null;
    }

    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        MOVING.remove(event.getEntity().getUUID());
        ASKED.remove(event.getEntity().getUUID());
    }

    private static boolean fail(ServerPlayer player, String key, Object... args) {
        tell(player, key, args);
        return false;
    }

    private static void tell(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(Component.translatable("message.bsp_teleport." + key, args), false);
    }
}
