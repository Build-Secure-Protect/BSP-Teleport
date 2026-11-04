package com.mrgregles.bsp_teleport.shop;

import com.mrgregles.bsp_teleport.access.AccessGrant;

/** The Access Shop's tabs (D174): a token sits on the tab of what it opens; regions go with servers. */
public enum ShopTab {
    SERVERS, PORTALS, WARPS;

    public static ShopTab of(AccessGrant.Kind kind) {
        return switch (kind) {
            case PORTAL -> PORTALS;
            case WARP -> WARPS;
            default -> SERVERS;
        };
    }
}
