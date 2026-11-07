package com.mrgregles.bsp_teleport.access;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.compat.BspCore;
import com.mrgregles.bsp_teleport.compat.RegionGuard;
import com.mrgregles.bsp_teleport.data.Position;
import com.mrgregles.bsp_teleport.data.TeleportData;
import com.mrgregles.bsp_teleport.data.Warp;
import com.mrgregles.bsp_teleport.item.AccessTokenItem;
import com.mrgregles.bsp_teleport.network.BandPackets;
import com.mrgregles.bsp_teleport.network.PlayerDirectory;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import com.mrgregles.bsp_teleport.perm.Permissions;
import com.mrgregles.bsp_teleport.storage.Storage;
import com.mrgregles.bsp_teleport.teleport.TeleportActions;
import com.mrgregles.bsp_teleport.teleport.TeleportService;
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

    /**
     * D162: on a network sharing the database the restricted list decides, so a server whose own switch was left off
     * still checks, bounces and shows the HUD once any server is restricted. Without the database, the switch alone.
     */
    public static boolean enabled() {
        return TeleportConfig.getOr(TeleportConfig.ACCESS_ENABLED, false) || network() && !RESTRICTED.isEmpty();
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

    /**
     * D187: a trip refused for want of access, kept for a short while so USE NOW in the pop-up can carry on with it.
     * Only while the player is still near where they were refused, on the same level.
     */
    private record Trip(AccessGrant.Kind kind, String target, Runnable retry, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> level,
                        net.minecraft.world.phys.Vec3 pos, long at) {
    }

    private static final Map<UUID, Trip> TRIPS = new HashMap<>();
    /** How long a refused trip waits for USE NOW, and how far the player may have walked meanwhile. */
    static final long TRIP_MS = 120_000;
    static final double TRIP_REACH = 8;

    /** The chat lines and the popup (D81); a player with no main server yet gets the choice instead (D82). */
    public static void deny(ServerPlayer player, AccessGrant.Kind kind, String target) {
        deny(player, kind, target, null);
    }

    /**
     * {@link #deny(ServerPlayer, AccessGrant.Kind, String)}; {@code retry} is the refused trip, run again when the player
     * buys the access with USE NOW in the pop-up (D187). Null for a refusal that has nothing to carry on with.
     */
    public static void deny(ServerPlayer player, AccessGrant.Kind kind, String target, @Nullable Runnable retry) {
        if (retry != null) {
            TRIPS.put(player.getUUID(), new Trip(kind, target, retry, player.level().dimension(), player.position(), System.currentTimeMillis()));
        } else {
            TRIPS.remove(player.getUUID());
        }
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
            reoffer(player, kind, target);
        }
    }

    /**
     * Sends the access pop-up for {@code target} without the chat lines (D185, D188): the one-card shop when its token has
     * a price on, else the banner (D193; the client decides from the synced token).
     */
    public static void reoffer(ServerPlayer player, AccessGrant.Kind kind, String target) {
        TokenAssignment token = tokenFor(player.server, kind, target);
        BandPackets.sendPopup(player, new AccessPopup(AccessPopup.Type.DENIED, target, token == null ? 0 : token.token(),
                token == null ? 0 : token.rentHours(), token == null ? "" : token.hint(), mainServer(player) == null, List.of(),
                BspCore.coins(player), BspCore.teleportPieces(player)));
    }

    /**
     * D187: the player just bought access to {@code target} with USE NOW; the trip refused for it goes ahead, if it was
     * refused less than {@link #TRIP_MS} ago and the player is still near where they were.
     */
    public static void resumeTrip(ServerPlayer player, AccessGrant.Kind kind, String target) {
        Trip trip = TRIPS.get(player.getUUID());
        if (trip == null || trip.kind() != kind || !trip.target().equalsIgnoreCase(target)) {
            return;
        }
        TRIPS.remove(player.getUUID());
        if (!tripStillOn(trip.at(), System.currentTimeMillis(), trip.level().equals(player.level().dimension()), trip.pos().distanceTo(player.position()))) {
            return;
        }
        trip.retry().run();
    }

    /** Whether a refused trip may still go ahead (D187): soon enough, on the same level, near enough. */
    static boolean tripStillOn(long refusedAt, long now, boolean sameLevel, double moved) {
        return sameLevel && now - refusedAt <= TRIP_MS && moved <= TRIP_REACH;
    }

    /**
     * D189: one tier of a token's price as players read it: "rent 7d for 500 pieces", "full access for free", or "rent not
     * for sale".
     */
    public static Component describePrice(TokenPrice price, boolean full, int rentHours) {
        if (!price.sells(full)) {
            return Component.translatable(full ? "message.bsp_teleport.access.price_full_off" : "message.bsp_teleport.access.price_rent_off");
        }
        int amount = price.price(full);
        Component cost = amount <= 0 ? Component.translatable("message.bsp_teleport.access.amount_free")
                : Component.translatable(price.pieces(full) ? "message.bsp_teleport.access.amount_pieces" : "message.bsp_teleport.access.amount_coins", String.format("%,d", amount));
        return full ? Component.translatable("message.bsp_teleport.access.price_full", cost)
                : Component.translatable("message.bsp_teleport.access.price_rent", TokenPrice.describeLength(rentHours), cost);
    }

    /** The choose-main-server popup (D82): every restricted server with its counts. */
    public static void showChoose(ServerPlayer player) {
        List<AccessPopup.ServerChoice> choices = new ArrayList<>();
        for (String server : restricted(player.server)) {
            int[] c = counts.getOrDefault(server, new int[3]);
            TokenAssignment token = tokenFor(player.server, AccessGrant.Kind.SERVER, server);
            choices.add(new AccessPopup.ServerChoice(server, token == null ? 0 : token.token(), c[0], c[1], c[2]));
        }
        BandPackets.sendPopup(player, new AccessPopup(AccessPopup.Type.CHOOSE, "", 0, 0, "", mainServer(player) == null, choices, 0, 0));
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
        com.mrgregles.bsp_teleport.shop.AccessShops.afterChoice(player);
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
        warnNoLobby();
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
        TRIPS.remove(event.getEntity().getUUID());
    }

    /** A player who arrived without access (by any route, or whose rent ran out while away) goes to the lobby (D68). */
    private static void checkArrival(ServerPlayer player) {
        String here = TeleportConfig.serverName();
        if (mayEnter(player, here)) {
            return;
        }
        tell(player, "access.bounced", here);
        deny(player, AccessGrant.Kind.SERVER, here);
        bounce(player, false);
    }

    /**
     * B26: moves a player who may not stay here to somewhere they may be: the lobby, their main server (first when
     * {@code mainFirst}, for rent running out), then any server that is up and open to them, never this one. Before, an
     * empty lobby meant this server's own spawn, so the player stayed. With nowhere up they are disconnected; a server
     * without messaging has nowhere else, so it keeps the old move to its own spawn.
     */
    private static void bounce(ServerPlayer player, boolean mainFirst) {
        if (!com.mrgregles.bsp_teleport.network.Messenger.enabled()) {
            sendTo(player, "", true);
            return;
        }
        String here = TeleportConfig.serverName();
        String lobby = lobby(player.server), main = mainServer(player);
        List<String> order = new ArrayList<>(mainFirst ? java.util.Arrays.asList(main, lobby) : java.util.Arrays.asList(lobby, main));
        order.addAll(PlayerDirectory.servers());
        for (String s : order) {
            if (s != null && !s.isEmpty() && !s.equalsIgnoreCase(here) && PlayerDirectory.isOnline(s) && mayEnter(player, s)) {
                sendTo(player, s, true);
                return;
            }
        }
        BSPTeleport.LOGGER.warn("{} has no access to {} and no other server is up for them; disconnected", player.getGameProfile().getName(), here);
        player.connection.disconnect(Component.translatable("message.bsp_teleport.access.nowhere", here));
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
                TeleportService.INSTANCE.place(player, spawn.toDestination(""));
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
                TeleportService.INSTANCE.place(player, spawn.toDestination(""));
            } else {
                TeleportService.INSTANCE.placeOnServer(player, server);
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
                        bounce(player, true);
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
        warnNoLobby();
        if (!network()) {
            refreshCounts(event.getServer());
        }
    }

    private static boolean warnedNoLobby;

    /** B26: said once, at start or when the shared list first switches access on here. */
    private static void warnNoLobby() {
        if (!warnedNoLobby && enabled() && TeleportConfig.getOr(TeleportConfig.ACCESS_LOBBY, "").isEmpty() && TeleportConfig.getOr(TeleportConfig.GLOBAL_SPAWN_WARP, "").isEmpty()) {
            warnedNoLobby = true;
            BSPTeleport.LOGGER.warn("access.lobbyServer is empty and there is no global spawn warp: players without access here go to their main server or the first open server that is up. Set access.lobbyServer to choose.");
        }
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        warnedNoLobby = false;
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
