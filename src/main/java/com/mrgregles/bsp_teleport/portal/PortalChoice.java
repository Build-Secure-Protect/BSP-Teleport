package com.mrgregles.bsp_teleport.portal;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The chooser a player sees in a portal with several usable destinations (D97, D100): the portal's name and one
 * option per entry. An empty option list closes the screen (the choice was cancelled or answered elsewhere).
 */
public record PortalChoice(Component portal, List<Option> options) {

    /**
     * One entry: its label, a detail line (where it leads, or why it cannot be pressed), whether it may be pressed, and
     * whether it is the Back entry, which the card draws as one wide key on top (D126).
     */
    public record Option(int index, Component label, Component detail, State state, boolean back) {
    }

    public enum State { OK, DOWN, DENIED }

    public static final PortalChoice CLOSE = new PortalChoice(Component.empty(), List.of());

    public boolean isClose() {
        return options.isEmpty();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeComponent(portal);
        buf.writeVarInt(options.size());
        for (Option o : options) {
            buf.writeVarInt(o.index());
            buf.writeComponent(o.label());
            buf.writeComponent(o.detail());
            buf.writeEnum(o.state());
            buf.writeBoolean(o.back());
        }
    }

    public static PortalChoice decode(FriendlyByteBuf buf) {
        Component portal = buf.readComponent();
        int n = buf.readVarInt();
        List<Option> options = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            options.add(new Option(buf.readVarInt(), buf.readComponent(), buf.readComponent(), buf.readEnum(State.class), buf.readBoolean()));
        }
        return new PortalChoice(portal, options);
    }
}
