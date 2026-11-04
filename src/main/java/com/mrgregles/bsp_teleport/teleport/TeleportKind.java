package com.mrgregles.bsp_teleport.teleport;

/** Why a player is teleporting; events and messages carry it. */
public enum TeleportKind {
    HOME, WARP, SPAWN, BACK, RANDOM, REQUEST, PORTAL, TEAM, OTHER,
    /** to an ally, after they accepted (BSP-Core's A-1, D168) */
    ALLY
}
