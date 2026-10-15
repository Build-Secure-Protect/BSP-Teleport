package com.mrgregles.bsp_teleport.util;

import com.mrgregles.bsp_teleport.TeleportConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/** The few sounds the mod plays, all to one player, all switched by {@code sounds.enabled}. */
public final class Sounds {
    private Sounds() {
    }

    /** One tick of the warm-up countdown; the pitch climbs as the seconds run out. */
    public static void countdown(ServerPlayer player, int secondsLeft, int total) {
        float pitch = total <= 1 ? 1.2f : 0.8f + 0.6f * (1f - (float) secondsLeft / total);
        play(player, SoundEvents.NOTE_BLOCK_PLING.get(), 0.6f, pitch);
    }

    public static void arrived(ServerPlayer player) {
        play(player, SoundEvents.ENDERMAN_TELEPORT, 0.8f, 1.1f);
    }

    public static void cancelled(ServerPlayer player) {
        play(player, SoundEvents.ITEM_BREAK, 0.7f, 0.9f);
    }

    public static void request(ServerPlayer player) {
        play(player, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.8f, 1.3f);
    }

    private static void play(ServerPlayer player, SoundEvent sound, float volume, float pitch) {
        if (TeleportConfig.getOr(TeleportConfig.SOUNDS, true)) {
            player.playNotifySound(sound, SoundSource.PLAYERS, volume, pitch);
        }
    }
}
