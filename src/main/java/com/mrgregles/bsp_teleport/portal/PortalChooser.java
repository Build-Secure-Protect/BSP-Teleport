package com.mrgregles.bsp_teleport.portal;

import com.mrgregles.bsp_teleport.block.PortalCoreBlockEntity;
import com.mrgregles.bsp_teleport.network.BandPackets;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds a player still while they choose between a portal's destinations (D98, D99). The server keeps them on the
 * cell they stepped into, the client screen swallows every key but Esc, damage or 30 s of silence cancels, and a
 * cancel applies the portal's cooldown and the arrival mark so stepping back out does not fire the field again.
 */
public final class PortalChooser {
    /** How long a choice may stay open before it counts as cancelled (D98). */
    public static final long TIMEOUT_MS = 30_000L;

    /** One entry a player may pick: the destination and whether the portal may send them there right now. */
    public record Candidate(int index, PortalDestination destination, PortalChoice.State state) {
    }

    private record Pending(ServerPlayer player, PortalCoreBlockEntity core, List<Candidate> candidates, long startedMs, Vec3 at, float yaw, float pitch) {
    }

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    private PortalChooser() {
    }

    public static boolean isChoosing(ServerPlayer player) {
        return PENDING.containsKey(player.getUUID());
    }

    /** Opens the chooser: the player is held where they stand and the client shows the options. */
    public static void start(ServerPlayer player, PortalCoreBlockEntity core, List<Candidate> candidates, PortalChoice choice) {
        PENDING.put(player.getUUID(), new Pending(player, core, candidates, System.currentTimeMillis(), player.position(), player.getYRot(), player.getXRot()));
        player.setDeltaMovement(Vec3.ZERO);
        BandPackets.sendChoice(player, choice);
    }

    /** The client's answer: an index from the options, or -1 for cancel. */
    public static void answer(ServerPlayer player, int index) {
        Pending p = PENDING.remove(player.getUUID());
        if (p == null) {
            return;
        }
        if (index < 0) {
            cancelled(player, p, "portal.choose_cancelled");
            return;
        }
        for (Candidate c : p.candidates()) {
            if (c.index() == index) {
                if (c.state() != PortalChoice.State.OK) {
                    // A greyed entry pressed anyway: the same refusal the screen showed, and the player stays held.
                    PENDING.put(player.getUUID(), p);
                    return;
                }
                BandPackets.sendChoice(player, PortalChoice.CLOSE);
                Portals.travel(p.core(), player, c.destination());
                return;
            }
        }
        // An index the card never offered (a stale or forged packet): ignored, the player stays held until Esc or the timeout.
        PENDING.put(player.getUUID(), p);
    }

    /** Every server tick: hold each choosing player in place and time out the silent ones. */
    public static void tick(MinecraftServer server) {
        if (PENDING.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Pending> e : PENDING.entrySet()) {
            Pending p = e.getValue();
            ServerPlayer player = p.player();
            if (player.hasDisconnected() || player.isRemoved() || p.core().isRemoved()) {
                PENDING.remove(e.getKey());
                continue;
            }
            if (now - p.startedMs() > TIMEOUT_MS) {
                PENDING.remove(e.getKey());
                cancelled(player, p, "portal.choose_timeout");
                continue;
            }
            if (player.position().distanceToSqr(p.at()) > 1.0E-4 || player.level() != p.core().getLevel()) {
                player.connection.teleport(p.at().x, p.at().y, p.at().z, p.yaw(), p.pitch());
                player.setDeltaMovement(Vec3.ZERO);
                player.fallDistance = 0f;
            }
        }
    }

    /** Damage ends the choice (D98), so a player held in a portal is never a still target. */
    public static void onHurt(LivingHurtEvent event) {
        if (event.isCanceled() || event.getAmount() <= 0f || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Pending p = PENDING.remove(player.getUUID());
        if (p != null) {
            cancelled(player, p, "portal.choose_hurt");
        }
    }

    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        PENDING.remove(event.getEntity().getUUID());
    }

    private static void cancelled(ServerPlayer player, Pending p, String key) {
        BandPackets.sendChoice(player, PortalChoice.CLOSE);
        Portals.leftWithoutGoing(player, p.core());
        player.displayClientMessage(Component.translatable("message.bsp_teleport." + key, p.core().name()), true);
    }

    /** The cell the player is held on, for tests and the arrival mark. */
    public static BlockPos heldAt(ServerPlayer player) {
        Pending p = PENDING.get(player.getUUID());
        return p == null ? null : BlockPos.containing(p.at());
    }
}
