package net.nullmod.totem;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** All runtime logic. Everything runs on the server thread (tick, item use, commands, events). */
public final class NullManager {
    public static final String SKULL_TAG = "nulltotem_skull";
    public static final String PFX = "\u00a75[NULL] \u00a7b";
    private static final double PORTAL_HEIGHT = 24.0;

    public enum TotemState { DORMANT, AWAKENED, OVERLOADED }
    enum Phase { CHARGING, FIRING }

    static final class Charge {
        final Ability ability; final ServerLevel level; final Vec3 target; final long start, end;
        Phase phase = Phase.CHARGING; int skullsLeft, skullsTotal; long nextSkull;
        Charge(Ability a, ServerLevel l, Vec3 t, long s, long e) { ability = a; level = l; target = t; start = s; end = e; }
    }
    record Cd(Ability ability, long start, long end) {}
    static final class Zone {
        final UUID owner; final ServerLevel level; final Vec3 center; final double radius; final long end; final long seed;
        Zone(UUID o, ServerLevel l, Vec3 c, double r, long e) { owner = o; level = l; center = c; radius = r; end = e; seed = Double.doubleToLongBits(c.x * 31 + c.z); }
    }
    record SkullRef(ServerLevel level, long spawned) {}

    private static long now = 0;
    private static final Map<UUID, Charge> charges = new HashMap<>();
    private static final Map<UUID, EnumMap<Ability, Long>> readyAt = new HashMap<>();
    private static final Map<UUID, Cd> lastCd = new HashMap<>();
    private static final Map<UUID, Long> lastUse = new HashMap<>();
    private static final Map<UUID, ServerBossEvent> bars = new HashMap<>();
    private static final Map<UUID, Long> shieldUntil = new HashMap<>();
    private static final Map<UUID, Long> awakeUntil = new HashMap<>();
    private static final Map<UUID, SkullRef> skulls = new HashMap<>();
    private static final List<Zone> zones = new ArrayList<>();
    /** Transient: players that carried a totem when ALLOW_DEATH fired. Confirmed (or dropped) by AFTER_DEATH / tick cleanup. */
    private static final Set<UUID> deathCandidates = new HashSet<>();

    private NullManager() {}

    private static void markMoved(Entity e) { e.needsSync = true; }
    private static NullConfig cfg() { return NullConfig.get(); }
    private static ServerLevel lvl(ServerPlayer p) { return (ServerLevel) p.level(); }
    private static String secs(long ticks) { return String.format(Locale.ROOT, "%.1fs", Math.max(0, ticks) / 20.0); }

    public static void msg(ServerPlayer p, String s) { p.sendSystemMessage(Component.literal(PFX + s), true); }

    // ------------------------------------------------------------------ lifecycle

    public static void reset() {
        charges.clear(); readyAt.clear(); lastCd.clear(); lastUse.clear(); shieldUntil.clear(); awakeUntil.clear();
        skulls.clear(); zones.clear(); deathCandidates.clear();
        for (ServerBossEvent b : bars.values()) b.removeAllPlayers();
        bars.clear();
    }

    public static void onJoin(ServerPlayer p) {
        clearBar(p.getUUID());
        PlayerData d = NullData.get(p.getUUID());
        if (d.awakened) p.addTag("nulltotem_awakened");
        if (d.deathPending) {
            if (cfg().spectatorOnDeath && p.isAlive()) applySpectator(p);
            else if (!cfg().spectatorOnDeath) { d.deathPending = false; NullData.markDirty(); }
        }
    }

    public static void onDisconnect(ServerPlayer p) {
        UUID id = p.getUUID();
        charges.remove(id); lastUse.remove(id); awakeUntil.remove(id); shieldUntil.remove(id); deathCandidates.remove(id);
        clearBar(id);
        zones.removeIf(z -> z.owner.equals(id));
    }

    // ------------------------------------------------------------------ helpers

    public static boolean holds(ServerPlayer p) {
        return p.getMainHandItem().is(NullTotemMod.NULL_TOTEM) || p.getOffhandItem().is(NullTotemMod.NULL_TOTEM);
    }

    public static boolean carries(ServerPlayer p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.is(NullTotemMod.NULL_TOTEM)) return true;
        }
        return false;
    }

    public static Ability selected(ServerPlayer p) { return Ability.byId(NullData.get(p.getUUID()).selected); }

    public static void cycle(ServerPlayer p) {
        PlayerData d = NullData.get(p.getUUID());
        Ability cur = Ability.byId(d.selected);
        Ability[] all = Ability.values();
        for (int i = 1; i <= all.length; i++) {
            Ability n = all[(cur.ordinal() + i) % all.length];
            if (cfg().enabled(n)) { d.selected = n.id; NullData.markDirty(); msg(p, "Selected: \u00a75" + n.label); return; }
        }
        msg(p, "All abilities are disabled.");
    }

    public static TotemState stateOf(ServerPlayer p) {
        if (charges.containsKey(p.getUUID())) return TotemState.OVERLOADED;
        return NullData.get(p.getUUID()).awakened ? TotemState.AWAKENED : TotemState.DORMANT;
    }

    public static long cooldownLeft(UUID id, Ability a) { return Math.max(0, readyAt(id, a) - now); }
    public static boolean shieldActive(UUID id) { return shieldUntil.getOrDefault(id, 0L) > now; }
    public static boolean isChanneling(UUID id) { return charges.containsKey(id); }

    private static long readyAt(UUID id, Ability a) {
        EnumMap<Ability, Long> m = readyAt.get(id);
        return m == null ? 0 : m.getOrDefault(a, 0L);
    }

    private static void setReady(UUID id, Ability a, long when) {
        readyAt.computeIfAbsent(id, k -> new EnumMap<>(Ability.class)).merge(a, when, Math::max);
    }

    private static void startCooldown(UUID id, Ability a) {
        long cd = cfg().cooldownTicks(a);
        readyAt.computeIfAbsent(id, k -> new EnumMap<>(Ability.class)).put(a, now + cd);
        lastCd.put(id, new Cd(a, now, now + cd));
    }

    public static void resetCooldowns(ServerPlayer p) {
        readyAt.remove(p.getUUID()); lastCd.remove(p.getUUID()); lastUse.remove(p.getUUID());
        clearBar(p.getUUID());
    }

    private static boolean isProtected(Entity owner, LivingEntity t) {
        NullConfig c = cfg();
        if (t == owner) return c.protectSelf;
        if (t instanceof ServerPlayer sp && sp.isSpectator()) return true;
        return owner != null && c.protectAllies && owner.isAlliedTo(t);
    }

    private static Vec3 raycastBlock(ServerPlayer p, double range) {
        Vec3 eye = p.getEyePosition();
        Vec3 end = eye.add(p.getLookAngle().scale(range));
        BlockHitResult r = lvl(p).clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        return r.getType() == HitResult.Type.MISS ? null : r.getLocation();
    }

    // ------------------------------------------------------------------ activation

    public static void activate(ServerPlayer p, Ability a) {
        NullConfig c = cfg();
        UUID id = p.getUUID();
        if (!p.isAlive() || p.isSpectator()) return;
        if (!carries(p)) { msg(p, "You need the Totem of NULL."); return; }
        if (!c.enabled(a)) { msg(p, a.label + " is disabled."); return; }
        Long last = lastUse.get(id);
        if (last != null && now - last < c.useLockTicks) return;           // anti right-click spam
        lastUse.put(id, now);
        PlayerData d = NullData.get(id);
        if (!d.awakened) { awaken(p); return; }                              // first activation = awakening only
        if (charges.containsKey(id)) { msg(p, "Already channeling."); return; }
        long ready = readyAt(id, a);
        if (ready > now) { msg(p, a.label + " cooldown: " + secs(ready - now)); return; }
        switch (a) {
            case CANNON, COLLAPSE -> startChannel(p, a);
            case RIFT -> doRift(p);
            case SHIELD -> doShield(p, false);
        }
    }

    private static void awaken(ServerPlayer p) {
        PlayerData d = NullData.get(p.getUUID());
        d.awakened = true; NullData.markDirty(); NullData.saveNow();
        p.addTag("nulltotem_awakened");
        ServerLevel l = lvl(p);
        awakeUntil.put(p.getUUID(), now + 60);
        if (cfg().enableAwakeningFx) {
            p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 50, 0, false, false, false));
            p.addEffect(new MobEffectInstance(MobEffects.NAUSEA, 60, 0, false, false, false));
        }
        Fx.sound(l, p.position(), SoundEvents.WITHER_SPAWN, 0.6F, 1.5F);
        Fx.sound(l, p.position(), SoundEvents.END_PORTAL_SPAWN, 0.5F, 1.7F);
        p.sendSystemMessage(Component.literal(PFX + "You are now \u00a75NULL Awakened\u00a7b. Right-click to use, sneak + right-click to switch ability."));
    }

    private static void startChannel(ServerPlayer p, Ability a) {
        NullConfig c = cfg();
        UUID id = p.getUUID();
        trimZones(id, c.maxEffectsPerPlayer - 1);
        Vec3 t = raycastBlock(p, a == Ability.CANNON ? c.cannonRange : c.collapseRange);
        if (t == null) { msg(p, "No valid target in range."); return; }
        ServerLevel l = lvl(p);
        long dur = a == Ability.CANNON ? c.cannonChargeTicks : c.collapseDurationTicks;
        charges.put(id, new Charge(a, l, t, now, now + dur));
        Fx.sound(l, t, SoundEvents.RESPAWN_ANCHOR_CHARGE, 1.0F, 0.6F);
        msg(p, a.label + " charging...");
        updateBar(p);
    }

    // ------------------------------------------------------------------ per-tick

    public static void tick(MinecraftServer server) {
        now++;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) tickPlayer(p);
        tickZones();
        tickSkulls();
        if (now % 6000 == 0) NullData.save();
        if (now % 40 == 0) deathCandidates.removeIf(id -> { ServerPlayer sp = server.getPlayerList().getPlayer(id); return sp == null || sp.isAlive(); });
    }

    private static void tickPlayer(ServerPlayer p) {
        UUID id = p.getUUID();
        if (!p.isAlive()) { charges.remove(id); shieldUntil.remove(id); clearBar(id); return; }
        Charge ch = charges.get(id);
        if (ch != null) tickCharge(p, ch);
        if (now % 4 == 0) ambient(p);
        if (now % 5 == 0) updateBar(p);
        tickShield(p);
        tickAwakening(p);
        if (now % 10 == 0 && cfg().shieldAutoActivate && !shieldActive(id) && carries(p)) doShield(p, true);
    }

    private static void ambient(ServerPlayer p) {
        if (!holds(p)) return;
        ServerLevel l = lvl(p);
        Vec3 h = p.position().add(p.getLookAngle().scale(0.6)).add(0, 1.0, 0);
        switch (stateOf(p)) {
            case DORMANT -> Fx.particles(l, ParticleTypes.GLOW, h.x, h.y, h.z, 1, 0.15, 0.15, 0.15, 0.0);
            case AWAKENED -> {
                Fx.particles(l, ParticleTypes.SOUL_FIRE_FLAME, h.x, h.y, h.z, 3, 0.2, 0.2, 0.2, 0.01);
                Fx.particles(l, Fx.BLUE, h.x, h.y, h.z, 2, 0.25, 0.25, 0.25, 0.0);
            }
            case OVERLOADED -> {
                Fx.particles(l, ParticleTypes.SOUL_FIRE_FLAME, h.x, h.y, h.z, 8, 0.35, 0.35, 0.35, 0.03);
                Fx.particles(l, ParticleTypes.ELECTRIC_SPARK, h.x, h.y, h.z, 4, 0.4, 0.4, 0.4, 0.2);
                Fx.particles(l, Fx.VIOLET, h.x, h.y, h.z, 4, 0.4, 0.4, 0.4, 0.0);
            }
        }
    }

    private static void tickAwakening(ServerPlayer p) {
        Long until = awakeUntil.get(p.getUUID());
        if (until == null) return;
        ServerLevel l = lvl(p);
        long t = until - now;
        if (t <= 0) {
            awakeUntil.remove(p.getUUID());
            Fx.particles(l, ParticleTypes.SOUL_FIRE_FLAME, p.getX(), p.getY() + 1, p.getZ(), 60, 0.6, 0.8, 0.6, 0.12);
            Fx.ring(l, Fx.BLUE, p.position(), 2.0, 32, 0.2, 0);
            return;
        }
        double y = (60 - t) / 60.0 * 2.0;
        for (int k = 0; k < 4; k++) {
            double a = now * 0.4 + k * Math.PI / 2;
            Fx.particles(l, Fx.BLUE, p.getX() + Math.cos(a) * 1.2, p.getY() + y, p.getZ() + Math.sin(a) * 1.2, 1, 0, 0, 0, 0);
            Fx.particles(l, Fx.DARK, p.getX() + Math.cos(a + Math.PI) * 0.8, p.getY() + 2 - y, p.getZ() + Math.sin(a + Math.PI) * 0.8, 1, 0, 0, 0, 0);
        }
    }

    // ------------------------------------------------------------------ channel abilities

    private static void tickCharge(ServerPlayer p, Charge ch) {
        UUID id = p.getUUID();
        if (p.isSpectator() || (Object) p.level() != (Object) ch.level) { cancel(p, ch, "Channel interrupted."); return; }
        if (cfg().cancelWhenTotemLost && now % 10 == 0 && !carries(p)) { cancel(p, ch, "Totem lost - channel cancelled."); return; }
        if (ch.ability == Ability.CANNON) tickCannon(p, ch); else tickCollapse(p, ch);
    }

    private static void cancel(ServerPlayer p, Charge ch, String why) {
        charges.remove(p.getUUID());
        setReady(p.getUUID(), ch.ability, now + 40);
        msg(p, why);
        clearBar(p.getUUID());
    }

    private static void finish(ServerPlayer p, Charge ch) {
        charges.remove(p.getUUID());
        startCooldown(p.getUUID(), ch.ability);
        updateBar(p);
    }

    private static void tickCannon(ServerPlayer p, Charge ch) {
        NullConfig c = cfg();
        ServerLevel l =
