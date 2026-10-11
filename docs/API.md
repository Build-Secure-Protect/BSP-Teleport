# BSP Teleport API

Other mods compile against the package `com.mrgregles.bsp_teleport.api` only. Everything outside it may change without notice.

```java
BspTeleportApi api = BspTeleportApi.get();   // available from the mod constructor on
```

`BspTeleportApi.API_VERSION` is raised whenever a method is added or changed.

## Forced moves

These skip warm-up, cooldown, cost and region checks. They are for rules that have already decided the player must move (Region Guard triggers, admin tools).

| Call | What |
|---|---|
| `serverName()` | this server's name in the proxy (config `network.serverName`) |
| `teleport(player, destination)` | move to a `Destination(server, world, x, y, z, message)`; empty `server` or this server's name means a local move (dimension change allowed); another server sends the player through the proxy and lands them on arrival |
| `sendToServer(player, server)` | proxy move only; the target server decides where they appear |
| `sendAllToServer(server)` | every player on this server; usable from `ServerStoppingEvent` |

`Destination.serialize()` / `Destination.parse(String)` give the wire form `server;world;x;y;z;message`.

## Cross-server landings

A cross-server `teleport` announces the landing to the target server, then asks the proxy to move the player; the target server lands the player when they log in (or as soon as the announcement arrives, if the player got there first). The announcement travels through a shared message store. Today that store is Region Guard's (`RegionGuardApi.publish`, kind `bsp_teleport.landing`), so landings work only when Region Guard is on both servers with storage on; without it the player is still moved and appears wherever the target server puts them. BSP Teleport's own MySQL and Redis transport replaces this in phase 2 without changing the API.

## Region Guard

When Region Guard is loaded, BSP Teleport listens for `RegionTriggerEvent` with a cross-server destination, cancels it and performs the move; same-server triggers are left to Region Guard. Teleports and homes ask Region Guard's `teleport-out`, `teleport-in` and `set-home` flags.
