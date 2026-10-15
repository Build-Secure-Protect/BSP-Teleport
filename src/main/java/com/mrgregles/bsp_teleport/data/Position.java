package com.mrgregles.bsp_teleport.data;

import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.api.Destination;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;

/** A saved place in the network: server, world and exact position with facing. */
public record Position(String server, String world, double x, double y, double z, float yaw, float pitch) {

    public static Position of(ServerPlayer player) {
        return new Position(TeleportConfig.serverName(), player.level().dimension().location().toString(),
                player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
    }

    public boolean isLocal() {
        return server.equals(TeleportConfig.serverName());
    }

    public BlockPos blockPos() {
        return BlockPos.containing(x, y, z);
    }

    /** The level on this server, or null when the position is elsewhere or its world is missing. */
    @Nullable
    public ServerLevel level(MinecraftServer server) {
        if (!isLocal()) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(world);
        return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }

    public Destination toDestination(String message) {
        return new Destination(isLocal() ? "" : server, world, x, y, z, yaw, pitch, message);
    }

    /** {@code x, y, z} rounded, plus the world and server when they differ from the player's. */
    public String describe() {
        String where = (int) Math.floor(x) + ", " + (int) Math.floor(y) + ", " + (int) Math.floor(z);
        return isLocal() ? where + " (" + world + ")" : where + " (" + world + " on " + server + ")";
    }

    /** {@code server;world;x;y;z;yaw;pitch}, for the database and messages. */
    public String serialize() {
        return String.join(";", server, world, Double.toString(x), Double.toString(y), Double.toString(z), Float.toString(yaw), Float.toString(pitch));
    }

    public static Position parse(String text) {
        String[] p = text.split(";", 7);
        if (p.length < 7) {
            throw new IllegalArgumentException("Not a position: " + text);
        }
        return new Position(p[0], p[1], Double.parseDouble(p[2]), Double.parseDouble(p[3]), Double.parseDouble(p[4]), Float.parseFloat(p[5]), Float.parseFloat(p[6]));
    }

    public static Position fromDestination(Destination d) {
        return new Position(d.server().isEmpty() ? TeleportConfig.serverName() : d.server(), d.world(), d.x(), d.y(), d.z(),
                d.hasFacing() ? d.yaw() : 0f, d.hasFacing() ? d.pitch() : 0f);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Server", server);
        tag.putString("World", world);
        tag.putDouble("X", x);
        tag.putDouble("Y", y);
        tag.putDouble("Z", z);
        tag.putFloat("Yaw", yaw);
        tag.putFloat("Pitch", pitch);
        return tag;
    }

    public static Position load(CompoundTag tag) {
        return new Position(tag.getString("Server"), tag.getString("World"), tag.getDouble("X"), tag.getDouble("Y"),
                tag.getDouble("Z"), tag.getFloat("Yaw"), tag.getFloat("Pitch"));
    }
}
