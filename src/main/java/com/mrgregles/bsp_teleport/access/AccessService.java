package com.mrgregles.bsp_teleport.access;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.api.BspTeleportApi;
import com.mrgregles.bsp_teleport.compat.RegionGuard;
import com.mrgregles.bsp_teleport.data.Position;
import com.mrgregles.bsp_teleport.data.TeleportData;
import com.mrgregles.bsp_teleport.data.Warp;
import com.mrgregles.bsp_teleport.item.AccessTokenItem;
import com.mrgregles.bsp_teleport.network.BandPackets;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import com.mrgregles.bsp_teleport.perm.Permissions;
import com.mrgregles.bsp_teleport.storage.Storage;
import com.mrgregles.bsp_teleport.teleport.TeleportActions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The server access system (D67 to D78, D83, D84, D87): who may enter which server or region, the grants behind it, the
 * token assignments and the restricted-server list shared by the network, the checks on every move and on login, and
 * the rent clock. Off unless {@code access.enabled}.
 */
public final class AccessService {
    /** Online players' grants in network mode (local mode reads the world save). */
    private static final Map<UUID, List<AccessGrant>> GRANTS = new HashMap<>();
    private static final Map<Integer, TokenAssignment> TOKENS = new LinkedHashMap<>();
    private static final Set<String> RESTRICTED = new LinkedHashSet<>();
    /** Per server: players who call it home, hold full access, rent it. */
    private static Map<String, int[]> counts = new HashMap<>();
    /** Per player, per grant key: the last warning stage sent (D78). */
    private static final Map<UUID, Map<String, Integer>> WARNED = new HashMap<>();

    private AccessService() {
    }

    public static boolean enabled() {
        return TeleportConfig.getOr(TeleportConfig.ACCESS_ENABLED, false);
    }

    private static boolean network() {
        return Storage.enabled();
    }

    // What this server knows

    public static List<AccessGrant> grantsOf(MinecraftServer server, UUID uuid) {
        if (network()) {
            List<AccessGrant> list = GRANTS.get(uuid);
            return list == null ? List.of() : new ArrayList<>(list);
        }
        return TeleportData.get(server).accessOf(uuid);
    }

    public static Set<String> restricted(MinecraftServer server) {
        return network() ? new LinkedHashSet<>(RESTRICTED) : new LinkedHashSet<>(TeleportData.get(server).restrictedServers());
    }

    public static boolean isRestricted(MinecraftServer server, String name) {
        for (String s : restricted(server)) {
            if (s.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    public static Map<Integer, TokenAssignment> tokens(MinecraftServer server) {
        return network() ? new LinkedHashMap<>(TOKENS) : new LinkedHashMap<>(TeleportData.get(server).accessTokens());
    }

    @Nullable
    public static TokenAssignment tokenFor(MinecraftServer server, AccessGrant.Kind kind, String target) {
        for (TokenAssignment t : tokens(server).values()) {
            if (t.kind() == kind && t.target().equalsIgnoreCase(target)) {
                return t;
            }
        }
        return null;
    }

    /** Home, full and rented counts per restricted server (D85). */
    public static Map<String, int[]> counts() {
        return counts;
    }

    @Nullable
    public static String mainServer(ServerPlayer player) {
        return AccessRules.mainServer(grantsOf(player.server, player.getUUID()));
    }

    // Checks (D68)

    /** Whether the player may enter the server; always when the system is off, the server is open, or the player is an admin. */
    public static boolean mayEnter(ServerPlayer player, String server) {
        if (!enabled() || server.isEmpty() || Permissions.isAdmin(player)) {
            return true;
        }
        return AccessRules.mayEnter(grantsOf(player.server, player.getUUID()), restricted(player.server), server, System.currentTimeMillis());
    }

    /** {@link #mayEnter}, telling the player what they need when the answer is no (D81's popup comes with T60). */
    public static boolean checkServer(ServerPlayer player, String server) {
        if (mayEnter(player, server)) {
            return true;
        }
        deny(player, AccessGrant.Kind.SERVER, server);
        return false;
    }

    /** Region access for Region Guard's exemption (D83): any region at the spot the player holds a live grant for. */
    public static boolean hasRegionAccess(ServerPlayer player, List<String> regionIds) {
        if (!enabled()) {
            return false;
        }
        List<AccessGrant> grants = grantsOf(player.server, player.getUUID());
        long now = System.currentTimeMillis();
        for (String id : regionIds) {
            if (AccessRules.has(grants, AccessGrant.Kind.REGION, TeleportConfig.serverName() + ":" + id, now)) {
                return true;
            }
        }
        return false;
    }

    /** The chat lines and the popup (D81); a player with no main server yet gets the choice instead (D82). */
    public static void deny(ServerPlayer player, AccessGrant.Kind kind, String target) {
        boolean noMain = mainServer(player) == null;
        if (kind == AccessGrant.Kind.SERVER) {
            tell(player, "access.denied_server", target);
            if (noMain) {
                tell(player, "access.no_main");
            }
        } else {
            tell(player, "access.denied_region");
        }
        TokenAssignment token = tokenFor(player.server, kind, target);
        if (token != null) {
            String name = "Access Token " + TokenAssignment.roman(token.token());
            if (token.hint().isEmpty()) {
                tell(player, "access.denied_token", name);
            } else {
                tell(player, "access.denied_hint", name, token.hint());
            }
        }
        if (kind == AccessGrant.Kind.SERVER && noMain && !restricted(player.server).isEmpty()) {
            showChoose(player);
        } else {
            BandPackets.sendPopup(player, new AccessPopup(AccessPopup.Type.DENIED, target, token == null ? 0 : token.token(),
                    token == null ? 0 : token.rentHours(), token == null ? "" : token.hint(), noMain, List.of()));
        }
    }

    /** The choose-main-server popup (D82): every restricted server with its counts. */
    public static void showChoose(ServerPlayer player) {
        List<AccessPopup.ServerChoice> choices = new ArrayList<>();
        for (String server : restricted(player.server)) {
            int[] c = counts.getOrDefault(server, new int[3]);
            TokenAssignment token = tokenFor(player.server, AccessGrant.Kind.SERVER, server);
            choices.add(new AccessPopup.ServerChoice(server, token == null ? 0 : token.token(), c[0], c[1], c[2]));
        }
        BandPackets.sendPopup(player, new AccessPopup(AccessPopup.Type.CHOOSE, "", 0, 0, "", mainServer(player) == null, choices));
    }

    public static void handle(ServerPlayer player, AccessAction action, String arg) {
        switch (action) {
            case CHOOSE_OPEN -> showChoose(player);
            case CHOOSE -> chooseMain(player, arg);
        }
    }

    // Grants (D71, D75, D87)

    /** Applies a grant; returns the lang key of the refusal, or null when it went through. */
    @Nullable
    public static String grant(MinecraftServer server, UUID uuid, AccessGrant.Kind kind, String target, AccessGrant.Tier tier, int hours) {
        long now = System.currentTimeMillis();
        List<AccessGrant> grants = grantsOf(server, uuid);
        String refusal = AccessRules.refuse(grants, kind, target, tier, now);
        if (refusal != null) {
            return refusal;
        }
        AccessGrant grant = new AccessGrant(uuid, kind, target, tier, tier == AccessGrant.Tier.RENT ? now + AccessRules.rentMs(hours) : 0, now);
        List<AccessGrant> merged = AccessRules.apply(grants, grant, now);
        store(server, uuid, merged);
        AccessGrant saved = AccessRules.find(merged, kind, target);
        if (network() && saved != null) {
            Storage.accessSaved(saved);
        }
        WARNED.remove(uuid);
        refreshCounts(server);
        ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        if (online != null) {
            sync(online);
        }
        return null;
    }

    public static boolean revoke(MinecraftServer server, UUID uuid, AccessGrant.Kind kind, String target) {
        List<AccessGrant> grants = grantsOf(server, uuid);
        AccessGrant existing = AccessRules.find(grants, kind, target);
        if (existing == null) {
            return false;
        }
        List<AccessGrant> left = new ArrayList<>(grants);
        left.remove(existing);
        store(server, uuid, left);
        if (network()) {
            Storage.accessDeleted(uuid, kind, existing.target());
        }
        refreshCounts(server);
        ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        if (online != null) {
            sync(online);
        }
        return true;
    }

    private static void store(MinecraftServer server, UUID uuid, List<AccessGrant> grants) {
        TeleportData.get(server).replaceAccess(uuid, grants);
        if (network()) {
            GRANTS.put(uuid, new ArrayList<>(grants));
        }
    }

    /** Right-click on a token (D75): grants what the token and the stack say, then uses one up. */
    public static void useToken(ServerPlayer player, ItemStack stack) {
        if (!(stack.getItem() instanceof AccessTokenItem item)) {
            return;
        }
        if (!enabled()) {
            tell(player, "access.disabled");
            return;
        }
        TokenAssignment token = tokens(player.server).get(item.index());
        if (token == null) {
            tell(player, "access.token_unassigned", TokenAssignment.roman(item.index()));
            return;
        }
        AccessGrant.Tier tier = AccessTokenItem.tierOf(stack);
        int hours = AccessTokenItem.hoursOf(stack) > 0 ? AccessTokenItem.hoursOf(stack) : token.rentHours();
        String refusal = grant(player.server, player.getUUID(), token.kind(), token.target(), tier, hours);
        if (refusal != null) {
            tell(player, refusal, token.target());
            return;
        }
        if (!player.isCreative()) {
            stack.shrink(1);
        }
        player.level().playSound(null, player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6F, 1.4F);
        if (tier == AccessGrant.Tier.RENT) {
            tell(player, "access.granted_rent", token.target(), AccessGrant.describeLeft(AccessRules.rentMs(hours)));
        } else {
            tell(player, "access.granted", token.target(), Component.translatable("message.bsp_teleport.access.tier_full"));
        }
        sync(player);
    }

    /** T80: what each online player was last sent, so an unchanged sync is skipped. */
    private static final java.util.Map<java.util.UUID, AccessSync> LAST_SENT = new java.util.concurrent.ConcurrentHashMap<>();

    /** Sends the player what the client shows (D79): their grants, every token assignment, the restricted servers. */
    public static void sync(ServerPlayer player) {
        long now = System.currentTimeMillis();
        List<AccessSync.Grant> grants = new ArrayList<>();
        for (AccessGrant g : grantsOf(player.server, player.getUUID())) {
            if (g.active(now)) {
                grants.add(new AccessSync.Grant(g.kind(), g.target(), g.tier(), g.tier() == AccessGrant.Tier.RENT ? g.remainingMs(now) : 0));
            }
        }
        List<AccessSync.Count> countList = new ArrayList<>();
        for (String server : restricted(player.server)) {
            int[] c = counts.getOrDefault(server, new int[3]);
            countList.add(new AccessSync.Count(server, c[0], c[1], c[2]));
        }
        AccessSync sync = new AccessSync(enabled(), grants, new ArrayList<>(tokens(player.server).values()), new ArrayList<>(restricted(player.server)), countList);
        // T80: the minute tick and every access notice called this for everyone; an unchanged answer is not sent again.
        if (sync.equals(LAST_SENT.put(player.getUUID(), sync))) {
            return;
        }
        BandPackets.sendAccess(player, sync);
    }

    private static void syncAll(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            sync(p);
        }
    }

    /** The player's one free choice (D71). */
    public static boolean chooseMain(ServerPlayer player, String server) {
        if (!enabled()) {
            return fail(player, "access.disabled");
        }
        if (!isRestricted(player.server, server)) {
            return fail(player, "access.main_unknown", server);
        }
        String refusal = grant(player.server, player.getUUID(), AccessGrant.Kind.SERVER, properName(player.server, server), AccessGrant.Tier.MAIN, 0);
        if (refusal != null) {
            return fail(player, refusal, mainServer(player));
        }
        tell(player, "access.main_set", server);
        return true;
    }

    /** The restricted server's name with its own capitalisation. */
    public static String properName(MinecraftServer server, String name) {
        for (String s : restricted(server)) {
            if (s.equalsIgnoreCase(name)) {
                return s;
            }
        }
        return name;
    }

    // Settings shared by the network (D70, D74)

    public static void setRestricted(MinecraftServer server, String name, boolean on) {
        TeleportData.get(server).setRestricted(name, on);
        if (network()) {
            if (on) {
                RESTRICTED.add(name);
            } else {
                RESTRICTED.removeIf(s -> s.equalsIgnoreCase(name));
            }
            Storage.restrictedSet(name, on);
        }
        syncAll(server);
    }

    public static void setToken(MinecraftServer server, TokenAssignment token) {
        TeleportData.get(server).setToken(token);
        if (network()) {
            TOKENS.put(token.token(), token);
            Storage.tokenSaved(token);
        }
        syncAll(server);
    }

    public static void clearToken(MinecraftServer server, int token) {
        TeleportData.get(server).deleteToken(token);
        if (network()) {
            TOKENS.remove(token);
            Storage.tokenDeleted(token);
        }
        syncAll(server);
    }

    /** The shared table was re-read. */
    public static void replaceSettings(List<TokenAssignment> tokens, List<String> servers) {
        TOKENS.clear();
        tokens.forEach(t -> TOKENS.put(t.token(), t));
        RESTRICTED.clear();
        RESTRICTED.addAll(servers);
        MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            syncAll(server);
        }
    }

    public static void replaceCounts(Map<String, int[]> loaded) {
        counts = loaded;
        MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            syncAll(server);
        }
    }

    private static void refreshCounts(MinecraftServer server) {
        if (network()) {
            Storage.reloadAccessCounts();
            return;
        }
        Map<String, int[]> out = new HashMap<>();
        long now = System.currentTimeMillis();
        for (List<AccessGrant> list : TeleportData.get(server).allAccess().values()) {
            for (AccessGrant g : list) {
                if (g.kind() == AccessGrant.Kind.SERVER && g.active(now)) {
                    out.computeIfAbsent(g.target(), k -> new int[3])[g.tier() == AccessGrant.Tier.MAIN ? 0 : g.tier() == AccessGrant.Tier.FULL ? 1 : 2]++;
                }
            }
        }
        counts = out;
        syncAll(server);
    }

    /** ACCESS_CHANGED from another server. */
    public static void onNotice(MinecraftServer server, String uuidText) {
        if (uuidText.isEmpty()) {
            Storage.reloadAccessSettings();
            Storage.reloadAccessCounts();
            return;
        }
        try {
            UUID uuid = UUID.fromString(uuidText);
            ServerPlayer online = server.getPlayerList().getPlayer(uuid);
            if (online != null) {
                Storage.loadAccess(uuid, grants -> {
                    GRANTS.put(uuid, grants);
                    sync(online);
                });
            }
        } catch (IllegalArgumentException ignored) {
        }
        Storage.reloadAccessCounts();
    }

    // Arrivals and the lobby (D68, D69)

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (network()) {
            UUID uuid = player.getUUID();
            Storage.loadAccess(uuid, grants -> {
                GRANTS.put(uuid, grants);
                if (!player.hasDisconnected()) {
                    sync(player);
                    checkArrival(player);
                }
            });
        } else {
            sync(player);
            checkArrival(player);
        }
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_SENT.remove(event.getEntity().getUUID());
        GRANTS.remove(event.getEntity().getUUID());
        WARNED.remove(event.getEntity().getUUID());
    }

    /** A player who arrived without access (by any route, or whose rent ran out while away) goes to the lobby (D68). */
    private static void checkArrival(ServerPlayer player) {
        String here = TeleportConfig.serverName();
        if (mayEnter(player, here)) {
            return;
        }
        tell(player, "access.bounced", here);
        deny(player, AccessGrant.Kind.SERVER, here);
        sendTo(player, lobby(player.server), true);
    }

    /** The network's main spawn: the config value, else the global spawn warp's server, else empty for this server. */
    public static String lobby(MinecraftServer server) {
        String configured = TeleportConfig.getOr(TeleportConfig.ACCESS_LOBBY, "");
        if (!configured.isEmpty()) {
            return configured;
        }
        String globalWarp = TeleportConfig.getOr(TeleportConfig.GLOBAL_SPAWN_WARP, "");
        if (!globalWarp.isEmpty()) {
            Warp warp = TeleportData.get(server).warp(globalWarp);
            if (warp != null) {
                return warp.position().server();
            }
        }
        return "";
    }

    /** A forced move to a server's spawn: here through the local spawn, elsewhere through that server's saved spawn, else wherever it puts them. */
    private static void sendTo(ServerPlayer player, String server, boolean lobbyFallback) {
        if (server.isEmpty() || server.equals(TeleportConfig.serverName())) {
            Position spawn = TeleportActions.spawnPosition(player);
            if (spawn != null) {
                BspTeleportApi.get().teleport(player, spawn.toDestination(""));
            } else if (lobbyFallback) {
                BSPTeleport.LOGGER.warn("No spawn to send {} to on {}", player.getGameProfile().getName(), TeleportConfig.serverName());
            }
            return;
        }
        Storage.spawnOf(server, spawn -> {
            if (player.hasDisconnected()) {
                return;
            }
            if (spawn != null) {
                BspTeleportApi.get().teleport(player, spawn.toDestination(""));
            } else {
                BspTeleportApi.get().sendToServer(player, server);
            }
        });
    }

    // The rent clock (D78)

    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !enabled()) {
            return;
        }
        MinecraftServer server = event.getServer();
        int tick = server.getTickCount();
        if (tick % 1200 == 0) {
            refreshCounts(server);
        }
        if (tick % 20 != 0) {
            return;
        }
        long now = System.currentTimeMillis();
        for (ServerPlayer player : new ArrayList<>(server.getPlayerList().getPlayers())) {
            for (AccessGrant g : grantsOf(server, player.getUUID())) {
                if (g.tier() != AccessGrant.Tier.RENT) {
                    continue;
                }
                long left = g.remainingMs(now);
                String key = g.kind() + ":" + g.target();
                if (left <= 0) {
                    revoke(server, player.getUUID(), g.kind(), g.target());
                    tell(player, "access.ran_out", g.target());
                    if (g.kind() == AccessGrant.Kind.SERVER && g.target().equalsIgnoreCase(TeleportConfig.serverName()) && !Permissions.isAdmin(player)) {
                        String main = mainServer(player);
                        sendTo(player, main == null ? lobby(server) : main, true);
                    }
                    continue;
                }
                int stage = AccessRules.warningStage(left);
                Map<String, Integer> warned = WARNED.computeIfAbsent(player.getUUID(), k -> new HashMap<>());
                if (stage > warned.getOrDefault(key, 0)) {
                    warned.put(key, stage);
                    player.displayClientMessage(Component.translatable("message.bsp_teleport.access.ends_in", g.target(), AccessGrant.describeLeft(left)), true);
                }
            }
        }
    }

    // Lifecycle

    public static void onServerStarted(ServerStartedEvent event) {
        RegionGuard.registerAccessExemption();
        if (!network()) {
            refreshCounts(event.getServer());
        }
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        GRANTS.clear();
        WARNED.clear();
        TOKENS.clear();
        RESTRICTED.clear();
    }

    /** The band's ACCESS list (D80): one line per restricted server with the player's state and the token that grants it. */
    public static List<com.mrgregles.bsp_teleport.band.BandData.AccessEntry> bandEntries(ServerPlayer player) {
        List<com.mrgregles.bsp_teleport.band.BandData.AccessEntry> out = new ArrayList<>();
        if (!enabled()) {
            return out;
        }
        long now = System.currentTimeMillis();
        List<AccessGrant> grants = grantsOf(player.server, player.getUUID());
        for (String server : restricted(player.server)) {
            AccessGrant g = AccessRules.find(grants, AccessGrant.Kind.SERVER, server);
            String state = g == null || !g.active(now) ? "none" : g.tier().name().toLowerCase(java.util.Locale.ROOT);
            String left = g != null && g.tier() == AccessGrant.Tier.RENT && g.active(now) ? AccessGrant.describeLeft(g.remainingMs(now)) : "";
            TokenAssignment token = tokenFor(player.server, AccessGrant.Kind.SERVER, server);
            out.add(new com.mrgregles.bsp_teleport.band.BandData.AccessEntry(server, state, left, token == null ? 0 : token.token()));
        }
        return out;
    }

    /** "main server", "full access" or "rented, 6d 5h left". */
    public static Component describeTier(AccessGrant g, long now) {
        return switch (g.tier()) {
            case MAIN -> Component.translatable("message.bsp_teleport.access.tier_main");
            case FULL -> Component.translatable("message.bsp_teleport.access.tier_full");
            case RENT -> Component.translatable("message.bsp_teleport.access.tier_rent", AccessGrant.describeLeft(g.remainingMs(now)));
        };
    }

    public static Collection<AccessGrant> all(MinecraftServer server, UUID uuid) {
        return grantsOf(server, uuid);
    }

    private static void tell(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(Component.translatable("message.bsp_teleport." + key, args), false);
    }

    private static boolean fail(ServerPlayer player, String key, Object... args) {
        tell(player, key, args);
        return false;
    }
}
