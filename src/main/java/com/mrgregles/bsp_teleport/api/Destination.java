package com.mrgregles.bsp_teleport.api;

/**
 * Where a teleport ends.
 *
 * @param server  the target server's name in the proxy, or empty for this server
 * @param world   the dimension id, for example {@code minecraft:overworld}
 * @param message shown to the player on arrival; empty for none
 * @param protect seconds the player cannot be hurt after arriving; -1 uses the config value
 */
public record Destination(String server, String world, double x, double y, double z, float yaw, float pitch, String message, int protect) {
    public static final String SEPARATOR = ";";

    /** A destination that keeps the player's current facing. */
    public Destination(String server, String world, double x, double y, double z, String message) {
        this(server, world, x, y, z, Float.NaN, Float.NaN, message, -1);
    }

    public Destination(String server, String world, double x, double y, double z, float yaw, float pitch, String message) {
        this(server, world, x, y, z, yaw, pitch, message, -1);
    }

    public Destination withProtect(int seconds) {
        return new Destination(server, world, x, y, z, yaw, pitch, message, seconds);
    }

    public boolean crossServer() {
        return !server.isEmpty();
    }

    /** Whether the destination sets the player's facing (otherwise they keep their own). */
    public boolean hasFacing() {
        return !Float.isNaN(yaw) && !Float.isNaN(pitch);
    }

    /** {@code server;world;x;y;z;yaw;pitch;protect;message}: the wire form used in cross-server messages. The message comes last so it may hold separators. */
    public String serialize() {
        return String.join(SEPARATOR, server, world, Double.toString(x), Double.toString(y), Double.toString(z),
                Float.toString(yaw), Float.toString(pitch), Integer.toString(protect), message);
    }

    public static Destination parse(String text) {
        String[] parts = text.split(SEPARATOR, 9);
        if (parts.length < 8) {
            throw new IllegalArgumentException("Not a destination: " + text);
        }
        return new Destination(parts[0], parts[1], Double.parseDouble(parts[2]), Double.parseDouble(parts[3]),
                Double.parseDouble(parts[4]), Float.parseFloat(parts[5]), Float.parseFloat(parts[6]), parts.length > 8 ? parts[8] : "",
                Integer.parseInt(parts[7]));
    }
}
