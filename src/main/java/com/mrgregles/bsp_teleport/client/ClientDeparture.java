package com.mrgregles.bsp_teleport.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.MovementInputUpdateEvent;

/**
 * B21: just before a move to another server the server asks the client to stop walking (packet 14), so no stream of
 * movement packets is still going when the proxy's switch puts the connection back into its login state. The keys are
 * off for the ticks given, with a hard limit, and come back by themselves.
 */
public final class ClientDeparture {
    private static long until;

    private ClientDeparture() {
    }

    /** Packet 14: movement keys off for this many ticks; 0 turns them on again. */
    public static void hold(int ticks) {
        Minecraft mc = Minecraft.getInstance();
        long now = mc.level == null ? 0 : mc.level.getGameTime();
        until = ticks <= 0 ? 0 : now + Math.min(ticks, 160);
    }

    public static void onMovementInput(MovementInputUpdateEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (until == 0 || mc.level == null) {
            return;
        }
        if (mc.level.getGameTime() >= until) {
            until = 0;
            return;
        }
        var input = event.getInput();
        input.up = input.down = input.left = input.right = input.jumping = input.shiftKeyDown = false;
        input.forwardImpulse = 0f;
        input.leftImpulse = 0f;
    }

    public static void clear() {
        until = 0;
    }
}
