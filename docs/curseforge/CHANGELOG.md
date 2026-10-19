# BSP-Teleport 0.2.0

Minecraft 1.20.1, Forge 47.4, Java 17. Needed on the client and the server. Requires BSP-Core 1.3.0 or newer; the Home Servers board on BSP-Core's Score Screens needs the BSP-Core release after 1.3.0, everything else runs on 1.3.0. BSP-Region Guard (0.1.0 or newer, built against 0.2.0) and BSP-RankUp are optional.

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
