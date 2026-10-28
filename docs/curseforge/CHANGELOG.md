# BSP-Teleport 0.2.6

Minecraft 1.20.1, Forge 47.4, Java 17. Needed on the client and the server, and both must run this version (the network protocol changed). Requires BSP-Core 1.3.0 or newer; the Home Servers board needs BSP-Core 1.4.0, and 1.4.5 is recommended (its steal checks no longer load chunks, and it hands this mod its MySQL driver directly). BSP-Region Guard (0.1.0 or newer, built against 0.5.0), BSP-RankUp, BSP-InventorySync and LuckPerms are optional.

## Added

**Portals with several destinations.** A portal may now lead to up to eight places: warps, portal warps, other portals, random spots and positions, each with a label the admin may set. The core screen's DESTINATION tab lists them on the right; pick on the left and press ADD. One destination works as before. With more, a player stepping in gets a card naming the portal and its destinations, picks one and goes, or cancels and steps back out. While the card shows the player is held still and cannot open the inventory or act; being hurt or waiting 30 seconds ends it; a cancel applies the portal's cooldown so stepping out does not fire the field again. Destinations that are down are listed greyed with the reason; destinations on a server the player may not enter say so and name the token. A portal that lists another portal leads to it one way; two portals listing each other are the old two-way link, and old link ids convert by themselves. The panel's PORTALS tab sums a row up as "3 destinations: Market, Arena, +1" and the map draws an arrow to each portal a portal leads to. Commands: `/bsptp portal dest add|del|label|clear|list`. Config `portals.maxDestinations`.

**Faster inventory sync on server switches.** With BSP-InventorySync installed, a player leaving for another server has their inventory save started just before the switch instead of at the logout, so the arriving server has it ready and the arrival freeze is shorter. Optional: without BSP-InventorySync nothing changes.

**Back portals.** A new destination kind, Back, sends each player to the spot `/back` would take them to: where they last teleported from, or where they died. Alone on a portal it makes a way home for everyone; among other destinations it is one wide key across the top of the chooser card. A player with nowhere to go back to, or whose spot is on a server that is down, stays and is told why. Back also checks the landing like `/back` does (Region Guard flags, Anti Totem zones, server access). Command: `/bsptp portal dest add back [label]`.

**Portal warm-ups.** A portal may make players wait up to 30 seconds in the field before going: set it under the cooldown on the Open/close tab or with `/bsptp portal warmup <seconds>`. The player is held still where they stepped in while a box at the top of the screen counts down; crouching or being hurt cancels it. Coins are taken only when the player actually goes. Off by default, and players who may skip warm-ups skip this one too.

**Open hours for portals and warps.** A Real clock portal may now open for up to four stretches a day instead of one (for example 15:00 to 17:00 and 03:00 to 05:00); the Open/close tab draws the day as a bar with the open hours lit and a gold mark at the server's time, lists the stretches with an x to remove each, and says whether it is open now. Warps (and portals) get a new **Time** rule with the same hours: outside them players are told "Closed now. Opens at 03:00 (Europe/London).", the band shows OPEN or OPENS 03:00 on the warp's row, and a portal entry leading to the warp is greyed in the chooser. Stretches that touch are joined, a second Time rule joins the first, and a warp that closes during a warm-up refuses the move without charging. Every day has the same hours; the zone is `portals.timeZone`. Command: `/bsptp portal mode realclock 15:00-17:00 03:00-05:00` (the old `15:00 17:00` form still works). Old Real clock portals keep their one window.

**Random teleports and the world border.** A random teleport now draws its spots only from the part of its ring that lies inside the border (Region Guard's, or the vanilla world border when it has been set), so a ring larger than the map no longer wastes its tries and ends in "no safe spot". New setting `randomTeleport.useBorder` (off by default, also on the panel's SETTINGS tab): on, the whole border is the range and `maxRadius` is ignored; `minRadius` still keeps players away from spawn.

**Portal names.** A new Portal Core is named after its server and the next free number on the network (BSP-Spawn-1, BSP-Frostwarf-2, ...), and no two portals may share a name, so pickers and `/bsptp portal <name>` always find the right one. The name and a Rename key now sit at the top of the core screen's DESTINATION tab.

**Network spawn.** `/bsptp setnetworkspawn` saves the network spawn where you stand: one shared warp (the one `spawn.globalWarp` names, or `spawn`). Players new to the whole network are sent there once their first login settles, on whichever server it is. A first visit to a server, and a respawn with no bed or anchor, go to that server's own `/setspawn` point, or to the network spawn when it is on that server; neither ever moves a player to another server.

## Fixed

**Portals that no longer exist.** Each server now checks the portal list for its own portals at start and every few minutes, and removes any whose core is no longer there, so pickers stop offering portals that lead nowhere. `/bsptp portal <name> forget` removes one by hand (for a server that is gone for good).

**No more kicks when walking into a portal to another server.** A client still walking when the proxy switch began could be thrown out with "Can't serialize unregistered packet". Every move to another server now holds the player still for one second with their movement keys off before the switch starts (`network.departureHoldTicks`, 20).

**Coins.** A warp with both a cost and a coin rule now asks for one total and takes nothing unless all of it can be paid; rules are checked again when the warm-up ends; `/tpnow` charges only once the teleport has started; a portal checks its landing world before charging. Setting an existing warp again keeps its icon, rules and portal-only setting.

**Smoother servers.** Redis messages are sent from their own thread; a random teleport loads at most four new chunks per search and remote requests are limited; portal change notices are grouped; access data is only sent when it changed; the TEAM tab cannot be refreshed faster than every 3 seconds; fewer database round trips; the cooldown reaches the next server before the player does.

**Screens.** Long names and help lines are cut to fit everywhere, keys no longer cover text, the band's title no longer sits on its tabs, and the access icons sit above the hunger bar.
**The core screen.** Buttons and lines that showed internal names ("screen.bsp_teleport.portal.add" and others) now read in plain English, and so do the keys on the destination card. The screen is larger with smaller text, long names are cut to fit instead of running off the panel, the picked destination is spelled out in full under the picker, and the portal picker scrolls.

**Permissions on plain Forge servers.** Permission nodes (restricted warps, the bypass nodes, rank and permission rules on portals and warps) are now asked of LuckPerms' Forge edition directly. Before, they were only asked through Bukkit, so on a Forge server only admins passed them. Mohist servers are unchanged.

**Single player.** In a single-player or LAN world the mod no longer tries to reach the network the modpack's config names: no MySQL, Redis or proxy, the admin panel's NETWORK tab is greyed out, and commands that need another server say so. Before, the world borrowed a network server's name and froze for seconds at a time trying to connect.

**No freezes while Redis is away.** Sending a network message no longer waits for Redis on the game's thread; the connection is retried in the background and messages are kept for a few seconds meanwhile.

**Admin panel and screens.** "Send everyone to..." only shows when another server is up and always has Cancel; keys are sized to their capital-letter labels so text no longer spills out of them; the panel's status lines are cut to fit.
**/back after a portal trip.** `/back` used to remember the spot inside the portal you left by, so it could throw you straight back through. It now remembers the spot in front of that portal, and any landing inside a portal's field no longer fires it until you walk off.

**Database and Redis passwords no longer reach players.** Forge sends every server config file to each player who joins, so the passwords in `bsp_teleport-server.toml` could be read by anyone on the server. The database and Redis connection (host, port, database, user, password, table prefix, SSL; Redis host, port, password, TLS, database) now live in `config/bsp_teleport-network.toml`, which is never sent. Settings still in the server file are moved there at the next start (or at `/bsptp reload`) and cleared; a value already in the new file wins, and the log names what moved without showing a password. `storage.mode`, `redis.enabled`, `redis.cluster` and `network.serverName` stay in the server config. Admins: clear the passwords from any `defaultconfigs/bsp_teleport-server.toml`, keep the new file out of the client pack, and change both passwords once every BSP mod is updated.

**MySQL on plain Forge servers.** On a Forge server (not Mohist) the shared storage could not find a MySQL driver, so homes, warps and cross-server landings stayed on each server. It now uses the driver inside BSP-Core's full jar. Mohist servers are unchanged.

# BSP-Teleport 0.2.0

Minecraft 1.20.1, Forge 47.4, Java 17. Needed on the client and the server. Requires BSP-Core 1.3.0 or newer; the Home Servers board on BSP-Core's Score Screens needs BSP-Core 1.4.0, everything else runs on 1.3.0. BSP-Region Guard (0.1.0 or newer, built against 0.2.0) and BSP-RankUp are optional.

## Added

**Team teleports** (BSP-Core's team system)
- A TEAM tab on the Teleport Band, between PLAYERS and MORE: a banner in the team's colour with the leader and the member and online counts, then one row per teammate with where they are: "here", "on <server>", or "offline, seen 2 h ago". GO takes you to a teammate on this server or on any other server in the network.
- BSP-Core's Team screen gains a TELEPORT key on every online teammate's row; this mod carries it out, with the same rules.
- Team teleports are plain teleports with the team as the target list: no cost, no request, the teammate sees "X is teleporting to you." Cooldown, warm-up, Region Guard flags, Anti Totem zones and server access all apply. In addition a team teleport refuses any spot within 150 blocks of any running steal on the network, whoever owns the totem, so a team cannot pour in behind a friend who started a raid.
- The tab refreshes every five seconds while open, and at once when the team changes on this server.

**Join and leave lines behind the proxy**
- Config `network.suppressJoinLeaveMessages` (off by default). On, this backend prints no "X joined the game" or "X left the game" for any login or logout, so a proxy plugin that announces real joins and leaves for the whole network (BSP-Velocity Additions) is the only voice. Other mods' and plugins' lines during login still show; nothing else about login changes.

**The Home Servers board on BSP-Core's Score Screens**
- With the access system on, this mod registers a "HOME SERVERS" board that BSP-Core's Score Screens can show on their own or in rotation with the leaderboard: one row per restricted server with its Access Token as the icon and "N home, F full, R renting". The same numbers as the Access Board, in BSP-Core's own looks.

**For developers**
- A Forge GameTest harness (`./gradlew runGameTestServer`) with mock players; the first tests cover the join and leave lines.

## Fixed

- **Landing after a kick during a server switch.** A player kicked by the proxy while switching servers used to lose their landing, and could be put at another server's coordinates on rejoining. The landing now waits in the shared table until it has actually been applied, and only the server it names applies it, so rejoining within two minutes lands you where you were going.
- **"Redis messaging on ... (not listening)" at start.** The start-up line was printed before the Redis subscriber had connected and always read "not listening". The subscriber now reports its real result: "Redis listening on ..." or "Redis not reachable at ... (reason)".

## Changed

- Requires BSP-Core 1.3.0 or newer (was 1.2.0). Compiled against BSP-Region Guard 0.2.0; 0.1.0 is still accepted, the API is unchanged.
- The throwaway `/bsptp spike` test commands are gone.

# BSP-Teleport 0.1.0

First release. See the file's own changelog on CurseForge for the full feature list: the Teleport Band, homes, warps, spawn, back, random teleports, requests, the BSP raid rules, the admin panel, portals with hangar doors, server access with Access Tokens, rules on portals and warps, admin tools, MySQL and Redis storage, and the API for other mods.
