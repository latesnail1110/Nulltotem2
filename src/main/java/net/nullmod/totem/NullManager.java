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
        ServerLevel l = ch.level; Vec3 t = ch.target;
        if (ch.phase == Phase.CHARGING) {
            double prog = Math.min(1.0, (now - ch.start) / (double) (ch.end - ch.start));
            if (now % 2 == 0) {
                Fx.ring(l, Fx.BLUE, t, c.cannonRadius, (int) (24 + prog * 40), 0.15, now * 0.05);       // target circle
                Fx.ring(l, ParticleTypes.SOUL_FIRE_FLAME, t, c.cannonRadius * (1.0 - prog) + 0.5, 16, 0.2, -now * 0.1); // closing energy
                Fx.particles(l, ParticleTypes.SOUL_FIRE_FLAME, t.x, t.y + 0.2, t.z, (int) (2 + prog * 10), 0.3, 1.5, 0.3, 0.02);
                if (prog > 0.4) {                                                                          // orbital portal
                    double pr = 1.5 + prog * 3.5;
                    Fx.ring(l, Fx.VIOLET, new Vec3(t.x, t.y + PORTAL_HEIGHT, t.z), pr, 28, 0, now * 0.2);
                    Fx.ring(l, ParticleTypes.REVERSE_PORTAL, new Vec3(t.x, t.y + PORTAL_HEIGHT, t.z), pr * 0.6, 14, 0, -now * 0.15);
                }
            }
            if (now % 10 == 0) Fx.sound(l, t, SoundEvents.BEACON_AMBIENT, 1.0F, (float) (0.5 + prog));
            if (now >= ch.end) {
                ch.phase = Phase.FIRING;
                ch.skullsLeft = ch.skullsTotal = c.cannonSkulls;
                ch.nextSkull = now;
                Fx.sound(l, t, SoundEvents.WITHER_SPAWN, 1.0F, 1.3F);
                if (c.corruptionEnabled) addZone(p.getUUID(), l, t, c.cannonRadius, c.corruptionDurationTicks);
            }
        } else {
            if (now % 2 == 0) Fx.ring(l, Fx.VIOLET, new Vec3(t.x, t.y + PORTAL_HEIGHT, t.z), 5.0, 28, 0, now * 0.2);
            if (ch.skullsLeft > 0 && now >= ch.nextSkull) {
                spawnSkull(p, ch);
                ch.skullsLeft--;
                ch.nextSkull = now + c.cannonSkullIntervalTicks;
            }
            if (ch.skullsLeft <= 0) finish(p, ch);
        }
    }

    private static void spawnSkull(ServerPlayer p, Charge ch) {
        NullConfig c = cfg();
        if (skulls.size() >= c.maxActiveProjectiles) return;           // skull is skipped, not queued
        ServerLevel l = ch.level; Vec3 t = ch.target;
        double ang = l.getRandom().nextDouble() * Math.PI * 2;
        double rad = Math.sqrt(l.getRandom().nextDouble()) * c.cannonRadius * 0.8;
        double sx = t.x + Math.cos(ang) * rad, sz = t.z + Math.sin(ang) * rad;
        double h = 3;                                                   // find free height above (caves/roofs)
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (; h < PORTAL_HEIGHT; h++) {
            mp.set(Math.floor(sx), Math.floor(t.y + h), Math.floor(sz));
            if (l.getBlockState(mp).blocksMotion()) { h = Math.max(2, h - 2); break; }
        }
        WitherSkull s = new WitherSkull(EntityType.WITHER_SKULL, l);
        s.setPos(sx, t.y + h, sz);
        s.setOwner(p);
        s.setDangerous(true);
        s.addTag(SKULL_TAG);
        s.setDeltaMovement(0, -1.4, 0);
        if (l.addFreshEntity(s)) skulls.put(s.getUUID(), new SkullRef(l, now));
        Fx.sound(l, sx, t.y + h, sz, SoundEvents.WITHER_SHOOT, 0.7F, 1.4F);
    }

    /** Called from WitherSkullMixin. Returns true if this skull is ours and vanilla handling must be cancelled. */
    public static boolean handleSkullHit(WitherSkull s, HitResult hit) {
        if (!(s.level() instanceof ServerLevel l) || !s.getTags().contains(SKULL_TAG)) return false;
        skulls.remove(s.getUUID());
        if (!s.isRemoved()) impact(l, hit.getLocation(), s);
        s.discard();
        return true;
    }

    private static void impact(ServerLevel l, Vec3 pos, WitherSkull skull) {
        NullConfig c = cfg();
        Entity owner = skull.getOwner();
        double r = c.cannonRadius;
        DamageSource src = l.damageSources().indirectMagic(skull, owner);
        AABB box = new AABB(pos.x - r, pos.y - r, pos.z - r, pos.x + r, pos.y + r, pos.z + r);
        for (LivingEntity e : l.getEntitiesOfClass(LivingEntity.class, box, LivingEntity::isAlive)) {
            if (isProtected(owner, e)) continue;
            double d = e.position().distanceTo(pos);
            if (d > r) continue;
            e.hurtServer(l, src, (float) (c.cannonDamage * (1.0 - 0.6 * (d / r))));
        }
        for (int k = 1; k <= 3; k++) Fx.ring(l, Fx.BLUE, pos, r * k / 3.0, 12 + k * 8, 0.2, k);    // shock waves
        Fx.particles(l, ParticleTypes.SOUL_FIRE_FLAME, pos.x, pos.y + 0.3, pos.z, 40, 0.6, 0.4, 0.6, 0.15);
        Fx.particles(l, ParticleTypes.ELECTRIC_SPARK, pos.x, pos.y + 0.5, pos.z, 20, 0.8, 0.5, 0.8, 0.3);
        Fx.sound(l, pos, SoundEvents.WITHER_BREAK_BLOCK, 0.8F, 1.2F);
        if (c.blockDamage) l.explode(skull, pos.x, pos.y, pos.z, 2.0F, false, Level.ExplosionInteraction.MOB);
    }

    private static void tickSkulls() {
        Iterator<Map.Entry<UUID, SkullRef>> it = skulls.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            Entity ent = e.getValue().level().getEntity(e.getKey());
            if (ent == null || ent.isRemoved()) { it.remove(); continue; }
            if (now - e.getValue().spawned() > 200) { ent.discard(); it.remove(); }
        }
    }

    private static void tickCollapse(ServerPlayer p, Charge ch) {
        NullConfig c = cfg();
        ServerLevel l = ch.level; Vec3 ctr = ch.target; double r = c.collapseRadius;
        // vortex
        for (int arm = 0; arm < 3; arm++) {
            for (int j = 0; j < 10; j++) {
                double f = j / 10.0, rad = r * (1.0 - f) + 0.3;
                double a = now * 0.3 + f * 4.0 + arm * (Math.PI * 2 / 3);
                double y = 0.2 + f * 1.5;
                Fx.particles(l, j % 2 == 0 ? Fx.DARK : Fx.BLUE, ctr.x + Math.cos(a) * rad, ctr.y + y, ctr.z + Math.sin(a) * rad, 1, 0, 0, 0, 0);
            }
        }
        if (now % 3 == 0) Fx.ring(l, ParticleTypes.SQUID_INK, ctr, r, 24, 0.1, now * 0.03);
        if (now % 20 == 0) Fx.sound(l, ctr, SoundEvents.PORTAL_AMBIENT, 0.8F, 0.5F);
        // pull + slow
        AABB box = new AABB(ctr.x - r, ctr.y - 3, ctr.z - r, ctr.x + r, ctr.y + 5, ctr.z + r);
        for (LivingEntity e : l.getEntitiesOfClass(LivingEntity.class, box, LivingEntity::isAlive)) {
            if (isProtected(p, e)) continue;
            Vec3 to = ctr.subtract(e.position());
            double d = to.length();
            if (d > r || d < 0.8) continue;
            Vec3 v = e.getDeltaMovement().add(to.normalize().scale(c.collapsePullStrength * (0.5 + 0.5 * d / r)));
            if (v.length() > 0.9) v = v.normalize().scale(0.9);
            e.setDeltaMovement(v);
            e.hurtMarked = true;
            if (now % 10 == 0) e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 30, c.collapseSlownessAmplifier, false, true));
        }
        if (now >= ch.end) {
            DamageSource src = l.damageSources().indirectMagic(p, p);
            for (LivingEntity e : l.getEntitiesOfClass(LivingEntity.class, box, LivingEntity::isAlive)) {
                if (isProtected(p, e)) continue;
                double d = e.position().distanceTo(ctr);
                if (d > r) continue;
                e.hurtServer(l, src, (float) (c.collapseDamage * (1.0 - 0.5 * (d / r))));
                Vec3 out = e.position().subtract(ctr).multiply(1, 0, 1);
                out = out.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 0) : out.normalize();
                e.setDeltaMovement(out.x * 0.8, 0.45, out.z * 0.8);
                e.hurtMarked = true;
            }
            for (int k = 1; k <= 4; k++) Fx.ring(l, Fx.BLUE, ctr, r * k / 4.0, 16 + k * 8, 0.3, k);
            Fx.particles(l, ParticleTypes.SOUL_FIRE_FLAME, ctr.x, ctr.y + 0.5, ctr.z, 80, 1.0, 0.8, 1.0, 0.2);
            Fx.particles(l, ParticleTypes.ELECTRIC_SPARK, ctr.x, ctr.y + 0.5, ctr.z, 40, 1.5, 1.0, 1.5, 0.4);
            Fx.sound(l, ctr, SoundEvents.WARDEN_SONIC_BOOM, 1.0F, 0.8F);
            if (c.corruptionEnabled) addZone(p.getUUID(), l, ctr, r * 0.6, Math.min(c.corruptionDurationTicks, 100));
            finish(p, ch);
        }
    }

    // ------------------------------------------------------------------ NULL Rift

    private static void doRift(ServerPlayer p) {
        NullConfig c = cfg();
        ServerLevel l = lvl(p);
        Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
        BlockHitResult hit = l.clip(new ClipContext(eye, eye.add(look.scale(c.riftRange)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        double dist = hit.getType() == HitResult.Type.MISS ? c.riftRange : Math.max(0, eye.distanceTo(hit.getLocation()) - 0.6);
        Vec3 dest = null;
        search:
        for (double d = dist; d >= 1.5; d -= 0.5) {
            Vec3 pt = eye.add(look.scale(d));
            BlockPos base = BlockPos.containing(pt.x, pt.y - p.getEyeHeight(), pt.z);
            for (int k = 0; k <= 3; k++) {
                BlockPos feet = base.below(k);
                if (l.getFluidState(feet).is(FluidTags.LAVA) || l.getFluidState(feet.above()).is(FluidTags.LAVA)) break;
                if (!standable(l, feet)) continue;
                BlockPos below = feet.below();
                double gy = below.getY() + l.getBlockState(below).getCollisionShape(l, below).max(Direction.Axis.Y);
                Vec3 cand = new Vec3(feet.getX() + 0.5, gy, feet.getZ() + 0.5);
                AABB moved = p.getBoundingBox().move(cand.x - p.getX(), cand.y - p.getY(), cand.z - p.getZ());
                if (!l.noCollision(p, moved)) continue;
                dest = cand;
                break search;
            }
        }
        if (dest == null) { msg(p, "No safe position in line of sight."); return; }
        Vec3 from = p.position();
        riftFx(l, from);
        if (!p.teleportTo(l, dest.x, dest.y, dest.z, Set.of(), p.getYRot(), p.getXRot(), true)) { msg(p, "Teleport failed."); return; }
        p.resetFallDistance();
        riftFx(l, dest);
        startCooldown(p.getUUID(), Ability.RIFT);
        updateBar(p);
    }

    private static void riftFx(ServerLevel l, Vec3 at) {
        Fx.particles(l, ParticleTypes.REVERSE_PORTAL, at.x, at.y + 1, at.z, 40, 0.4, 0.8, 0.4, 0.2);
        Fx.particles(l, ParticleTypes.SOUL_FIRE_FLAME, at.x, at.y + 1, at.z, 20, 0.3, 0.8, 0.3, 0.05);
        Fx.ring(l, Fx.BLUE, at, 0.9, 16, 0.1, 0);
        Fx.sound(l, at, SoundEvents.ENDERMAN_TELEPORT, 1.0F, 0.7F);
    }

    private static boolean standable(ServerLevel l, BlockPos feet) {
        if (l.isOutsideBuildHeight(feet) || !l.getWorldBorder().isWithinBounds(feet)) return false;
        BlockPos head = feet.above(), below = feet.below();
        BlockState fs = l.getBlockState(feet), hs = l.getBlockState(head), bs = l.getBlockState(below);
        if (!fs.getCollisionShape(l, feet).isEmpty() || !hs.getCollisionShape(l, head).isEmpty()) return false;
        if (l.getFluidState(feet).is(FluidTags.LAVA) || l.getFluidState(head).is(FluidTags.LAVA) || l.getFluidState(below).is(FluidTags.LAVA)) return false;
        if (fs.is(BlockTags.FIRE) || hs.is(BlockTags.FIRE)) return false;
        if (fs.is(Blocks.SWEET_BERRY_BUSH) || fs.is(Blocks.WITHER_ROSE) || fs.is(Blocks.COBWEB) || fs.is(Blocks.POWDER_SNOW)) return false;
        if (!bs.isFaceSturdy(l, below, Direction.UP)) return false;
        return !(bs.is(Blocks.MAGMA_BLOCK) || bs.is(Blocks.CACTUS) || bs.is(Blocks.CAMPFIRE) || bs.is(Blocks.SOUL_CAMPFIRE));
    }

    // ------------------------------------------------------------------ NULL Shield

    private static void doShield(ServerPlayer p, boolean silentFail) {
        NullConfig c = cfg();
        UUID id = p.getUUID();
        if (!c.enableShield) return;
        if (shieldActive(id)) { if (!silentFail) msg(p, "Shield already active."); return; }
        if (readyAt(id, Ability.SHIELD) > now) { if (!silentFail) msg(p, "NULL Shield cooldown: " + secs(readyAt(id, Ability.SHIELD) - now)); return; }
        if (p.getHealth() > c.shieldHealthThreshold) { if (!silentFail) msg(p, "Health too high - shield needs \u2264 " + (int) c.shieldHealthThreshold + " HP."); return; }
        if (charges.containsKey(id) && !silentFail) { msg(p, "Already channeling."); return; }
        ServerLevel l = lvl(p);
        p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, c.shieldDurationTicks, c.shieldResistanceAmplifier, false, true, true));
        shieldUntil.put(id, now + c.shieldDurationTicks);
        startCooldown(id, Ability.SHIELD);
        Fx.sound(l, p.position(), SoundEvents.BEACON_ACTIVATE, 1.0F, 0.6F);
        Fx.sphere(l, Fx.DARK, p.position().add(0, 1, 0), 1.3, 40, 0);
        msg(p, "NULL Shield active.");
        updateBar(p);
    }

    private static void tickShield(ServerPlayer p) {
        Long until = shieldUntil.get(p.getUUID());
        if (until == null) return;
        ServerLevel l = lvl(p);
        if (until <= now) {
            shieldUntil.remove(p.getUUID());
            Fx.sound(l, p.position(), SoundEvents.BEACON_DEACTIVATE, 0.8F, 0.8F);
            return;
        }
        if (now % 4 == 0) {
            Vec3 c = p.position().add(0, 1, 0);
            Fx.sphere(l, Fx.DARK, c, 1.3, 14, now * 0.1);
            Fx.sphere(l, ParticleTypes.SOUL_FIRE_FLAME, c, 1.2, 4, -now * 0.1);
        }
    }

    // ------------------------------------------------------------------ NULL Corruption (visual only)

    private static void addZone(UUID owner, ServerLevel l, Vec3 c, double r, int dur) {
        trimZones(owner, cfg().maxEffectsPerPlayer - 1);
        zones.add(new Zone(owner, l, c, r, now + dur));
    }

    private static void trimZones(UUID owner, int keep) {
        List<Zone> mine = new ArrayList<>();
        for (Zone z : zones) if (z.owner.equals(owner)) mine.add(z);
        for (int i = 0; i < mine.size() - Math.max(0, keep); i++) zones.remove(mine.get(i));   // oldest first
    }

    private static void tickZones() {
        Iterator<Zone> it = zones.iterator();
        while (it.hasNext()) {
            Zone z = it.next();
            if (z.end <= now) { it.remove(); continue; }
            if (now % 3 != 0) continue;
            Vec3 c = z.center; ServerLevel l = z.level; double r = z.radius;
            Fx.ring(l, Fx.BLUE, c, r, 28, 0.1, now * 0.02);                                  // ground circle
            Random rnd = new Random(z.seed);
            for (int i = 0; i < 4; i++) {                                                      // blue rifts
                double a = rnd.nextDouble() * Math.PI * 2, len = r * (0.5 + rnd.nextDouble() * 0.5);
                for (int s = 1; s <= 6; s++) {
                    double f = len * s / 6.0, wob = Math.sin(s * 1.7 + i) * 0.3;
                    Fx.particles(l, ParticleTypes.SOUL_FIRE_FLAME, c.x + Math.cos(a) * f - Math.sin(a) * wob, c.y + 0.15, c.z + Math.sin(a) * f + Math.cos(a) * wob, 1, 0, 0.1, 0, 0.0);
                }
            }
            Fx.particles(l, ParticleTypes.SQUID_INK, c.x, c.y + 0.5, c.z, 3, r * 0.5, 0.4, r * 0.5, 0.01);   // dark fog
            if (l.getRandom().nextInt(3) == 0)                                                // flicker
                Fx.particles(l, ParticleTypes.ELECTRIC_SPARK, c.x, c.y + 1.5, c.z, 6, r * 0.5, 1.0, r * 0.5, 0.3);
        }
    }

    // ------------------------------------------------------------------ boss bar

    private static ServerBossEvent bar(ServerPlayer p) {
        return bars.computeIfAbsent(p.getUUID(), id -> {
            ServerBossEvent b = new ServerBossEvent(Component.empty(), BossEvent.BossBarColor.BLUE, BossEvent.BossBarOverlay.PROGRESS);
            b.addPlayer(p);
            return b;
        });
    }

    private static void clearBar(UUID id) {
        ServerBossEvent b = bars.remove(id);
        if (b != null) b.removeAllPlayers();
    }

    private static void updateBar(ServerPlayer p) {
        UUID id = p.getUUID();
        if (!cfg().showBossbar || !p.isAlive() || !carries(p)) { clearBar(id); return; }   // only visible for totem holders
        Charge ch = charges.get(id);
        if (ch != null) {
            ServerBossEvent b = bar(p);
            b.setColor(BossEvent.BossBarColor.PURPLE);
            if (ch.phase == Phase.CHARGING) {
                b.setProgress((float) Math.max(0, Math.min(1, (now - ch.start) / (double) (ch.end - ch.start))));
                b.setName(Component.literal("\u00a75" + ch.ability.label + " \u00a7b- charging " + secs(ch.end - now)));
            } else {
                b.setProgress(ch.skullsTotal == 0 ? 0 : Math.max(0f, 1f - ch.skullsLeft / (float) ch.skullsTotal));
                b.setName(Component.literal("\u00a75" + ch.ability.label + " \u00a7b- firing"));
            }
            return;
        }
        Cd cd = lastCd.get(id);
        if (cd != null && cd.end() > now) {
            ServerBossEvent b = bar(p);
            b.setColor(BossEvent.BossBarColor.BLUE);
            b.setProgress((float) Math.max(0, Math.min(1, (cd.end() - now) / (double) (cd.end() - cd.start()))));
            b.setName(Component.literal("\u00a7b" + cd.ability().label + " cooldown " + secs(cd.end() - now)));
        } else {
            lastCd.remove(id);
            clearBar(id);
        }
    }

    // ------------------------------------------------------------------ Death Protocol

    /** ALLOW_DEATH: only remember that the player carried a totem; the death may still be cancelled by others. */
    public static void onAllowDeath(ServerPlayer p) {
        if (cfg().spectatorOnDeath && carries(p)) deathCandidates.add(p.getUUID());
    }

    /** AFTER_DEATH: the death really happened. Dedupe via the persistent deathPending flag. */
    public static void onAfterDeath(ServerPlayer p) {
        UUID id = p.getUUID();
        charges.remove(id); shieldUntil.remove(id); clearBar(id);
        if (!deathCandidates.remove(id)) return;
        PlayerData d = NullData.get(id);
        if (d.deathPending) return;                      // same death already recorded
        d.deathPending = true;
        NullData.markDirty();
        NullData.saveNow();
    }

    public static void onAfterRespawn(ServerPlayer newPlayer, boolean alive) {
        PlayerData d = NullData.get(newPlayer.getUUID());
        if (alive || !d.deathPending) return;
        if (!cfg().spectatorOnDeath) { d.deathPending = false; NullData.markDirty(); return; }
        applySpectator(newPlayer);
    }

    private static void applySpectator(ServerPlayer p) {
        PlayerData d = NullData.get(p.getUUID());
        if (!d.deathPending) return;
        d.deathPending = false;                          // cleared first -> can never fire twice
        NullData.markDirty(); NullData.saveNow();
        p.setGameMode(GameType.SPECTATOR);
        p.sendSystemMessage(Component.literal("\u00a75[NULL] \u00a7bYour existence has been disconnected."));
        Fx.sound(lvl(p), p.position(), SoundEvents.WITHER_HURT, 0.8F, 0.5F);
    }
}
