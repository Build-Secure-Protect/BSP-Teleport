package com.mrgregles.bsp_teleport.portal;

import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.api.Destination;
import com.mrgregles.bsp_teleport.block.PortalCoreBlockEntity;
import com.mrgregles.bsp_teleport.network.BandPackets;
import com.mrgregles.bsp_teleport.util.Sounds;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A portal's warm-up (D118 to D124, D139): the player is held still where they touched the field (or where the chooser
 * held them) for the portal's seconds, then goes. The box at the top of the screen counts down (D126, packet 13).
 * Crouching, being hurt (per {@code cancelOnDamage}), dying or logging out cancels, with the portal's cooldown and the
 * arrival mark (D121). At the end the portal's checks run again and the coins are taken only then (D122).
 */
public final class PortalWarmup {
    /** Where the chooser held the player, so the hold carries on through the countdown (D120); null to let them move in the field. */
    public record Hold(Vec3 at, float yaw, float pitch) {
    }

    private static final class Pending {
        final ServerPlayer player;
        final PortalCoreBlockEntity core;
        final Destination destination;
        final int totalTicks;
        final Hold hold;
        /** The list entry when it leads to a warp, so the warp's open hours are checked again at the end (T82 audit); else null. */
        @Nullable
        final PortalDestination entry;
        int ticksLeft;
        /** D139: a crouch cancels only once the player has stood up after the start, so walking in crouched does not. */
        boolean armed;

        Pending(ServerPlayer player, PortalCoreBlockEntity core, Destination destination, int totalTicks, Hold hold, @Nullable PortalDestination entry) {
            this.player = player;
            this.core = core;
            this.destination = destination;
            this.totalTicks = totalTicks;
            this.hold = hold;
            this.entry = entry;
            this.ticksLeft = totalTicks;
            this.armed = !player.isShiftKeyDown();
        }
    }

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    private PortalWarmup() {
    }

    public static boolean isWarming(ServerPlayer player) {
        return PENDING.containsKey(player.getUUID());
    }

    /** Starts the countdown; the portal's checks have passed and nothing has been charged yet. */
    static void start(ServerPlayer player, PortalCoreBlockEntity core, Destination destination, int seconds, @Nullable Hold hold, @Nullable PortalDestination entry) {
        // B17, D139: a portal is one block thick, so a walking player would be through it at once: hold them where they
        // touched the field (or where the chooser held them).
        Hold at = hold != null ? hold : new Hold(player.position(), player.getYRot(), player.getXRot());
        Pending p = new Pending(player, core, destination, seconds * 20, at, entry);
        PENDING.put(player.getUUID(), p);
        player.setDeltaMovement(Vec3.ZERO);
        BandPackets.sendWarmup(player, core.name(), p.totalTicks, p.ticksLeft, true);
        Sounds.countdown(player, seconds, seconds);
    }

    /** Every server tick: count down, keep each player still, cancel one who crouched, and send the rest on. */
    public static void tick(MinecraftServer server) {
        if (PENDING.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, Pending> e : PENDING.entrySet()) {
            Pending p = e.getValue();
            ServerPlayer player = p.player;
            if (player.hasDisconnected() || player.isRemoved() || p.core.isRemoved()) {
                PENDING.remove(e.getKey());
                continue;
            }
            if (player.isDeadOrDying()) {
                PENDING.remove(e.getKey());
                BandPackets.sendWarmup(player, "", 0, 0, false);
                continue;
            }
            boolean crouching = player.isShiftKeyDown();
            if (!p.armed && !crouching) {
                p.armed = true;
            } else if (p.armed && crouching) {
                PENDING.remove(e.getKey());
                cancelled(player, p, "portal.warmup_left");
                continue;
            }
            Vec3 at = p.hold.at();
            if (player.position().distanceToSqr(at) > 1.0E-4 || player.level() != p.core.getLevel()) {
                // The player keeps looking where they like; only their place is held.
                player.connection.teleport(at.x, at.y, at.z, player.getYRot(), player.getXRot());
                player.setDeltaMovement(Vec3.ZERO);
                player.fallDistance = 0f;
            }
            p.ticksLeft--;
            if (p.ticksLeft <= 0) {
                PENDING.remove(e.getKey());
                BandPackets.sendWarmup(player, "", 0, 0, false);
                // D154 for portals: a warp that closed during the countdown refuses the move, nothing charged.
                Component closed = p.entry == null ? null : Portals.warpClosed(player, p.entry);
                if (closed != null) {
                    Portals.leftWithoutGoing(player, p.core);
                    player.displayClientMessage(closed, true);
                    Sounds.cancelled(player);
                    continue;
                }
                Portals.go(p.core, player, p.destination, null, true);
                continue;
            }
            if (p.ticksLeft % 20 == 0) {
                int secondsLeft = p.ticksLeft / 20;
                BandPackets.sendWarmup(player, p.core.name(), p.totalTicks, p.ticksLeft, true);
                Sounds.countdown(player, secondsLeft, p.totalTicks / 20);
            }
        }
    }

    /** Damage ends the warm-up when the config says so (D119). Registered at LOWEST so a cancelled hurt does not count. */
    public static void onHurt(LivingHurtEvent event) {
        if (event.isCanceled() || event.getAmount() <= 0f || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!TeleportConfig.getOr(TeleportConfig.CANCEL_ON_DAMAGE, true)) {
            return;
        }
        Pending p = PENDING.remove(player.getUUID());
        if (p != null) {
            cancelled(player, p, "portal.warmup_hurt");
        }
    }

    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        PENDING.remove(event.getEntity().getUUID());
    }

    /** The seconds left for a warming player, for tests; -1 when not warming. */
    public static int ticksLeft(ServerPlayer player) {
        Pending p = PENDING.get(player.getUUID());
        return p == null ? -1 : p.ticksLeft;
    }

    private static void cancelled(ServerPlayer player, Pending p, String key) {
        BandPackets.sendWarmup(player, "", 0, 0, false);
        Portals.leftWithoutGoing(player, p.core);
        player.displayClientMessage(Component.translatable("message.bsp_teleport." + key, p.core.name()), true);
        Sounds.cancelled(player);
    }
}
