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

Needs BSP-Core 1.4.0 on the client and on every backend (`bsp-core-1.4.0.jar`, `bsp-core-1.4.0-slim.jar` on Mohist). Nothing to configure here: the board registers itself at client setup from the counts the access sync already sends.

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

## MySQL on plain Forge (B8, 2026-10-21)

On plain Forge the only MySQL driver is the one inside BSP-Core's full jar, which keeps it under a relocated name. BSP-Teleport now tries that name after the server's own MySQL driver (Mohist) and before MariaDB.

Headless, already passing: `MySqlDriversTest` (the order, and that BSP-Core's full jar has the driver under that name).

Needs the owner:

- [ ] A plain Forge 1.20.1 server with BSP-Core's **full** jar (not `-slim`), BSP-Teleport and `storage.mode = mysql`: `/bsptp status` reads "mysql: connected to ...", not "mysql: FAILED, No MySQL or MariaDB JDBC driver".
- [ ] A Mohist server with the slim BSP-Core jar still reads "mysql: connected" (Mohist's own driver is tried first).
- [ ] Two servers, one of each kind: a warp set on one is listed on the other, and a teleport between them lands.

## Inventory save before a server switch (BSP-InventorySync T1, 2026-10-21)

With BSP-InventorySync loaded, every cross-server departure calls `InventorySyncApi.beginTransfer(player, target)` after BSP-Core has taken the totems and before the proxy `Connect`. Without it nothing changes.

Headless, already passing: GameTests `without_inventory_sync_the_adapter_is_absent_and_a_departure_still_goes` and `inventory_sync_is_told_after_bsp_core_and_before_the_connect` (through the adapter's real lookup on a stand-in class: the call comes after BSP-Core holds the transfer and before the Connect; `cancelTransfer` reaches it; a throw is logged and the Connect still goes).

Needs the owner, both mods on two backends behind Velocity:

- [ ] Start-up log on each backend: "BSP-InventorySync found: departures start the inventory save before the switch". A backend without the mod says "BSP-InventorySync not loaded"; a warning naming the api means the lookup failed and the switch goes without it.
- [ ] Switch servers (a warp, a home on the other server, a portal, `/bsptp send`). On the source run `/invsync snapshots <you>`: a transfer snapshot is listed, taken before the logout snapshot.
- [ ] The arrival has no visible freeze, and the inventory, armour and offhand are as they were. Carry a Shatter Totem across: it arrives through BSP-Core as before and is not duplicated in the synced inventory.
- [ ] A backend with the sync switched off still switches normally (the call answers false and is ignored).

## Back portals and portal warm-ups (T73, D110 to D126, B9, 2026-10-22)

A portal destination kind **Back** (each player's own `/back` spot), a per-portal **warm-up** (0 to 30 s, off by default) with a countdown box at the top of the screen, and the B9 fix (`/back` after a portal trip lands in front of that portal, never in its field).

Headless, already passing: `PortalWireTest.a_back_entry_round_trips_and_is_one_place`; GameTests `a_held_warmup_counts_down_keeps_the_player_still_then_moves_them`, `stepping_out_or_being_hurt_cancels_a_warmup`, `a_back_entry_takes_the_player_to_their_back_spot_or_says_why_not` (on a core without a frame: the countdown, the chooser's hold through it, the cancels, the Back spot and the refusal with no spot).

Needs the owner, one server first, then two behind Velocity. Client and server both need this build (the network protocol is now "2"; an older client is told to update).

Back portals:
- [ ] DESTINATION tab: a sixth pill BACK; the left column reads "Each player goes back to / where they came from." and ADD adds a row "Back". A second ADD of Back is refused (already listed).
- [ ] A portal with only Back: walk through it and you land where you last teleported from (or died). Walk through again from there (another portal or `/back`) and you flip between the two places.
- [ ] A new player, or one whose `/back` spot is in a world `/back` excludes: "Nowhere to go back to." on the action bar, no move, no re-fire for 3 s.
- [ ] Two servers: teleport from A to B, then walk into a Back portal on B: you go to A. Stop A first: "BSP-A is not answering."
- [ ] Mixed list (Back plus two warps): the card shows BACK as one wide turquoise key across the top with "where you came from" (or "where you came from: <server>"), the warps under it. With no spot the wide key is grey with the reason and does nothing.
- [ ] Back obeys the landing rules: a spot inside a Region Guard region with `teleport-in` denied, or an Anti Totem zone that forbids it, says "You cannot teleport there."; a spot near your totem being stolen is refused with the raid line; a spot on a server you may not enter shows the access popup.
- [ ] `/bsptp portal dest add back` and `/bsptp portal dest add back Home again` add it from a command; the panel's PORTALS row reads "back" for a Back-only portal.

B9, `/back` after a portal trip:
- [ ] Walk through portal P (same server or across servers), then type `/back`: you land in front of P, facing away from it, not inside the field, and P does not fire.
- [ ] Stand inside a portal's opening and `/sethome` there, walk away, then teleport home: you land in the field without being sent on. Walk two blocks off and back in, and it fires as normal.

Warm-ups:
- [ ] Open/close tab: a "Warm-up" row under "Cooldown per player" with - and +; 0 to 30. `/bsptp portal warmup 3` sets it too. The panel's PORTALS row adds ", warm-up 3 s".
- [ ] Walk into a portal with a 3 s warm-up: a box at the top of the screen with the portal's name, "Going in 3 s. Crouch to stay." and a bar that empties, with the countdown plings; at 0 you go through. Coins on the portal are taken only then.
- [ ] Walk into the field at normal speed: you stop where you touched it and stay there until the countdown ends (B17; you can look around). Crouch during the countdown: "You stepped back from <portal>.", the box goes, and the field does not restart the countdown at once. Walking in already crouched does not cancel until you stand up and crouch again.
- [ ] Get hit during the countdown: "You were hurt: <portal> let you go." (with `cancelOnDamage = false` in the server config the countdown carries on).
- [ ] Several destinations with a warm-up: pick one on the card; the card closes, the box reads "Going in 3 s." without the hint, and you cannot move until it ends or you are hurt.
- [ ] An admin (or a player with `bsp_teleport.bypass.warmup`) goes through at once, no box.
- [ ] Log out or die during the countdown: nothing happens on the next login; the box is gone.

## Core screen fixes (B10, T75, D127 to D131, 2026-10-22)

The text fixes (no raw keys, plain button names), the larger screen in layout A, names `<server>-<n>`, unique names.

Headless, already passing: `LangKeysTest` (every key the code names is in the lang file), `PortalNamesTest`; the build and GameTests.

Needs the owner:
- [ ] Open a core: nothing reads like `screen.bsp_teleport...`. The help line says "Up to eight destinations. Pick on the left, then Add destination."; the pills read WARP, PORTAL WARP, PORTAL LINK, RANDOM, POSITION, BACK; the keys read ADD DESTINATION, MOVE UP, REMOVE; the switch reads "Close when every destination is down". The ACCESS tab is no longer greyed.
- [ ] The screen is larger with smaller text and stays inside the window at your usual GUI scale and window size; `ui.screenScale` in `config/bsp_teleport-client.toml` still makes it smaller. Nothing runs past the panel's edge or under a key on any of the four tabs, with long portal, warp and server names.
- [ ] DESTINATION tab: a Name row at the top. Type a name and press RENAME: the title line and other portals' pickers show it. Rename to a name another portal already uses: "Another portal is already called X. Pick a different name."
- [ ] LOOK tab: no Name row any more (colour, doors, facing, arrival line only); the name is changed only on DESTINATION.
- [ ] Place a new core in a frame: it is named `<server>-<n>`, one more than the highest number on any portal name on the network. Existing portals keep their names.
- [ ] PORTAL LINK picker: rows show the name and the server (violet for another server); click one and the box under the picker shows the full name and the server, world and position. The mouse wheel scrolls the list when there are more portals than fit.
- [ ] Walk into a portal with several destinations: the card's keys read GO, DOWN, NO ACCESS and CANCEL (ESC), and the help line "Choose a destination. Esc steps back out."

## Permissions on plain Forge servers (T70, D108, D109, 2026-10-22)

Permission nodes are asked of LuckPerms' own API first (its Forge edition, the BSP build 5.5.88-bsp.2), then Bukkit (Mohist), then BSP-Core's admin list. A `group.<name>` rule also counts inherited groups.

Headless, already passing: GameTest `without_luckperms_or_bukkit_nodes_fall_back_to_the_admin_list`. The LuckPerms route needs the LuckPerms Forge mod and is only testable in game.

Needs the owner, on a plain Forge server with the LuckPerms Forge mod:
- [ ] Start-up log: "Permissions are answered by LuckPerms (Forge edition)" on the first permission check, and "No Bukkit on this server: LuckPerms answers permission nodes".
- [ ] A non-admin player: mark a warp restricted; it is hidden and refused. `/lp user <name> permission set bsp_teleport.warp.<warp> true`: the warp shows and works.
- [ ] `/lp user <name> permission set bsp_teleport.bypass.warmup true`: band teleports and portal warm-ups go at once.
- [ ] A portal or warp with a rank rule for group `vip`: refused; `/lp user <name> parent add vip`: passes. A group that inherits `vip` passes too.
- [ ] `/lp user <name> permission set bsp_teleport.admin true`: `/bsptp admin` opens for them.
- [ ] On a Mohist server nothing changes (the Bukkit plugin still answers).

## Single player, Redis freezes, the Send picker (B14 to B16, T76, D133 to D138, 2026-10-23)

Not testable headless (the GameTest server is a dedicated server). Needs the owner:

- [ ] A new single-player world with the modpack's defaultconfigs (Frostwarf's network settings): the log says "Single player: network features off" once; no MySQL or Redis lines, no timeouts.
- [ ] `/bsptp admin`: opens on SETTINGS; the NETWORK tab is greyed and does nothing; the footer reads "This world (single player): network features are off."; SETTINGS, WARPS, PORTALS, TOKENS work.
- [ ] Homes, warps, `/back`, portals and random teleport work as normal in the world, including homes saved before this build.
- [ ] `/mainserver x`, `/bsptp send <player> x`, `/rtp <other server>`: "Not available in single player." `/tpall all`: moves this world's players only.
- [ ] Open to LAN: the same as single player.
- [ ] Dedicated server with Redis stopped: no freeze when using the panel or commands; the footer's Redis line is not green while it says "not listening"; after Redis comes back, messages flow again within about 8 s.
- [ ] Dedicated server, NETWORK tab: with no other server up there is no "Send everyone to..."; with one up, it opens a picker with Cancel; no key text spills out of its box anywhere (portal screen FLIP, admin keys).

## Network spawn (T77, D140 to D145, 2026-10-23)

Headless, already passing: GameTest `the_servers_own_spawn_comes_first_then_a_network_spawn_on_this_server`. The rest needs real logins and deaths. Put the spawn server first in Velocity's `try` list.

- [ ] On the spawn server, `/bsptp setnetworkspawn`: "Network spawn set here (warp spawn). New players start here." `/warps` lists `spawn`. With access on and this server restricted, the warning line follows.
- [ ] A brand-new account joins (no record anywhere on the network): about 3 s after joining it is moved to the network spawn, also when Velocity first put it on another server. The log says "<name> is new to the network: sent to the network spawn (spawn)".
- [ ] An existing player who has never been on server X joins X by `/server X` (no landing): with a `/setspawn` on X they are placed there; without one, and with the network spawn on another server, they stay where Minecraft put them.
- [ ] A player arriving on X by a warp, home or portal (a landing) is not moved again.
- [ ] Die with no bed: you respawn at the server's `/setspawn` point (or the network spawn when it is on this server). With a bed you respawn at the bed. Dying inside a Region Guard region with a `spawn` flag still respawns at that flag's spot.
- [ ] `/spawn` on a server whose config has `globalWarp = ""` still uses that server's own `/setspawn`. A fresh server (default `globalWarp = "spawn"`) uses the network spawn warp, or its own spawn while the warp does not exist.

## Stale portal rows (B18, T78, D146 to D148, 2026-10-23)

Headless, already passing: GameTest `rows_without_a_core_here_are_removed_and_the_rest_kept`. First point the local server copies at their own tables (D148).

- [ ] Restart BSP-SafeRoom, BSP-Stormfall and BSP-Spawn: each log lists "Removed portal row Portal (...): no Portal Core at ..." for the rows with no core; the PORTAL LINK picker no longer offers them. Real portals are still listed.
- [ ] Break a core with a tool that skips normal block breaking (or delete its row by hand): within 5 minutes the server that owns a live core writes its row again.
- [ ] `/bsptp portal <name> forget` on a row from a server that no longer runs: "Removed the portal row ..."; on a real portal of this server: "<name> is a real portal on this server. Break its core to remove it."

## Audit fixes (B19, T79, T80, 2026-10-23)

- [ ] A warp with cost 50 and a COINS 50 rule, holding 60 coins: refused before the warm-up ("costs 100"), nothing taken. Holding 100: both taken once, after the warm-up.
- [ ] A warp with a consume ITEM rule: swap the item out of your hand during the warm-up: the move is refused at the end, nothing taken.
- [ ] `/tpnow` while the move would be refused (for example standing in a no-teleport region): no coins taken, the cooldown is still there.
- [ ] `/setwarp` on an existing warp with an icon, rules and the portal flag: all three are kept.
- [ ] Stop Redis on a backend: commands, the band and portals keep working with no freeze; messages flow again within about 8 s of Redis coming back.
- [ ] `/rtp` and random portals into new terrain: no multi-second freeze; "no safe spot" can happen more often in fresh worlds (at most four new chunks per search).
- [ ] Screens: long warp, home, portal and server names are cut with "..." and never run under keys; the band title does not overlap its tabs with six tabs; the access icons sit above the hunger bar.

## Departure hold (B21, 2026-10-26)

- [ ] A portal to another server with warm-up 0, walking in at full speed (and sprinting): you stop for about a second, then switch, with no "Can't serialize unregistered packet" kick. Try ten times in each direction.
- [ ] Warps, homes and `/tpaccept` to another server: the same short stop, then the switch.
- [ ] `network.departureHoldTicks = 0` brings back the old behaviour (for comparison only).
- [ ] Clients and servers all on this jar (network protocol 3); an older client is told to update.
