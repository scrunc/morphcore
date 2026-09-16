package dev.servereer.morphcore;

import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.WeatherType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.bukkit.plugin.Plugin;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The cosmetic layer of an arena morph: falling {@link BlockDisplay} "meteor heads", impact bursts
 * with optional lightning and player shockwaves, a title card + boss bar, client-side sky theming,
 * an ambient rumble, and a completion firework. Everything here is main-thread and side-effect-only —
 * it never touches the world's real blocks (that's {@link ArenaMorph}). Driven per-tick by the morph.
 *
 * <p>All spawned entities are tracked so {@link #dispose()} can guarantee nothing is orphaned when a
 * morph finishes, is cancelled, or the plugin disables mid-sweep.
 */
public final class MorphFx {

    private final Plugin plugin;
    private final World world;
    private final MorphSpec spec;
    private final Location center;
    private final double range;

    private BossBar bossBar;
    private Location riftA, riftB;   // RIFT seam endpoints; null = no rift FX
    private final Map<Integer, Meteor> live = new HashMap<>();
    private final List<Player> audience = new ArrayList<>();
    private int nextId = 1;
    private boolean started;

    MorphFx(Plugin plugin, World world, MorphSpec spec, Location center, int audiencePadding) {
        this.plugin = plugin;
        this.world = world;
        this.spec = spec;
        this.center = center;
        this.range = Math.max(48, center == null ? 48 : 48 + Math.max(0, audiencePadding));
    }

    /** Give the FX a RIFT seam to draw energy along (endpoints in world space). */
    void setRift(Location a, Location b) { this.riftA = a; this.riftB = b; }

    // --- lifecycle ---

    void start() {
        if (!spec.fxEnabled || world == null || center == null) return;
        started = true;
        refreshAudience();
        if (spec.bossBar) {
            bossBar = plugin.getServer().createBossBar(Colors.translate(spec.bossBarText), BarColor.RED, BarStyle.SEGMENTED_12);
            bossBar.setProgress(0.0);
            for (Player p : audience) bossBar.addPlayer(p);
        }
        for (Player p : audience) {
            if (spec.title != null && !spec.title.isBlank()) {
                p.sendTitle(Colors.translate(spec.title), Colors.translate(spec.subtitle == null ? "" : spec.subtitle), 8, 45, 20);
            }
            if (spec.darkenSky) {
                p.setPlayerWeather(WeatherType.DOWNFALL);
                p.setPlayerTime(17500L, false);
            }
        }
    }

    /** Called every tick; {@code frac} is overall morph progress 0..1, {@code tick} the elapsed tick count. */
    void tick(double frac, int tick) {
        if (!started) return;
        if (bossBar != null) bossBar.setProgress(Math.max(0.0, Math.min(1.0, frac)));
        // keep audience/boss-bar membership fresh as players wander in/out
        if (tick % 20 == 0) {
            refreshAudience();
            if (bossBar != null) {
                bossBar.getPlayers().forEach(p -> { if (!audience.contains(p)) bossBar.removePlayer(p); });
                for (Player p : audience) if (!bossBar.getPlayers().contains(p)) bossBar.addPlayer(p);
            }
        }
        // ambient rumble + drifting ash over the arena
        if (spec.ambientRumble && tick % 14 == 0 && !audience.isEmpty()) {
            playSound(center, "block.deepslate.break", 0.5f, 0.5f);
            pp(Particle.ASH, center.getX(), center.getY() + 6, center.getZ(),
                    40, range / 3.0, 4, range / 3.0, 0.0);
        }
        if (riftA != null && tick % 2 == 0) tickRift();
        moveMeteors();
    }

    /** Bleed portal energy along the tearing seam (RIFT style). */
    private void tickRift() {
        final int samples = 30;
        double dx = riftB.getX() - riftA.getX(), dy = riftB.getY() - riftA.getY(), dz = riftB.getZ() - riftA.getZ();
        for (int i = 0; i <= samples; i++) {
            double f = (double) i / samples;
            double jitter = Math.sin(f * 9.0 + (System.currentTimeMillis() % 6283) / 1000.0) * 0.5;
            double x = riftA.getX() + dx * f, y = riftA.getY() + dy * f + jitter, z = riftA.getZ() + dz * f;
            pp(Particle.REVERSE_PORTAL, x, y, z, 2, 0.12, 0.7, 0.12, 0.03);
            if (i % 3 == 0) pp(Particle.WITCH, x, y + 0.4, z, 1, 0.1, 0.4, 0.1, 0.0);
            if (i % 6 == 0) pp(Particle.DRAGON_BREATH, x, y, z, 1, 0.05, 0.2, 0.05, 0.0);
        }
    }

    void dispose() {
        for (Meteor m : live.values()) if (m.entity != null && !m.entity.isDead()) m.entity.remove();
        live.clear();
        if (bossBar != null) { bossBar.removeAll(); bossBar = null; }
        if (spec.darkenSky) {
            for (Player p : audience) { p.resetPlayerWeather(); p.resetPlayerTime(); }
        }
        started = false;
    }

    void complete() {
        if (started && spec.completionFirework && center != null) launchCompletionFireworks();
        dispose();
    }

    // --- meteors ---

    /** Spawn a meteor head high above {@code impactGround} and return an id used later by {@link #impact(int)}. */
    int launchMeteor(Location impactGround) {
        int id = nextId++;
        if (!spec.fxEnabled || world == null) return id;   // still hand back an id so the caller can pair impacts
        Location from = impactGround.clone().add(0, spec.spawnHeight, 0);
        BlockDisplay disp;
        try {
            disp = world.spawn(from, BlockDisplay.class, d -> {
                d.setBlock(spec.displayBlock.createBlockData());
                d.setBrightness(new Display.Brightness(15, 15));
                d.setGlowing(true);
                d.setPersistent(false);
                d.setTeleportDuration(1);
                float s = spec.scale;
                d.setTransformation(new Transformation(
                        new Vector3f(-s / 2f, -s / 2f, -s / 2f), new AxisAngle4f(),
                        new Vector3f(s, s, s), new AxisAngle4f()));
            });
        } catch (RuntimeException ex) {
            return id;   // display entities unsupported / spawn failed — carry on without a visual
        }
        if (spec.launchSound != null && !spec.launchSound.isBlank()) playSound(from, spec.launchSound, 0.8f, 1.4f);
        live.put(id, new Meteor(disp, from, impactGround.clone(), spec.travelTicks));
        return id;
    }

    private void moveMeteors() {
        List<Integer> remove = null;
        for (Map.Entry<Integer, Meteor> en : live.entrySet()) {
            Meteor m = en.getValue();
            if (m.entity == null || m.entity.isDead()) continue;
            m.progress++;
            double f = Math.min(1.0, (double) m.progress / m.travelTicks);
            double y = m.from.getY() + (m.to.getY() - m.from.getY()) * f + m.arcHeight * 4.0 * f * (1.0 - f);
            Location loc = new Location(world,
                    m.from.getX() + (m.to.getX() - m.from.getX()) * f, y,
                    m.from.getZ() + (m.to.getZ() - m.from.getZ()) * f);
            m.entity.teleport(loc);
            if (m.spinDeg != 0) {                          // tumble the ejecta chunk as it flies
                m.angle += Math.toRadians(m.spinDeg) / Math.max(1, m.travelTicks);
                float s = m.baseScale;
                try {
                    m.entity.setInterpolationDelay(0);
                    m.entity.setInterpolationDuration(1);
                    m.entity.setTransformation(new Transformation(
                            new Vector3f(-s / 2f, -s / 2f, -s / 2f),
                            new AxisAngle4f((float) m.angle, 0.267f, 0.802f, 0.535f),
                            new Vector3f(s, s, s), new AxisAngle4f()));
                } catch (RuntimeException ignored) { }
            }
            trailParticles(loc, m.flavor);
            if (m.autoRemove && m.progress >= m.travelTicks) {   // risers self-retire at the top of their flight
                landPuff(loc, m.flavor);
                m.entity.remove();
                if (remove == null) remove = new ArrayList<>();
                remove.add(en.getKey());
            }
        }
        if (remove != null) for (int id : remove) live.remove(id);
    }

    /** Trail left behind a flying block, keyed by FX flavour (lava ejecta / geyser steam / rock / spark). */
    private void trailParticles(Location loc, String flavor) {
        switch (flavor) {
            case "steam":
                pp(Particle.CLOUD, loc, 6, 0.12, 0.3, 0.12, 0.02);
                pp(Particle.BUBBLE_POP, loc, 3, 0.1, 0.2, 0.1, 0.0);
                break;
            case "rock":
                pp(Particle.LARGE_SMOKE, loc, 4, 0.2, 0.2, 0.2, 0.0);
                pp(Particle.ASH, loc, 5, 0.2, 0.2, 0.2, 0.0);
                break;
            case "spark":
                pp(Particle.END_ROD, loc, 4, 0.1, 0.15, 0.1, 0.01);
                pp(Particle.CRIT, loc, 4, 0.15, 0.15, 0.15, 0.05);
                break;
            default:   // "lava" / "fire" (the original meteor trail)
                pp(Particle.FLAME, loc, 6, 0.15, 0.15, 0.15, 0.01);
                pp(Particle.LAVA, loc, 2, 0.1, 0.1, 0.1, 0.0);
                pp(Particle.LARGE_SMOKE, loc, 3, 0.15, 0.3, 0.15, 0.0);
        }
    }

    /** Small bloom where a rising flyer retires at the top of its arc/jet. */
    private void landPuff(Location loc, String flavor) {
        switch (flavor) {
            case "steam": pp(Particle.CLOUD, loc, 12, 0.3, 0.2, 0.3, 0.02); break;
            case "spark": pp(Particle.END_ROD, loc, 8, 0.2, 0.2, 0.2, 0.02); break;
            case "rock":  pp(Particle.LARGE_SMOKE, loc, 10, 0.3, 0.2, 0.3, 0.0); break;
            default:      pp(Particle.LAVA, loc, 8, 0.25, 0.2, 0.25, 0.0);
        }
    }

    /**
     * The block-flight primitive behind the rising morphs: spawn a cosmetic {@link BlockDisplay} below the
     * floor and fly it upward. A {@code "steam"} flavour streaks straight up to {@code apex} (a geyser jet);
     * every other flavour arcs up to {@code apex} and falls back near the crater (volcanic ejecta). The flyer
     * is purely cosmetic — the real blocks are placed by {@link ArenaMorph}, bottom-up, over the same window.
     */
    int launchFlyer(Location ground, double depth, double apex, int travelTicks, Material block, float scale,
                    double spinDeg, String flavor, double spreadXZ) {
        int id = nextId++;
        if (!spec.fxEnabled || world == null) return id;
        double ox = (Math.random() - 0.5) * 2.0 * spreadXZ;
        double oz = (Math.random() - 0.5) * 2.0 * spreadXZ;
        Location from = ground.clone().add(0, -Math.max(0.0, depth), 0);
        double arc;
        Location to;
        if ("steam".equals(flavor)) {              // geyser: a vertical jet that streaks up and fades at the top
            to = ground.clone().add(ox * 0.3, apex, oz * 0.3);
            arc = 0.0;
        } else {                                    // ejecta: thrown up and arcing back down beside the crater
            to = ground.clone().add(ox, 0, oz);
            arc = apex;
        }
        final Material mat = (block != null && block.isBlock()) ? block : Material.MAGMA_BLOCK;
        final float s = Math.max(0.2f, scale);
        final boolean glow = "lava".equals(flavor) || "spark".equals(flavor);
        BlockDisplay disp;
        try {
            disp = world.spawn(from, BlockDisplay.class, d -> {
                d.setBlock(mat.createBlockData());
                d.setBrightness(new Display.Brightness(15, 15));
                d.setGlowing(glow);
                d.setPersistent(false);
                d.setTeleportDuration(1);
                d.setTransformation(new Transformation(
                        new Vector3f(-s / 2f, -s / 2f, -s / 2f), new AxisAngle4f(),
                        new Vector3f(s, s, s), new AxisAngle4f()));
            });
        } catch (RuntimeException ex) {
            return id;   // display entities unsupported — the block paint still runs, just without a flyer
        }
        live.put(id, new Meteor(disp, from, to, Math.max(2, travelTicks), arc, spinDeg, true, flavor, s));
        return id;
    }

    /** The ground burst when a patch erupts (at its launch, not a landing) — flavour-matched particles,
     *  sound, and an optional player shockwave. */
    void eruptBurst(Location ground, String flavor, boolean shake) {
        if (!spec.fxEnabled || world == null || ground == null) return;
        switch (flavor) {
            case "steam":
                pp(Particle.CLOUD, ground, 20, 0.6, 0.2, 0.6, 0.05);
                pp(Particle.BUBBLE_POP, ground, 24, 0.7, 0.1, 0.7, 0.0);
                playSound(ground, "block.fire.extinguish", 0.7f, 0.7f);
                break;
            case "rock":
                pp(Particle.EXPLOSION, ground, 4, 0.6, 0.2, 0.6, 0.0);
                pp(Particle.LARGE_SMOKE, ground, 24, 0.7, 0.3, 0.7, 0.0);
                pp(Particle.ASH, ground, 30, 0.8, 0.2, 0.8, 0.0);
                playSound(ground, "entity.generic.explode", 0.9f, 0.5f);
                break;
            case "spark":
                pp(Particle.END_ROD, ground, 14, 0.4, 0.2, 0.4, 0.03);
                pp(Particle.CRIT, ground, 16, 0.5, 0.2, 0.5, 0.1);
                playSound(ground, "block.amethyst_block.chime", 0.6f, 1.6f);
                break;
            default:   // lava
                pp(Particle.EXPLOSION_EMITTER, ground, 1);
                pp(Particle.LAVA, ground, 24, 0.7, 0.3, 0.7, 0.0);
                pp(Particle.FLAME, ground, 20, 0.6, 0.3, 0.6, 0.02);
                try {
                    world.spawnParticle(Particle.BLOCK, ground, 30, 0.7, 0.3, 0.7, 0.0, Material.MAGMA_BLOCK.createBlockData());
                } catch (RuntimeException ignored) { }
                playSound(ground, "entity.generic.explode", 0.8f, 0.6f);
        }
        if (shake) shockwave(ground);
    }

    /**
     * A single block physically emerging from the ground: spawn a block-aligned {@link BlockDisplay} of the
     * real block at {@code from} (below the floor) and lift it straight up to {@code to} (its slot) over
     * {@code ticks}. The caller ({@link ArenaMorph}) places the real block and calls {@link #retireLift} when
     * the cap arrives. Returns {@code -1} if display entities aren't supported, so the caller can place directly.
     */
    int launchLift(Location from, Location to, int ticks, Material block, String flavor) {
        if (!spec.fxEnabled || world == null) return -1;
        final Material mat = (block != null && block.isBlock()) ? block : Material.STONE;
        BlockDisplay disp;
        try {
            disp = world.spawn(from, BlockDisplay.class, d -> {
                d.setBlock(mat.createBlockData());
                d.setBrightness(new Display.Brightness(15, 15));
                d.setPersistent(false);
                d.setTeleportDuration(2);
                // block-aligned: unit cube, no centring translation, so it fills its cell exactly on arrival
                d.setTransformation(new Transformation(
                        new Vector3f(0, 0, 0), new AxisAngle4f(), new Vector3f(1, 1, 1), new AxisAngle4f()));
            });
        } catch (RuntimeException ex) {
            return -1;
        }
        int id = nextId++;
        live.put(id, new Meteor(disp, from, to, Math.max(2, ticks), 0.0, 0.0, false, flavor, 1f));
        return id;
    }

    /** Retire a rising cap's display (its real block has just been placed) with a small settle bloom. */
    void retireLift(int id) {
        Meteor m = live.remove(id);
        if (m == null) return;
        if (m.entity != null && !m.entity.isDead()) m.entity.remove();
        if (m.to != null) landPuff(m.to, m.flavor);
    }


    /** Fire the impact burst for the meteor with {@code id} and retire its entity. Safe if the id is unknown. */
    void impact(int id) {
        Meteor m = live.remove(id);
        Location at = (m != null) ? m.to : null;
        if (m != null && m.entity != null && !m.entity.isDead()) m.entity.remove();
        if (!spec.fxEnabled || world == null || at == null) return;
        pp(Particle.EXPLOSION_EMITTER, at, 1);
        pp(Particle.EXPLOSION, at, 8, 1.0, 1.0, 1.0, 0.0);
        pp(Particle.LAVA, at, 24, 1.4, 0.8, 1.4, 0.0);
        try {
            world.spawnParticle(Particle.BLOCK, at, 40, 1.5, 0.8, 1.5, 0.0, spec.coolBlock.createBlockData());
        } catch (RuntimeException ignored) { /* block-crack data unsupported */ }
        if (spec.impactSound != null && !spec.impactSound.isBlank()) playSound(at, spec.impactSound, 1.0f, 0.9f);
        if (spec.lightning) world.strikeLightningEffect(at);
        if (spec.shockwave) shockwave(at);
        if (spec.riftParticles) miniRift(at);   // a SMALL rift tears open at the impact
    }

    /** A short, randomly-oriented rift tear of portal energy at a meteor impact (small-scale rift look). */
    private void miniRift(Location at) {
        double ang = Math.random() * Math.PI;
        double dx = Math.cos(ang), dz = Math.sin(ang);
        for (int s = -6; s <= 6; s++) {
            double t = s * 0.4;
            double x = at.getX() + dx * t, z = at.getZ() + dz * t;
            pp(Particle.REVERSE_PORTAL, x, at.getY() + 0.5, z, 2, 0.08, 0.5, 0.08, 0.02);
            if (s % 2 == 0) pp(Particle.DRAGON_BREATH, x, at.getY() + 0.3, z, 1, 0.05, 0.2, 0.05, 0.0);
        }
    }

    // Some particles require extra data on certain server forks (e.g. Leaf 1.21.11 wants a Float for a few),
    // and calling them without it throws every tick. These guarded spawns skip a particle type permanently
    // after its first failure — no crash, and no repeated (expensive) exception on the morph tick.
    private final java.util.Set<Particle> brokenParticles = java.util.EnumSet.noneOf(Particle.class);

    private void pp(Particle part, double x, double y, double z, int count, double ox, double oy, double oz, double extra) {
        if (world == null || brokenParticles.contains(part)) return;
        try { world.spawnParticle(part, x, y, z, count, ox, oy, oz, extra); }
        catch (RuntimeException ex) { brokenParticles.add(part); }
    }

    private void pp(Particle part, Location at, int count, double ox, double oy, double oz, double extra) {
        if (world == null || brokenParticles.contains(part)) return;
        try { world.spawnParticle(part, at, count, ox, oy, oz, extra); }
        catch (RuntimeException ex) { brokenParticles.add(part); }
    }

    private void pp(Particle part, Location at, int count) {
        if (world == null || brokenParticles.contains(part)) return;
        try { world.spawnParticle(part, at, count); }
        catch (RuntimeException ex) { brokenParticles.add(part); }
    }

    private void shockwave(Location at) {
        double r2 = (double) spec.shockwaveRadius * spec.shockwaveRadius;
        for (Player p : world.getPlayers()) {
            if (p.getLocation().distanceSquared(at) > r2) continue;
            Vector dir = p.getLocation().toVector().subtract(at.toVector());
            dir.setY(0);
            if (dir.lengthSquared() < 1.0E-4) dir = new Vector(Math.random() - 0.5, 0, Math.random() - 0.5);
            dir.normalize().multiply(spec.shockwaveStrength).setY(spec.shockwaveLift);
            p.setVelocity(p.getVelocity().add(dir));
        }
    }

    private void launchCompletionFireworks() {
        for (int i = 0; i < 3; i++) {
            Location loc = center.clone().add((Math.random() - 0.5) * 6, 1.5, (Math.random() - 0.5) * 6);
            try {
                Firework fw = world.spawn(loc, Firework.class);
                FireworkMeta meta = fw.getFireworkMeta();
                meta.addEffect(FireworkEffect.builder()
                        .withColor(Color.RED, Color.ORANGE).withFade(Color.YELLOW)
                        .with(FireworkEffect.Type.BURST).trail(true).flicker(true).build());
                meta.setPower(1);
                fw.setFireworkMeta(meta);
                fw.detonate();
            } catch (RuntimeException ignored) { /* firework spawn failed — skip */ }
        }
    }

    // --- helpers ---

    private void refreshAudience() {
        audience.clear();
        if (center == null) return;
        double r2 = range * range;
        for (Player p : world.getPlayers()) {
            if (p.getLocation().distanceSquared(center) <= r2) audience.add(p);
        }
    }

    private void playSound(Location loc, String key, float vol, float pitch) {
        if (loc == null || key == null) return;
        try {
            world.playSound(loc, key.toLowerCase(java.util.Locale.ROOT), vol, pitch);
        } catch (RuntimeException ignored) { /* unknown sound key — silent */ }
    }

    private static final class Meteor {
        final BlockDisplay entity;
        final Location from, to;
        final int travelTicks;
        final double arcHeight, spinDeg;   // arcHeight>0 = parabolic (ejecta); spinDeg = tumble per flight
        final boolean autoRemove;          // true = the flyer retires itself at the end (risers); false = retired on impact()
        final String flavor;               // particle set: lava | steam | rock | spark | fire
        final float baseScale;
        int progress;
        double angle;
        Meteor(BlockDisplay entity, Location from, Location to, int travelTicks) {
            this(entity, from, to, travelTicks, 0.0, 0.0, false, "fire", 1f);
        }
        Meteor(BlockDisplay entity, Location from, Location to, int travelTicks, double arcHeight,
               double spinDeg, boolean autoRemove, String flavor, float baseScale) {
            this.entity = entity; this.from = from; this.to = to; this.travelTicks = travelTicks;
            this.arcHeight = arcHeight; this.spinDeg = spinDeg; this.autoRemove = autoRemove;
            this.flavor = flavor; this.baseScale = baseScale;
        }
    }
}
