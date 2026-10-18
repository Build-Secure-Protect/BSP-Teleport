package com.mrgregles.bsp_teleport.client;

import com.mrgregles.bsp_teleport.BSPTeleport;
import com.mrgregles.bsp_teleport.access.AccessGrant;
import com.mrgregles.bsp_teleport.compat.BspCore;
import com.mrgregles.bsp_teleport.registry.ModItems;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The "Home servers" board on BSP-Core's Score Screens (R8, D85): one line per restricted server with its Access
 * Token as the icon and "N home, F full, R renting" as the value, from the counts the access sync already carries
 * ({@link ClientAccess#counts()}). Registered once at client setup; the lines are empty until the first sync, and
 * while the access system is off, so a screen that shows the board says "Nothing on this board yet" until then.
 */
public final class HomeServersBoard {
    public static final ResourceLocation ID = new ResourceLocation(BSPTeleport.MODID, "home_servers");

    /** The counts list the cached lines were built from; the sync replaces the list, so identity is enough. */
    private static List<ClientAccess.Count> builtFrom = List.of();
    private static List<BspCore.BoardEntry> lines = List.of();

    private HomeServersBoard() {
    }

    public static void register() {
        BspCore.registerBoard(ID, Component.translatable("screen.bsp_teleport.board.home_servers"), HomeServersBoard::lines);
    }

    /** Runs on the render thread about once a second per screen: rebuilds only when a new sync has arrived. */
    static List<BspCore.BoardEntry> lines() {
        if (!ClientAccess.enabled()) {
            return List.of();
        }
        List<ClientAccess.Count> counts = ClientAccess.counts();
        if (counts != builtFrom) {
            List<BspCore.BoardEntry> out = new ArrayList<>(counts.size());
            for (ClientAccess.Count c : counts) {
                int token = ClientAccess.tokenFor(AccessGrant.Kind.SERVER, c.server());
                ItemStack icon = token == 0 ? ItemStack.EMPTY : new ItemStack(ModItems.ACCESS_TOKENS[token - 1].get());
                out.add(new BspCore.BoardEntry(Component.literal(c.server()),
                        Component.translatable("screen.bsp_teleport.board.line", c.home(), c.full(), c.rent()), icon));
            }
            lines = List.copyOf(out);
            builtFrom = counts;
        }
        return lines;
    }
}
