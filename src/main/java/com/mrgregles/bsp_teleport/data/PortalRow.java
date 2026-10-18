package com.mrgregles.bsp_teleport.data;

import com.mrgregles.bsp_teleport.api.Destination;
import com.mrgregles.bsp_teleport.portal.PortalDestination;
import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;

/**
 * What every server knows about a Portal Core (D62): one row per core in the shared table, mirrored from the core's
 * block entity by the server it stands on. Links are resolved from these rows; the admin panel lists them.
 *
 * @param id          {@code server|world|x,y,z}
 * @param exit        where a linked partner lands its players: in front of this portal (D56); null while the frame is not fine
 * @param frameOk     whether the frame scanned fine (the field also needs the destination, which may depend on another row)
 * @param status      the core's last status name, for the panel
 * @param doors       open, closed, opening, closing, locked, or empty when the portal has no doors
 * @param createdMs   when the core was first published; the two oldest rows own a link id (D55)
 */
public record PortalRow(String id, String server, String world, int x, int y, int z, String name, PortalDestination destination,
                        boolean arriveOnly, @Nullable Destination exit, boolean frameOk, String colour, String status, String doors, long createdMs) {

    public static String idOf(String server, String world, int x, int y, int z) {
        return server + "|" + world + "|" + x + "," + y + "," + z;
    }

    public String linkId() {
        return destination.isLink() ? destination.target() : "";
    }

    public PortalRow withCreated(long createdMs) {
        return new PortalRow(id, server, world, x, y, z, name, destination, arriveOnly, exit, frameOk, colour, status, doors, createdMs);
    }

    /** Everything but the creation time: whether a republish is needed. */
    public boolean sameAs(PortalRow other) {
        return other != null && withCreated(0).equals(other.withCreated(0));
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Id", id);
        tag.putString("Server", server);
        tag.putString("World", world);
        tag.putInt("X", x);
        tag.putInt("Y", y);
        tag.putInt("Z", z);
        tag.putString("Name", name);
        tag.put("Destination", destination.save());
        tag.putBoolean("ArriveOnly", arriveOnly);
        tag.putString("Exit", exit == null ? "" : exit.serialize());
        tag.putBoolean("FrameOk", frameOk);
        tag.putString("Colour", colour);
        tag.putString("Status", status);
        tag.putString("Doors", doors);
        tag.putLong("Created", createdMs);
        return tag;
    }

    public static PortalRow load(CompoundTag tag) {
        String exit = tag.getString("Exit");
        return new PortalRow(tag.getString("Id"), tag.getString("Server"), tag.getString("World"), tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z"),
                tag.getString("Name"), PortalDestination.load(tag.getCompound("Destination")), tag.getBoolean("ArriveOnly"),
                exit.isEmpty() ? null : Destination.parse(exit), tag.getBoolean("FrameOk"), tag.getString("Colour"), tag.getString("Status"), tag.getString("Doors"), tag.getLong("Created"));
    }
}
