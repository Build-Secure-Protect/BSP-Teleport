package com.mrgregles.bsp_teleport.network;

import com.google.gson.Gson;

import java.util.HashMap;
import java.util.Map;

/**
 * One cross-server message, carried as JSON.
 *
 * <p>{@code target} is a player name (case-insensitive), a server name, or {@link #ALL}, according to {@code to}.
 */
public final class Message {
    public static final String ALL = "*";
    private static final Gson GSON = new Gson();

    public enum To { PLAYER, SERVER, ALL }

    public enum Type {
        PLAYER_LIST, PLAYER_LIST_REQUEST,
        TP_REQUEST, TP_RESPONSE,
        /** Move a named player, forced, to a destination (admin tools and /tpall). */
        TP_FORCE,
        /** Everyone on the target server(s), except "exclude", is moved to a destination. */
        TP_ALL,
        /** Everyone on the target server is sent to the server named "to". */
        SEND_ALL,
        /** Where is this player? Answered with POSITION_RESULT to the asking player. */
        POSITION_REQUEST, POSITION_RESULT,
        HOMES_CHANGED, HOMES_WIPED, WARPS_CHANGED,
        /** A portal row was written or deleted: re-read the shared table. */
        PORTALS_CHANGED,
        /** Open, close, lock or unlock a portal on the target server: "id", "command". */
        PORTAL_DO,
        RTP_REQUEST, RTP_RESULT,
        TELL,
        CUSTOM
    }

    public Type type;
    public String source = "";
    public To to = To.ALL;
    public String target = ALL;
    public Map<String, String> data = new HashMap<>();

    public Message() {
    }

    public Message(Type type, To to, String target) {
        this.type = type;
        this.to = to;
        this.target = target;
    }

    public Message put(String key, String value) {
        data.put(key, value == null ? "" : value);
        return this;
    }

    public String get(String key) {
        String v = data.get(key);
        return v == null ? "" : v;
    }

    public boolean flag(String key) {
        return "true".equals(data.get(key));
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public static Message fromJson(String json) {
        Message m = GSON.fromJson(json, Message.class);
        if (m == null || m.type == null || m.to == null || m.target == null) {
            throw new IllegalArgumentException("Incomplete message: " + json);
        }
        if (m.data == null) {
            m.data = new HashMap<>();
        }
        if (m.source == null) {
            m.source = "";
        }
        return m;
    }
}
