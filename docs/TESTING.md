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

## Network passwords out of the synced server config (D149, 2026-10-28)

Forge sends `<world>/serverconfig/bsp_teleport-server.toml` to every player who joins, so the database and Redis settings now live in `config/bsp_teleport-network.toml` (a COMMON config, never sent).

- [ ] On a backend with the settings still in the server file: start it. The log has one warning, "the network connection settings were in bsp_teleport-server.toml ...", naming what moved (hosts and ports shown, passwords never). `config/bsp_teleport-network.toml` now has them; the server file's old keys are back to their defaults with a "Moved to" comment. `/bsptp status` still shows mysql and Redis connected.
- [ ] Restart: no warning this time, everything still connects.
- [ ] Put a password back in the server file's `[redis]` and run `/bsptp reload`: the same warning, the password is in the network file and gone from the server file.
- [ ] A network file that already has a password keeps it; the server file's old one is dropped and the log says "already set here".
- [ ] Clear the passwords from every `defaultconfigs/bsp_teleport-server.toml`, keep `config/bsp_teleport-network.toml` out of the client pack, and change the database and Redis passwords once every BSP mod is updated.

## Open hours for portals and warps (T82, D150 to D158, 2026-10-28)

Set `portals.timeZone` (for example `Europe/London`) and pick hours a few minutes either side of now so both states can be seen.

- [ ] Core screen, Open/close tab, Real clock: the day bar is drawn with tick marks 00 to 24 and a gold mark at the server's time. Add 15:00 to 17:00, then 03:00 to 05:00: both chips appear and light on the bar; the line under it says "OPEN NOW, CLOSES ..." in green or "CLOSED NOW, OPENS ..." in red. The doors open and close at the window edges.
- [ ] Add a window that touches one (17:00 to 18:00): it joins into 15:00-18:00. A fifth separate window is refused with the HH:MM line. 15:00 to 15:00 is refused. A window across midnight (22:00 to 02:00) lights both ends of the bar.
- [ ] The x on a chip removes that window; with one window left there is no x. A player (not admin) sees the bar and chips but no boxes or x.
- [ ] An old Real clock portal (set before this jar) shows its one window and still opens and closes as before.
- [ ] `/bsptp portal mode realclock 15:00-17:00 03:00-05:00` sets both; `/bsptp portal mode realclock 15:00 17:00` sets one.
- [ ] Admin panel, WARPS, Rules of a warp: the TIME pill shows two boxes; Add puts a "time" row with a small day bar. Adding a second window joins it into the same row.
- [ ] As a player outside the hours: `/warp <name>` and GO on the band are refused with "Closed now. Opens at 03:00 (Europe/London)."; the band row has a red edge, an OPENS 03:00 chip and a dim GO. Inside the hours: a green OPEN chip and GO works. An admin passes at any time.
- [ ] Start a warp's warm-up just before it closes: at the end the move is refused and no coins are taken.
- [ ] A portal with several destinations, one a closed warp: in the chooser that entry is greyed with the closed line. A portal whose only destination is that warp refuses with the same line on the action bar.
- [ ] A TIME rule on a portal's ACCESS tab: outside the hours players are refused at the portal (doors stay as they are).
- [ ] Clients and servers all on this jar (network protocol 4).

## Random teleports and the world border (T83, D160, 2026-10-28)

- [ ] A Region Guard border of about 2000 across with `randomTeleport.maxRadius = 1000000` and `useBorder = false`: `/rtp` ten times finds a spot each time (before, most tries fell outside the border), never outside the border or within 16 blocks of its edge.
- [ ] `useBorder = true` (config or the panel's SETTINGS tab, "Random: whole border as range"): spots spread over the whole border, out to its corners; none closer to spawn than `minRadius`.
- [ ] No Region Guard border but a vanilla `/worldborder set 3000`: the same, with the vanilla border as the limit.
- [ ] No border at all and `useBorder = true`: `maxRadius` still limits the range.
- [ ] A random portal and a remote `/rtp <server>` behave the same way on the target server.

## Paid warps on the band (B23, 2026-10-31)

- [ ] `/setwarp market 100`, then with fewer than 100 coins in your inventory: GO has a gold outline and a grey label. Pressing it closes the band and chat says "This warp costs 100 coins and you are carrying ...". No warm-up, nothing taken.
- [ ] With 100 coins or more in your inventory: the label is gold, the warm-up runs, the coins are taken at the end and you arrive.

## Warp cost on the admin panel (B24, 2026-10-31)

- [ ] WARPS tab: a free warp's row has a gold FREE key where the minus and plus keys were. A warp set with `/setwarp market 100` shows 100C, and the same amount is no longer repeated in the small text under its name.
- [ ] Press the key: the bottom strip reads COST OF MARKET with a box holding the cost, selected, and SAVE and CANCEL. The name box and Set warp here are gone until it closes.
- [ ] Type 25 and press SAVE: the key reads 25C, chat confirms "Cost: 25", and the band's row and `/warp` charge 25. Pressing Enter saves the same way.
- [ ] Set it to 0: the key reads FREE and the warp costs nothing.
- [ ] Letters cannot be typed in the box. An empty box and SAVE leaves the cost alone.
- [ ] Esc or CANCEL closes the strip with no change and leaves the panel open. Changing tab, or pressing Rules, closes it too.
- [ ] Press E while the box has focus: the panel stays open.
- [ ] A long warp name: the label is cut with "..." and does not run into the box. The row's keys do not cover the name's first letters.
- [ ] As a moderator: no cost key, and the cost still shows in the small text.

## /warp tab completion (B25, 2026-11-02)

- [ ] As a player without `bsp_teleport.warp.<name>`: `/warp ` and Tab lists the warps the band lists, and no restricted warp or portal warp.
- [ ] Give the player's group the node in LuckPerms: the restricted warp is offered at once (no relog).
- [ ] As an admin: restricted warps are offered; portal warps are not. `/delwarp ` still offers every warp, portal warps included.

## Bounced players leave the restricted server (B26, 2026-11-02)

- [ ] `access.enabled = true` everywhere, `lobbyServer = ""`, no global spawn warp. Start a backend: its log warns that `access.lobbyServer` is empty.
- [ ] A player with no access to Frostwarf uses the proxy's `/server BSP-Frostwarf` (or logs in with Frostwarf as their last server): chat says they no longer have access, the access popup shows, and they are moved to their main server if it is up, else the first open server that is up. Before, they stayed on Frostwarf at its spawn.
- [ ] The same with `lobbyServer = "BSP-Spawn"`: they go to Spawn.
- [ ] Rent for Frostwarf runs out while the player is there: they go to their main server first, then the lobby, then any open server.
- [ ] Every other server down: the player is disconnected with "You have no access to BSP-Frostwarf, and no other server is up to send you to."

## Players' servers on the band (B27, 2026-11-02)

- [ ] As a player in no team: the PLAYERS tab lists names only, sorted A to Z, with no server under any of them. TPA and HERE still work.
- [ ] A teammate's row shows their server; other rows do not.
- [ ] A teleport request from a non-teammate reads "N s left" with no server; from a teammate, "from BSP-Frostwarf, N s left".
- [ ] As an admin: every row and every request shows the server.

## The shared restricted list switches access on everywhere (D162, 2026-11-02)

- [ ] `access.enabled = true` on Frostwarf only, `false` on Spawn; Frostwarf restricted on the NETWORK tab. On Spawn a player without access uses random teleport (band or `/rtp BSP-Frostwarf`), a warp or a portal to Frostwarf: refused with the access popup, and they stay on Spawn. Before, Spawn let them go.
- [ ] On Spawn the HUD and the band's ACCESS list show, although Spawn's own switch is off.
- [ ] Un-restrict every server on the NETWORK tab: on Spawn the HUD goes and nothing is checked; on Frostwarf (switch on) the system stays on.
- [ ] A server with no database (`storage` off): only its own switch counts, as before.

## Bring across servers (B28, 2026-11-02)

- [ ] Admin on Spawn, a player on Frostwarf: the panel's bring (or `/bsptp bring <player>`) moves the player to Spawn, beside the admin. Before, they were put at the admin's coordinates on Frostwarf, in the air.
- [ ] Bring all from one other server, and from every server: everyone arrives on the admin's server beside the admin.
- [ ] Bring a player on the admin's own server: as before, a local move.

## Warps are free for admins and operators (B29, 2026-11-02)

- [ ] As an admin, or an operator (level 2) who is not on BSP-Core's admin list, with no coins: a warp with a cost shows no price and a turquoise GO on the band; GO, `/warp` and the warm-up work, nothing is taken, no "paid" line. `/warps` lists it without a price.
- [ ] As a player: the cost shows, is checked and is taken as before.

## Config edits after /bsptp reload (B30, 2026-11-02)

- [ ] On a running backend, run `/bsptp reload`. Then set `suppressJoinLeaveMessages = true` in `<world>/serverconfig/bsp_teleport-server.toml` and save. Without another reload, switch a player away and back: no "left the game" or "joined the game" from this backend (Forge reloads the file by itself).
- [ ] Change any setting on the panel's SETTINGS tab, then open the file: `suppressJoinLeaveMessages` is still `true`. Before, the panel wrote the old value back.
- [ ] `/bsptp reload` after an edit still applies it at once.

## Back after dying (B31, 2026-11-02)

- [ ] With no bed, die away from spawn (note the spot). Respawn: you are moved to the server's spawn point as before. Press Back on the band: you go to the death spot, not to a spot in the air above the world spawn.
- [ ] Press Back again: you go to where you stood when you pressed it the first time.
- [ ] First join on a server (placed at its spawn) and an access bounce: Back still goes to the last spot you teleported from.
- [ ] Teleport across servers, then press Back on the band: you return to the spot on the first server.

## Logging in inside a portal (B32, 2026-11-02)

- [ ] Walk into a portal to another server and quit the game (or kill the client) during the one-second hold, before the switch. Log in again: within about a second you are moved to the spot in front of the portal, facing away from it, and the portal does not fire. The server log says "logged in inside portal <name>; moved in front of it".
- [ ] The same with a block (or your totem) on the spot in front: you are put behind the portal instead ("moved behind it").
- [ ] Both sides blocked: two or three blocks out on either side; with every spot blocked, the server's spawn (a warning in the log).
- [ ] A cross-server portal whose destination server is down at the moment of the switch: when the proxy puts you back, you land in front of the portal, not in it, and nothing loops.
- [ ] A player who arrives at a portal's own arrival spot (a link) is not moved.

## Warp and portal costs in pieces (D163, 2026-11-02)

- [ ] WARPS tab: press a warp's cost key. The strip shows COST OF <NAME>, the amount, and PIECES and COINS (a free warp starts on PIECES, a warp set before keeps COINS lit). Type 5000, keep PIECES, Save: the key reads `5,000P`. Pick COINS, type 25, Save: it reads `25C`.
- [ ] As a player with 10,000 pieces in the bank and no coins: the band shows "5,000 pieces" on the warp with a gold GO. GO: after the warm-up you arrive, the chat says "Paid 5,000 pieces from your bank.", and the bank's log names BSP Teleport.
- [ ] With 1,000 pieces: GO is grey; pressing it (or `/warp`) says "This warp costs 5,000 pieces and your bank holds 1,000 pieces." Nothing is taken.
- [ ] A warp still on coins: as before, coins come out of the inventory.
- [ ] Core screen, ACCESS tab: add a PIECES rule of 2000. Walk through with enough pieces: 2000 are taken and you go. Without enough: "This costs 2,000 pieces from your bank." and you stay. With a warm-up, the pieces are taken only at its end.
- [ ] A warp with a Pieces rule and a coins cost: both are checked first, and either both are paid or neither.
- [ ] On the network (bank in MySQL): the move happens a moment after the warm-up ends, once the bank answers; walking through a priced portal twice quickly charges once.
- [ ] As an admin: nothing is taken in either currency.
- [ ] Existing warps after updating: their cost key still reads `...c` and they still charge coins.

## No charge for a move that does not happen (B34, 2026-11-03)

- [ ] A priced warp whose world is gone (or rename the world in its saved position): GO. "You did not move, so what you paid has been given back." The coins or pieces are back and there is no cooldown.
- [ ] A priced warp or portal on another server that is stopped (but still listed): pay and go. After 45 seconds you get the same line and everything back (pieces in the bank log as "sold to BSP Teleport", coins in the inventory, used-up items in the inventory or at your feet). The server log says "paid move to '<server>' did not happen".
- [ ] The same to a server that is up: you arrive, nothing comes back, and the origin server's log has no refund line.
- [ ] Pay for a portal to another server and quit during the one-second hold: on your next login the pieces, and the coins as pieces, are in your bank ("left before their paid move ... started" in the log). An item rule's items come back at your next login on that server.
- [ ] `/tpnow` (with `skipCooldownCoins` set): the warm-up starts and nothing is taken yet. Walk to cancel: nothing is taken and the cooldown is still there. Try again and stay still: the price is taken at the end with the move ("Paid N coins to skip the cooldown.").
- [ ] Not covered: another server that takes you in and then sends you back counts as a move made.


## /spawn goes to the network spawn (B35, 2026-11-03)

Setup: the network spawn set with `/bsptp setnetworkspawn` on BSP-Spawn; `spawn.globalWarp` left empty (as on every live server); each other server keeps its own `/setspawn` point.

1. On another server (for example Moongrove), run `/spawn`. After the warm-up you are moved to BSP-Spawn and land at the network spawn, not that server's world spawn.
2. Open the band, press Spawn. Same result.
3. On BSP-Spawn itself, run `/spawn`. You move to the network spawn there, with no server switch.
4. Die on Moongrove with no bed. You still respawn at Moongrove's own spot (respawns and first visits keep the server's own point, D142, D143).
5. Optional: delete the `spawn` warp, then run `/spawn` on Moongrove. You go to Moongrove's own `/setspawn` point. With neither set, the message is "No network spawn is set (the warp spawn). Ask an admin to set it."

## Asking before a home is deleted (B36, 2026-11-03)

1. Open the band, HOMES tab, press DEL on a home. The band dims and a gold box asks "Delete the home <name>?" with YES and NO. The home is still listed.
2. Press NO. The box closes and the home is still there. Press DEL again, then Esc: same, and the band stays open.
3. With the box up, click the list, a tab, or the name box beside it, scroll, and press Enter. Nothing happens.
4. Press DEL, then YES. The home is deleted and the list updates.
5. `/delhome <name>` still deletes without asking (on purpose). The panel's warp Del still asks as before.

## /tpnow after a refused /rtp (B37, 2026-11-03)

Setup: `teleport.skipCooldownCoins` above 0 (for example 5), a cooldown of a minute or more, and coins in your inventory.

1. Run `/rtp`, then `/rtp` again while the cooldown runs. You are told to wait or pay with `/tpnow`.
2. Run `/tpnow`. A random spot is searched for, the warm-up starts, and at its end you land somewhere new and the price is taken. Before, `/tpnow` took the price and left you where you stood.
3. Repeat with `/rtp <another server>`: `/tpnow` asks that server for a spot ("asking ..."), then the warm-up and the move to it, the price taken with the move.
4. Run `/tpnow` after a refused `/rtp` in a world where /rtp is off, or with no safe spot found: nothing is taken, and the next teleport still has its cooldown.

## A player without access is moved off only once their client is in (B38, 2026-11-03)

Setup: server access on, a player whose main server is not Moongrove and who has no token for it, and Velocity Additions remembering Moongrove as their last server (they were last there). Keep `access.refuseAtLogin = false` (the default) on Moongrove; with it on, B39 below refuses them before they get in.

1. The player logs in. They land on Moongrove, see "You have no access to BSP-Moongrove" and, once the world has loaded, "Leaving for BSP-Spawn in 10 s" above the hotbar.
2. About 10 seconds after the world shows, the one-second hold, then the switch to BSP-Spawn. No "Can't serialize unregistered packet" kick.
3. Log out and back in: Velocity Additions now sends them to BSP-Spawn directly.
4. A player with access who warps to another server straight after logging in waits only `switchGapSeconds` (3) from the moment their world showed.
5. If it still kicks: send the client's `logs/latest.log` lines starting "BSP-Teleport diagnostic", which name the packet and the mod that sent it.

`network.placeGapSeconds` (10) is new in `bsp_teleport-server.toml`; Forge adds it at the next start.

## A player without access is refused while still logging in (B39, 2026-11-03)

Off by default and not for the live network: behind Ambassador the refusal comes after the Forge handshake has started and the proxy drops the player with "Connection reset" (seen live on Stormfall, 2026-11-03). Only for a proxy without Ambassador.

Setup: as for B38, with `access.refuseAtLogin = true` on Moongrove, and LuckPerms' Forge edition on the servers.

1. The player logs in. They never see Moongrove: the proxy sends them straight to BSP-Spawn, and the chat says they were kicked from BSP-Moongrove: "You have no access to BSP-Moongrove." No "Can't serialize unregistered packet".
2. Moongrove's log: "<name> has no access to BSP-Moongrove: refused at login, so the proxy sends them on". The proxy's log: "<name> could not join BSP-Moongrove; sending them to BSP-Spawn".
3. They log out and in again: they go to BSP-Spawn directly (it is now their last server).
4. An admin (operator, a name in BSP-Core's `admins`, or `bsp_teleport.admin` in LuckPerms) logs in to Moongrove without a grant: let in as before.
5. A player who rents Moongrove, or has it as their main server, logs in there: let in.
6. While on BSP-Spawn, `/server BSP-Moongrove` (the proxy's own command) without access: the proxy says they could not join and they stay on Spawn, or, if Ambassador cannot put the client back, they are asked to reconnect and land on Spawn. Tell me which.

## Set home asks before moving or replacing a home (B40, 2026-11-03)

Setup: a player with three home slots and homes `base`, `farm`, `mine`, standing somewhere new. The client needs this jar too (band protocol 6).

1. Band, HOMES tab: type `base` (or `BASE`), press Set home. The Set home strip turns into "Move base here?" with MOVE and KEEP above a gold line; the home list above still works.
2. KEEP (or Esc): nothing changes, and `base` is back in the name box. MOVE: chat says "Home base moved here." and its row shows the new spot.
3. Empty name box with a home called `home`: Set home asks "Move home here?" the same way.
4. Type `tower`, press Set home: "All 3 homes used. Replace one with tower:" with a gold key per home and KEEP. Press FARM: chat says "Home farm replaced by tower.", and `tower` is in farm's place (second in the list), with the default icon.
5. With more homes than slots (locked homes), only the unlocked ones are offered.
6. Chat: `/sethome base` says "You already have a home base. [Move it here]"; clicking runs `/sethome base confirm`. `/sethome tower` with every slot used lists `[base] [farm] [mine]`; clicking one runs `/sethome tower replace <home>`.
7. On a second server: the band and `/homes` there show the replaced home (the change reaches other servers).
8. Where homes may not be set (Region Guard, a raid zone), the band still asks first, then MOVE or a replace key says "You cannot set a home here." and nothing changes.

## Ally teleports (BSP-Core A-1 to A-3, 2026-11-03)

Setup: BSP-Core 1.10.0 on every server, and this jar on the servers and in the client pack (band protocol 7). Team Red with player A; team Blue with player B and C; Red and Blue in one alliance. Blue shares its coordinates, Red does not.

1. A opens the band, TEAM tab: a MY TEAM / ALLIES switch sits at the top. MY TEAM is the old team list. ALLIES lists B and C with "Blue" after their names in Blue's colour, "here" or "on <server>", the dimension, and their coordinates.
2. B's band, ALLIES: A is listed without coordinates (Red does not share them).
3. A presses ASK on B (same server). A: "Asked B. They have 30 s to accept." B: "Ally A (Red) wants to teleport to you. [Accept] [Deny] 30 s", and the band's request line. B clicks Accept: A gets the warm-up, then lands on B. No coins taken.
4. Again, B clicks Deny: A is told B denied. Again, B waits 30 s: the request is gone ("You have no teleport request" on /tpaccept).
5. B turns on /tpignore: A's ASK says "B is not taking teleport requests."
6. Cross-server: B on another server. A's ASK reaches B there; on Accept A gets the warm-up and the switch, landing where B stood.
7. BSP-Core's Team screen, ALLIES page: TELEPORT on B does the same as ASK.
8. While A's ask waits, Blue leaves the alliance on A's or B's server: the waiting ask is dropped and both are told. During A's warm-up: the warm-up is cancelled ("Your alliance ended"). If the alliance ends on a third server, the accept or the end of the warm-up refuses instead ("You and B are no longer allies").
9. A starts a steal: ASK says they cannot while stealing. B starts a steal: A is told B is stealing; an Accept while one of them steals refuses too.
10. A steal runs within 150 blocks of B: B's row shows RAID and no ASK; an accepted move there is refused.
11. A team in no alliance: no switch on TEAM, the tab is as before.

## The Access Shop (D174 to D180, 2026-11-04)

Setup: access on, two restricted servers with tokens assigned (for example II for BSP-Frostwarf, IX for a portal, XII for a warp), this jar on the server and in the client pack (band protocol 8). BSP-Core must be on the client (the shop's steel comes from its textures).

1. As an admin, take the Access Shop from the creative tab and place it: it stands two wide and two tall, facing you, in the Trade Post's steel, with "ACCESS TOKENS. Right-click to buy." on its screen. A non-admin cannot place it, and cannot break it in survival.
2. Click it as the admin: the shop opens with SETTINGS. In SETTINGS set a name and a message, then on SERVERS press + ADD: token II appears. Set RENT on, 72 hours, price 25000, PIECES; FULL on, 200000. On PORTALS add IX with rent only, price 40, COINS. SAVE: the screen on the block shows the new message and the coins of the first tokens.
3. Place a second shop. In its SETTINGS, COPY FROM lists the first shop by name and position; COPY gives it the same list and message, and it keeps its own name. Change its prices and SAVE: the first shop is unchanged.
4. A player with no main server clicks the shop: the free main-server pick opens. After choosing, the shop opens.
5. That player, on SERVERS: the main server's card says YOUR MAIN SERVER. On II, RENT shows 25,000p and 72 h; FULL shows 200,000p. BUY: the box asks "Rent 72 hours of access to BSP-Frostwarf for 25,000 pieces?" with USE NOW, TAKE TOKEN and CANCEL. CANCEL and Esc take nothing.
6. USE NOW: the pieces are taken, chat says "Access to BSP-Frostwarf for 3d 0h", the card shows "Rented: ... left". Buying rent again adds time.
7. TAKE TOKEN: the pieces are taken and Access Token II (rent, 72 h) goes into the inventory; right-clicking it grants the access.
8. FULL with USE NOW: the card then says OWNED and has no BUY.
9. With too few pieces or coins the price shows red and BUY is grey.
10. Breaking any part as an admin removes the whole shop; the other shop's COPY FROM no longer lists it.

## /tpall to <server> (2026-11-04)

Setup: three servers on Redis (A, B, C), players on each, an admin on A. B has a `/setspawn` point.

1. On A: `/tpall to B`. Everyone on A and C goes to B's `/setspawn` point, each told who moved them; the players already on B stay where they are; the admin stays on A. Chat: "Sending everyone on the network to B (n from this server)."
2. Without a `/setspawn` on B (or without the database): the same, landing wherever B puts arriving players.
3. On A: `/tpall to B 100 70 -200`. Everyone, those on B too, lands at 100 70 -200 in B's overworld, keeping their own facing.
4. `/tpall to B 0 80 0 nether` (or `end`, or a full id in quotes such as `"bsp_core:mining"`): the same in that world.
5. `/tpall to A 10 64 10`: everyone on B and C comes across to that spot on A; players on A are moved there too.
6. A server that is down: "B is not online." and nothing moves. Without Redis: "Redis messaging is off, so other servers cannot be reached." and nothing moves.
7. Everyone moved can `/back` to where they were.
