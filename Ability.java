package net.nullmod.totem;

public enum Ability {
    CANNON("cannon", "Orbital Cannon"),
    RIFT("rift", "NULL Rift"),
    COLLAPSE("collapse", "Void Collapse"),
    SHIELD("shield", "NULL Shield");

    public final String id;
    public final String label;

    Ability(String id, String label) { this.id = id; this.label = label; }

    public static Ability byId(String id) {
        for (Ability a : values()) if (a.id.equalsIgnoreCase(id)) return a;
        return CANNON;
    }
}
