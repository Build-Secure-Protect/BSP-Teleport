# BSP-Teleport

Forge mod for Minecraft 1.20.1: cross-server teleports, homes and warps for the Build Secure Protect (BSP) modpack. Companion to BSP-Core (`bsp_core`), which it requires.

## Before doing anything
1. Read the Obsidian vault via the `obsidian-bsp` MCP connection, starting with `BSP-Teleport/Claude Notes/Teleport - Handoff (Start Here).md`.
2. Check `BSP-Teleport/Claude Notes/Teleport - Task Queue.md` and `Teleport - Design Decisions.md`.
3. Check session usage and warn the owner if the task will not fit.

## Rules
- Never `git push`. The owner pushes manually. Commits are fine when asked.
- Record notes only under `BSP-Teleport/` in the vault. BSP-Core's notes are read-only; changes needed in BSP-Core go in `Teleport - Requests for BSP-Core.md`.
- Work in short, queueable tasks. Put open questions in chat with a default for each.
- UI and visuals: three mock-up options first.
- Claude cannot run the Minecraft client. Say plainly when something is built but untested in game.

## Conventions
- Java 17, Forge 47.4.10, official mappings, MIT license. Mod id `bsp_teleport`, package `com.mrgregles.bsp_teleport`.
- Every call into BSP-Core goes through `compat/BspCore.java`.
- BSP-Core is compiled against from `../BSP-Core/build/libs` (version in `gradle.properties`). Build BSP-Core first.
- Network: Velocity proxy, Mohist backends, Proxy Compatible Forge. MySQL for data, Redis for cross-server messages, own table prefix.

## Build
`./gradlew build` (jar in `build/libs/`).
