package com.mrgregles.bsp_teleport.portal;

/** What the core screen asks the server to do with its portal. {@code arg} is "a" or "a;b" as each action needs. */
public enum PortalScreenAction {
    OPEN,
    /** warp name */
    DEST_WARP,
    /** server, or empty for this one */
    DEST_RANDOM,
    /** where the admin stands */
    DEST_HERE,
    /** link id */
    DEST_LINK,
    DEST_NONE,
    /** text */
    NAME,
    /** colour name */
    COLOUR,
    /** blocks|rendered */
    DOORS,
    FLIP,
    /** true|false */
    ARRIVE_ONLY,
    /** text, empty clears */
    ARRIVAL_LINE,
    MODE_MANUAL,
    /** true|false: open when powered */
    MODE_REDSTONE,
    /** HH:MM;HH:MM */
    MODE_REAL,
    /** open;close in world time */
    MODE_GAME,
    OPEN_NOW,
    CLOSE_NOW,
    /** true|false */
    LOCK,
    /** true|false */
    CLOSE_WHEN_DOWN,
    /** seconds */
    COOLDOWN,
    /** kind;value;count;consume (D86) */
    RULE_ADD,
    /** index */
    RULE_DEL
}
