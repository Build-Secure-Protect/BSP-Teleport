package com.mrgregles.bsp_teleport.band;

/** What the band screen asks the server to do. {@code arg} is a home, warp, player or server name, or empty. */
public enum BandAction {
    OPEN, HOME, DEL_HOME, SET_HOME, WARP, TPA, TPA_HERE, ACCEPT, DENY, SPAWN, BACK, RTP, TOGGLE_IGNORE, TPNOW, ADMIN
}
