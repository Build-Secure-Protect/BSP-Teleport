package com.mrgregles.bsp_teleport.api;

/**
 * Where a teleport ends.
 *
 * @param server  the target server's name in the proxy, or empty for this server
 * @param world   the dimension id, for example {@code minecraft:overworld}
 * @param message shown to the player on arrival; empty for none
 */
public record Destination(String server, String world, double x, double y, double z, String message) {
    public static final String SEPARATOR = ";";

    public boolean crossServer() {
        return !server.isEmpty();
    }

    /** {@code server;world;x;y;z;message}: the wire form used in cross-server messages. */
    public String serialize() {
        return String.join(SEPARATOR, server, world, Double.toString(x), Double.toString(y), Double.toString(z), message);
    }

    public static Destination parse(String text) {
        String[] parts = text.split(SEPARATOR, 6);
        if (parts.length < 5) {
            throw new IllegalArgumentException("Not a destination: " + text);
        }
        return new Destination(parts[0], parts[1], Double.parseDouble(parts[2]), Double.parseDouble(parts[3]),
                Double.parseDouble(parts[4]), parts.length > 5 ? parts[5] : "");
    }
}
