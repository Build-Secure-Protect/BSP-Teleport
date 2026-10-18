package com.mrgregles.bsp_teleport.admin;

/** What the admin panel asks the server to do. {@code arg} is "a" or "a;b" as each action needs. */
public enum AdminAction {
    OPEN,
    /** id;value */
    SET_SETTING,
    /** name;cost;restricted: set a warp where the admin stands */
    SET_WARP,
    /** name: move the warp to where the admin stands */
    MOVE_WARP,
    /** name;cost */
    WARP_COST,
    /** name;true|false */
    WARP_RESTRICT,
    /** name */
    DEL_WARP,
    /** name: the warp's icon becomes the item the admin holds (empty hand clears it) */
    WARP_ICON,
    /** re-read the config file */
    RELOAD,
    /** name */
    GO_WARP,
    /** server */
    GO_SERVER,
    /** server, or * for every server: everyone there comes to the admin */
    BRING_ALL,
    /** from;to: everyone on "from" is sent to "to" */
    SEND_ALL,
    /** player */
    BRING_PLAYER,
    /** player */
    GO_PLAYER,
    /** player */
    GO_OFFLINE,
    /** name;true|false: a portal warp (only portals reach it) or an ordinary one */
    WARP_PORTAL,
    /** row id: go to the portal (its exit, else the core) */
    GO_PORTAL,
    /** row id;open|close|lock|unlock */
    PORTAL_COMMAND,
    /** server;true|false: the server needs access (D70) */
    ACCESS_SERVER,
    /** token;kind;target: the token stands for that (D74) */
    TOKEN_ASSIGN,
    /** token */
    TOKEN_CLEAR,
    /** token;hours */
    TOKEN_HOURS,
    /** token;text */
    TOKEN_HINT,
    /** token;full|rent;hours: a stack for a shop lands in the admin's inventory */
    TOKEN_STACK,
    /** name;kind;value;count;consume: a rule on a warp (D86) */
    WARP_RULE_ADD,
    /** name;index */
    WARP_RULE_DEL
}
