package com.mrgregles.bsp_teleport.data;

import com.mrgregles.bsp_teleport.BSPTeleport;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Homes, warps and spawn, saved with the overworld so they are known while chunks are unloaded.
 *
 * <p>Phase 1 keeps everything on this server. The shared MySQL store (phase 2) will load the same shapes from the
 * network and write through; callers go through this class either way.
 */
public class TeleportData extends SavedData {
    public static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,16}");

    /** Homes per player, oldest first: the first N are the ones that fit the player's slots. */
    private final Map<UUID, LinkedHashMap<String, Home>> homes = new HashMap<>();
    private final Map<String, Warp> warps = new LinkedHashMap<>();
    /** Where each player last logged out, for {@code /tpoffline}. */
    private final Map<UUID, Position> offline = new HashMap<>();
    /** Players who do not want teleport requests. */
    private final Set<UUID> ignoring = new HashSet<>();
    /** Last known name of each player seen here, for looking homes up by owner name. */
    private final Map<UUID, String> names = new HashMap<>();
    @Nullable
    private Position spawn;

    public static TeleportData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TeleportData::load, TeleportData::new, BSPTeleport.MODID);
    }

    public static String normalise(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public static boolean validName(String name) {
        return NAME.matcher(normalise(name)).matches();
    }

    // Homes

    public List<Home> homesOf(UUID player) {
        LinkedHashMap<String, Home> map = homes.get(player);
        return map == null ? List.of() : new ArrayList<>(map.values());
    }

    @Nullable
    public Home home(UUID player, String name) {
        LinkedHashMap<String, Home> map = homes.get(player);
        return map == null ? null : map.get(normalise(name));
    }

    /** Index of a home in the player's list (oldest first), or -1. */
    public int homeIndex(UUID player, String name) {
        int i = 0;
        for (Home h : homesOf(player)) {
            if (h.name().equals(normalise(name))) {
                return i;
            }
            i++;
        }
        return -1;
    }

    /** Adds or moves a home; a moved home keeps its place in the order, its description and its privacy. */
    public Home setHome(UUID player, String name, Position position) {
        String key = normalise(name);
        LinkedHashMap<String, Home> map = homes.computeIfAbsent(player, k -> new LinkedHashMap<>());
        Home old = map.get(key);
        Home home = old == null ? new Home(key, position, System.currentTimeMillis()) : old.withPosition(position);
        map.put(key, home);
        setDirty();
        return home;
    }

    /** Stores an edited home under its (possibly new) name, keeping its place in the order when the name is unchanged. */
    public void putHome(UUID player, String oldName, Home home) {
        LinkedHashMap<String, Home> map = homes.computeIfAbsent(player, k -> new LinkedHashMap<>());
        if (!oldName.equals(home.name())) {
            // Rebuild to keep the order by creation.
            map.remove(oldName);
            map.put(home.name(), home);
            LinkedHashMap<String, Home> sorted = new LinkedHashMap<>();
            map.values().stream().sorted((a, b) -> Long.compare(a.createdAt(), b.createdAt())).forEach(h -> sorted.put(h.name(), h));
            homes.put(player, sorted);
        } else {
            map.put(home.name(), home);
        }
        setDirty();
    }

    /** Every public home on this server's records, with the owner's uuid. */
    public List<Map.Entry<UUID, Home>> publicHomes() {
        List<Map.Entry<UUID, Home>> list = new ArrayList<>();
        homes.forEach((uuid, map) -> map.values().stream().filter(Home::isPublic).forEach(h -> list.add(Map.entry(uuid, h))));
        return list;
    }

    // Players

    public void rememberName(UUID player, String name) {
        if (!name.equals(names.put(player, name))) {
            setDirty();
        }
    }

    @Nullable
    public String nameOf(UUID player) {
        return names.get(player);
    }

    @Nullable
    public UUID findByName(String name) {
        for (Map.Entry<UUID, String> e : names.entrySet()) {
            if (e.getValue().equalsIgnoreCase(name)) {
                return e.getKey();
            }
        }
        return null;
    }

    @Nullable
    public Position offline(UUID player) {
        return offline.get(player);
    }

    public void setOffline(UUID player, Position position) {
        offline.put(player, position);
        setDirty();
    }

    public boolean isIgnoring(UUID player) {
        return ignoring.contains(player);
    }

    public void setIgnoring(UUID player, boolean value) {
        if (value ? ignoring.add(player) : ignoring.remove(player)) {
            setDirty();
        }
    }

    public boolean deleteHome(UUID player, String name) {
        LinkedHashMap<String, Home> map = homes.get(player);
        boolean removed = map != null && map.remove(normalise(name)) != null;
        if (removed) {
            setDirty();
        }
        return removed;
    }

    public void clearHomes() {
        homes.clear();
        setDirty();
    }

    /** Replaces a player's homes with what the shared store holds (network mode). */
    public void replaceHomes(UUID player, List<Home> loaded) {
        LinkedHashMap<String, Home> map = new LinkedHashMap<>();
        loaded.stream().sorted((a, b) -> Long.compare(a.createdAt(), b.createdAt())).forEach(h -> map.put(h.name(), h));
        homes.put(player, map);
        setDirty();
    }

    public void forgetHomes(UUID player) {
        homes.remove(player);
    }

    /** Replaces every warp with what the shared store holds (network mode). */
    public void replaceWarps(Collection<Warp> loaded) {
        warps.clear();
        loaded.forEach(w -> warps.put(w.name(), w));
        setDirty();
    }

    // Warps

    public Collection<Warp> warps() {
        return warps.values();
    }

    @Nullable
    public Warp warp(String name) {
        return warps.get(normalise(name));
    }

    public void setWarp(Warp warp) {
        warps.put(normalise(warp.name()), warp);
        setDirty();
    }

    public boolean deleteWarp(String name) {
        boolean removed = warps.remove(normalise(name)) != null;
        if (removed) {
            setDirty();
        }
        return removed;
    }

    // Spawn

    @Nullable
    public Position spawn() {
        return spawn;
    }

    public void setSpawn(@Nullable Position spawn) {
        this.spawn = spawn;
        setDirty();
    }

    // Persistence

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag homeList = new ListTag();
        homes.forEach((uuid, map) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Player", uuid);
            ListTag list = new ListTag();
            map.values().forEach(h -> list.add(h.save()));
            entry.put("Homes", list);
            homeList.add(entry);
        });
        tag.put("Homes", homeList);
        ListTag warpList = new ListTag();
        warps.values().forEach(w -> warpList.add(w.save()));
        tag.put("Warps", warpList);
        if (spawn != null) {
            tag.put("Spawn", spawn.save());
        }
        ListTag players = new ListTag();
        for (UUID uuid : union(names.keySet(), offline.keySet(), ignoring)) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Player", uuid);
            String name = names.get(uuid);
            if (name != null) {
                entry.putString("Name", name);
            }
            Position off = offline.get(uuid);
            if (off != null) {
                entry.put("Offline", off.save());
            }
            entry.putBoolean("Ignoring", ignoring.contains(uuid));
            players.add(entry);
        }
        tag.put("Players", players);
        return tag;
    }

    private static Set<UUID> union(Collection<UUID> a, Collection<UUID> b, Collection<UUID> c) {
        Set<UUID> all = new HashSet<>(a);
        all.addAll(b);
        all.addAll(c);
        return all;
    }

    public static TeleportData load(CompoundTag tag) {
        TeleportData data = new TeleportData();
        for (Tag t : tag.getList("Homes", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) t;
            LinkedHashMap<String, Home> map = new LinkedHashMap<>();
            for (Tag h : entry.getList("Homes", Tag.TAG_COMPOUND)) {
                Home home = Home.load((CompoundTag) h);
                map.put(home.name(), home);
            }
            data.homes.put(entry.getUUID("Player"), map);
        }
        for (Tag t : tag.getList("Warps", Tag.TAG_COMPOUND)) {
            Warp warp = Warp.load((CompoundTag) t);
            data.warps.put(warp.name(), warp);
        }
        if (tag.contains("Spawn")) {
            data.spawn = Position.load(tag.getCompound("Spawn"));
        }
        for (Tag t : tag.getList("Players", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) t;
            UUID uuid = entry.getUUID("Player");
            if (entry.contains("Name")) {
                data.names.put(uuid, entry.getString("Name"));
            }
            if (entry.contains("Offline")) {
                data.offline.put(uuid, Position.load(entry.getCompound("Offline")));
            }
            if (entry.getBoolean("Ignoring")) {
                data.ignoring.add(uuid);
            }
        }
        return data;
    }
}
