package com.mrgregles.bsp_teleport.teleport;

import com.mrgregles.bsp_teleport.TeleportConfig;
import com.mrgregles.bsp_teleport.compat.BspCore;
import com.mrgregles.bsp_teleport.data.Home;
import com.mrgregles.bsp_teleport.data.HomeLimits;
import com.mrgregles.bsp_teleport.data.Position;
import com.mrgregles.bsp_teleport.data.TeleportData;
import com.mrgregles.bsp_teleport.data.Warp;
import com.mrgregles.bsp_teleport.network.Messenger;
import com.mrgregles.bsp_teleport.network.PlayerDirectory;
import com.mrgregles.bsp_teleport.perm.Permissions;
import com.mrgregles.bsp_teleport.storage.Storage;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.List;
import java.util.stream.Collectors;

/** What a player can do, shared by the commands and the band screen. Every message is a lang key. */
public final class TeleportActions {
    private TeleportActions() {
    }

    // Homes

    /** {@code /home [name]}: with one home and no name, goes there; with several and no name, lists them. */
    public static boolean home(ServerPlayer player, @Nullable String name) {
        TeleportData data = TeleportData.get(player.server);
        List<Home> homes = data.homesOf(player.getUUID());
        if (homes.isEmpty()) {
            return fail(player, "home.none");
        }
        if (name == null) {
            if (homes.size() > 1) {
                listHomes(player);
                return false;
            }
            name = homes.get(0).name();
        }
        Home home = data.home(player.getUUID(), name);
        if (home == null) {
            return fail(player, "home.unknown", name);
        }
        if (!HomeLimits.unlocked(player, data.homeIndex(player.getUUID(), name))) {
            return fail(player, "home.locked", home.name());
        }
        return Teleports.start(player, home.position().toDestination(""), Component.literal(home.name()), TeleportKind.HOME, null);
    }

    public static void listHomes(ServerPlayer player) {
        List<Home> homes = TeleportData.get(player.server).homesOf(player.getUUID());
        if (homes.isEmpty()) {
            fail(player, "home.none");
            return;
        }
        int slots = HomeLimits.slots(player);
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < homes.size(); i++) {
            if (i > 0) {
                list.append(", ");
            }
            list.append(homes.get(i).name());
            if (i >= slots) {
                list.append(" (locked)");
            }
        }
        tell(player, "home.list", homes.size(), slots, list.toString());
    }

    public static boolean setHome(ServerPlayer player, String name) {
        return setHome(player, name, false);
    }

    /**
     * Sets a home here. B40: a name already in use is only moved with {@code move}, and with every slot used a new name
     * asks which home to replace; both questions go to chat with keys that run the answering command.
     */
    public static boolean setHome(ServerPlayer player, String name, boolean move) {
        if (!TeleportData.validName(name)) {
            return fail(player, "home.bad_name");
        }
        name = TeleportData.normalise(name);
        TeleportData data = TeleportData.get(player.server);
        boolean exists = data.home(player.getUUID(), name) != null;
        int slots = HomeLimits.slots(player);
        if (!Rules.canSetHome(player.serverLevel(), player.blockPosition(), player)) {
            return fail(player, "home.blocked_here");
        }
        if (exists && !move) {
            player.displayClientMessage(Component.translatable("message.bsp_teleport.home.taken", name,
                    chatKey("message.bsp_teleport.home.taken_move", "/sethome " + name + " confirm")), false);
            return false;
        }
        List<Home> homes = data.homesOf(player.getUUID());
        if (!exists && homes.size() >= slots) {
            List<Component> keys = new java.util.ArrayList<>();
            for (int i = 0; i < Math.min(slots, homes.size()); i++) {
                keys.add(chatKey(homes.get(i).name(), "/sethome " + name + " replace " + homes.get(i).name()));
            }
            player.displayClientMessage(Component.translatable(keys.isEmpty() ? "message.bsp_teleport.home.full" : "message.bsp_teleport.home.full_pick",
                    slots, name, net.minecraft.network.chat.ComponentUtils.formatList(keys, Component.literal(" "))), false);
            return false;
        }
        Home home = data.setHome(player.getUUID(), name, Position.of(player));
        Storage.homeSaved(player.getUUID(), home);
        tell(player, exists ? "home.updated" : "home.set", home.name());
        return true;
    }

    /** B40: a new home here takes the place in the list of an unlocked home, which is deleted; its icon and description go with it. */
    public static boolean replaceHome(ServerPlayer player, String oldName, String name) {
        if (!TeleportData.validName(name)) {
            return fail(player, "home.bad_name");
        }
        name = TeleportData.normalise(name);
        TeleportData data = TeleportData.get(player.server);
        Home old = data.home(player.getUUID(), oldName);
        if (old == null) {
            return fail(player, "home.unknown", oldName);
        }
        if (old.name().equals(name)) {
            return setHome(player, name, true);
        }
        if (!HomeLimits.unlocked(player, data.homeIndex(player.getUUID(), old.name()))) {
            return fail(player, "home.replace_locked", old.name());
        }
        if (data.home(player.getUUID(), name) != null) {
            return fail(player, "home.exists", name);
        }
        if (!Rules.canSetHome(player.serverLevel(), player.blockPosition(), player)) {
            return fail(player, "home.blocked_here");
        }
        Home home = new Home(name, Position.of(player), old.createdAt());
        data.putHome(player.getUUID(), old.name(), home);
        Storage.homeDeleted(player.getUUID(), old.name());
        Storage.homeSaved(player.getUUID(), home);
        tell(player, "home.replaced", old.name(), home.name());
        return true;
    }

    /** A gold chat key that runs {@code command} when clicked; the label is a lang key or a plain name. */
    public static Component chatKey(String label, String command) {
        Component text = label.startsWith("message.") ? Component.translatable(label) : Component.literal(label);
        return Component.literal("[").append(text).append("]").withStyle(style -> style
                .withColor(net.minecraft.ChatFormatting.GOLD)
                .withClickEvent(new net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new net.minecraft.network.chat.HoverEvent(net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT, Component.literal(command))));
    }

    public static boolean delHome(ServerPlayer player, String name) {
        if (!TeleportData.get(player.server).deleteHome(player.getUUID(), name)) {
            return fail(player, "home.unknown", name);
        }
        Storage.homeDeleted(player.getUUID(), TeleportData.normalise(name));
        tell(player, "home.deleted", TeleportData.normalise(name));
        return true;
    }

    /** Sets or clears a home's icon; the band passes the held item. */
    public static boolean setHomeIcon(ServerPlayer player, String name, String icon) {
        TeleportData data = TeleportData.get(player.server);
        Home home = data.home(player.getUUID(), name);
        if (home == null) {
            return fail(player, "home.unknown", name);
        }
        Home edited = home.withIcon(icon);
        data.putHome(player.getUUID(), home.name(), edited);
        Storage.homeSaved(player.getUUID(), edited);
        tell(player, "home.edited", edited.name());
        return true;
    }

    // Warps

    /** B29: admins and operators (permission level 2) use every warp without paying its cost. */
    public static boolean warpsFree(ServerPlayer player) {
        return Permissions.isAdmin(player) || player.hasPermissions(2);
    }

    /** What this player pays for the warp: its cost, or 0 when {@link #warpsFree}. */
    public static int costFor(ServerPlayer player, Warp warp) {
        return warpsFree(player) ? 0 : warp.cost();
    }

    /** The warps this player may use. */
    public static List<Warp> usableWarps(ServerPlayer player) {
        return TeleportData.get(player.server).warps().stream()
                .filter(w -> !w.portal())
                .filter(w -> !w.restricted() || Permissions.has(player, Permissions.warpNode(w.name())))
                .collect(Collectors.toList());
    }

    /** B49: lists each warp's own price, admins included. */
    public static void listWarps(ServerPlayer player) {
        List<Warp> usable = usableWarps(player);
        if (usable.isEmpty()) {
            fail(player, "warp.none");
            return;
        }
        tell(player, "warp.list", usable.stream().map(w -> w.cost() > 0 ? w.name() + " (" + costText(w, w.cost()) + ")" : w.name()).collect(Collectors.joining(", ")));
    }

    public static boolean warp(ServerPlayer player, String name) {
        Warp warp = TeleportData.get(player.server).warp(name);
        if (warp == null || (warp.portal() && !Permissions.isAdmin(player))) {
            return fail(player, "warp.unknown", name);
        }
        return warpTrip(player, warp, Component.literal(warp.name()), TeleportKind.WARP);
    }

    /**
     * A trip to a warp with all its checks: its permission, its rules (open hours, items, money), its cost, and payment
     * when the warm-up ends. B47: /spawn and the band's Spawn come here too when the network spawn is a warp, so a spawn
     * warp's price is paid either way; they travel as {@link TeleportKind#SPAWN}.
     */
    private static boolean warpTrip(ServerPlayer player, Warp warp, Component label, TeleportKind kind) {
        if (warp.restricted() && !Permissions.has(player, Permissions.warpNode(warp.name()))) {
            return fail(player, "warp.no_permission", warp.name());
        }
        int cost = costFor(player, warp);
        // D86: the warp's own rules, all of which must pass; charges happen with the move.
        com.mrgregles.bsp_teleport.access.AccessChecks.Result rules = com.mrgregles.bsp_teleport.access.AccessChecks.evaluate(player,
                com.mrgregles.bsp_teleport.access.AccessRule.parseList(warp.rules()), com.mrgregles.bsp_teleport.access.AccessGrant.Kind.WARP, warp.name());
        if (!rules.ok()) {
            if (!rules.refusal().getString().isEmpty()) {
                player.displayClientMessage(rules.refusal(), false);
            }
            return false;
        }
        // Audit: the warp's cost and its COINS and PIECES rules are one price; checking them apart let a player pay the cost and then be refused.
        if (!canAfford(player, warp, cost, rules)) {
            return false;
        }
        String warpName = warp.name();
        return Teleports.start(player, warp.position().toDestination(""), label, kind, then -> {
            // Audit: the warp and its rules are read again when the warm-up ends, so an item swapped out meanwhile or a
            // changed warp is not charged from the old answer; nothing is paid unless all of it can be.
            Warp now = TeleportData.get(player.server).warp(warpName);
            if (now == null) {
                tell(player, "warp.unknown", warpName);
                then.accept(null);
                return;
            }
            com.mrgregles.bsp_teleport.access.AccessChecks.Result fresh = com.mrgregles.bsp_teleport.access.AccessChecks.evaluate(player,
                    com.mrgregles.bsp_teleport.access.AccessRule.parseList(now.rules()), com.mrgregles.bsp_teleport.access.AccessGrant.Kind.WARP, now.name());
            if (!fresh.ok()) {
                if (!fresh.refusal().getString().isEmpty()) {
                    player.displayClientMessage(fresh.refusal(), false);
                }
                then.accept(null);
                return;
            }
            int nowCost = costFor(player, now);
            if (!canAfford(player, now, nowCost, fresh)) {
                then.accept(null);
                return;
            }
            // D163: the warp's cost goes with the rules' charges, in pieces from the bank or in coins carried.
            com.mrgregles.bsp_teleport.access.AccessChecks.charge(player, fresh, now.pieces() ? 0 : nowCost, now.pieces() ? nowCost : 0, "Warp " + now.name(), then);
        });
    }

    /** Whether the player holds the warp's cost and its rules' charges together, in each currency; says so when not. */
    private static boolean canAfford(ServerPlayer player, Warp warp, int cost, com.mrgregles.bsp_teleport.access.AccessChecks.Result rules) {
        int coins = com.mrgregles.bsp_teleport.access.AccessChecks.sum(warp.pieces() ? 0 : cost, rules.coins());
        int pieces = com.mrgregles.bsp_teleport.access.AccessChecks.sum(warp.pieces() ? cost : 0, rules.pieces());
        if (coins > 0 && BspCore.coins(player) < coins) {
            return fail(player, "warp.cannot_afford", coins, BspCore.coins(player));
        }
        // B48: the bank plus teleport store credit (SC-1), as the charge itself counts it.
        long have = BspCore.teleportPieces(player);
        if (pieces > 0 && have < pieces) {
            return fail(player, "warp.cannot_afford_pieces", BspCore.piecesText(pieces), BspCore.piecesText(have));
        }
        return true;
    }

    /** For the band: whether the player holds the warp's cost and its rules' money now, saying nothing. */
    public static boolean affords(ServerPlayer player, Warp warp) {
        int cost = costFor(player, warp);
        int coins = warp.pieces() ? 0 : cost, pieces = warp.pieces() ? cost : 0;
        if (!Permissions.isAdmin(player)) {
            for (com.mrgregles.bsp_teleport.access.AccessRule r : com.mrgregles.bsp_teleport.access.AccessRule.parseList(warp.rules())) {
                if (r.kind() == com.mrgregles.bsp_teleport.access.AccessRule.Kind.COINS) {
                    coins = com.mrgregles.bsp_teleport.access.AccessChecks.sum(coins, r.count());
                } else if (r.kind() == com.mrgregles.bsp_teleport.access.AccessRule.Kind.PIECES) {
                    pieces = com.mrgregles.bsp_teleport.access.AccessChecks.sum(pieces, r.count());
                }
            }
        }
        return (coins <= 0 || BspCore.coins(player) >= coins) && (pieces <= 0 || BspCore.teleportPieces(player) >= pieces); // B48: credit counts
    }

    /** A warp's cost as players read it: {@code 250 coins} or {@code 50,000 pieces}. */
    public static String costText(Warp warp, int cost) {
        return warp.pieces() ? BspCore.piecesText(cost) : cost + " coins";
    }

    // Spawn, back, random

    /**
     * B35: where /spawn and the band's Spawn go: the network spawn (the warp {@code spawn.globalWarp} names, or
     * {@code spawn} when that is empty), across servers if needed; this server's own /setspawn point only while no
     * network spawn is set. Before, an empty globalWarp (every live config) meant each server's own point, so /spawn
     * never reached the network spawn.
     */
    @Nullable
    public static Position spawnPosition(ServerPlayer player) {
        return spawnPosition(player.server);
    }

    @Nullable
    public static Position spawnPosition(MinecraftServer server) {
        Position network = NetworkSpawn.position(server);
        return network != null ? network : TeleportData.get(server).spawn();
    }

    /** B47: the network spawn's warp, when there is one; its cost and rules are the price of /spawn. */
    @Nullable
    public static Warp spawnWarp(MinecraftServer server) {
        return TeleportData.get(server).warp(NetworkSpawn.warpName());
    }

    /**
     * /spawn and the band's Spawn. B47: with a network spawn warp the trip goes through that warp's checks and cost (a
     * free /spawn to a paid spawn warp went round its price); this server's own /setspawn point, used only while there
     * is no such warp, stays free.
     */
    public static boolean spawn(ServerPlayer player) {
        Warp warp = spawnWarp(player.server);
        if (warp != null) {
            return warpTrip(player, warp, Component.translatable("message.bsp_teleport.spawn.name"), TeleportKind.SPAWN);
        }
        Position own = TeleportData.get(player.server).spawn();
        if (own == null) {
            return fail(player, "spawn.global_missing", NetworkSpawn.warpName());
        }
        return Teleports.start(player, own.toDestination(""), Component.translatable("message.bsp_teleport.spawn.name"), TeleportKind.SPAWN, null);
    }

    public static boolean back(ServerPlayer player) {
        Position back = Teleports.back(player);
        if (back == null) {
            return fail(player, "back.none");
        }
        if (!back.isLocal() && Messenger.enabled() && !PlayerDirectory.isOnline(back.server())) {
            return fail(player, "back.server_offline", back.server());
        }
        return Teleports.start(player, back.toDestination(""), Component.translatable("message.bsp_teleport.back.name"), TeleportKind.BACK, null);
    }

    /** {@code /rtp [server]}: here, or asked of another server over Redis. */
    public static boolean rtp(ServerPlayer player, @Nullable String server) {
        if (server != null && !server.isEmpty() && !server.equals(TeleportConfig.serverName())) {
            if (TeleportConfig.singlePlayer()) {
                return fail(player, "single_player");
            }
            if (!com.mrgregles.bsp_teleport.access.AccessService.mayEnter(player, server)) {
                com.mrgregles.bsp_teleport.access.AccessService.deny(player, com.mrgregles.bsp_teleport.access.AccessGrant.Kind.SERVER, server,
                        () -> rtp(player, server)); // D187: USE NOW carries on
                return false;
            }
            if (Teleports.isWaiting(player) || Teleports.cooldownBlocks(player, TeleportKind.RANDOM)) {
                return Teleports.refuseRandom(player, () -> rtp(player, server));
            }
            if (!Messenger.enabled()) {
                return fail(player, "rtp.no_network");
            }
            if (!PlayerDirectory.servers().contains(server)) {
                return fail(player, "rtp.server_unknown", server);
            }
            Messenger.requestRandomSpot(player, server);
            tell(player, "rtp.asking", server);
            return true;
        }
        if (!RandomTeleport.enabled()) {
            return fail(player, "rtp.disabled");
        }
        if (!RandomTeleport.allowedIn(player.serverLevel())) {
            return fail(player, "rtp.wrong_world");
        }
        if (Teleports.isWaiting(player) || Teleports.cooldownBlocks(player, TeleportKind.RANDOM)) {
            // Let the engine explain, without searching for a spot first; /tpnow then searches (B37).
            return Teleports.refuseRandom(player, () -> rtp(player, server));
        }
        Position spot = RandomTeleport.find(player.serverLevel(), player);
        if (spot == null) {
            return fail(player, "rtp.no_spot");
        }
        return Teleports.start(player, spot.toDestination(""), Component.translatable("message.bsp_teleport.rtp.name"), TeleportKind.RANDOM, null);
    }

    static void tell(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(Component.translatable("message.bsp_teleport." + key, args), false);
    }

    static boolean fail(ServerPlayer player, String key, Object... args) {
        tell(player, key, args);
        return false;
    }
}
