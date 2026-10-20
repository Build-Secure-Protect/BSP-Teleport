# Testing BSP Teleport in game

What to try after a build, newest at the bottom. Tick what works, note what does not. This list is the hand-off between a headless build and the owner's client.

Headless checks already run before the hand-off: `./gradlew build` (JUnit: wire formats, border maths, portal shapes, access rules, the join/leave text rule) and `./gradlew runGameTestServer` (3 Forge GameTests in `src/main/java/com/mrgregles/bsp_teleport/gametest`, template `empty` from `tools/gen_gametest_structures.py`). The earlier features (phases 1 to 6, portals, access) have their owner checklists in the vault's `Teleport - Task Queue.md`.

## Join and leave lines behind the proxy (VA-T1, 2026-10-18)

Config: `[network] suppressJoinLeaveMessages` in `<world>/serverconfig/bsp_teleport-server.toml`, off by default. `/bsptp reload` re-reads it; a restart is never needed.

Headless, already passing: with the switch on, a mock player's real login (`PlayerList.placeNewPlayer`) and logout (`onDisconnect`) send no "joined the game" or "left the game" line to another player, the player is still listed and the tab-list update still goes out; other lines during login pass; with the switch off both vanilla lines arrive.

Needs the owner, one Mohist backend first, then two behind Velocity:

- [ ] Switch off (default): log in and out; chat shows "X joined the game" and "X left the game" as before. Console shows the same two lines.
- [ ] Set `suppressJoinLeaveMessages = true`, `/bsptp reload` (or restart). Log in: no join line in chat or console; the console still shows "X[/ip] logged in with entity id ...". Log out: no leave line; the console still shows "X lost connection".
- [ ] With it on, the tab list still gains and loses the player, the player still lands where they should, `/back`, homes and the band work as before (nothing else about login changed).
- [ ] With it on, kick a player (`/kick`): no leave line. Stop the server with players on it: no leave lines.
- [ ] Any other mod's or plugin's message on login (BSP-Core's "Your Shatter Totem arrived with you", a plugin's welcome line) still appears.
- [ ] Mohist specifically: Bukkit turns the vanilla line into plain text before it is sent; the switch must still hide it. If a join line slips through on Mohist, copy it exactly (the matcher compares plain text) and report.
- [ ] Two backends behind Velocity with the switch on everywhere and BSP-Velocity Additions printing the network lines: a server switch (`/server`, a cross-server warp, a portal) prints nothing on either backend; a real join or leave prints exactly one line, the proxy's.
- [ ] A backend run without the proxy and the switch off behaves like vanilla.

## Team teleports (BSP-Core T-1 to T-3, 2026-10-18)

Needs BSP-Core with the Teams line (1.2.0 built 2026-10-18 or later) on every backend. No config: team teleports are plain teleports with the team as the target list.

Headless, already passing: the JUnit tests for the "seen" text and the member lookup (`TeamTeleportsTest`); the build against BSP-Core's `BspTeams` and `TeamEvent`. Nothing in game yet.

Needs the owner, one server first, then two behind Velocity with Redis:

- [ ] Make a team with two players on one server (BSP-Core's Team screen). Open the band: a TEAM tab sits between PLAYERS and MORE; the banner shows the team name in its colour, "leader X  2 members  2 online"; the teammate's row says "here" with a GO key. Press GO: the screen closes, the warm-up counts down, you land next to the teammate, "Teleported to <name>.", and the teammate sees "<you> is teleporting to you."
- [ ] The same from BSP-Core's Team screen: a TELEPORT key is drawn on the online teammate's row (T-3); pressing it does the same teleport with the same lines.
- [ ] Rules: on cooldown the teleport is refused with the cooldown line (and `/tpnow` works on it); move during the warm-up and it cancels; stand in a Region Guard `teleport-out` region or an Anti Totem no-teleport-out zone and it is refused; a teammate standing in a `teleport-in` region is refused with "You cannot teleport there."
- [ ] Raid rule: start a steal on any totem (not the teleporting player's) and have the teammate stand within 150 blocks of it: the row shows "a steal within 150" and a red RAID key; the Team screen's TELEPORT key answers "A totem is being stolen near <name>...". Beyond 150 blocks it works.
- [ ] Not in a team: the TEAM tab says "You are not in a team" with the hint. A player who is not a teammate cannot be reached (the band never lists them; the Team screen refuses).
- [ ] Presence: with the TEAM tab open, have the teammate log out: within 5 s the row turns grey with "offline, seen just now" and loses its GO key; log back in: "here" again. Invite a third player: the row appears at once on the server where they accepted (the event), within 5 s elsewhere. Kick, leave, transfer the lead, disband: the tab follows.
- [ ] Two servers with Redis: a teammate on the other server shows "on <server>" in violet with a violet GO key. Press GO: "Finding <name> on <server>...", then the warm-up, then the switch; you land at the teammate's position; the teammate saw "<you> is teleporting to you." With `/bsptp status` showing Redis off on your server the key is greyed FAR and the Team screen's key answers "<name> is on <server> and this server cannot reach them."
- [ ] Two servers, access on: a teammate on a server you have no access to is refused with the access denial (and the popup), as any teleport would be.
- [ ] Admin band: six tabs; the title reads BAND; every tab still opens.

## Home Servers board on BSP-Core's Score Screens (R8, 2026-10-18)

Needs BSP-Core 1.3.0 with the boards line (built 2026-10-18 or later) on the client and on every backend; the servers must run the 1.3.0 jar (slim on Mohist). Nothing to configure here: the board registers itself at client setup from the counts the access sync already sends.

Headless, already passing: the build against `BspBoards`. Nothing in game yet.

Needs the owner, one server with `access.enabled = true` and at least one restricted server:

- [ ] Place a BSP-Core Score Screen and open its settings as an admin: under Show, pick Board; the board picker lists "HOME SERVERS" from `bsp_teleport:home_servers`. The screen shows one row per restricted server: its Access Token as the icon (no icon while no token is assigned to it), the server's name, and "N home · F full · R renting" at the right.
- [ ] `/mainserver <server>` as another player: the home count on the screen rises within a minute (the counts refresh every minute, at once after a grant on the server where it was made). Rent and full grants move the other two numbers.
- [ ] Try the three looks in the screen's settings (Ledger rows, Tiles, Side column): the icon, the name and the value sit where BSP-Core's look puts them.
- [ ] Rotate: with the board among the pages, the screen cycles leaderboard and board at the chosen interval.
- [ ] With `access.enabled = false`, or before the first sync (a freshly logged-in client), the screen says "Nothing on this board yet" and no lines are drawn.
- [ ] A client that joins with the board picked on a screen but without BSP-Teleport installed sees BSP-Core's "not installed here" line once and the rotation skips it (BSP-Core's side; no change expected here).

## Landings after a kick during a server switch (B7, 2026-10-19)

No config. A cross-server landing row now stays in the shared `pending` table until the landing has been applied, and only the server it names applies it.

Headless, already passing: `PendingLandingsTest` (a row is for the server it names, case-insensitive; a row with no server is local).

Needs the owner, two backends behind Velocity with Velocity Additions remembering the last server:

- [ ] Walk through a portal to the other server as normal: you land at the exit as before, and the row is gone afterwards (`SELECT * FROM bsptp_pending` on the database is empty for your uuid).
- [ ] Provoke a kick during a switch (the B5 pair of trips, or a portal trip right after logging in with `switchGapSeconds = 0`). Rejoin within two minutes. If the proxy puts you on the destination server you land at the portal's exit, not at your old spot there; the server log says the landing was applied, not "left before landing".
- [ ] If the proxy puts you on the departure server (or the lobby) instead, you stay where that server has you; its log says "Landing for <you> is for <server>, not this server; leaving it". Then `/server <destination>` (or a warp there): you land at the exit, because the row waited.
- [ ] Rejoin after more than two minutes: nothing is applied anywhere (the row expired) and the log says nothing about a landing.

## Multi-destination portals (T69, D94 to D107, 2026-10-20)

Config: `portals.maxDestinations` (8). A core now holds a list of destinations; one behaves exactly as before.

Headless, already passing: `PortalWireTest` (the list's wire form with labels, old single rows, the summary text) and the GameTest `a_choosing_player_is_held_still_and_a_cancel_lets_them_go` (the hold puts a moved player back, an unknown answer is ignored, cancel ends it).

Needs the owner, one server first, then two behind Velocity:

- [ ] Right-click a core: DESTINATION shows pills WARP, PORTAL WARP, PORTAL, RANDOM, POSITION on the left (no LINK) and "DESTINATIONS 0 OF 8" on the right. Pick a warp, press ADD: it appears as row 1 with a red Del key; the status line says Ready. Add a second (a portal warp) and a third (another portal from the PORTAL picker, which lists every other portal on the network with its server). Up moves a row up; Del removes it; click a row's name, type a label, press SET: the row shows the label.
- [ ] Walk in with one destination: straight through, as before. With two or more usable: the centred card opens with the portal's name, one row per destination (label on top, where it leads underneath) with GO, and Cancel. You cannot move, open the inventory or type while it shows. GO: "Through <portal>" and you arrive. Esc or Cancel: "You stepped back out of <portal>", you stay, and the field does not fire again until you walk two blocks off (then it does).
- [ ] Hold: while the card shows, have someone push you or hit you. A push puts you back on the cell each tick; a hit closes the card with "You were hurt: <portal> let you go." Wait 30 s without answering: the card closes with "No choice made".
- [ ] Down entries: delete one listed warp or stop the partner server. The card shows that row greyed with "Destination missing" or "Destination down" and a DOWN key that does nothing. With only one usable entry left you go straight through, no card. With none, "The portal is not answering."
- [ ] Access (system on): a destination on a server you may not enter shows red with "needs access to <server> (Access Token N)" and a NO ACCESS key; pressing it does nothing. With one entry total you get the usual denied popup instead.
- [ ] Portal destinations: on B make a portal listing only A's portal; on A list B's portal among three others. Walk into B: straight to A. Walk into A: the card lists B among the others. Old link pairs: a portal built before this version with a link id shows its entry as "portal <partner>" once both servers have read the rows (the server log says "converted its link").
- [ ] The panel's PORTALS tab reads "3 destinations: Market, Arena, +1" on the row; MAP draws a violet line from a portal to each portal it leads to, with a dot near the far end; a two-way pair has dots at both ends.
- [ ] Commands on the looked-at core: `/bsptp portal dest add warp market Market Square`, `dest add portalwarp arena`, `dest add portal "Hub East"`, `dest add random BSP-Wilds The Wilds`, `dest add here`, `dest list`, `dest label 2 Arena Door`, `dest del 2`, `dest clear`. `dest warp <name>` still works and replaces the whole list with that one entry.
- [ ] Rules: a portal with a rank or coin rule and several destinations refuses before the card (the rule line on the action bar), and charges coins only when a destination is chosen and the move happens.
