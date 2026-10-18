package com.mrgregles.bsp_teleport.teleport;

import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.api.BspTeleportApi;
import com.mrgregles.bsp_teleport.api.Destination;
import com.mrgregles.bsp_teleport.api.event.TeleportStartEvent;
import com.mrgregles.bsp_teleport.compat.BspCore;
import com.mrgregles.bsp_teleport.data.Position;
import com.mrgregles.bsp_teleport.perm.Permissions;
import com.mrgregles.bsp_teleport.storage.Storage;
import com.mrgregles.bsp_teleport.util.Sounds;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Player teleports: the warm-up, the cancel rules, the cooldown and {@code /back}.
 *
 * <p>The forced moves in {@link TeleportService} skip all of this; everything a player asks for comes through here.
 */
public final class Teleports {
    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    private static final Map<UUID, Long> COOLDOWN_UNTIL = new HashMap<>();
    private static final Map<UUID, Position> BACK = new HashMap<>();
    /** The last teleport refused for a cooldown, so {@code /tpnow} can pay for it. */
    private static final Map<UUID, Pending> REFUSED = new HashMap<>();

    /**
     * @param label      what the player is going to, for the arrival message
     * @param beforeMove runs just before the move (charging a warp's cost); false aborts the teleport
     */
    private record Pending(ServerPlayer player, Destination destination, Component label, TeleportKind kind,
                           @Nullable BooleanSupplier beforeMove, Vec3 start, int ticksLeft) {
        Pending tick() {
            return new Pending(player, destination, label, kind, beforeMove, start, ticksLeft - 1);
        }
    }

    private Teleports() {
    }

    /** Starts a teleport for the player, with warm-up and cooldown unless they may skip them. */
    public static boolean start(ServerPlayer player, Destination destination, Component label, TeleportKind kind, @Nullable BooleanSupplier beforeMove) {
        UUID id = player.getUUID();
        if (player.isDeadOrDying() || player.isRemoved()) {
            return false;
        }
        if (PENDING.containsKey(id)) {
            tell(player, "already_waiting");
            return false;
        }
        long left = cooldownLeft(player);
        if (left > 0) {
            int price = TeleportConfig.getOr(TeleportConfig.SKIP_COOLDOWN_COINS, 0);
            if (price > 0) {
                REFUSED.put(id, new Pending(player, destination, label, kind, beforeMove, player.position(), 0));
                tell(player, "cooldown_pay", left, price);
            } else {
                tell(player, "cooldown", left);
            }
            return false;
        }
        long thief = BspCore.thiefBlockLeft(player);
        if (thief > 0) {
            tell(player, "blocked_thief", thief);
            return false;
        }
        if (!com.mrgregles.bsp_teleport.access.AccessService.checkServer(player, destination.server())) {
            return false;
        }
        // D39: during a raid every teleport still works, except one that would land near the totem being stolen,
        // on this server or on another (R6).
        if (kind != TeleportKind.RANDOM && BspCore.beingRaided(player)) {
            int distance = TeleportConfig.getOr(TeleportConfig.RAID_HOME_DISTANCE, 150);
            if (BspCore.nearRaid(player.server, player.getUUID(), destination.server(), destination.world(), destination.x(), destination.z(), distance)) {
                tell(player, "blocked_raid", distance);
                return false;
            }
        }
        if (!Rules.canTeleportFrom(player.serverLevel(), player.blockPosition(), player)) {
            tell(player, "blocked_out");
            return false;
        }
        if (!destination.crossServer()) {
            ServerLevel level = TeleportService.levelOf(player.server, destination.world());
            if (level == null) {
                tell(player, "no_world");
                return false;
            }
            if (!Rules.canTeleportTo(level, BlockPos.containing(destination.x(), destination.y(), destination.z()), player)) {
                tell(player, "blocked_in");
                return false;
            }
        }
        TeleportStartEvent event = new TeleportStartEvent(player, destination, kind.name());
        if (MinecraftForge.EVENT_BUS.post(event)) {
            if (!event.reason().isEmpty()) {
                player.displayClientMessage(Component.literal(event.reason()), false);
            }
            return false;
        }
        int warmup = Permissions.has(player, Permissions.BYPASS_WARMUP) ? 0 : TeleportConfig.getOr(TeleportConfig.WARMUP_SECONDS, 5);
        Pending pending = new Pending(player, destination, label, kind, beforeMove, player.position(), warmup * 20);
        if (warmup == 0) {
            finish(pending);
            return true;
        }
        if (TeleportConfig.getOr(TeleportConfig.CANCEL_ON_MOVE, true) && (player.isSprinting() || player.isSwimming() || player.isFallFlying())) {
            tell(player, "moving");
            return false;
        }
        PENDING.put(id, pending);
        player.displayClientMessage(Component.translatable("message.bsp_teleport.warmup", warmup), true);
        Sounds.countdown(player, warmup, warmup);
        return true;
    }

    /** {@code /tpnow}: pays the configured coins and runs the teleport the cooldown refused. */
    public static boolean payAndRetry(ServerPlayer player) {
        int price = TeleportConfig.getOr(TeleportConfig.SKIP_COOLDOWN_COINS, 0);
        Pending refused = REFUSED.get(player.getUUID());
        if (price <= 0 || refused == null) {
            tell(player, "tpnow.nothing");
            return false;
        }
        if (cooldownLeft(player) <= 0) {
            REFUSED.remove(player.getUUID());
            return start(player, refused.destination(), refused.label(), refused.kind(), refused.beforeMove());
        }
        if (BspCore.coins(player) < price) {
            tell(player, "tpnow.cannot_afford", price);
            return false;
        }
        if (!BspCore.pay(player, price)) {
            tell(player, "tpnow.cannot_afford", price);
            return false;
        }
        REFUSED.remove(player.getUUID());
        COOLDOWN_UNTIL.remove(player.getUUID());
        Storage.cooldownSet(player.getUUID(), 0);
        tell(player, "tpnow.paid", price);
        return start(player, refused.destination(), refused.label(), refused.kind(), refused.beforeMove());
    }

    public static boolean cancel(ServerPlayer player, String reasonKey) {
        if (PENDING.remove(player.getUUID()) == null) {
            return false;
        }
        tell(player, reasonKey);
        player.displayClientMessage(Component.translatable("message.bsp_teleport.cancelled"), true);
        Sounds.cancelled(player);
        return true;
    }

    public static boolean isWaiting(ServerPlayer player) {
        return PENDING.containsKey(player.getUUID());
    }

    /** Seconds left on the player's cooldown, 0 when none. */
    public static long cooldownLeft(ServerPlayer player) {
        Long until = COOLDOWN_UNTIL.get(player.getUUID());
        if (until == null || Permissions.has(player, Permissions.BYPASS_COOLDOWN)) {
            return 0;
        }
        return Math.max(0, (until - System.currentTimeMillis() + 999) / 1000);
    }

    @Nullable
    public static Position back(ServerPlayer player) {
        return BACK.get(player.getUUID());
    }

    /** What the shared store knows about the player, applied on join. */
    public static void restore(UUID uuid, long cooldownUntil, @Nullable Position back) {
        if (cooldownUntil > System.currentTimeMillis()) {
            COOLDOWN_UNTIL.put(uuid, cooldownUntil);
        }
        if (back != null) {
            BACK.put(uuid, back);
        }
    }

    /** Saves where the player is now as their {@code /back} target, unless this world is excluded by config. */
    public static void recordBack(ServerPlayer player) {
        Position here = Position.of(player);
        if (TeleportConfig.getOr(TeleportConfig.BACK_RESTRICTED_WORLDS, java.util.List.of()).contains(here.world())) {
            return;
        }
        BACK.put(player.getUUID(), here);
        Storage.backSet(player.getUUID(), here);
    }

    private static void finish(Pending pending) {
        ServerPlayer player = pending.player();
        if (player.isDeadOrDying() || player.hasDisconnected()) {
            return;
        }
        if (pending.beforeMove() != null && !pending.beforeMove().getAsBoolean()) {
            return;
        }
        if (!BspTeleportApi.get().teleport(player, pending.destination())) {
            tell(player, "failed");
            return;
        }
        int cooldown = BspCore.cooldownSeconds(player, TeleportConfig.getOr(TeleportConfig.COOLDOWN_SECONDS, 60));
        if (cooldown > 0) {
            long until = System.currentTimeMillis() + cooldown * 1000L;
            COOLDOWN_UNTIL.put(player.getUUID(), until);
            Storage.cooldownSet(player.getUUID(), until);
        }
        if (!pending.destination().crossServer()) {
            player.displayClientMessage(Component.translatable("message.bsp_teleport.teleported", pending.label()), false);
        }
    }

    // Events

    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PENDING.isEmpty()) {
            return;
        }
        boolean cancelOnMove = TeleportConfig.getOr(TeleportConfig.CANCEL_ON_MOVE, true);
        Iterator<Map.Entry<UUID, Pending>> it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Pending> entry = it.next();
            Pending p = entry.getValue();
            ServerPlayer player = p.player();
            if (player.isRemoved() || player.hasDisconnected() || player.isDeadOrDying()) {
                it.remove();
                continue;
            }
            if (cancelOnMove && player.position().distanceToSqr(p.start()) > 1.0) {
                it.remove();
                tell(player, "cancelled_move");
                player.displayClientMessage(Component.translatable("message.bsp_teleport.cancelled"), true);
                Sounds.cancelled(player);
                continue;
            }
            if (p.ticksLeft() <= 1) {
                it.remove();
                finish(p);
                continue;
            }
            Pending next = p.tick();
            entry.setValue(next);
            if (next.ticksLeft() % 20 == 0) {
                int secondsLeft = next.ticksLeft() / 20;
                player.displayClientMessage(Component.translatable("message.bsp_teleport.warmup", secondsLeft), true);
                Sounds.countdown(player, secondsLeft, TeleportConfig.getOr(TeleportConfig.WARMUP_SECONDS, 5));
            }
        }
    }

    /** Registered at LOWEST priority so a hurt that another mod cancels does not cancel the warm-up. */
    public static void onHurt(LivingHurtEvent event) {
        if (event.isCanceled() || event.getAmount() <= 0f) {
            return;
        }
        if (event.getEntity() instanceof ServerPlayer player && TeleportConfig.getOr(TeleportConfig.CANCEL_ON_DAMAGE, true)) {
            cancel(player, "cancelled_damage");
        }
    }

    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PENDING.remove(player.getUUID());
            recordBack(player);
        }
    }

    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        PENDING.remove(event.getEntity().getUUID());
        REFUSED.remove(event.getEntity().getUUID());
    }

    private static void tell(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(Component.translatable("message.bsp_teleport." + key, args), false);
    }
}
