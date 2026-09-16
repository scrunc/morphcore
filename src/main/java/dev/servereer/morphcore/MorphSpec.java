package dev.servereer.morphcore;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;

/**
 * Fully-resolved settings for a single morph: the animation {@link Style}, the meteor-shower parameters,
 * the molten "cooling" crust, and the cosmetic FX (title card, boss bar, lightning, shockwave, sky
 * theming, completion firework).
 *
 * <p>Every value is resolved by layering an <em>optional</em> per-template override map over a section
 * the CALLER already scoped to its own style block (e.g. KOTH hands in {@code getConfig()
 * .getConfigurationSection("arena")}; a different consumer might use a section named anything else —
 * MorphCore has no opinion). A template stores its overrides as a flat map of dotted keys relative to
 * that same section (e.g. {@code "morph-style"}, {@code "meteor.count"}, {@code "fx.title"}); anything it
 * doesn't set inherits the section's default. That keeps the whole spectacle toggleable and tunable per
 * template without bloating the consumer plugin's own template record — {@code section}/{@code
 * templateOverrides} are handed in already resolved, MorphCore never reads a config file itself. Mirrors
 * {@link MorphSettings#resolve(ConfigurationSection)}'s shape on purpose.
 */
public final class MorphSpec {

    /** Which choreography paints the changed cells. */
    public enum Style {
        /** Classic single ring expanding centre→edge — clean, geometric (the original behaviour). */
        SWEEP,
        /** Organic blob spread from the centre: the reveal front is noise-perturbed so it creeps out in
         *  irregular lobes and tendrils instead of a circle. */
        SPREAD,
        /** A "space rift" tears open along a jagged seam and organic corruption bleeds out both sides. */
        RIFT,
        /** A staggered meteor shower — each meteor paints its own patch in an organic splatter on impact. */
        METEOR,
        /** Living corruption: branching tendrils crawl out along ridged-noise veins, not a smooth blob. */
        VEINS,
        /** Black-hole spiral: cells reveal along a rotating logarithmic arm winding out from the centre. */
        SPIRAL,
        /** Digital rain: vertical streams drain top→floor, each column staggered like falling code. */
        RAIN,
        /** Conway's Game of Life: reveal follows CA generations — gliders and organic growth. */
        LIFE,
        /** Implosion: the front closes edges→centre (optionally yanking players inward at the end). */
        COLLAPSE,
        /** Shatter: the arena reveals as randomly-ordered Voronoi shards bursting into place. */
        SHATTER,
        /** Rise: the build reveals bottom→top (ground layer first) so a pyramid/tower grows up out of the
         *  ground; players caught in the rising blocks are flung up and outward. For high-hill KOTH arenas. */
        RISE;

        static Style of(String s) {
            if (s == null) return SWEEP;
            switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "spread": case "organic": return SPREAD;
                case "rift": case "space-rift": return RIFT;
                case "meteor": return METEOR;
                case "veins": case "corruption": case "tendril": case "tendrils": return VEINS;
                case "spiral": case "blackhole": case "black-hole": return SPIRAL;
                case "rain": case "digital": case "digital-rain": return RAIN;
                case "life": case "gameoflife": case "game-of-life": case "conway": return LIFE;
                case "collapse": case "implode": case "implosion": return COLLAPSE;
                case "shatter": case "shards": return SHATTER;
                case "rise": case "tower": case "pyramid": case "ascend": case "build": return RISE;
                default: return SWEEP;
            }
        }

        public String id() { return name().toLowerCase(Locale.ROOT); }
    }

    /** Launch ordering for the meteor shower. */
    public enum Order { RANDOM, CENTER_OUT, EDGES_FIRST;
        static Order of(String s) {
            if (s == null) return RANDOM;
            switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "center-out": case "centre-out": case "center": return CENTER_OUT;
                case "edges-first": case "edges": case "edge": return EDGES_FIRST;
                default: return RANDOM;
            }
        }
    }

    public final Style style;
    public final String styleId;   // the raw morph-style id — resolved against the MorphRegistry by ArenaMorph

    // --- organic reveal (SPREAD / RIFT, and the local splatter of METEOR) ---
    public final double organicAmplitude;  // how far (blocks) the noise pushes the reveal front → tendril size
    public final double organicScale;       // noise frequency (per block); smaller = bigger lobes
    public final double organicWarp;        // domain-warp strength (blocks); more = curlier, less grid-like
    public final int organicOctaves;        // fBm detail
    public final double riftPerpWeight;     // RIFT: how tightly the tear opens along its seam (higher = thinner)
    public final boolean riftParticles;     // RIFT: emit portal energy along the tearing seam

    // --- meteor shower ---
    public final int meteorCount;        // 0 = auto (scale with area)
    public final int spawnHeight;        // blocks above the arena top the meteor head spawns
    public final int travelTicks;        // fall time per meteor
    public final double splatterSeconds; // time each impact takes to paint its patch
    public final Order order;
    public final Material displayBlock;  // the glowing meteor-head block
    public final float scale;            // meteor-head cube scale
    public final double meteorRiftBlend; // 0 = pure organic blob splatter, 1 = each impact is a small rift tear

    // --- veins (living corruption): ridged-noise tendrils ---
    public final double veinAmplitude, veinScale, veinSharpness;
    // --- spiral (black hole): rotating arm ---
    public final double spiralTightness; public final int spiralArms;
    // --- rain (digital): top→floor streams ---
    public final double rainStagger, rainFallWeight;
    // --- life (game of life): CA-generation reveal ---
    public final double lifeSeedDensity, lifeStep; public final int lifeGenerations;
    // --- collapse (implosion): pull players inward at the end ---
    public final boolean implodePlayers; public final double implodeStrength, implodeLift;
    // --- shatter: Voronoi shard burst ---
    public final int shatterShards; public final double shatterStagger;
    // --- rise (bottom→top build): each layer reveals before the next; players in the way get flung up+out ---
    public final double riseLayerWeight;     // how strictly cells are ordered by Y (higher = cleaner layering)
    public final boolean riseEject; public final double riseEjectUp, riseEjectOut;

    // --- elemental theme preset (none|frost|inferno|void): overlays matched particle + sound + crust ---
    public final String theme;
    // --- beat-sync: pulse the reveal rate in time to a tempo ---
    public final boolean beatEnabled; public final double beatBpm, beatPulse;
    // --- glitch: each surface cell flickers a "glitch" block a few times before settling ---
    public final boolean glitchEnabled; public final int glitchFlickers, glitchGap; public final Material glitchBlock;
    // --- hazard: freshly-placed hazard cells damage players standing on/near them ---
    public final boolean hazardEnabled; public final double hazardDamage; public final int hazardRadius;
    // --- contested: players slow (or speed) the reveal front near them ---
    public final boolean contestedEnabled; public final double contestedRadius, contestedSlow;

    // --- display entities: respawn captured Displays, synced to the reveal front, animating in ---
    public final boolean displayEnabled;
    public final String displayTransition;      // spinpop | grow | none
    public final int displayTransitionTicks;    // main interpolation length (ticks)
    public final int displayPopTicks;           // overshoot→settle length (ticks); spinpop only
    public final double displayOvershoot;       // peak scale multiplier at the top of the pop (spinpop only)
    public final double displaySpinDegrees;     // how far the seed transform is spun before settling
    public final boolean displayShrinkOutOld;   // shrink previously-placed arena displays out instead of a hard cut
    public final boolean displayReplaceAll;     // retire EVERY display in the footprint (not just KOTH-placed ones)
    public final boolean displayEmerge;         // emerge from the nearest surface (else descend from the sky)
    public final int displaySurfaceSearch;      // how many blocks to probe for a nearby surface
    public final double displayEmergeDepth;     // how far INTO the surface it starts (so it visibly slides out)
    public final double displaySkyHeight;       // if no surface is near, start this many blocks above and descend
    public final int displayTeleportSmooth;     // teleport-duration ticks for smoothing the per-tick slide

    // --- molten cooling crust (applies to the surface cells of any style) ---
    public final boolean coolEnabled;
    public final Material coolBlock;
    public final int coolTicks;

    // --- cosmetic FX ---
    public final boolean fxEnabled;
    public final String title, subtitle;
    public final boolean bossBar;
    public final String bossBarText;
    public final String launchSound, impactSound;
    public final boolean lightning;
    public final boolean shockwave;
    public final int shockwaveRadius;
    public final double shockwaveStrength, shockwaveLift;
    public final boolean darkenSky;
    public final boolean ambientRumble;
    public final boolean completionFirework;

    private MorphSpec(ConfigurationSection section, Map<String, Object> tpl) {
        Reader r = new Reader(section, tpl);
        this.styleId = r.str("morph-style", "sweep").trim().toLowerCase(Locale.ROOT);
        this.style = Style.of(styleId);

        this.organicAmplitude = Math.max(0, r.number("organic.amplitude", 14));
        this.organicScale = Math.max(0.001, r.number("organic.scale", 0.05));
        this.organicWarp = Math.max(0, r.number("organic.warp", 8));
        this.organicOctaves = Math.max(1, Math.min(6, r.integer("organic.octaves", 4)));
        this.riftPerpWeight = Math.max(1.0, r.number("rift.perp-weight", 3.5));
        this.riftParticles = r.bool("rift.particles", true);

        this.meteorCount = Math.max(0, r.integer("meteor.count", 0));
        this.spawnHeight = Math.max(3, r.integer("meteor.spawn-height", 30));
        this.travelTicks = Math.max(2, r.integer("meteor.travel-ticks", 14));
        this.splatterSeconds = Math.max(0.1, r.number("meteor.splatter-seconds", 1.0));
        this.order = Order.of(r.str("meteor.order", "random"));
        this.displayBlock = material(r.str("meteor.display-block", "MAGMA_BLOCK"), Material.MAGMA_BLOCK);
        this.scale = (float) Math.max(0.2, r.number("meteor.scale", 1.6));
        this.meteorRiftBlend = Math.max(0, Math.min(1, r.number("meteor.rift-blend", 0.55)));

        this.veinAmplitude = Math.max(0, r.number("veins.amplitude", 22));
        this.veinScale = Math.max(0.001, r.number("veins.scale", 0.06));
        this.veinSharpness = Math.max(1.0, r.number("veins.sharpness", 2.2));
        this.spiralTightness = Math.max(0.01, r.number("spiral.tightness", 0.35));
        this.spiralArms = Math.max(1, r.integer("spiral.arms", 1));
        this.rainStagger = Math.max(0, r.number("rain.stagger", 18));
        this.rainFallWeight = Math.max(0.1, r.number("rain.fall-weight", 1.0));
        this.lifeSeedDensity = Math.max(0.05, Math.min(0.9, r.number("life.seed-density", 0.32)));
        this.lifeGenerations = Math.max(1, Math.min(200, r.integer("life.generations", 40)));
        this.lifeStep = Math.max(0.1, r.number("life.step", 2.5));
        this.implodePlayers = r.bool("collapse.implode-players", false);
        this.implodeStrength = Math.max(0, r.number("collapse.implode-strength", 0.8));
        this.implodeLift = Math.max(0, r.number("collapse.implode-lift", 0.3));
        this.shatterShards = Math.max(0, r.integer("shatter.shards", 0));   // 0 = auto by area
        this.shatterStagger = Math.max(0, r.number("shatter.stagger", 0.75));
        this.riseLayerWeight = Math.max(1.0, r.number("rise.layer-weight", 1000.0));
        this.riseEject = r.bool("rise.eject", true);
        this.riseEjectUp = Math.max(0, r.number("rise.eject-up", 0.9));
        this.riseEjectOut = Math.max(0, r.number("rise.eject-out", 0.7));

        this.theme = r.str("theme", "none").trim().toLowerCase(Locale.ROOT);
        this.beatEnabled = r.bool("beat.enabled", false);
        this.beatBpm = Math.max(0, r.number("beat.bpm", 128));
        this.beatPulse = Math.max(1.0, r.number("beat.pulse", 1.8));
        this.glitchEnabled = r.bool("glitch.enabled", false);
        this.glitchFlickers = Math.max(1, Math.min(6, r.integer("glitch.flickers", 3)));
        this.glitchGap = Math.max(1, r.integer("glitch.gap-ticks", 2));
        this.glitchBlock = material(r.str("glitch.block", "AMETHYST_BLOCK"), Material.AMETHYST_BLOCK);
        this.hazardEnabled = r.bool("hazard.enabled", false);
        this.hazardDamage = Math.max(0, r.number("hazard.damage", 4));
        this.hazardRadius = Math.max(0, r.integer("hazard.radius", 2));
        this.contestedEnabled = r.bool("contested.enabled", false);
        this.contestedRadius = Math.max(1, r.number("contested.radius", 6));
        this.contestedSlow = Math.max(0, Math.min(1, r.number("contested.slow", 0.4)));

        this.displayEnabled = r.bool("display.enabled", true);
        this.displayTransition = r.str("display.transition", "spinpop").trim().toLowerCase(Locale.ROOT);
        this.displayTransitionTicks = Math.max(1, r.integer("display.transition-ticks", 12));
        this.displayPopTicks = Math.max(0, r.integer("display.pop-ticks", 4));
        this.displayOvershoot = Math.max(1.0, r.number("display.overshoot", 1.15));
        this.displaySpinDegrees = r.number("display.spin-degrees", 90);
        this.displayShrinkOutOld = r.bool("display.shrink-out-old", true);
        this.displayReplaceAll = r.bool("display.replace-all", true);
        this.displayEmerge = r.bool("display.emerge", true);
        this.displaySurfaceSearch = Math.max(1, r.integer("display.surface-search", 4));
        this.displayEmergeDepth = Math.max(1.0, r.number("display.emerge-depth", 5));
        this.displaySkyHeight = Math.max(1.0, r.number("display.sky-height", 18));
        this.displayTeleportSmooth = Math.max(1, r.integer("display.teleport-smooth", 3));

        this.coolEnabled = r.bool("cooling.enabled", false);
        this.coolBlock = material(r.str("cooling.block", "MAGMA_BLOCK"), Material.MAGMA_BLOCK);
        this.coolTicks = Math.max(1, r.integer("cooling.ticks", 12));

        this.fxEnabled = r.bool("fx.enabled", true);
        this.title = r.str("fx.title", "&c&l⚔ THE TIDE TURNS ⚔");
        this.subtitle = r.str("fx.subtitle", "&7The arena bends to the King");
        this.bossBar = r.bool("fx.boss-bar", true);
        this.bossBarText = r.str("fx.boss-bar-text", "&c&lThe arena is reshaping…");
        this.launchSound = r.str("fx.launch-sound", "entity.ghast.shoot");
        this.impactSound = r.str("fx.impact-sound", "entity.generic.explode");
        this.lightning = r.bool("fx.lightning", true);
        this.shockwave = r.bool("fx.shockwave.enabled", true);
        this.shockwaveRadius = Math.max(1, r.integer("fx.shockwave.radius", 5));
        this.shockwaveStrength = Math.max(0, r.number("fx.shockwave.strength", 0.9));
        this.shockwaveLift = Math.max(0, r.number("fx.shockwave.lift", 0.4));
        this.darkenSky = r.bool("fx.darken-sky", false);
        this.ambientRumble = r.bool("fx.ambient-rumble", true);
        this.completionFirework = r.bool("fx.completion-firework", true);
    }

    /** Resolve a spec by overlaying {@code templateOverrides} (may be null/empty) on {@code section}
     *  (also may be null — every key just falls back to its hardcoded default). */
    public static MorphSpec resolve(ConfigurationSection section, Map<String, Object> templateOverrides) {
        return new MorphSpec(section, templateOverrides == null ? Collections.emptyMap() : templateOverrides);
    }

    public boolean isMeteor() { return style == Style.METEOR; }
    public boolean isRift() { return style == Style.RIFT; }
    public boolean isVeins() { return style == Style.VEINS; }
    public boolean isSpiral() { return style == Style.SPIRAL; }
    public boolean isRain() { return style == Style.RAIN; }
    public boolean isLife() { return style == Style.LIFE; }
    public boolean isCollapse() { return style == Style.COLLAPSE; }
    public boolean isShatter() { return style == Style.SHATTER; }
    public boolean isRise() { return style == Style.RISE; }
    /** True for the styles whose reveal boundary uses the SPREAD-style organic blob noise cache. */
    public boolean usesOrganic() {
        return (style == Style.SPREAD || style == Style.RIFT || style == Style.METEOR) && organicAmplitude > 0;
    }

    private static Material material(String name, Material fallback) {
        if (name == null) return fallback;
        Material m = Material.matchMaterial(name.trim());
        return (m != null && m.isBlock()) ? m : fallback;
    }

    /** Reads a dotted key from the template override map, falling back to that same key in {@code
     *  section} (already scoped by the caller — see the class javadoc) — or the hardcoded default if
     *  {@code section} itself is null. */
    private static final class Reader {
        private final ConfigurationSection cfg;
        private final Map<String, Object> tpl;
        Reader(ConfigurationSection cfg, Map<String, Object> tpl) { this.cfg = cfg; this.tpl = tpl; }

        String str(String key, String def) {
            Object o = tpl.get(key);
            if (o != null) return String.valueOf(o);
            return cfg != null ? cfg.getString(key, def) : def;
        }
        int integer(String key, int def) {
            Object o = tpl.get(key);
            if (o != null) { try { return Integer.parseInt(String.valueOf(o).trim()); } catch (NumberFormatException e) { return def; } }
            return cfg != null ? cfg.getInt(key, def) : def;
        }
        double number(String key, double def) {
            Object o = tpl.get(key);
            if (o != null) { try { return Double.parseDouble(String.valueOf(o).trim()); } catch (NumberFormatException e) { return def; } }
            return cfg != null ? cfg.getDouble(key, def) : def;
        }
        boolean bool(String key, boolean def) {
            Object o = tpl.get(key);
            if (o != null) return Boolean.parseBoolean(String.valueOf(o).trim());
            return cfg != null ? cfg.getBoolean(key, def) : def;
        }
    }
}
