package net.nullmod.totem;

/** Persistent per-player state (saved per world). Cooldowns are intentionally NOT persisted. */
public final class PlayerData {
    public boolean awakened = false;
    public boolean effectsOn = true;
    public String selected = "cannon";
    /** Set when a totem-carrying player died; cleared once spectator was applied (or feature disabled). */
    public boolean deathPending = false;
}
