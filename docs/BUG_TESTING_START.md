# Starting the bug-testing conversation

The full handoff lives in the Obsidian vault (`obsidian-bsp`):

| Note | What it holds |
|---|---|
| `BSP-Teleport/Claude Notes/Teleport - Handoff for Bug Testing.md` | Rules, where things are, the code by package, what to test in order and what is most likely to be wrong, how to read a report |
| `BSP-Teleport/Claude Notes/Teleport - Handoff (Start Here).md` | The owner's standing rules |
| `BSP-Teleport/Claude Notes/Teleport - Design Decisions.md` | Every decision, D1 to D52 |
| `BSP-Teleport/Claude Notes/Teleport - Task Queue.md`, `Teleport - Changelog (Claude).md` | State and history |

## Message to paste into the new conversation

```
I'm bug-testing BSP-Teleport, my Forge 1.20.1 teleport and homes mod for the Build Secure Protect modpack. It is fully built and has never run in game; I will test and report, you fix.

Before anything else, use the obsidian-bsp MCP connection and read, in this order:
1. BSP-Teleport/Claude Notes/Teleport - Handoff for Bug Testing.md
2. BSP-Teleport/Claude Notes/Teleport - Handoff (Start Here).md
3. BSP-Teleport/Claude Notes/Teleport - Task Queue.md (the status updates at the bottom)

Follow the working rules in both handoffs. Keep notes under BSP-Teleport/ in the vault only; BSP-Core's and Region Guard's notes are read-only. Keep a bug log at BSP-Teleport/Claude Notes/Teleport - Bug Log.md. Never git push; commit only when I ask. Say plainly when a fix is built but untested.

The repo is this folder. BSP-Core is at /Users/mrgregles/Github/BSP-Core and Region Guard at /Users/mrgregles/Github/BSP-Region Guard if you need to read them.

Start by confirming the build passes, then wait for my first report.
```

## Before you start it

- Open the conversation in `/Users/mrgregles/Github/BSP-Teleport`, with the `obsidian-bsp` MCP connection. Add the BSP-Core and Region Guard folders to the session if it should read their source.
- Have `logs/latest.log` from the server (and the client, for screen problems) ready to paste with each report.
