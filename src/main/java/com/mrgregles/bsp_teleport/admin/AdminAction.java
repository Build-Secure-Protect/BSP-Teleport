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
    GO_OFFLINE
}
