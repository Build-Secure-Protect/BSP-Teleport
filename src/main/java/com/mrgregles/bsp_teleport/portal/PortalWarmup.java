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
 * A portal's warm-up (D118 to D124): the player waits in the field for the portal's seconds before going. The box at
 * the top of the screen counts down (D126, packet 13). Stepping out of the field, being hurt (per
 * {@code cancelOnDamage}), dying or logging out cancels, with the portal's cooldown and the arrival mark (D121). After
 * a chooser the player is held still where they chose instead (D120). At the end the portal's checks run again and the
 * coins are taken only then (D122).
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
        @Nullable
        final Hold hold;
        int ticksLeft;

        Pending(ServerPlayer player, PortalCoreBlockEntity core, Destination destination, int totalTicks, @Nullable Hold hold) {
            this.player = player;
            this.core = core;
            this.destination = destination;
            this.totalTicks = totalTicks;
            this.hold = hold;
            this.ticksLeft = totalTicks;
        }
    }

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    private PortalWarmup() {
    }

    public static boolean isWarming(ServerPlayer player) {
        return PENDING.containsKey(player.getUUID());
    }

    /** Starts the countdown; the portal's checks have passed and nothing has been charged yet. */
    static void start(ServerPlayer player, PortalCoreBlockEntity core, Destination destination, int seconds, @Nullable Hold hold) {
        Pending p = new Pending(player, core, destination, seconds * 20, hold);
        PENDING.put(player.getUUID(), p);
        if (hold != null) {
            player.setDeltaMovement(Vec3.ZERO);
        }
        BandPackets.sendWarmup(player, core.name(), p.totalTicks, p.ticksLeft, hold != null);
        Sounds.countdown(player, seconds, seconds);
    }

    /** Every server tick: count down, keep a chosen player still, cancel one who stepped out, and send the rest on. */
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
            if (p.hold != null) {
                Vec3 at = p.hold.at();
                if (player.position().distanceToSqr(at) > 1.0E-4 || player.level() != p.core.getLevel()) {
                    player.connection.teleport(at.x, at.y, at.z, p.hold.yaw(), p.hold.pitch());
                    player.setDeltaMovement(Vec3.ZERO);
                    player.fallDistance = 0f;
                }
            } else if (!inField(p.core, player)) {
                PENDING.remove(e.getKey());
                cancelled(player, p, "portal.warmup_left");
                continue;
            }
            p.ticksLeft--;
            if (p.ticksLeft <= 0) {
                PENDING.remove(e.getKey());
                BandPackets.sendWarmup(player, "", 0, 0, false);
                Portals.go(p.core, player, p.destination, null, true);
                continue;
            }
            if (p.ticksLeft % 20 == 0) {
                int secondsLeft = p.ticksLeft / 20;
                BandPackets.sendWarmup(player, p.core.name(), p.totalTicks, p.ticksLeft, p.hold != null);
                Sounds.countdown(player, secondsLeft, p.totalTicks / 20);
            }
        }
    }

    /** Whether the player still stands in the portal's opening, feet or head. */
    static boolean inField(PortalCoreBlockEntity core, ServerPlayer player) {
        if (player.level() != core.getLevel()) {
            return false;
        }
        BlockPos feet = player.blockPosition();
        return core.opening().contains(feet) || core.opening().contains(feet.above());
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
