package com.mrgregles.bsp_teleport.api;

/** Internal: lets the mod install the API instance without widening the holder. */
public final class BspTeleportApiSetter {
    private BspTeleportApiSetter() {
    }

    public static void set(BspTeleportApi api) {
        BspTeleportApiHolder.INSTANCE = api;
    }
}
