![BSP-Teleport](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-Teleport/main/docs/curseforge/images/banner.png)

# BSP-Teleport

**Homes, warps, portals and server access across the Build Secure Protect network.** Every player gets a Teleport Band on their hotbar that takes them home, to a warp, to a friend or to a random spot on any server in the network. Admins build walk-through portals with hangar doors, and sell access to servers, regions, portals and warps with Access Tokens. Built around [BSP-Core](https://www.curseforge.com/minecraft/mc-mods/bsp-core)'s Shatter Totem: your totem travels with you, and nothing here can take you into a raid for free.

- Minecraft 1.20.1, Forge 47 or newer, Java 17. Needed on the client and the server.
- Requires BSP-Core 1.2.0 or newer. BSP-Region Guard and BSP-RankUp are optional and switch more on.
- Made for a Velocity network of Mohist backends with Proxy Compatible Forge, one MySQL database and one Redis. Runs on a single server with no database too.
- MIT licence, source on [GitHub](https://github.com/Build-Secure-Protect/BSP-Teleport).

---

## The Teleport Band

![The band slot](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-Teleport/main/docs/curseforge/images/band-slot.png)

The first thing you see: a tenth hotbar slot after the nine, drawn for every player. Scroll past the last slot, or press the band key (`.` by default, rebindable), and the band is selected; right-click opens its screen. Your hand keeps whatever it was holding. Nothing to craft, nothing to lose on death.

![The band screen](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-Teleport/main/docs/curseforge/images/band-screen.png)

**Homes.** Set one where you stand, on any server, and go there from anywhere on the network. You start with three slots; the Extra Homes power on your Shatter Totem adds more. Lose the totem and the extra homes are not deleted, only locked until you earn the slots back. Homes can be public (`/phome <player>`), carry a description and show an icon you pick by clicking with an item in hand.

**Warps.** Admin-set places with a coin cost and, if the admin says so, a permission node. **Players.** Everyone online across the network: ask to teleport to them, ask them to come to you, answer requests. **Admin.** The panel, for admins and moderators.

![The MORE tab](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-Teleport/main/docs/curseforge/images/band-more.png)

**More.** Spawn and Back are one click. Random teleport lands you in a ring around spawn, inside the world border, never in lava or on a cactus, never near a raid; pick another server and that server finds the spot. Pay Shatter Coins to skip a cooldown, switch request ignoring on, and see your server access.

Every move has a warm-up with a countdown and sounds, cancelled if you move or get hit, and a cooldown that the Swift Travel power shortens. Cross-server moves go through the proxy and land you exactly where you asked. `/back` is saved on every move, even the forced ones, so after a restart evacuation one command brings you home.

Commands do the same as the screen: `/home`, `/sethome`, `/warp`, `/spawn`, `/back`, `/rtp [server]`, `/tpa`, `/tpahere`, `/tpaall`, `/tpaccept`, `/tpdeny`, `/tpignore`, `/tpnow`, `/edithome`, `/phome`.

## The rules that keep raids honest

The whole pack is built on one promise: nobody can make a Shatter Totem impossible to steal. So:

- A teleport is not a logout. BSP-Core takes your carried totem the moment you leave a server and hands it back when you arrive, so it is never left behind and never sits somewhere nobody can reach.
- Nothing lands within 150 blocks of a totem of yours while it is being stolen, on any server in the network. The Recall power is the quick way back to a raid, and the only one. Random teleports keep 200 blocks away.
- A thief who just took a totem cannot teleport for the invincibility window.
- Region Guard's regions and BSP-Core's Anti Totem zones can forbid teleporting in, teleporting out, or setting a home.

## The admin panel

![The admin panel](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-Teleport/main/docs/curseforge/images/admin-network.png)

`/bsptp admin`, or the ADMIN tab on the band. Moderators see everything and can press nothing. NETWORK draws the servers as a map with their players: bring everyone here before a restart, send everyone to another server, go there, bring one player or go to them, and switch "needs access" on for a server (more on that below). SETTINGS edits the config values you change often and saves them to the file at once. WARPS sets a warp where you stand, moves it, prices it, restricts it, gives it an icon, turns it into a portal warp, or opens its rules.

---

## Portals

![Blocks and items](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-Teleport/main/docs/curseforge/images/blocks-and-items.png)

Two admin blocks make a portal: **Portal Frame** and **Portal Core**. Build a ring of frame blocks in one vertical plane, any shape you like, even with several openings, up to 24 by 24, and put the core in the ring (or against it). Only admins can place or break them, and TNT, pistons and endermen leave them alone. The core's face is the side players arrive on; flip it from the screen.

![A portal, open and closed](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-Teleport/main/docs/curseforge/images/portal-open-closed.png)

Give the core a destination and the opening fills with a lit field in one of 24 colours. Walk in and you are through: no warm-up, no cooldown, and the portal you land beside will not fire again until you walk away. Five seconds before the same player may use the same portal twice, so nobody bounces back and forth. The raid rules above still apply.

![The core screen](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-Teleport/main/docs/curseforge/images/core-screen.png)

Right-click the core for its screen. **Destination** is one of five:

- **Warp**: any existing warp.
- **Portal warp**: a warp only portals can reach. The band never shows it and `/warp` refuses it, so a portal can lead somewhere a bracelet cannot.
- **Random**: a random spot on this or any other server, with that server's rules.
- **Position**: where you stand.
- **Link**: a link id. Give the same id to one other core anywhere on the network and the two are joined, two-way, or one-way with "arrive only".

**Look** sets the colour, the door style, the arrival side, the name and an optional arrival line.

![Open and close](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-Teleport/main/docs/curseforge/images/core-doors.png)

**Open/close** picks how the portal opens: by hand, by a redstone signal into the core, on a real-time clock in the server's time zone, or on the game clock. A Lock forces it closed in any mode. With "close when the destination is down" on, the doors shut by themselves while the partner portal or its server is away, and reopen when it is back.

Closed portals grow hangar doors that slide in from both ends of every row and meet in the middle, hazard stripes facing each other. Two styles: solid **blocks** that fill in cell by cell, or smooth **rendered** panels. Either way the wall is real.

![Portals on the panel](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-Teleport/main/docs/curseforge/images/admin-portals.png)

The panel's PORTALS tab lists every portal on the network under its server, or draws them as a map with the links between them. Go to any of them, open, close, lock or unlock it, even on another server. `/bsptp portal <name> open|close|lock|unlock|tp|info` does the same from a command block.

---

## Server access

Off by default (`access.enabled` in the config), because it is made for the BSP network: four servers to build on, and a player must choose one.

**How it works for a player.** The first time you try to enter a server that needs access, you are asked to choose your main server. One choice, free, for ever: your base goes there and you always have access. Every other server you rent for a while or buy outright, with Access Tokens from the shop.

![The HUD](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-Teleport/main/docs/curseforge/images/access-hud.png)

What you may enter sits above your hotbar: a gold frame for your main server, turquoise for full access, violet with a countdown for rented. Three layouts, Row, Strip or Column, picked on the band's MORE tab. Rent warns you an hour, ten minutes and a minute before it runs out; at the end you are moved to your main server.

**Access Tokens.** Twenty items, Access Token I to XX, one colour each. An admin makes a token stand for a server; a shop sells it as a rent stack (so many hours) or a full stack. Buy one, keep it until you want it, give it to a team mate, right-click it to use it. Rent adds up; full access replaces rent. They sell in BSP-Core's shops like any other item.

![Access needed and choose](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-Teleport/main/docs/curseforge/images/access-popups.png)

A refused teleport, portal or warp tells you what you need: the server, the token that sells it, and where the admin says it is sold. A player who arrives on a restricted server by any other route, or whose rent ran out while they were away, is sent to the network's main spawn.

![The Access Board](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-Teleport/main/docs/curseforge/images/access-board.png)

**The Access Board** helps a new player choose: an admin block whose panels join into one screen, like BSP-Core's Score Screen, showing how many players call each server home and how many hold full or rented access. Three styles per board: Bars, Tiles or Columns.

![Assigning tokens](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-Teleport/main/docs/curseforge/images/admin-tokens.png)

**Setting it up.** On the panel's NETWORK tab switch "needs access" on for each server that needs it. On the TOKENS tab pick a token and click the server it stands for, set the rent length and a shop hint, and press Rent or Full to get the stack for your shop. The same tab puts tokens on admin regions, portals and warps, so a token can sell access to a single arena, a single portal or a single warp. Everything is shared through the database, so every server agrees.

## Rules on portals and warps

Any portal or warp can carry rules an admin sets on the portal's ACCESS tab or the warp's Rules button on the panel: a **rank** (a LuckPerms group, this or better), a **held item** with a count, used up or not, a **permission node**, **coins per pass**, and **token**, meaning bought access through an Access Token. Every rule must pass; admins always pass. Coins are charged and items taken only when the move actually happens.

---

## With the other BSP mods

![Compatibility](https://raw.githubusercontent.com/Build-Secure-Protect/BSP-Teleport/main/docs/curseforge/images/compat.png)

**BSP-Core** (required). Totems travel with you on every cross-server move. When your totem is being stolen on another server, BSP-Core's Recall offer brings you back through this mod and lands you beside it. The raid distances, the thief's block and the Anti Totem zones' teleport rules come from BSP-Core's events and API. Extra Homes and Swift Travel on the totem tree give more home slots and shorter cooldowns. Warps and rules charge Shatter Coins; Access Tokens sell in BSP-Core's shops; a full season reset wipes homes.

**BSP-Region Guard** (optional). Regions forbid teleporting in or out and setting homes with their flags. Region triggers (enter, exit, timer, shutdown) and world-edge links that lead to another server are carried out by this mod, with the landing exactly where the trigger says. Random teleport stays inside the world border. Set a region's `entry` flag to deny and assign a token to it: players who bought access walk in, everyone else is kept out. Shutdown triggers evacuate everyone to a holding server; `/back` brings them home.

**BSP-RankUp** (optional). RankUp's ranks are LuckPerms groups, so a rung's name is a rank rule on any portal or warp: "Warden or better" gates the portal to the arena.

---

## Installing

1. Put the jar in `mods/` on every backend and on every client, next to BSP-Core 1.2.0 or newer.
2. Start each server once. The config appears at `<world>/serverconfig/bsp_teleport-server.toml`.
3. On a network: `storage.mode = "mysql"` with your database (BSP-Core's can be shared, the tables are prefixed), `redis.enabled = true` with your Redis, and `network.serverName` set to this server's Velocity name on every backend. Without a database the mod keeps everything in the world save and works on one server.
4. Set `access.enabled = true` if you want the server access system, and `access.lobbyServer` to the network's main spawn server.
5. `/bsptp status` shows the storage and Redis state; `/bsptp reload` re-reads the file.

Players get a client config at `config/bsp_teleport-client.toml` with the screen scale and the HUD layout.

## Commands

| For players | What |
|---|---|
| `/home [name]`, `/sethome [name]`, `/delhome <name>`, `/homes`, `/edithome <name> rename\|relocate\|description\|public\|icon`, `/phome <player> [home]` | homes |
| `/warp <name>`, `/warps`, `/spawn`, `/back`, `/rtp [server]`, `/tpcancel`, `/tpnow` | moving |
| `/tpa`, `/tpahere`, `/tpaall`, `/tpaccept`, `/tpdeny`, `/tpignore` | requests |
| `/mainserver <server>` | the one free main-server choice |

| For admins | What |
|---|---|
| `/bsptp admin`, `/bsptp status`, `/bsptp players`, `/bsptp reload`, `/bsptp send <player> <server>` | the panel and the network |
| `/setwarp <name> [cost] [restricted]`, `/delwarp`, `/setspawn` | warps and spawn |
| `/tpall [all\|from <server>]`, `/tphere`, `/tpto`, `/tppos`, `/tpoffline` | forced moves and evacuation |
| `/bsptp portal ...`, `/bsptp portal <name> ...`, `/bsptp portalwarp set\|del\|list` | portals |
| `/bsptp access servers\|server\|list\|grant\|revoke\|main\|token\|tokens\|stack` | access |

Other mods can move players and talk over the mod's Redis link through `BspTeleportApi`; see `docs/API.md` in the repository.
