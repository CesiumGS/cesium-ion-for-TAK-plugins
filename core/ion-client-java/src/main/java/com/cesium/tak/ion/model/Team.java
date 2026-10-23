package com.cesium.tak.ion.model;

/** An ion team workspace the token can list assets from. */
public final class Team {
    private final String id;
    private final String name;
    private final boolean isDefault;

    public Team(String id, String name, boolean isDefault) {
        this.id = id;
        this.name = name;
        this.isDefault = isDefault;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public boolean isDefault() {
        return isDefault;
    }

    @Override
    public String toString() {
        return "Team[" + id + ", " + name + "]";
    }
}
