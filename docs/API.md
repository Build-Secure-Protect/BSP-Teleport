# BSP Teleport API

Other mods compile against the package `com.mrgregles.bsp_teleport.api` only. Everything outside it may change without notice.

```java
BspTeleportApi api = BspTeleportApi.get();   // available from the mod constructor on
```

`BspTeleportApi.API_VERSION` is raised whenever a method is added or changed.

## Forced moves

These skip warm-up, cooldown, cost and region checks. They are for rules that have already decided the player must move (Region Guard triggers, admin tools). Every move first records where the player was, so `/back` brings them home afterwards: evacuate a server with `sendAllToServer` or a `teleport` loop, and once it is back up the players return themselves.

| Call | What |
|---|---|
| `serverName()` | this server's name in the proxy (config `network.serverName`) |
| `teleport(player, destination)` | move to a `Destination(server, world, x, y, z, message)`; empty `server` or this server's name means a local move (dimension change allowed); another server sends the player through the proxy and lands them on arrival |
| `sendToServer(player, server)` | proxy move only; the target server decides where they appear |
| `sendAllToServer(server)` | every player on this server; usable from `ServerStoppingEvent` |

`Destination(server, world, x, y, z, yaw, pitch, message, protect)`; the six- and eight-argument constructors keep the player's facing and use the config's arrival protection (`protect` = -1); `withProtect(seconds)` asks for a set number of seconds (Region Guard trigger landings get 3). `serialize()` / `parse(String)` give the wire form `server;world;x;y;z;yaw;pitch;protect;message`.

## Cross-server landings

A cross-server `teleport` writes the landing to the shared database (`storage.mode = mysql`, table `<prefix>pending`), dismounts them, tells BSP-Core the player is transferring, tells BSP-InventorySync to start saving their inventory (when loaded) and asks the proxy to move them; the target server reads the row when the player logs in and lands them. Rows older than `network.landingSeconds` are ignored. Without the shared database, the landing is announced through Region Guard's message store instead (kind `bsp_teleport.landing`) when Region Guard is present; without either, the player is still moved and appears wherever the target server puts them.

## Messaging

| Call | What |
|---|---|
| `messagingAvailable()` | whether Redis is configured (`redis.enabled`, needs `storage.mode = mysql`) and started |
| `publish(channel, payload)` | sends a line to every other server on the mod's Redis channel; false when messaging is off |
| `subscribe(channel, handler)` | `handler.accept(sourceServer, payload)` on the server thread for each line published on that channel by another server; one handler per channel |

The Redis channel is `bsp_teleport:<redis.cluster>:v<API_VERSION>`, so servers running different API versions never read each other's messages. BSP Teleport uses the same link for its own traffic: player lists, teleport requests and answers, home and warp cache invalidation, random-teleport requests.

## Events (Forge bus, `com.mrgregles.bsp_teleport.api.event`)

| Event | When | Cancel means |
|---|---|---|
| `TeleportStartEvent` | a player asked for a teleport (home, warp, spawn, back, random, request, `PORTAL` when they stepped into a portal (or chose a destination in one, Back included; with a portal warm-up it fires when the countdown starts and again when it ends), or `TEAM` for a teammate from the band or BSP-Core's Team screen); fired after the cooldown and region checks, before the warm-up; `player()`, `destination()`, `kind()` | the teleport is refused; `setReason(text)` tells the player why. Forced API moves do not fire it. |

## Region Guard

When Region Guard is loaded, BSP Teleport listens for `RegionTriggerEvent` with a cross-server destination, whatever its cause (ENTER, EXIT, TIMER, SHUTDOWN, EDGE), cancels it and performs the move to the exact x/z given, with 3 s of arrival protection; same-server triggers are left to Region Guard. Teleports and homes ask Region Guard's `teleport-out`, `teleport-in` and `set-home` flags, and `/rtp` samples inside Region Guard's world border (shrunk by 16 blocks). Both mods must call this server by the same name: `network.serverName` here, `storage.serverId` there; a warning is logged at start when they differ.

## BSP-InventorySync

Optional, never compiled against. When `bsp_inventorysync` is loaded, every cross-server departure (teleports, `sendToServer`, portals, warps, homes, requests, bounces, admin sends) calls its `InventorySyncApi.beginTransfer(player, targetServer)` after BSP-Core has taken the player's totems and just before the proxy `Connect`, so the inventory save starts before the switch instead of at the logout and the arriving server finds the snapshot ready. The answer does not change the departure (false means the sync is off on this server or the player's data is still arriving), and a throw is logged and ignored. Nothing undoes a departure once it has started, so `cancelTransfer` is not called; BSP-InventorySync clears a leftover mark at the next logout. The start-up log says whether the mod was found. Every call goes through `compat/BspInventorySync`.
