package net.nullmod.totem;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/** Particle/sound helpers. Particles are sent per viewer so /null effects off really hides them. */
public final class Fx {
    public static final DustParticleOptions BLUE = new DustParticleOptions(0x2F6BFF, 1.3F);
    public static final DustParticleOptions DARK = new DustParticleOptions(0x0B1A5E, 2.0F);
    public static final DustParticleOptions VIOLET = new DustParticleOptions(0x7B2FFF, 1.2F);

    private Fx() {}

    public static void particles(ServerLevel lvl, ParticleOptions opt, double x, double y, double z,
                                 int count, double dx, double dy, double dz, double speed) {
        NullConfig c = NullConfig.get();
        double scaled = count * c.particleIntensity;
        int n = (int) scaled;
        if (Math.random() < scaled - n) n++;
        if (n <= 0) return;
        double max = c.particleViewDistance;
        for (ServerPlayer p : lvl.players()) {
            if (!NullData.get(p.getUUID()).effectsOn) continue;
            if (p.distanceToSqr(x, y, z) > max * max) continue;
            lvl.sendParticles(p, opt, false, false, x, y, z, n, dx, dy, dz, speed);
        }
    }

    public static void point(ServerLevel lvl, ParticleOptions opt, Vec3 v) {
        particles(lvl, opt, v.x, v.y, v.z, 1, 0, 0, 0, 0);
    }

    public static void ring(ServerLevel lvl, ParticleOptions opt, Vec3 c, double radius, int points, double yOff, double phase) {
        for (int i = 0; i < points; i++) {
            double a = phase + (Math.PI * 2 * i) / points;
            particles(lvl, opt, c.x + Math.cos(a) * radius, c.y + yOff, c.z + Math.sin(a) * radius, 1, 0, 0, 0, 0);
        }
    }

    public static void sphere(ServerLevel lvl, ParticleOptions opt, Vec3 c, double radius, int points, double phase) {
        for (int i = 0; i < points; i++) {
            double y = 1.0 - 2.0 * (i + 0.5) / points;
            double r = Math.sqrt(Math.max(0, 1 - y * y));
            double phi = i * 2.399963 + phase;
            particles(lvl, opt, c.x + Math.cos(phi) * r * radius, c.y + y * radius, c.z + Math.sin(phi) * r * radius, 1, 0, 0, 0, 0);
        }
    }

    public static void sound(ServerLevel lvl, double x, double y, double z, SoundEvent s, float vol, float pitch) {
        double i = NullConfig.get().soundIntensity;
        if (i <= 0) return;
        lvl.playSound(null, x, y, z, s, SoundSource.PLAYERS, (float) (vol * i), pitch);
    }

    public static void sound(ServerLevel lvl, Vec3 v, SoundEvent s, float vol, float pitch) {
        sound(lvl, v.x, v.y, v.z, s, vol, pitch);
    }
}
