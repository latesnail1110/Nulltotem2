package net.nullmod.totem;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Config file: config/nulltotem.json (created with safe defaults on first start). All durations are in ticks (20 = 1s). */
public final class NullConfig {
    // ---- ability toggles
    public boolean enableCannon = true, enableRift = true, enableCollapse = true, enableShield = true;

    // ---- Orbital Cannon
    public int cannonChargeTicks = 60;
    public int cannonCooldownTicks = 1200;
    public int cannonRange = 64;
    public int cannonSkulls = 6;
    public int cannonSkullIntervalTicks = 3;
    public double cannonRadius = 6.0;
    /** Damage PER skull at the center (falls off to 40% at the edge). */
    public double cannonDamage = 6.0;

    // ---- NULL Rift
    public int riftRange = 24;
    public int riftCooldownTicks = 100;

    // ---- Void Collapse
    public int collapseRange = 32;
    public int collapseDurationTicks = 80;
    public int collapseCooldownTicks = 900;
    public double collapseRadius = 8.0;
    public double collapseDamage = 14.0;
    public double collapsePullStrength = 0.12;
    public int collapseSlownessAmplifier = 1;

    // ---- NULL Shield
    public double shieldHealthThreshold = 8.0;
    public int shieldDurationTicks = 100;
    public int shieldCooldownTicks = 1800;
    public int shieldResistanceAmplifier = 2;
    public boolean shieldAutoActivate = false;

    // ---- safety
    public boolean protectSelf = true;
    public boolean protectAllies = true;
    /** If true, Cannon impacts additionally cause a vanilla explosion that respects the mobGriefing gamerule. */
    public boolean blockDamage = false;

    // ---- NULL Corruption (visual only, never changes blocks)
    public boolean corruptionEnabled = true;
    public int corruptionDurationTicks = 200;

    // ---- feedback
    public double particleIntensity = 1.0;
    public double soundIntensity = 1.0;
    public int particleViewDistance = 64;
    public boolean showBossbar = true;
    public boolean enableAwakeningFx = true;

    // ---- Death Protocol
    public boolean spectatorOnDeath = true;

    // ---- limits / anti spam
    public int maxActiveProjectiles = 12;
    public int maxEffectsPerPlayer = 3;
    public int useLockTicks = 10;
    public boolean cancelWhenTotemLost = true;

    private static NullConfig instance = new NullConfig();
    public static NullConfig get() { return instance; }

    public boolean enabled(Ability a) {
        return switch (a) {
            case CANNON -> enableCannon;
            case RIFT -> enableRift;
            case COLLAPSE -> enableCollapse;
            case SHIELD -> enableShield;
        };
    }

    public int cooldownTicks(Ability a) {
        return switch (a) {
            case CANNON -> cannonCooldownTicks;
            case RIFT -> riftCooldownTicks;
            case COLLAPSE -> collapseCooldownTicks;
            case SHIELD -> shieldCooldownTicks;
        };
    }

    public static void load() {
        Path f = FabricLoader.getInstance().getConfigDir().resolve("nulltotem.json");
        Gson g = new GsonBuilder().setPrettyPrinting().create();
        try {
            if (Files.exists(f)) {
                try (Reader r = Files.newBufferedReader(f)) {
                    NullConfig c = g.fromJson(r, NullConfig.class);
                    if (c != null) instance = c;
                }
            }
            instance.sanitize();
            try (Writer w = Files.newBufferedWriter(f)) { g.toJson(instance, w); }
        } catch (Exception e) {
            NullTotemMod.LOGGER.error("Config unreadable, using safe defaults", e);
            instance = new NullConfig();
            instance.sanitize();
        }
    }

    private static int ci(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    private static double cd(double v, double lo, double hi) { return Double.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v)); }

    private void sanitize() {
        cannonChargeTicks = ci(cannonChargeTicks, 10, 600);
        cannonCooldownTicks = ci(cannonCooldownTicks, 20, 72000);
        cannonRange = ci(cannonRange, 4, 128);
        cannonSkulls = ci(cannonSkulls, 1, 24);
        cannonSkullIntervalTicks = ci(cannonSkullIntervalTicks, 1, 40);
        cannonRadius = cd(cannonRadius, 1, 24);
        cannonDamage = cd(cannonDamage, 0, 200);
        riftRange = ci(riftRange, 2, 64);
        riftCooldownTicks = ci(riftCooldownTicks, 10, 72000);
        collapseRange = ci(collapseRange, 4, 96);
        collapseDurationTicks = ci(collapseDurationTicks, 10, 600);
        collapseCooldownTicks = ci(collapseCooldownTicks, 20, 72000);
        collapseRadius = cd(collapseRadius, 2, 24);
        collapseDamage = cd(collapseDamage, 0, 200);
        collapsePullStrength = cd(collapsePullStrength, 0, 0.5);
        collapseSlownessAmplifier = ci(collapseSlownessAmplifier, 0, 5);
        shieldHealthThreshold = cd(shieldHealthThreshold, 1, 40);
        shieldDurationTicks = ci(shieldDurationTicks, 20, 1200);
        shieldCooldownTicks = ci(shieldCooldownTicks, 20, 72000);
        shieldResistanceAmplifier = ci(shieldResistanceAmplifier, 0, 3);
        corruptionDurationTicks = ci(corruptionDurationTicks, 20, 1200);
        particleIntensity = cd(particleIntensity, 0, 3);
        soundIntensity = cd(soundIntensity, 0, 2);
        particleViewDistance = ci(particleViewDistance, 8, 128);
        maxActiveProjectiles = ci(maxActiveProjectiles, 1, 64);
        maxEffectsPerPlayer = ci(maxEffectsPerPlayer, 1, 10);
        useLockTicks = ci(useLockTicks, 2, 100);
    }
}
