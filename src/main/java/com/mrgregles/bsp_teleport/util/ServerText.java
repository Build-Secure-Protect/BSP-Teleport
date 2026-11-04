package com.mrgregles.bsp_teleport.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mrgregles.bsp_teleport.BSPTeleport;
import net.minecraft.network.chat.Component;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.IllegalFormatException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Messages that may go to the server console. The console has no copy of this mod's language file, so a plain
 * translatable message shows there as its key; these carry the English text from the jar as the fallback, while players
 * still see their own language.
 */
public final class ServerText {
    private static final Map<String, String> ENGLISH = new ConcurrentHashMap<>();
    private static volatile boolean loaded;

    private ServerText() {
    }

    /** {@code message.bsp_teleport.<key>} with its English text as the fallback. */
    public static Component msg(String key, Object... args) {
        String full = "message.bsp_teleport." + key;
        String english = english(full);
        String fallback;
        try {
            Object[] plain = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                plain[i] = args[i] instanceof Component c ? c.getString() : String.valueOf(args[i]);
            }
            fallback = english == null ? full : String.format(english, plain);
        } catch (IllegalFormatException e) {
            fallback = english;
        }
        return Component.translatableWithFallback(full, fallback, args);
    }

    /** The English text of a key, or null when the jar's language file does not have it. */
    static String english(String key) {
        if (!loaded) {
            load();
        }
        return ENGLISH.get(key);
    }

    private static synchronized void load() {
        if (loaded) {
            return;
        }
        try (InputStream in = ServerText.class.getResourceAsStream("/assets/bsp_teleport/lang/en_us.json")) {
            if (in != null) {
                JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                json.entrySet().forEach(e -> ENGLISH.put(e.getKey(), e.getValue().getAsString()));
            }
        } catch (Exception e) {
            BSPTeleport.LOGGER.warn("Could not read the English messages for the console: {}", e.toString());
        }
        loaded = true;
    }
}
