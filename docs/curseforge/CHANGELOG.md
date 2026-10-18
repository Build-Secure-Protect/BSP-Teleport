# BSP-Teleport 0.1.0

First release. Minecraft 1.20.1, Forge 47.4, Java 17. Needed on the client and the server. Requires BSP-Core 1.2.0 or newer; BSP-Region Guard and BSP-RankUp are optional. Built for a Velocity network of Mohist backends with Proxy Compatible Forge, sharing one MySQL database and one Redis; it also runs on a single server with no database at all.

## What it does

Homes, warps, spawn, back, random teleports and player requests across every server of the Build Secure Protect network, all from the Teleport Band, a tenth hotbar slot every player has. Admins build walk-through portals out of blocks, with hangar doors that open and close, and sell access to servers, regions, portals and warps with Access Token items. Your Shatter Totem travels with you on every move and nothing can take you into a raid for free.

## Features

**The Teleport Band**
- A tenth hotbar slot after the nine, drawn for every player. Scroll past the last slot or press the band key (default `.`) to select it; right-click opens its screen. Your hand keeps its real item.
- HOMES: your homes with world and server, Set Home Here, delete. Home slots come from the config plus your totem's Extra Homes power; homes above your slots are kept but locked, oldest first, until you earn the slots back. Homes can be public, carry a description and an icon.
- WARPS: every admin warp you may use, with its coin cost and icon.
- PLAYERS: everyone online on the network; ask to teleport to them or bring them to you, and answer requests.
- MORE: spawn, back, random teleport here or on another server, ignore requests, pay coins to skip a cooldown, and your server access.
- ADMIN tab for admins and moderators.

**Teleports**
- Warm-up with a countdown and sounds, cancelled by damage or by moving; cooldown shortened by the totem's Swift Travel power and skippable for Shatter Coins with `/tpnow`.
- Cross-server moves through the proxy, with the landing written to the shared database so you arrive exactly where you asked.
- `/back` saved on every move, forced ones too, so a player evacuated before a restart comes home with one command.
- Random teleport in a ring around spawn, inside the world border, never on lava, fire, cactus or the other unsafe blocks, Nether-aware, and never within 200 blocks of a raid. Ask another server and it finds the spot.
- Requests: `/tpa`, `/tpahere`, `/tpaall`, accept, deny, ignore; position snapshots so you land where the other player stood when they asked.
- Arrival invulnerability, optional, in the config.

**The BSP rules**
- A teleport is not a logout: BSP-Core carries your Shatter Totem across with you and hands it back on arrival.
- Nothing lands within 150 blocks of a totem of yours being stolen, on any server, so the Recall power stays the only quick way back to a raid. A thief who just took a totem cannot teleport for the invincibility window.
- Region Guard's teleport flags and BSP-Core's Anti Totem zones can forbid teleporting in, out, or setting a home.
- Homes are wiped on a full season reset; warps are kept.

**Admin panel** (`/bsptp admin` or the band's ADMIN tab; moderators read-only)
- NETWORK: the servers as a map with player counts; bring everyone here, send everyone to another server, go there; each player with bring, go to, last position; a Needs-access switch per server.
- SETTINGS: the config values admins change often, saved to the file at once; a Reload button.
- WARPS: set, move, cost, restricted, icon, portal switch, delete, and a rules editor per warp.
- PORTALS: every portal on the network under its server, or as a map with the links drawn; go to, open, close, lock, from any server.
- TOKENS: put an Access Token next to a server, region, portal or warp, set its rent length and shop hint, and take the rent and full stacks for a shop.

**Portals**
- Portal Frame and Portal Core blocks (admin-only, immune to TNT and pistons). Any ring shape in one vertical plane, several openings in one frame, up to 24 by 24. The core counts as part of the frame and its face is the side players arrive on.
- Destinations: an existing warp, a portal warp (a warp only portals can reach), a random spot on any server, a position, or a link id shared with one other core anywhere on the network, two-way or arrive-only.
- The opening fills with a lit field in one of 24 colours; walk in and you are through. No warm-up, no cooldown, five seconds before the same player may use it again. The raid rules still apply.
- Open and close by hand, by redstone, on a real-time clock or on the game clock; a Lock; "close when the destination is down" shuts the doors while the partner or its server is away and reopens them after.
- Hangar doors slide in from both ends and meet in the middle, in two styles: solid blocks filling cell by cell, or smooth rendered panels.
- A core screen for admins with DESTINATION, LOOK, OPEN/CLOSE and ACCESS tabs; everyone else is told the portal's name and whether it is open.
- `/bsptp portal ...` for the core you look at, `/bsptp portal <name> open|close|lock|unlock|tp|info` for any portal on the network.

**Server access** (off by default, `access.enabled`)
- Admins tag the servers that need access. A player picks one main server, free and for ever; the others are rented or bought.
- Twenty Access Token items, one colour each. A token is a server's symbol; a shop sells it as a rent stack or a full stack. Right-click to use. Tokens are ordinary items: trade them, give them to a team mate, sell them in BSP-Core's shops.
- The icons of what you may enter sit above the hotbar, with a countdown on rented access, in three layouts you pick: Row, Strip or Column.
- Warnings an hour, ten minutes and a minute before rent runs out; at expiry you are moved to your main server.
- A refused teleport, portal or warp shows a popup naming the server and the token that sells it, with the admin's shop hint. A player with no main server is offered the choice right there, with how many players call each server home.
- Tokens also sell access to admin regions (with Region Guard), to a single portal or to a single warp.
- The Access Board block: panels join into one screen showing how many players call each server home, in Bars, Tiles or Columns.
- `/mainserver <server>` for players; `/bsptp access ...` for admins.

**Rules on portals and warps**
- Any mix of: a rank (a LuckPerms group, this or better), a held item with a count, optionally used up, a permission node, coins per pass, and bought access through a token. Every rule must pass; admins always pass.

**Admin tools**
- `/tpall`, `/tpall all`, `/tpall from <server>` for restart evacuations; `/tphere`, `/tpto`, `/tppos`, `/tpoffline`; `/bsptp status|players|send|reload`.
- Permission nodes `bsp_teleport.admin`, `bsp_teleport.bypass.cooldown`, `bsp_teleport.bypass.warmup`, `bsp_teleport.warp.<name>`, answered by LuckPerms on Mohist.

**Storage**
- MySQL through the server's own driver (nothing bundled), the mod's own tables under its own prefix; the world save on a single server.
- Redis through the mod's own small client for requests, player lists, cache notices and portal commands; everything that needs only the database still works without it.

**For other mods**
- `BspTeleportApi`: forced moves, send to server, publish and subscribe over the mod's Redis link, and a cancellable `TeleportStartEvent`. See `docs/API.md`.
