package com.mrgregles.bsp_teleport.api.event;

import com.mrgregles.bsp_teleport.api.Destination;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.Cancelable;
import net.minecraftforge.eventbus.api.Event;

/**
 * Fired on the Forge bus when a player asks for a teleport, after the cooldown and region checks and before the
 * warm-up. Cancel it to refuse; set {@link #setReason} to tell the player why (a plain sentence).
 *
 * <p>Forced moves through {@code BspTeleportApi} do not fire this.
 */
@Cancelable
public class TeleportStartEvent extends Event {
    private final ServerPlayer player;
    private final Destination destination;
    private final String kind;
    private String reason = "";

    public TeleportStartEvent(ServerPlayer player, Destination destination, String kind) {
        this.player = player;
        this.destination = destination;
        this.kind = kind;
    }

    public ServerPlayer player() {
        return player;
    }

    public Destination destination() {
        return destination;
    }

    /** HOME, WARP, SPAWN, BACK, RANDOM, REQUEST or OTHER. */
    public String kind() {
        return kind;
    }

    public String reason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
