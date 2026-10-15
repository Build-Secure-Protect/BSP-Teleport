package com.mrgregles.bsp_teleport.data;

import net.minecraft.nbt.CompoundTag;

/** A player's home. Names are kept lower-case. A public home can be visited by anyone with {@code /phome}. {@code icon} is an item id or empty. */
public record Home(String name, Position position, long createdAt, String description, boolean isPublic, String icon) {
    public static final int MAX_DESCRIPTION = 128;

    public Home(String name, Position position, long createdAt) {
        this(name, position, createdAt, "", false, "");
    }

    public Home withPosition(Position position) {
        return new Home(name, position, createdAt, description, isPublic, icon);
    }

    public Home withName(String name) {
        return new Home(name, position, createdAt, description, isPublic, icon);
    }

    public Home withDescription(String description) {
        return new Home(name, position, createdAt, description, isPublic, icon);
    }

    public Home withPublic(boolean isPublic) {
        return new Home(name, position, createdAt, description, isPublic, icon);
    }

    public Home withIcon(String icon) {
        return new Home(name, position, createdAt, description, isPublic, icon);
    }

    public CompoundTag save() {
        CompoundTag tag = position.save();
        tag.putString("Name", name);
        tag.putLong("Created", createdAt);
        tag.putString("Description", description);
        tag.putBoolean("Public", isPublic);
        tag.putString("Icon", icon);
        return tag;
    }

    public static Home load(CompoundTag tag) {
        return new Home(tag.getString("Name"), Position.load(tag), tag.getLong("Created"), tag.getString("Description"), tag.getBoolean("Public"), tag.getString("Icon"));
    }
}
