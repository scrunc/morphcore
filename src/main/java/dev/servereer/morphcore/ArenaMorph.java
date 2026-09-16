package dev.servereer.morphcore;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Level;

/**
 * Animates a region from one {@link MorphGrid} into another by swapping <b>only the cells that
 * differ</b>. Two choreographies are supported (see {@link MorphSpec.Style}):
 *
 * <ul>
 *   <li><b>SWEEP</b> — the original: a single cylindrical ring expands from the footprint centre to
 *       its edge over {@code seconds}.</li>
 *   <li><b>METEOR</b> — the footprint is diced into patches, each assigned a falling meteor. Meteors
 *       are launched staggered across the duration; when one lands its patch is painted outward from
 *       the impact point ("splatter"). Purely a re-ordering of <em>when</em> each cell is written —
 *       the diff and the precise per-cell writes are identical to SWEEP.</li>
 * </ul>
 *
 * <p>Precision guarantee (both styles): the diff compares full {@code BlockData} strings, so a cell
 * that is identical in both snapshots is never written. Blocks are set with physics disabled so
 * sand/water/redstone don't cascade mid-morph. Optionally ({@code cooling}) surface cells land as a
 * glowing "molten" block and settle into their real block a few ticks later.
 *
 * <p>All placement and all cosmetic {@link MorphFx} happen on the main thread; at up to {@link
 * MorphSettings#maxBlocksPerTick()} per tick this stays well under a millisecond per tick.
 *
 * <p>Snapshot-format-agnostic by design: the caller (a consumer plugin, via its own bridge code) hands
 * in {@link MorphGrid}s built from whatever its own capture/persistence format is — this class never
 * reads a snapshot file itself, so it has no opinion on what "capture a region" means for its caller.
 */
public final class ArenaMorph {

    private final Plugin plugin;
    private World world;                   // resolved on start() (main thread) so the diff can be built off-thread
    private final MorphGrid target;       // the grid we are morphing TO
    private final MorphSpec spec;
    private final MorphSettings settings;
    private final int minX, minY, minZ, sizeX, sizeY, sizeZ;
    private final int durationTicks;
    private final int maxPerTick;
    private final int totalCells;
    private final Map<Integer, BlockData> dataCache = new HashMap<>();
    private BlockData coolData;           // parsed molten-crust block (lazy)
    private BlockData glitchData;         // parsed glitch block (lazy)
    // resolved style (from the MorphRegistry): a formula OR a named Java generator
    private final String generator;       // "rift"/"meteor"/"life"/"shatter" — null when formula-driven
    private final Expr formula;           // compiled reveal-score formula — null when generator-driven
    private final boolean meteor;         // == "meteor".equals(generator) (uses the falling-head path)
    private final boolean rising;         // eruption/geyser/upheaval/springup — the upward block-flight path
    private RiseParams rp;                 // resolved rising knobs (rising generators only)
    private static final java.util.Set<String> RISING_GENS =
            java.util.Set.of("eruption", "geyser", "upheaval", "springup");
    // layered fill (meteor): a block is placed only once the rising front has reached its Y layer (+ a small lead)
    private boolean layered;
    private int[] layerRemaining;         // count of unplaced solid cells per local-Y layer
    private int frontLayer;               // lowest layer still holding unplaced cells
    private int layerLead = 1;            // how many layers above the front are allowed (tall structures keep up)
    private int lastPlacedSnapshot = -1;  // watchdog: placedCells at the previous tick
    private int stuckTicks = 0;           // watchdog: consecutive ticks with work pending but zero placement
    private final double[] cellVars = new double[Expr.VAR_COUNT];   // reused per-cell formula input
    private final java.util.Random fxRng = new java.util.Random();   // cosmetic-only jitter (theme particles)
    private MorphFx fx;

    // support-aware reveal (meteor + rising): a block is never placed before something holds it up, so an
    // arch/overhang never appears as free-floating disconnected cells mid-morph.
    private final boolean supportAware;
    private boolean[] inDiff;              // full-grid membership of the changed cells
    private boolean[] placedFlag;          // full-grid: this cell already has a block (real or transient)
    private final java.util.ArrayDeque<Integer> pending = new java.util.ArrayDeque<>();   // cells waiting on support
    // rising EMERGENCE: each exposed cell physically rides up out of the ground as a BlockDisplay, then solidifies
    private final List<Cap> caps = new ArrayList<>();     // in-flight rising caps
    private int maxMovers = 140;                          // cap on simultaneous rising displays (rising only)
    private final Map<Integer, Material> capMatCache = new HashMap<>();

    // ordered-reveal state (SWEEP / SPREAD / RIFT)
    private int[] order;                  // diff cells sorted by the style's reveal score
    private int pointer = 0;

    // METEOR state
    private List<Impact> impacts;
    private List<Fade> fades;                 // small floating decorations twinkled in with particles (no strike)
    private final int splatterTicks;
    // structure-aware meteor knobs (resolved in ctor)
    private final boolean structureAware;
    private final int floatMaxCells;
    private final int minStructureHeight;

    // organic / rift geometry
    private final NoiseField noise;
    private double[] colOff;                    // per-(x,z) noise cache, lazily filled (organic styles only)
    private double[] veinOff, rainPh, shardScore;   // per-(x,z) caches for VEINS / RAIN / SHATTER
    private int[] lifeGrid;                     // per-(x,z) Game-of-Life activation generation (LIFE)
    private final double aCenterX, aCenterZ;   // arena centre (world, block-centred)
    private double halfDiag;                    // half the footprint diagonal (RIFT seam normalisation)
    private double seamSin, seamCos;           // RIFT seam normal (unit); the tear opens perpendicular to it
    private double seamDirX, seamDirZ;         // RIFT seam direction (unit); used to draw the FX seam line

    // cooling: FIFO of packed (revealTick<<32 | cell), added in non-decreasing revealTick order
    private final ArrayDeque<Long> reveals = new ArrayDeque<>();

    private BukkitRunnable task;
    private int elapsed = 0;
    private int placedCells = 0;
    private Runnable onDone;

    // --- display entities: respawn the target's captured Displays, synced to the reveal front ---
    private MorphDisplay[] displayQueue = new MorphDisplay[0];       // sorted ascending by spawn threshold
    private double[] displayThreshold = new double[0];               // parallel: reveal progress 0..1 to spawn at
    private int[] displayY = new int[0];                             // parallel: each display's local Y (layered gate)
    private int displayPtr = 0;
    private final List<RevealingDisplay> livingDisplays = new ArrayList<>();   // in-flight spin+pop animations
    private final List<Display> spawnedDisplays = new ArrayList<>();           // everything we've placed (cleanup)
    private final String ownerKeyId;                                 // owner PDC tag name, namespaced in start()
    private NamespacedKey displayKey;                                // owner PDC tag, resolved in start()
    private double scoreMin = Double.NaN, scoreMax = Double.NaN;     // reveal-score range, for display sync

    // RISE style: track how high the solid build has reached so players can be flung off as it passes them
    private int riseFrontY = Integer.MIN_VALUE;                      // highest WORLD-Y a solid cell has been placed at
    private final java.util.Set<java.util.UUID> risenEjected = new java.util.HashSet<>();

    /**
     * @param target    the grid to morph the world into
     * @param compare   cells equal in both are left untouched; {@code null} pastes EVERY cell (full reset)
     * @param seconds   sweep duration; &le;0 pastes as fast as {@code settings.maxBlocksPerTick()} allows
     * @param spec      the resolved style + FX settings for this morph
     * @param settings  engine tunables (per-tick budget, structural-support knobs) — the caller's own config
     * @param eff       the resolved style's formula/generator (from the caller's registry lookup on
     *                  {@code spec.styleId}), or {@code null} to fall back to a plain sweep
     * @param ownerKeyId string used to namespace the PDC tag stamped on spawned displays, so a later morph
     *                  can find and retire the ones THIS engine placed (e.g. {@code "arena_display"})
     */
    public ArenaMorph(Plugin plugin, MorphGrid target, MorphGrid compare, int seconds, MorphSpec spec,
                       MorphSettings settings, MorphEffect eff, String ownerKeyId) {
        this.plugin = plugin;
        this.target = target;
        this.spec = spec;
        this.settings = settings;
        this.ownerKeyId = ownerKeyId;
        // NB: no Bukkit API here — this whole ctor (diff + noise + sort) runs OFF the main thread. World is resolved in start().
        this.minX = target.minX();
        this.minY = target.minY();
        this.minZ = target.minZ();
        this.sizeX = target.sizeX();
        this.sizeY = target.sizeY();
        this.sizeZ = target.sizeZ();
        this.durationTicks = Math.max(1, seconds * 20);
        this.maxPerTick = Math.max(1, settings.maxBlocksPerTick());
        this.splatterTicks = Math.max(1, (int) Math.round(spec.splatterSeconds * 20));
        this.noise = new NoiseField(new java.util.Random().nextLong());
        this.aCenterX = minX + sizeX / 2.0;
        this.aCenterZ = minZ + sizeZ / 2.0;
        this.halfDiag = 0.5 * Math.sqrt((double) sizeX * sizeX + (double) sizeZ * sizeZ);
        double theta = (noise.noise(1.5, 2.5) + 1.0) * Math.PI;   // seeded seam angle 0..2π (RIFT)
        this.seamDirX = Math.cos(theta); this.seamDirZ = Math.sin(theta);
        this.seamSin = Math.sin(theta);  this.seamCos = Math.cos(theta);

        // eff was already resolved by the caller against its registry (off-thread; no concurrent writes).
        this.generator = eff != null ? eff.generator : null;
        this.formula = eff != null ? eff.formula : null;
        this.meteor = "meteor".equals(generator);
        this.rising = generator != null && RISING_GENS.contains(generator);

        this.structureAware = settings.structureAware();
        this.floatMaxCells = Math.max(1, settings.floatMaxCells());
        this.minStructureHeight = Math.max(2, settings.minStructureHeight());
        int[] changed = computeDiff(compare);
        this.totalCells = changed.length;
        if (rising) this.rp = RiseParams.forGenerator(generator, eff);   // resolve knobs before patching
        this.inDiff = new boolean[target.cellCount()];
        for (int c : changed) inDiff[c] = true;
        this.placedFlag = new boolean[target.cellCount()];
        boolean sa = false;
        if (meteor || rising) {                       // support-awareness only for the physical styles
            Object o = eff != null ? eff.params.get("support-aware") : null;
            sa = (o == null) || Boolean.parseBoolean(String.valueOf(o).trim());
        }
        this.supportAware = sa;
        if (rising) this.maxMovers = Math.max(1, rp.maxMovers);
        // Layered fill: meteor rises strictly bottom→top layer.
        this.layered = meteor;
        this.layerLead = Math.max(0, settings.layerLead());
        if (layered) {
            layerRemaining = new int[sizeY];
            for (int c : changed) if (!isAir(target.blockAt(c))) layerRemaining[(c / sizeZ) % sizeY]++;
            frontLayer = 0;
            while (frontLayer < sizeY - 1 && layerRemaining[frontLayer] == 0) frontLayer++;
        }
        if ("life".equals(generator)) precomputeLife(eff);   // one-off CA sim (off-thread), feeds the reveal order
        if ("shatter".equals(generator)) precomputeShatter(eff);  // Voronoi shard assignment + random burst order
        if (meteor) {
            buildImpacts(changed);
        } else if (rising) {
            buildRiseImpacts(changed);            // upward eruption patches (off-thread)
        } else {
            buildOrderedReveal(changed);
        }
        buildDisplayReveal(changed);   // off-thread: sort the target's displays to spawn along the front
    }

    /** Cells where target != compare (or every cell when compare == null). */
    private int[] computeDiff(MorphGrid compare) {
        int n = target.cellCount();
        int[] tmp = new int[n];
        int count = 0;
        for (int i = 0; i < n; i++) {
            if (compare != null && compare.blockAt(i).equals(target.blockAt(i))) continue;
            tmp[count++] = i;
        }
        return Arrays.copyOf(tmp, count);
    }

    // --- ordered-reveal setup (SWEEP geometric / SPREAD organic / RIFT organic seam) ---

    private void buildOrderedReveal(int[] changed) {
        long[] keyed = new long[changed.length];
        for (int k = 0; k < changed.length; k++) {
            int i = changed[k];
            long scaled = Math.round((revealScore(i) + 100_000.0) * 16.0);   // shift non-negative, sortable
            keyed[k] = (scaled << 32) | (i & 0xFFFFFFFFL);
        }
        Arrays.sort(keyed);                                                  // ascending score = revealed first
        this.order = new int[keyed.length];
        for (int k = 0; k < keyed.length; k++) order[k] = (int) (keyed[k] & 0xFFFFFFFFL);
    }

    // --- display-entity reveal setup (off-thread: pure math over the captured display positions) ---

    /** Sort the target snapshot's display entities by the same reveal score as the blocks and normalise
     *  each into a 0..1 spawn threshold, so a display pops in exactly when the block front reaches it. */
    private void buildDisplayReveal(int[] changed) {
        List<MorphDisplay> src = target.displays();
        int n = src.size();
        this.displayQueue = new MorphDisplay[n];
        this.displayThreshold = new double[n];
        this.displayY = new int[n];
        if (n == 0) return;
        computeScoreRange(changed);
        boolean validRange = scoreMax > scoreMin;
        double[] score = new double[n];
        int[] yLocal = new int[n];
        Integer[] rankOrder = new Integer[n];
        for (int k = 0; k < n; k++) {
            MorphDisplay d = src.get(k);
            int x = clampInt((int) Math.floor(d.relX()), 0, sizeX - 1);
            int y = clampInt((int) Math.floor(d.relY()), 0, sizeY - 1);
            int z = clampInt((int) Math.floor(d.relZ()), 0, sizeZ - 1);
            score[k] = revealScoreXYZ(x, y, z);
            yLocal[k] = y;
            rankOrder[k] = k;
        }
        // Layered morphs reveal displays LOWER-Y-FIRST (so a floating hologram never pops in before its layer);
        // other styles keep the reveal-score (radius/formula) order.
        if (layered) Arrays.sort(rankOrder, (a, b) -> yLocal[a] != yLocal[b] ? Integer.compare(yLocal[a], yLocal[b]) : Double.compare(score[a], score[b]));
        else Arrays.sort(rankOrder, (a, b) -> Double.compare(score[a], score[b]));
        for (int rank = 0; rank < n; rank++) {
            int k = rankOrder[rank];
            displayQueue[rank] = src.get(k);
            displayY[rank] = yLocal[k];
            // No block-score range to sync against (e.g. a display-only morph) → reveal them at the start.
            displayThreshold[rank] = validRange ? clamp01((score[k] - scoreMin) / (scoreMax - scoreMin)) : 0.0;
        }
    }

    /** One extra off-thread pass over the changed cells to find the reveal-score range the displays
     *  normalise against. Only run when there are displays to place, so styles without any pay nothing. */
    private void computeScoreRange(int[] changed) {
        double mn = Double.POSITIVE_INFINITY, mx = Double.NEGATIVE_INFINITY;
        for (int i : changed) {
            double s = revealScore(i);
            if (s < mn) mn = s;
            if (s > mx) mx = s;
        }
        scoreMin = mn; scoreMax = mx;
    }

    private static int clampInt(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
    private static double clamp01(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    /** Reveal ordering score for a cell (lower = painted sooner). Each style is a different scalar field
     *  over the changed cells; sorting by it produces the choreography. All fields are cheap (cached per
     *  (x,z) column) so this stays a one-off off-thread cost regardless of style. */
    private double revealScore(int i) {
        int z = i % sizeZ, y = (i / sizeZ) % sizeY, x = (i / sizeZ) / sizeY;
        return revealScoreXYZ(x, y, z);
    }

    /** Reveal score at footprint-local coordinates — shared by the block sweep (via {@link #revealScore})
     *  and by display entities so they spawn exactly as the block front passes their column. */
    private double revealScoreXYZ(int x, int y, int z) {
        double dx = (minX + x + 0.5) - aCenterX, dz = (minZ + z + 0.5) - aCenterZ;
        double r = Math.sqrt(dx * dx + dz * dz);
        if (spec.isRise())                            // bottom→top: each layer reveals before the next (centre-out within)
            return y * spec.riseLayerWeight + r;
        if (formula != null) {                       // data-defined style (morphs/*.yml score formula)
            fillVars(x, y, z, dx, dz, r);
            return formula.eval(cellVars, noise);
        }
        if ("rift".equals(generator)) {
            // rise out of the floor: Y dominates (layer-by-layer bottom-up), the tear side orders WITHIN a layer.
            double seam = Math.abs(dx * seamSin - dz * seamCos) / Math.max(1.0, halfDiag);   // 0..1 across the arena
            return y * 1000.0 + seam * 400.0 + columnOffset(x, z);
        }
        if ("life".equals(generator))
            return lifeGenAt(x, z) * lifeStep + rainPhase(x, z) * 0.3;   // CA gen + tie-break jitter
        if ("shatter".equals(generator))
            return shardScore[x * sizeZ + z];
        return r;   // unknown / missing style → plain sweep
    }

    /** Fill the reused variable array for a formula evaluation. Indices come from {@link Expr}. */
    private void fillVars(int x, int y, int z, double dx, double dz, double r) {
        cellVars[Expr.X] = x; cellVars[Expr.Y] = y; cellVars[Expr.Z] = z;
        cellVars[Expr.WX] = minX + x + 0.5; cellVars[Expr.WY] = minY + y + 0.5; cellVars[Expr.WZ] = minZ + z + 0.5;
        cellVars[Expr.DX] = dx; cellVars[Expr.DZ] = dz; cellVars[Expr.R] = r;
        cellVars[Expr.ANGLE] = Math.atan2(dz, dx);
        cellVars[Expr.CX] = aCenterX; cellVars[Expr.CZ] = aCenterZ;
        cellVars[Expr.SIZEX] = sizeX; cellVars[Expr.SIZEY] = sizeY; cellVars[Expr.SIZEZ] = sizeZ;
        cellVars[Expr.SEAMSIN] = seamSin; cellVars[Expr.SEAMCOS] = seamCos;
        cellVars[Expr.SEED] = 0;
    }

    /** Ridged-noise tendril field for VEINS: high on the noise "ridge" lines so corruption veins race out. */
    private double veinOffset(int x, int z) {
        if (veinOff == null) { veinOff = new double[sizeX * sizeZ]; Arrays.fill(veinOff, Double.NaN); }
        int key = x * sizeZ + z;
        double v = veinOff[key];
        if (Double.isNaN(v)) {
            double wx = minX + x + 0.5, wz = minZ + z + 0.5;
            double n = noise.warped(wx, wz, spec.veinScale, 4, spec.organicWarp);   // ~ -1..1
            double ridge = Math.max(0.0, 1.0 - Math.abs(n));
            v = spec.veinAmplitude * Math.pow(ridge, spec.veinSharpness);
            veinOff[key] = v;
        }
        return v;
    }

    /** Stable per-column pseudo-random phase in 0..1 (for RAIN stagger + LIFE tie-breaks). */
    private double rainPhase(int x, int z) {
        if (rainPh == null) { rainPh = new double[sizeX * sizeZ]; Arrays.fill(rainPh, Double.NaN); }
        int key = x * sizeZ + z;
        double v = rainPh[key];
        if (Double.isNaN(v)) {
            v = (noise.noise((x + 0.31) * 12.9898, (z + 0.72) * 78.233) + 1.0) * 0.5;
            rainPh[key] = v;
        }
        return v;
    }

    // resolved generator knobs (pack params override the arena.* config defaults)
    private double lifeStep;
    private int lifeGenerations;

    private double lifeGenAt(int x, int z) {
        int g = lifeGrid[x * sizeZ + z];
        return g == Integer.MAX_VALUE ? lifeGenerations + 1 : g;   // never-lit columns reveal last
    }

    /** Run Conway's Game of Life on the (x,z) footprint and record the generation each column first lit. */
    private void precomputeLife(MorphEffect eff) {
        double seedDensity = eff != null ? eff.paramD("seed-density", spec.lifeSeedDensity) : spec.lifeSeedDensity;
        this.lifeGenerations = eff != null ? eff.paramI("generations", spec.lifeGenerations) : spec.lifeGenerations;
        this.lifeStep = eff != null ? eff.paramD("step", spec.lifeStep) : spec.lifeStep;
        int w = sizeX, d = sizeZ;
        boolean[] cur = new boolean[w * d], next = new boolean[w * d];
        java.util.Random rnd = new java.util.Random((long) totalCells * 0x9E3779B97F4A7C15L + w * 31L + d);
        lifeGrid = new int[w * d];
        Arrays.fill(lifeGrid, Integer.MAX_VALUE);
        for (int i = 0; i < cur.length; i++) if (rnd.nextDouble() < seedDensity) { cur[i] = true; lifeGrid[i] = 0; }
        for (int gen = 1; gen <= lifeGenerations; gen++) {
            for (int x = 0; x < w; x++) {
                for (int z = 0; z < d; z++) {
                    int n = 0;
                    for (int ax = -1; ax <= 1; ax++) for (int az = -1; az <= 1; az++) {
                        if (ax == 0 && az == 0) continue;
                        int nx = x + ax, nz = z + az;
                        if (nx < 0 || nz < 0 || nx >= w || nz >= d) continue;
                        if (cur[nx * d + nz]) n++;
                    }
                    boolean live = cur[x * d + z] ? (n == 2 || n == 3) : (n == 3);
                    next[x * d + z] = live;
                    if (live && lifeGrid[x * d + z] == Integer.MAX_VALUE) lifeGrid[x * d + z] = gen;
                }
            }
            boolean[] tmp = cur; cur = next; next = tmp;
        }
    }

    /** Assign every column to a noise-warped Voronoi shard, then score = shard's random reveal rank +
     *  distance within the shard, so the arena bursts in one jagged shard at a time. */
    private void precomputeShatter(MorphEffect eff) {
        int w = sizeX, d = sizeZ;
        long area = (long) w * d;
        int cfgShards = eff != null ? eff.paramI("shards", spec.shatterShards) : spec.shatterShards;
        int shards = cfgShards > 0 ? cfgShards : (int) Math.max(8, Math.min(60, area / 300));
        java.util.Random rnd = new java.util.Random((long) totalCells * 2654435761L + shards);
        double[] sx = new double[shards], sz = new double[shards];
        for (int s = 0; s < shards; s++) { sx[s] = rnd.nextDouble() * w; sz[s] = rnd.nextDouble() * d; }
        int[] rank = new int[shards];
        for (int s = 0; s < shards; s++) rank[s] = s;
        for (int s = shards - 1; s > 0; s--) { int j = rnd.nextInt(s + 1); int t = rank[s]; rank[s] = rank[j]; rank[j] = t; }
        shardScore = new double[w * d];
        double warp = Math.max(w, d) * 0.06;
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                double nx = x + noise.warped(x * 0.1, z * 0.1, 0.1, 3, warp);
                double nz = z + noise.warped((x + 50) * 0.1, (z + 50) * 0.1, 0.1, 3, warp);
                int best = 0; double bd = Double.MAX_VALUE;
                for (int s = 0; s < shards; s++) {
                    double ddx = nx - sx[s], ddz = nz - sz[s], dd = ddx * ddx + ddz * ddz;
                    if (dd < bd) { bd = dd; best = s; }
                }
                shardScore[x * d + z] = rank[best] * 1000.0 + Math.sqrt(bd);
            }
        }
    }

    /** Noise displacement (blocks) for the column (x,z), cached — every cell in a vertical column shares it,
     *  so this collapses up to {@code sizeY}× redundant noise evaluations into one. 0 for the clean SWEEP style. */
    private double columnOffset(int x, int z) {
        if (!spec.usesOrganic()) return 0;
        if (colOff == null) { colOff = new double[sizeX * sizeZ]; Arrays.fill(colOff, Double.NaN); }
        int key = x * sizeZ + z;
        double v = colOff[key];
        if (Double.isNaN(v)) {
            double wx = minX + x + 0.5, wz = minZ + z + 0.5;
            v = spec.organicAmplitude * noise.warped(wx, wz, spec.organicScale, spec.organicOctaves, spec.organicWarp);
            colOff[key] = v;
        }
        return v;
    }

    // --- METEOR setup ---

    private void buildImpacts(int[] changed) {
        this.impacts = new ArrayList<>();
        this.fades = new ArrayList<>();
        if (changed.length == 0) return;
        if (!structureAware || !buildStructureImpacts(changed)) {
            voronoiImpacts(changed);   // structure-aware off, or a pure-flat morph → organic-patch meteors
        }
        scheduleLaunches();
        scheduleFades();
    }

    /**
     * Structure-aware meteors: segment the changed SOLID cells into connected structures, strike each
     * raised structure with its own meteor (cells revealed bottom-up from the impact), twinkle small
     * floating decorations in with particles instead of striking them, and meteor the leftover flat-ground
     * repaint via {@link #voronoiImpacts}. Returns {@code false} (→ plain Voronoi) when nothing raised is added.
     */
    private boolean buildStructureImpacts(int[] changed) {
        int total = sizeX * sizeY * sizeZ;
        int cols = sizeX * sizeZ;
        boolean[] solid = new boolean[total];
        int[] colMaxY = new int[cols];
        Arrays.fill(colMaxY, -1);
        int groundLevel = Integer.MAX_VALUE;
        for (int i : changed) {
            if (isAir(target.blockAt(i))) continue;
            solid[i] = true;
            int z = i % sizeZ, y = (i / sizeZ) % sizeY, x = (i / sizeZ) / sizeY;
            int ck = x * sizeZ + z;
            if (y > colMaxY[ck]) colMaxY[ck] = y;
            if (y < groundLevel) groundLevel = y;
        }
        if (groundLevel == Integer.MAX_VALUE) return false;                 // nothing solid changed

        // A "structure column" rises at least min-height above the flat floor; thin ground repaint is NOT one.
        int threshold = groundLevel + minStructureHeight;
        boolean[] structCol = new boolean[cols];
        boolean anyStructCol = false;
        int globalTop = groundLevel;
        for (int ck = 0; ck < cols; ck++) if (colMaxY[ck] >= threshold) {
            structCol[ck] = true; anyStructCol = true;
            if (colMaxY[ck] > globalTop) globalTop = colMaxY[ck];
        }
        if (!anyStructCol) return false;                                    // nothing rises → plain Voronoi

        // Connected components of the STRUCTURE COLUMNS only (8-conn in XZ) — this splits the castle/towers
        // apart from the flat floor that connects everything, so a band is one structure, not the whole arena.
        int[] label = new int[cols];
        Arrays.fill(label, -1);
        int compCount = 0;
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int ck = 0; ck < cols; ck++) {
            if (!structCol[ck] || label[ck] >= 0) continue;
            int id = compCount++;
            label[ck] = id; q.add(ck);
            while (!q.isEmpty()) {
                int c = q.poll();
                int cx = c / sizeZ, cz = c % sizeZ;
                for (int ax = -1; ax <= 1; ax++) for (int az = -1; az <= 1; az++) {
                    if (ax == 0 && az == 0) continue;
                    int nx = cx + ax, nz = cz + az;
                    if (nx < 0 || nz < 0 || nx >= sizeX || nz >= sizeZ) continue;
                    int nk = nx * sizeZ + nz;
                    if (structCol[nk] && label[nk] < 0) { label[nk] = id; q.add(nk); }
                }
            }
        }

        // Gather each structure's full-height solid cells (every solid changed cell in its columns).
        List<List<Integer>> compCells = new ArrayList<>();
        for (int k = 0; k < compCount; k++) compCells.add(new ArrayList<>());
        boolean[] consumed = new boolean[total];
        for (int i : changed) {
            if (!solid[i]) continue;
            int z = i % sizeZ, x = (i / sizeZ) / sizeY;
            int ck = x * sizeZ + z;
            if (!structCol[ck]) continue;
            compCells.get(label[ck]).add(i);
            consumed[i] = true;
        }

        for (List<Integer> cellsL : compCells) {
            if (cellsL.isEmpty()) continue;
            int[] comp = new int[cellsL.size()];
            for (int j = 0; j < comp.length; j++) comp[j] = cellsL.get(j);
            int minCY = Integer.MAX_VALUE, maxCY = Integer.MIN_VALUE;
            double sx = 0, sz = 0;
            for (int c : comp) {
                int y = (c / sizeZ) % sizeY, x = (c / sizeZ) / sizeY, z = c % sizeZ;
                if (y < minCY) minCY = y;
                if (y > maxCY) maxCY = y;
                sx += x; sz += z;
            }
            boolean grounded = isGrounded(comp, minCY);
            if (!grounded && comp.length <= floatMaxCells) {              // small + airborne → lantern/balloon fade
                double cx = minX + sx / comp.length + 0.5;
                double cz = minZ + sz / comp.length + 0.5;
                double cy = minY + (minCY + maxCY) / 2.0 + 0.5;
                fades.add(new Fade(comp, cx, cy, cz));
            } else {                                                     // grounded (or large airborne) → build up in bands
                addStructureMeteors(comp, minCY, maxCY, groundLevel, globalTop);
            }
        }

        // Everything else (thin ground repaint, removed-air cells, low bumps) → the organic ground meteors.
        List<Integer> leftover = new ArrayList<>();
        for (int i : changed) if (!consumed[i]) leftover.add(i);
        if (!leftover.isEmpty()) {
            int[] arr = new int[leftover.size()];
            for (int j = 0; j < arr.length; j++) arr[j] = leftover.get(j);
            voronoiImpacts(arr);
        }
        return true;
    }

    /** A component is grounded if it sits on the flat: its lowest cells rest on a solid target block below,
     *  or it reaches the footprint floor. Ungrounded + small = a floating decoration (lantern / balloon). */
    private boolean isGrounded(int[] comp, int minCY) {
        if (minCY == 0) return true;
        for (int c : comp) {
            int y = (c / sizeZ) % sizeY;
            if (y != minCY) continue;
            int below = c - sizeZ;                       // y-1 in the same column
            if (below >= 0 && !isAir(target.blockAt(below))) return true;
        }
        return false;
    }

    /**
     * Build a grounded structure BOTTOM-UP over the morph: split it into horizontal Y-bands and give each
     * band its own meteor, launched in sequence so the structure steps up to full height (a meteor keeps
     * striking, each landing higher on the growing build) — instead of the whole thing appearing at once.
     */
    private void addStructureMeteors(int[] comp, int minCY, int maxCY, int globalGround, int globalTop) {
        int height = maxCY - minCY + 1;
        int bandH = Math.max(1, settings.meteorBandHeight());
        int maxBands = Math.max(2, settings.meteorMaxBands());
        int perBandCells = Math.max(4, settings.meteorPerBandCells());
        int maxPerBand = Math.max(1, settings.meteorMaxPerBand());
        int steps = Math.max(2, Math.min(maxBands, (int) Math.ceil((double) height / bandH)));

        List<List<Integer>> bands = new ArrayList<>();
        for (int k = 0; k < steps; k++) bands.add(new ArrayList<>());
        for (int c : comp) {
            int rel = ((c / sizeZ) % sizeY) - minCY;
            int b = Math.min(steps - 1, rel * steps / height);
            bands.get(b).add(c);
        }
        // Time each band by its ABSOLUTE floor-height across the whole build (not this piece's own height),
        // so an overhang at height Y appears exactly when the build front reaches Y — it never floats early.
        int denom = Math.max(1, globalTop - globalGround);
        int usable = Math.max(1, durationTicks - spec.travelTicks);
        for (int k = 0; k < steps; k++) {
            List<Integer> band = bands.get(k);
            if (band.isEmpty()) continue;
            int bandMinY = Integer.MAX_VALUE, bandMaxY = Integer.MIN_VALUE;
            for (int c : band) { int y = (c / sizeZ) % sizeY; if (y < bandMinY) bandMinY = y; if (y > bandMaxY) bandMaxY = y; }
            double frac = clamp01((double) (bandMinY - globalGround) / denom);
            int launch = Math.max(0, (int) Math.round(frac * usable));
            int land = launch + spec.travelTicks;
            int spread = Math.max(2, (int) Math.round((double) (bandMaxY - bandMinY + 1) / denom * durationTicks));
            // Wide layers get MULTIPLE meteors (a tall/big structure = many strikes); thin layers = one.
            int meteorsH = Math.max(1, Math.min(maxPerBand, band.size() / perBandCells));
            for (List<Integer> bucket : gridSplitXZ(band, meteorsH)) {
                if (bucket.isEmpty()) continue;
                double bcx = 0, bcz = 0;
                int bmy = Integer.MAX_VALUE;
                for (int c : bucket) { bcx += (c / sizeZ) / sizeY; bcz += c % sizeZ; int y = (c / sizeZ) % sizeY; if (y < bmy) bmy = y; }
                final double ccx = minX + bcx / bucket.size() + 0.5;
                final double ccz = minZ + bcz / bucket.size() + 0.5;
                bucket.sort((a, b) -> {               // bottom-up within the bucket, radial tiebreak
                    int ya = (a / sizeZ) % sizeY, yb = (b / sizeZ) % sizeY;
                    if (ya != yb) return Integer.compare(ya, yb);
                    return Double.compare(radial2(a, ccx, ccz), radial2(b, ccx, ccz));
                });
                int[] arr = new int[bucket.size()];
                for (int j = 0; j < arr.length; j++) arr[j] = bucket.get(j);
                Impact im = new Impact(ccx, ccz, minY + bmy, arr);   // meteor lands on the growing top
                im.launchTick = launch;
                im.landTick = land;
                im.spreadTicks = spread;
                im.preTimed = true;
                impacts.add(im);
            }
        }
    }

    /** Split a band's cells into up to {@code parts} XZ buckets (a coarse grid) so a wide layer gets several
     *  meteors striking at once, spread across it, instead of a single one at the centroid. */
    private List<List<Integer>> gridSplitXZ(List<Integer> cells, int parts) {
        if (parts <= 1) { List<List<Integer>> one = new ArrayList<>(); one.add(cells); return one; }
        int mnX = Integer.MAX_VALUE, mnZ = Integer.MAX_VALUE, mxX = Integer.MIN_VALUE, mxZ = Integer.MIN_VALUE;
        for (int c : cells) {
            int x = (c / sizeZ) / sizeY, z = c % sizeZ;
            if (x < mnX) mnX = x; if (x > mxX) mxX = x;
            if (z < mnZ) mnZ = z; if (z > mxZ) mxZ = z;
        }
        double spanX = mxX - mnX + 1, spanZ = mxZ - mnZ + 1;
        int gx = Math.max(1, (int) Math.round(Math.sqrt(parts * spanX / Math.max(1, spanZ))));
        int gz = Math.max(1, (int) Math.ceil((double) parts / gx));
        Map<Integer, List<Integer>> m = new HashMap<>();
        for (int c : cells) {
            int x = (c / sizeZ) / sizeY, z = c % sizeZ;
            int bx = Math.min(gx - 1, (int) ((x - mnX) / spanX * gx));
            int bz = Math.min(gz - 1, (int) ((z - mnZ) / spanZ * gz));
            m.computeIfAbsent(bx * gz + bz, k -> new ArrayList<>()).add(c);
        }
        return new ArrayList<>(m.values());
    }

    private double radial2(int cell, double px, double pz) {
        double x = minX + (cell / sizeZ) / sizeY + 0.5, z = minZ + (cell % sizeZ) + 0.5;
        double dx = x - px, dz = z - pz;
        return dx * dx + dz * dz;
    }

    /** The original organic-patch meteors, now callable over any subset of cells (the flat-ground remainder). */
    private void voronoiImpacts(int[] changed) {
        if (changed.length == 0) return;
        long area = (long) sizeX * sizeZ;
        int meteors = spec.meteorCount > 0 ? spec.meteorCount
                : (int) Math.max(6, Math.min(40, area / 400));    // auto: ~1 meteor per 20×20

        // Scatter JITTERED seed points (not a rigid grid) and assign every cell to its nearest seed on a
        // noise-WARPED position — an organic Voronoi. This is what stops the patches looking like squares:
        // the boundary between one meteor's territory and the next wiggles instead of running dead-straight.
        int gcols = Math.max(1, (int) Math.round(Math.sqrt((double) meteors * sizeX / Math.max(1, sizeZ))));
        int grows = Math.max(1, (int) Math.ceil((double) meteors / gcols));
        double patchX = (double) sizeX / gcols, patchZ = (double) sizeZ / grows;
        double warpAmt = Math.max(patchX, patchZ) * 0.65;
        double[] seedX = new double[gcols * grows], seedZ = new double[gcols * grows];
        for (int gx = 0; gx < gcols; gx++) {
            for (int gz = 0; gz < grows; gz++) {
                int s = gx * grows + gz;
                seedX[s] = minX + (gx + 0.5) * patchX + noise.noise(gx * 3.1 + 0.5, gz * 1.7 + 0.9) * patchX * 0.5;
                seedZ[s] = minZ + (gz + 0.5) * patchZ + noise.noise(gx * 1.3 + 9.2, gz * 2.9 + 4.4) * patchZ * 0.5;
            }
        }

        int[] colSeed = new int[sizeX * sizeZ];       // cache: every cell in a column shares a seed
        Arrays.fill(colSeed, -1);
        Map<Integer, List<Integer>> buckets = new HashMap<>();
        for (int i : changed) {
            int z = i % sizeZ, x = (i / sizeZ) / sizeY;
            int colKey = x * sizeZ + z;
            int seed = colSeed[colKey];
            if (seed < 0) {
                double wx = minX + x + 0.5, wz = minZ + z + 0.5;
                double nwx = wx + noise.fbm(wx * spec.organicScale, wz * spec.organicScale, spec.organicOctaves, 2, 0.5) * warpAmt;
                double nwz = wz + noise.fbm((wx + 123.4) * spec.organicScale, (wz - 77.7) * spec.organicScale, spec.organicOctaves, 2, 0.5) * warpAmt;
                int cgx = Math.min(gcols - 1, (int) (x / patchX)), cgz = Math.min(grows - 1, (int) (z / patchZ));
                double best = Double.MAX_VALUE;
                for (int ax = Math.max(0, cgx - 1); ax <= Math.min(gcols - 1, cgx + 1); ax++) {
                    for (int az = Math.max(0, cgz - 1); az <= Math.min(grows - 1, cgz + 1); az++) {
                        int s = ax * grows + az;
                        double dx = nwx - seedX[s], dz = nwz - seedZ[s], d = dx * dx + dz * dz;
                        if (d < best) { best = d; seed = s; }
                    }
                }
                if (seed < 0) seed = 0;
                colSeed[colKey] = seed;
            }
            buckets.computeIfAbsent(seed, k -> new ArrayList<>()).add(i);
        }

        for (Map.Entry<Integer, List<Integer>> e : buckets.entrySet()) {
            List<Integer> cells = e.getValue();
            int s = e.getKey();
            int maxSolidY = -1;
            for (int i : cells) {
                int y = (i / sizeZ) % sizeY;
                if (!isAir(target.blockAt(i)) && y > maxSolidY) maxSolidY = y;
            }
            double cx = seedX[s], cz = seedZ[s];      // meteor falls to its seed; splatter spreads from there
            double landY = minY + (maxSolidY >= 0 ? maxSolidY + 1 : sizeY / 2);
            // paint outward from the seed: organic like SPREAD, blended with a SMALL per-impact rift seam.
            double th = (noise.noise(cx * 0.13, cz * 0.13) + 1.0) * Math.PI;
            final double iSin = Math.sin(th), iCos = Math.cos(th), blend = spec.meteorRiftBlend;
            cells.sort((a, b) -> Double.compare(
                    meteorScore(a, cx, cz, iSin, iCos, blend), meteorScore(b, cx, cz, iSin, iCos, blend)));
            int[] arr = new int[cells.size()];
            for (int j = 0; j < arr.length; j++) arr[j] = cells.get(j);
            impacts.add(new Impact(cx, cz, landY, arr));
        }
    }

    /** Twinkle floating decorations in during the LAST third of the morph, so lanterns/balloons appear
     *  after the structures that hold them are built — not floating in empty air at the start. */
    private void scheduleFades() {
        int m = fades.size();
        int startAt = (int) (durationTicks * 0.65);
        int span = Math.max(1, durationTicks - startAt);
        for (int k = 0; k < m; k++) {
            fades.get(k).fadeTick = startAt + (m <= 1 ? span / 2 : (int) Math.round((double) k / (m - 1) * span));
        }
    }

    private void scheduleLaunches() {
        // Only the auto (flat-ground Voronoi) impacts get timed here; structure bands carry their own timing.
        List<Impact> auto = new ArrayList<>();
        for (Impact im : impacts) if (!im.preTimed) auto.add(im);
        double aCenterX = minX + sizeX / 2.0, aCenterZ = minZ + sizeZ / 2.0;
        switch (spec.order) {
            case CENTER_OUT:
                auto.sort((p, q) -> Double.compare(distSq(p, aCenterX, aCenterZ), distSq(q, aCenterX, aCenterZ)));
                break;
            case EDGES_FIRST:
                auto.sort((p, q) -> Double.compare(distSq(q, aCenterX, aCenterZ), distSq(p, aCenterX, aCenterZ)));
                break;
            default:
                Collections.shuffle(auto, new Random(totalCells * 31L + auto.size()));
        }
        int m = auto.size();
        int lastLaunch = Math.max(0, durationTicks - spec.travelTicks - splatterTicks);
        for (int k = 0; k < m; k++) {
            Impact im = auto.get(k);
            im.launchTick = (m <= 1) ? 0 : (int) Math.round((double) k / (m - 1) * lastLaunch);
            im.landTick = im.launchTick + spec.travelTicks;
        }
    }

    // --- RISING setup (eruption / geyser / upheaval / spring-up) ---

    /**
     * Rising family: dice the footprint into eruption patches, each of which bursts up out of the floor —
     * a cosmetic block flies up from below (arc for ejecta, jet for a geyser) while that patch's cells paint
     * BOTTOM-UP so it visibly grows out of the crater. The four generators differ only in patch size, the
     * launch ordering across patches, arc height, and the FX flavour — all carried by {@link RiseParams}.
     * Pure off-thread math (patching + sort + scheduling); the flying displays are spawned later on-thread.
     */
    private void buildRiseImpacts(int[] changed) {
        this.impacts = new ArrayList<>();
        this.fades = new ArrayList<>();
        if (changed.length == 0) return;

        long area = (long) sizeX * sizeZ;
        int patches = rp.patchTarget > 0 ? rp.patchTarget
                : (int) Math.max(rp.autoMin, Math.min(rp.autoMax, area / rp.autoArea));

        // Jittered-grid organic Voronoi (same approach as the meteor patches) so eruption territories have
        // wiggly, non-square boundaries. Every cell in a column shares its patch (cached by column key).
        int gcols = Math.max(1, (int) Math.round(Math.sqrt((double) patches * sizeX / Math.max(1, sizeZ))));
        int grows = Math.max(1, (int) Math.ceil((double) patches / gcols));
        double patchX = (double) sizeX / gcols, patchZ = (double) sizeZ / grows;
        double warpAmt = Math.max(patchX, patchZ) * 0.6;
        double[] seedX = new double[gcols * grows], seedZ = new double[gcols * grows];
        for (int gx = 0; gx < gcols; gx++) {
            for (int gz = 0; gz < grows; gz++) {
                int s = gx * grows + gz;
                seedX[s] = minX + (gx + 0.5) * patchX + noise.noise(gx * 3.1 + 0.5, gz * 1.7 + 0.9) * patchX * 0.5;
                seedZ[s] = minZ + (gz + 0.5) * patchZ + noise.noise(gx * 1.3 + 9.2, gz * 2.9 + 4.4) * patchZ * 0.5;
            }
        }

        int[] colSeed = new int[sizeX * sizeZ];
        Arrays.fill(colSeed, -1);
        Map<Integer, List<Integer>> buckets = new HashMap<>();
        for (int i : changed) {
            int z = i % sizeZ, x = (i / sizeZ) / sizeY;
            int colKey = x * sizeZ + z;
            int seed = colSeed[colKey];
            if (seed < 0) {
                double wx = minX + x + 0.5, wz = minZ + z + 0.5;
                double nwx = wx + noise.fbm(wx * spec.organicScale, wz * spec.organicScale, spec.organicOctaves, 2, 0.5) * warpAmt;
                double nwz = wz + noise.fbm((wx + 123.4) * spec.organicScale, (wz - 77.7) * spec.organicScale, spec.organicOctaves, 2, 0.5) * warpAmt;
                int cgx = Math.min(gcols - 1, (int) (x / patchX)), cgz = Math.min(grows - 1, (int) (z / patchZ));
                double best = Double.MAX_VALUE;
                for (int ax = Math.max(0, cgx - 1); ax <= Math.min(gcols - 1, cgx + 1); ax++) {
                    for (int az = Math.max(0, cgz - 1); az <= Math.min(grows - 1, cgz + 1); az++) {
                        int s = ax * grows + az;
                        double dx = nwx - seedX[s], dz = nwz - seedZ[s], d = dx * dx + dz * dz;
                        if (d < best) { best = d; seed = s; }
                    }
                }
                if (seed < 0) seed = 0;
                colSeed[colKey] = seed;
            }
            buckets.computeIfAbsent(seed, k -> new ArrayList<>()).add(i);
        }

        List<Impact> built = new ArrayList<>();
        for (Map.Entry<Integer, List<Integer>> e : buckets.entrySet()) {
            List<Integer> cells = e.getValue();
            int s = e.getKey();
            final double cx = seedX[s], cz = seedZ[s];
            int minCY = Integer.MAX_VALUE;
            for (int c : cells) { int y = (c / sizeZ) % sizeY; if (y < minCY) minCY = y; }
            cells.sort((a, b) -> {          // floor cells first, radial tiebreak → the patch grows up from the crater
                int ya = (a / sizeZ) % sizeY, yb = (b / sizeZ) % sizeY;
                if (ya != yb) return Integer.compare(ya, yb);
                return Double.compare(radial2(a, cx, cz), radial2(b, cx, cz));
            });
            int[] arr = new int[cells.size()];
            for (int j = 0; j < arr.length; j++) arr[j] = cells.get(j);
            built.add(new Impact(cx, cz, minY + minCY, arr));   // erupts from the patch floor
        }

        // Ordering across patches IS the choreography: a sweeping wave, a centre-out ripple, or random chaos.
        switch (rp.order) {
            case "center-out":
                built.sort((p, q) -> Double.compare(distSq(p, aCenterX, aCenterZ), distSq(q, aCenterX, aCenterZ)));
                break;
            case "wave": {
                double ang = (noise.noise(7.3, 2.1) + 1.0) * Math.PI;   // seeded sweep direction
                double wx = Math.cos(ang), wz = Math.sin(ang);
                built.sort((p, q) -> Double.compare(p.cx * wx + p.cz * wz, q.cx * wx + q.cz * wz));
                break;
            }
            default:
                Collections.shuffle(built, new Random(totalCells * 31L + built.size()));
        }
        int m = built.size();
        int lastLaunch = Math.max(1, (int) Math.round(durationTicks * rp.staggerFrac));
        for (int k = 0; k < m; k++) {
            Impact im = built.get(k);
            im.launchTick = (m <= 1) ? 0 : (int) Math.round((double) k / (m - 1) * lastLaunch);
            im.landTick = im.launchTick;
            im.spreadTicks = rp.riseTicks;
            im.preTimed = true;
            impacts.add(im);
        }
    }

    /** Erupt each patch as its launch tick arrives: a ground burst + a rising cosmetic flyer, then paint the
     *  patch's cells bottom-up over its {@code spreadTicks} window so the build climbs out of the crater. */
    private int runRising(int budget) {
        for (Impact im : impacts) {
            if (!im.launched && elapsed >= im.launchTick) {
                im.launched = true;
                Location ground = new Location(world, im.cx, im.landY, im.cz);
                fx.eruptBurst(ground, rp.flavor, rp.shake);
                im.fxId = fx.launchFlyer(ground, rp.depth, rp.apex, rp.travelTicks,
                        rp.block, rp.scale, rp.spinDeg, rp.flavor, rp.ejectaSpreadXZ);
            }
            if (!im.launched) continue;
            int window = im.spreadTicks > 0 ? im.spreadTicks : splatterTicks;
            int rate = Math.max(1, (int) Math.ceil((double) im.cells.length / window));
            int placed = 0;
            while (im.pointer < im.cells.length && placed < rate && budget > 0) {
                revealRising(im.cells[im.pointer++]);   // exposed cells physically rise up out of the ground
                placed++;
                budget--;
            }
            if (budget <= 0) break;
        }
        return budget;
    }

    /** Resolved knobs for one rising generator: per-generator defaults, overridable from the pack's
     *  {@code style.params}. Kept a plain value object so {@link #buildRiseImpacts} stays off-thread. */
    private static final class RiseParams {
        final int patchTarget, autoMin, autoMax, autoArea;
        final double apex, depth, spinDeg, staggerFrac, ejectaSpreadXZ;
        final int travelTicks, riseTicks, maxMovers;
        final float scale;
        final Material block;
        final String order, flavor;
        final boolean shake;

        private RiseParams(int patchTarget, int autoMin, int autoMax, int autoArea, double apex, double depth,
                           double spinDeg, double staggerFrac, double ejectaSpreadXZ, int travelTicks, int riseTicks,
                           int maxMovers, float scale, Material block, String order, String flavor, boolean shake) {
            this.patchTarget = patchTarget; this.autoMin = autoMin; this.autoMax = autoMax; this.autoArea = autoArea;
            this.apex = apex; this.depth = depth; this.spinDeg = spinDeg; this.staggerFrac = staggerFrac;
            this.ejectaSpreadXZ = ejectaSpreadXZ; this.travelTicks = travelTicks; this.riseTicks = riseTicks;
            this.maxMovers = maxMovers;
            this.scale = scale; this.block = block; this.order = order; this.flavor = flavor; this.shake = shake;
        }

        static RiseParams forGenerator(String gen, MorphEffect eff) {
            int patch, amin, amax, aarea, travel, rise, movers;
            double apex, depth, spin, stagger, spread;
            float scale; Material block; String order, flavor; boolean shake;
            switch (gen == null ? "" : gen) {
                case "geyser":     // tall water/steam jets, many thin columns, fired at random
                    patch = 0; amin = 10; amax = 48; aarea = 180; apex = 16; depth = 2; spin = 60;
                    stagger = 0.75; spread = 0.4; travel = 12; rise = 8; scale = 0.9f; movers = 120;
                    block = Material.PRISMARINE; order = "random"; flavor = "steam"; shake = false; break;
                case "upheaval":   // few HUGE tectonic slabs heaved up and crashing back, heavy shake
                    patch = 0; amin = 4; amax = 14; aarea = 900; apex = 6; depth = 4; spin = 120;
                    stagger = 0.6; spread = 2.5; travel = 16; rise = 26; scale = 2.2f; movers = 90;
                    block = Material.STONE; order = "random"; flavor = "rock"; shake = true; break;
                case "springup":   // many small, snappy hops in a fast sweeping wave
                    patch = 0; amin = 16; amax = 80; aarea = 90; apex = 3; depth = 1; spin = 40;
                    stagger = 0.85; spread = 0.3; travel = 7; rise = 5; scale = 0.8f; movers = 180;
                    block = Material.LIME_CONCRETE; order = "wave"; flavor = "spark"; shake = false; break;
                case "eruption":
                default:           // volcanic ejecta arcing out of centre-first craters, lava + shake
                    patch = 0; amin = 6; amax = 30; aarea = 380; apex = 11; depth = 3; spin = 220;
                    stagger = 0.5; spread = 1.6; travel = 10; rise = 18; scale = 1.4f; movers = 140;
                    block = Material.MAGMA_BLOCK; order = "center-out"; flavor = "lava"; shake = true; break;
            }
            if (eff != null) {
                patch = eff.paramI("patches", patch);
                apex = eff.paramD("apex", apex);
                depth = eff.paramD("depth", depth);
                spin = eff.paramD("spin-degrees", spin);
                stagger = clampD(eff.paramD("stagger", stagger), 0.05, 1.0);
                spread = eff.paramD("ejecta-spread", spread);
                travel = eff.paramI("travel-ticks", travel);
                rise = eff.paramI("rise-ticks", rise);
                movers = eff.paramI("max-movers", movers);
                scale = (float) eff.paramD("scale", scale);
                order = str(eff, "order", order);
                flavor = str(eff, "flavor", flavor);
                shake = bool(eff, "shake", shake);
                Material b = Material.matchMaterial(str(eff, "block", block.name()));
                if (b != null && b.isBlock()) block = b;
            }
            return new RiseParams(Math.max(0, patch), amin, amax, Math.max(50, aarea), Math.max(0, apex),
                    Math.max(0, depth), spin, stagger, Math.max(0, spread), Math.max(2, travel), Math.max(2, rise),
                    Math.max(1, movers), Math.max(0.2f, scale), block, order.toLowerCase(java.util.Locale.ROOT),
                    flavor.toLowerCase(java.util.Locale.ROOT), shake);
        }

        private static String str(MorphEffect e, String k, String def) {
            Object o = e.params.get(k); return o == null ? def : String.valueOf(o).trim();
        }
        private static boolean bool(MorphEffect e, String k, boolean def) {
            Object o = e.params.get(k); return o == null ? def : Boolean.parseBoolean(String.valueOf(o).trim());
        }
        private static double clampD(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }
    }

    /** Number of cells that will actually change (used for status messages / boss-bar total). */
    public int changeCount() {
        return totalCells;
    }

    /** Kick off the animated sweep. {@code done} (nullable) runs on the main thread when finished. */
    public void start(Runnable done) {
        this.onDone = done;
        this.world = Bukkit.getWorld(target.world());   // main thread — the only Bukkit lookup the build deferred
        if (world == null) {
            plugin.getLogger().warning("Arena morph aborted — world '" + target.world() + "' is not loaded.");
            if (onDone != null) onDone.run();
            return;
        }
        this.displayKey = new NamespacedKey(plugin, ownerKeyId);
        if (spec.displayEnabled) retireOldDisplays();   // clear the previous theme's arena displays (main thread)
        if (totalCells == 0 && displayQueue.length == 0) {   // nothing differs and no displays — done instantly
            if (onDone != null) onDone.run();
            return;
        }
        Location center = new Location(world, minX + sizeX / 2.0, minY + sizeY / 2.0, minZ + sizeZ / 2.0);
        this.fx = new MorphFx(plugin, world, spec, center, settings.fxAudiencePadding());
        if ("rift".equals(generator) && spec.riftParticles) {
            double half = 0.5 * Math.sqrt((double) sizeX * sizeX + (double) sizeZ * sizeZ);
            double y = minY + sizeY / 2.0;
            fx.setRift(new Location(world, aCenterX - seamDirX * half, y, aCenterZ - seamDirZ * half),
                       new Location(world, aCenterX + seamDirX * half, y, aCenterZ + seamDirZ * half));
        }
        fx.start();
        task = new BukkitRunnable() {
            @Override public void run() { tickOnce(); }
        };
        task.runTaskTimer(plugin, 1L, 1L);
    }

    private void tickOnce() {
        elapsed++;
        int budget = maxPerTick;
        if (meteor) budget = runMeteor(budget);
        else if (rising) budget = runRising(budget);
        else budget = runOrdered(budget);
        budget = drainReveals(budget);
        if (rising) tickCaps();                 // land rising caps FIRST so their support frees deferred cells now
        int pendBefore = pending.size();
        budget = drainPending(budget);          // re-try cells whose support has now arrived
        boolean drainedFreely = budget > 0;     // budget wasn't the limiter this tick
        if (spec.displayEnabled) tickDisplays();
        if (spec.isRise() && spec.riseEject) ejectRisingPlayers();
        if (fx != null) fx.tick(progress(), elapsed);
        tickHazard();
        tickTheme();

        boolean blocksDone = (meteor || rising) ? allImpactsDone() : pointer >= order.length;
        boolean displaysDone = !spec.displayEnabled
                || (displayPtr >= displayQueue.length && livingDisplays.isEmpty());
        // Producers are done but cells still wait AND the drain made no progress → those are genuinely
        // unsupported (a floating decoration in the target). Place them now instead of stalling to the timeout.
        if (blocksDone && reveals.isEmpty() && caps.isEmpty()
                && drainedFreely && !pending.isEmpty() && pending.size() >= pendBefore) {
            while (!pending.isEmpty()) doPlace(pending.pollFirst());
        }
        // Watchdog: if nothing has been placed for a while but cells are still waiting, the fill front is
        // WEDGED (e.g. a mutually-supporting pair, or a meteor gate that can't launch because the front is
        // stuck). Force the lowest waiting layer in so the front advances — never let a morph hang.
        if (placedCells != lastPlacedSnapshot) { lastPlacedSnapshot = placedCells; stuckTicks = 0; }
        else if (!pending.isEmpty()) stuckTicks++;
        if (stuckTicks >= 60) { unstickFront(); stuckTicks = 0; }
        if (blocksDone && reveals.isEmpty() && pending.isEmpty() && caps.isEmpty() && displaysDone) {
            finish();
        } else if (elapsed > (durationTicks + spec.travelTicks + splatterTicks) * 4L + 2400) {
            // Pathological only (e.g. the world changed mid-morph). Stop cleanly — NEVER dump the remainder
            // in one tick: every tick above already placed at most maxPerTick, and we keep that guarantee.
            plugin.getLogger().warning("Arena morph exceeded its time budget with "
                    + (totalCells - placedCells) + " cells unplaced — stopping to protect the tick.");
            finish();
        }
    }

    /** Reveal the pre-sorted diff at a steady rate so the front advances over {@code durationTicks}. The
     *  order is what carries the shape (geometric for SWEEP, organic/rift otherwise); a steady rate keeps
     *  the noise-perturbed boundary reading as a creeping, blobby front rather than a snapping radius. */
    private int runOrdered(int budget) {
        int perTick = Math.max(1, (int) Math.ceil((double) totalCells / durationTicks * rateMultiplier()));
        int placed = 0;
        while (pointer < order.length && placed < perTick && budget > 0) {
            placeCell(order[pointer++]);
            placed++;
            budget--;
        }
        return budget;
    }

    private int runMeteor(int budget) {
        for (Impact im : impacts) {
            // Meteors launch on their own schedule (they're cosmetic projectiles); the real blocks are still
            // layer/support-gated in placeCell, so the BUILD fills bottom-up even though a head may fall past it.
            if (!im.launched && elapsed >= im.launchTick) {
                im.launched = true;
                im.fxId = fx.launchMeteor(new Location(world, im.cx, im.landY, im.cz));
            }
        }
        for (Impact im : impacts) {
            if (!im.launched || elapsed < im.landTick) continue;
            if (!im.impacted) { im.impacted = true; fx.impact(im.fxId); }
            int window = im.spreadTicks > 0 ? im.spreadTicks : splatterTicks;
            int rate = Math.max(1, (int) Math.ceil((double) im.cells.length / window));
            int placed = 0;
            while (im.pointer < im.cells.length && placed < rate && budget > 0) {
                placeCell(im.cells[im.pointer++]);
                placed++;
                budget--;
            }
            if (budget <= 0) break;
        }
        // floating decorations (lanterns / balloons) twinkle in with particles — never struck by a meteor
        for (Fade fd : fades) {
            if (fd.done || elapsed < fd.fadeTick) continue;
            fd.done = true;
            for (int c : fd.cells) placeCell(c);   // gated: decorations obey support/layer too (no early floaters)
            fadeBurst(fd);
        }
        return budget;
    }

    /** A soft particle bloom where a floating decoration appears (in place of a meteor strike). */
    private void fadeBurst(Fade fd) {
        if (world == null) return;
        try { world.spawnParticle(Particle.END_ROD, fd.cx, fd.cy, fd.cz, 14, 0.35, 0.35, 0.35, 0.02); } catch (Throwable ignored) { }
        Particle glow = safeParticle("GLOW");
        if (glow == null) glow = safeParticle("WAX_ON");
        if (glow != null) { try { world.spawnParticle(glow, fd.cx, fd.cy, fd.cz, 10, 0.3, 0.3, 0.3, 0.0); } catch (Throwable ignored) { } }
    }

    private boolean allImpactsDone() {
        for (Impact im : impacts) if (im.pointer < im.cells.length) return false;
        for (Fade fd : fades) if (!fd.done) return false;
        return true;
    }

    /** Stop the sweep where it is (plugin disable / a new morph supersedes this one). Leaves no molten crust. */
    public void cancel() {
        if (task != null) { task.cancel(); task = null; }
        flushPendingAndCaps();          // place any deferred/rising cells so none are lost
        flushRevealsRemaining();        // settle any pending molten cells to their real block
        if (world != null) for (RevealingDisplay r : livingDisplays) r.settle();   // no half-grown displays left
        livingDisplays.clear();
        if (fx != null) { fx.dispose(); fx = null; }
    }

    public boolean isRunning() {
        return task != null;
    }

    private void finish() {
        if (task != null) { task.cancel(); task = null; }
        flushPendingAndCaps();      // place any deferred/rising cells so nothing floats or is lost
        flushRevealsRemaining();
        flushDisplaysToFinal();     // spawn any not-yet-revealed displays + settle in-flight ones
        implodePlayers();
        if (fx != null) { fx.complete(); fx = null; }
        if (onDone != null) onDone.run();
    }

    // --- stacking layers (beat / contested / hazard / theme / implosion) — all opt-in, all cheap ---

    /** Reveal-rate multiplier from beat-sync (surge on the beat) and contested (defenders slow the front). */
    private double rateMultiplier() {
        double mult = 1.0;
        if (spec.beatEnabled && spec.beatBpm > 0) {
            double period = 1200.0 / spec.beatBpm;            // ticks per beat (20*60/bpm)
            double ph = (elapsed % period) / period;          // 0..1 within the beat
            mult *= 1.0 + (spec.beatPulse - 1.0) * Math.max(0, 1.0 - ph * 3.0);   // spike right on the beat
        }
        if (spec.contestedEnabled && spec.contestedSlow > 0 && anyDefenderInFootprint()) {
            mult *= (1.0 - spec.contestedSlow);               // players inside hold the corruption back
        }
        return Math.max(0.05, mult);
    }

    private boolean anyDefenderInFootprint() {
        for (Player p : world.getPlayers()) if (inFootprint(p.getLocation())) return true;
        return false;
    }

    /** Every half-second, players standing in a freshly-morphed hazard block take a bite of damage. */
    private void tickHazard() {
        if (!spec.hazardEnabled || spec.hazardDamage <= 0 || (elapsed % 10) != 0) return;
        for (Player p : world.getPlayers()) {
            Location l = p.getLocation();
            if (!inFootprint(l)) continue;
            if (isHazardBlock(l.getBlock().getType()) || isHazardBlock(l.getBlock().getRelative(0, -1, 0).getType())) {
                p.damage(spec.hazardDamage);
                p.setFireTicks(Math.max(p.getFireTicks(), 40));
            }
        }
    }

    private static boolean isHazardBlock(Material m) {
        switch (m) {
            case LAVA: case MAGMA_BLOCK: case FIRE: case SOUL_FIRE: case CAMPFIRE: case SOUL_CAMPFIRE:
            case LAVA_CAULDRON: return true;
            default: return false;
        }
    }

    /** Sprinkle a handful of theme particles across the arena each tick (frost / inferno / void ambience). */
    private void tickTheme() {
        Particle part = themeParticle();
        if (part == null) return;
        for (int k = 0; k < 3; k++) {
            double px = minX + fxRng.nextDouble() * sizeX;
            double py = minY + fxRng.nextDouble() * sizeY;
            double pz = minZ + fxRng.nextDouble() * sizeZ;
            try { world.spawnParticle(part, px, py, pz, 2, 0.3, 0.3, 0.3, 0.01); } catch (Throwable ignored) { }
        }
    }

    private Particle themeParticle() {
        switch (spec.theme) {
            case "frost": case "ice": case "winter": return safeParticle("SNOWFLAKE");
            case "inferno": case "fire": case "hell": return safeParticle("FLAME");
            case "void": case "end": case "portal": return safeParticle("REVERSE_PORTAL");
            default: return null;
        }
    }

    private static Particle safeParticle(String name) {
        try { return Particle.valueOf(name); } catch (IllegalArgumentException e) { return null; }
    }

    /** COLLAPSE + implode-players: at the end, yank everyone in the footprint toward the centre. */
    private void implodePlayers() {
        if (!spec.implodePlayers || world == null) return;   // now works on any style, gated by config
        double cy = minY + sizeY / 2.0;
        for (Player p : world.getPlayers()) {
            Location l = p.getLocation();
            if (!inFootprint(l)) continue;
            Vector dir = new Vector(aCenterX - l.getX(), 0, aCenterZ - l.getZ());
            if (dir.lengthSquared() < 1e-4) continue;
            dir.normalize().multiply(spec.implodeStrength).setY(spec.implodeLift);
            p.setVelocity(dir);
            try { world.spawnParticle(Particle.PORTAL, l.getX(), cy, l.getZ(), 12, 0.4, 0.6, 0.4, 0.2); } catch (Throwable ignored) { }
        }
    }

    private boolean inFootprint(Location l) {
        if (l.getWorld() != world) return false;
        int bx = l.getBlockX(), by = l.getBlockY(), bz = l.getBlockZ();
        return bx >= minX && bx < minX + sizeX && by >= minY && by < minY + sizeY && bz >= minZ && bz < minZ + sizeZ;
    }

    /** RISE: as the build's solid top climbs past a player, fling them up and outward off the structure.
     *  Each player is thrown once as the front reaches their feet; a player who drops well back below the
     *  front re-arms, so riding the rising surface repeatedly keeps bouncing them off. */
    private void ejectRisingPlayers() {
        if (riseFrontY == Integer.MIN_VALUE) return;     // nothing solid placed yet this morph
        for (Player p : world.getPlayers()) {
            Location l = p.getLocation();
            if (!inFootprint(l)) { risenEjected.remove(p.getUniqueId()); continue; }
            int feetY = l.getBlockY();
            if (feetY < riseFrontY - 3) risenEjected.remove(p.getUniqueId());   // fell well below the front → re-arm
            if (feetY > riseFrontY) continue;                                   // build hasn't reached them yet
            if (!risenEjected.add(p.getUniqueId())) continue;                   // already thrown on this pass
            Vector dir = new Vector(l.getX() - aCenterX, 0, l.getZ() - aCenterZ);
            if (dir.lengthSquared() < 1e-4) dir = new Vector(Math.random() - 0.5, 0, Math.random() - 0.5);
            dir.normalize().multiply(spec.riseEjectOut).setY(spec.riseEjectUp);
            p.setVelocity(dir);
            try { world.spawnParticle(Particle.CLOUD, l.getX(), l.getY(), l.getZ(), 12, 0.3, 0.1, 0.3, 0.05); }
            catch (Throwable ignored) { }
        }
    }

    // --- cooling crust ---

    /** Reveal gate for meteor + ordered styles: a solid cell whose support hasn't landed yet is DEFERRED to
     *  {@link #pending} instead of appearing to float; air cells and supported cells go straight through. */
    private void placeCell(int cell) {
        if (!readyNow(cell)) { pending.addLast(cell); return; }
        doPlace(cell);
    }

    /** A cell may be placed now if its layer has been reached (layered fill) AND it has support (support-aware).
     *  Air always passes. Cells that fail either gate wait in {@link #pending} until the condition is met. */
    private boolean readyNow(int cell) {
        if (isAir(target.blockAt(cell))) return true;
        if (layered && ((cell / sizeZ) % sizeY) > frontLayer + layerLead) return false;
        if (supportAware && !supportedNow(cell)) return false;
        return true;
    }

    /** Reveal gate for the RISING styles: an exposed (visible) cell physically rides up out of the ground as a
     *  BlockDisplay and only solidifies on arrival; hidden/interior cells are placed directly. Support is still
     *  enforced first so nothing rises before what holds it up exists. */
    private void revealRising(int cell) {
        if (isAir(target.blockAt(cell))) { doPlace(cell); return; }        // carving air out needs no support
        if (!readyNow(cell)) { pending.addLast(cell); return; }
        revealRisingResolved(cell);
    }

    /** Support already satisfied → either launch a rising cap (visible cell, movers available) or place now. */
    private void revealRisingResolved(int cell) {
        if (spec.fxEnabled && caps.size() < maxMovers && isExposed(cell)) spawnRiser(cell);
        else doPlace(cell);
    }

    /** Spawn a BlockDisplay of this cell's real block below the floor and lift it up into place; the actual
     *  block solidifies when the cap arrives ({@link #tickCaps}). Falls back to a direct place if no display. */
    private void spawnRiser(int cell) {
        int z = cell % sizeZ, y = (cell / sizeZ) % sizeY, x = (cell / sizeZ) / sizeY;
        int wx = minX + x, wy = minY + y, wz = minZ + z;
        double fromY = wy - Math.max(1.0, rp.depth + 1.0);
        int id = fx.launchLift(new Location(world, wx, fromY, wz), new Location(world, wx, wy, wz),
                rp.travelTicks, capMaterial(cell), rp.flavor);
        if (id < 0) { doPlace(cell); return; }            // display entities unsupported → just place it
        caps.add(new Cap(cell, elapsed + rp.travelTicks, id));
    }

    /** Land every rising cap whose flight has completed: solidify the real block and retire its display. */
    private void tickCaps() {
        if (caps.isEmpty()) return;
        for (Iterator<Cap> it = caps.iterator(); it.hasNext(); ) {
            Cap c = it.next();
            if (elapsed >= c.arriveTick) {
                doPlace(c.cell);
                if (fx != null) fx.retireLift(c.fxId);
                it.remove();
            }
        }
    }

    /** Re-try deferred cells whose support has since arrived. Cheap: one pass over the queue per tick, and the
     *  queue only shrinks as the build fills in from the ground up. */
    private int drainPending(int budget) {
        if (pending.isEmpty()) return budget;
        // Cap freed cells to the smooth reveal rate so a just-completed layer doesn't dump in ONE tick (that
        // burst is the stutter + a lighting spike). Also bound how many we re-examine so a big deferred backlog
        // isn't rescanned in full every tick. Ready cells the cap skips get their turn next tick as the FIFO cycles.
        int cap = Math.max(1, (int) Math.ceil((double) totalCells / durationTicks * rateMultiplier()));
        int examineCap = Math.max(cap * 2, 1024);
        int placed = 0, examined = 0, n = pending.size();
        while (examined < n && examined < examineCap && placed < cap && budget > 0) {
            int cell = pending.pollFirst();
            examined++;
            if (!readyNow(cell)) { pending.addLast(cell); continue; }       // support/layer not ready yet — wait
            if (rising) revealRisingResolved(cell); else doPlace(cell);
            placed++;
            budget--;
        }
        return budget;
    }

    /** Actually reveal a cell (real block, or a transient cooling/glitch crust that settles later) and mark it
     *  placed so cells resting on it can follow. Idempotent. */
    private void doPlace(int cell) {
        if (placedFlag[cell]) return;
        placedFlag[cell] = true;
        // Glitch and cooling both land a TRANSIENT block now and settle to the real one a few ticks later,
        // via the same deferred-reveal FIFO. Glitch wins if both are on (a flicker beats a molten crust).
        if ((spec.glitchEnabled || spec.coolEnabled) && isSurface(cell)) {
            placeTransient(cell);
            int settle = spec.glitchEnabled ? Math.max(1, spec.glitchGap * spec.glitchFlickers) : spec.coolTicks;
            reveals.addLast(((long) (elapsed + settle) << 32) | (cell & 0xFFFFFFFFL));
        } else {
            place(cell);
        }
        placedCells++;
        if (layered && !isAir(target.blockAt(cell))) {                     // advance the layered-fill front
            int y = (cell / sizeZ) % sizeY;
            if (layerRemaining[y] > 0) layerRemaining[y]--;
            while (frontLayer < sizeY - 1 && layerRemaining[frontLayer] == 0) frontLayer++;
        }
    }

    /** Break a wedged front: force in every pending cell at the LOWEST waiting layer (bypassing the gate) so
     *  the front can advance. Only called by the watchdog after real stall detection, so it stays bottom-up. */
    private void unstickFront() {
        if (pending.isEmpty()) return;
        int minY = Integer.MAX_VALUE;
        for (int c : pending) { int y = (c / sizeZ) % sizeY; if (y < minY) minY = y; }
        int n = pending.size();
        for (int k = 0; k < n; k++) {
            int c = pending.pollFirst();
            if (((c / sizeZ) % sizeY) == minY) doPlace(c);
            else pending.addLast(c);
        }
    }

    /** On finish/cancel: place every still-deferred cell and land every in-flight cap immediately. */
    private void flushPendingAndCaps() {
        while (!pending.isEmpty()) doPlace(pending.pollFirst());
        for (Cap c : caps) { doPlace(c.cell); if (fx != null) fx.retireLift(c.fxId); }
        caps.clear();
    }

    /** Is there something (already placed, or pre-existing untouched terrain, or the footprint floor) holding
     *  this solid cell up — directly below, or an already-present orthogonal neighbour for overhangs? */
    private boolean supportedNow(int cell) {
        int z = cell % sizeZ, y = (cell / sizeZ) % sizeY, x = (cell / sizeZ) / sizeY;
        if (y == 0) return true;                                  // rests on the footprint floor
        int below = cell - sizeZ;                                 // y-1, same column
        boolean belowSolid = !isAir(target.blockAt(below));
        if (belowSolid && (!inDiff[below] || placedFlag[below])) return true;   // resting on a placed/existing block
        if (hasSolidNeighbor(x, y, z)) return true;              // attached to a placed lateral block (arch/overhang)
        // Not supported yet — but CAN it ever be, from below or the side? A hanging lantern/chain (support is
        // ABOVE) or a floating decoration has nothing solid meant below or beside it: don't wedge the front
        // forever waiting — place it with its own layer (it hangs/floats in the target design anyway).
        if (!belowSolid && !hasSolidTargetNeighbor(x, y, z)) return true;
        return false;
    }

    private boolean hasSolidNeighbor(int x, int y, int z) {
        return lateralOk(x - 1, y, z) || lateralOk(x + 1, y, z) || lateralOk(x, y, z - 1) || lateralOk(x, y, z + 1);
    }

    private boolean lateralOk(int x, int y, int z) {
        if (x < 0 || z < 0 || y < 0 || x >= sizeX || z >= sizeZ || y >= sizeY) return false;
        int c = (x * sizeY + y) * sizeZ + z;
        if (isAir(target.blockAt(c))) return false;
        return placedFlag[c] || !inDiff[c];
    }

    /** Is any lateral neighbour a SOLID target block (placed or not) — i.e. could this cell ever be supported
     *  from the side? Used to tell a real overhang (defer until its side lands) from a hanging/floating block. */
    private boolean hasSolidTargetNeighbor(int x, int y, int z) {
        return solidTargetAt(x - 1, y, z) || solidTargetAt(x + 1, y, z) || solidTargetAt(x, y, z - 1) || solidTargetAt(x, y, z + 1);
    }

    private boolean solidTargetAt(int x, int y, int z) {
        if (x < 0 || z < 0 || y < 0 || x >= sizeX || z >= sizeZ || y >= sizeY) return false;
        return !isAir(target.blockAt((x * sizeY + y) * sizeZ + z));
    }

    /** A visible cell: some orthogonal face is exposed to air (or the footprint edge), so it's worth animating. */
    private boolean isExposed(int cell) {
        int z = cell % sizeZ, y = (cell / sizeZ) % sizeY, x = (cell / sizeZ) / sizeY;
        if (x == 0 || x == sizeX - 1 || z == 0 || z == sizeZ - 1 || y == 0 || y == sizeY - 1) return true;
        int xStep = sizeY * sizeZ;                                                                 // one step in x
        return isAir(target.blockAt(cell + sizeZ)) || isAir(target.blockAt(cell - sizeZ))          // above / below
                || isAir(target.blockAt(cell + xStep)) || isAir(target.blockAt(cell - xStep))      // +x / -x
                || isAir(target.blockAt(cell + 1)) || isAir(target.blockAt(cell - 1));             // +z / -z
    }

    /** The Bukkit {@link Material} of a cell's target block, for the rising cap's display (cached by palette). */
    private Material capMaterial(int cell) {
        return capMatCache.computeIfAbsent(target.paletteIndexAt(cell), k -> {
            try { return Bukkit.createBlockData(target.blockAt(cell)).getMaterial(); }
            catch (RuntimeException ex) { return Material.STONE; }
        });
    }

    /** One rising block-display in flight: which cell it becomes, when it arrives, and its FX id. */
    private static final class Cap {
        final int cell, arriveTick, fxId;
        Cap(int cell, int arriveTick, int fxId) { this.cell = cell; this.arriveTick = arriveTick; this.fxId = fxId; }
    }

    private int drainReveals(int budget) {
        while (budget > 0 && !reveals.isEmpty()) {
            long head = reveals.peekFirst();
            if ((int) (head >>> 32) > elapsed) break;
            reveals.pollFirst();
            place((int) (head & 0xFFFFFFFFL));
            budget--;
        }
        return budget;
    }

    /** Settle all still-molten cells to their real block, budget be damned (called on finish/cancel). */
    private void flushRevealsRemaining() {
        while (!reveals.isEmpty()) {
            long head = reveals.pollFirst();
            place((int) (head & 0xFFFFFFFFL));
        }
    }

    /** A surface cell = a non-air target cell with air (or the footprint top) directly above it. */
    private boolean isSurface(int cell) {
        if (isAir(target.blockAt(cell))) return false;
        int y = (cell / sizeZ) % sizeY;
        if (y == sizeY - 1) return true;
        return isAir(target.blockAt(cell + sizeZ));   // the cell at y+1 in the same column
    }

    private void placeTransient(int cell) {
        BlockData d;
        if (spec.glitchEnabled) {
            if (glitchData == null) glitchData = spec.glitchBlock.createBlockData();
            d = glitchData;
        } else {
            if (coolData == null) coolData = spec.coolBlock.createBlockData();
            d = coolData;
        }
        int z = cell % sizeZ, y = (cell / sizeZ) % sizeY, x = (cell / sizeZ) / sizeY;
        int wx = minX + x, wy = minY + y, wz = minZ + z;
        ensureChunk(wx, wz);
        world.getBlockAt(wx, wy, wz).setBlockData(d, false);
    }

    // --- placement + geometry helpers ---

    private void place(int i) {
        int z = i % sizeZ, y = (i / sizeZ) % sizeY, x = (i / sizeZ) / sizeY;
        int wx = minX + x, wy = minY + y, wz = minZ + z;
        BlockData data = dataCache.computeIfAbsent(target.paletteIndexAt(i), k -> {
            try {
                return Bukkit.createBlockData(target.blockAt(i));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().log(Level.WARNING, "Arena morph: unknown block '" + target.blockAt(i) + "', skipping.");
                return null;
            }
        });
        if (data == null) return;
        ensureChunk(wx, wz);
        world.getBlockAt(wx, wy, wz).setBlockData(data, false);   // false = no physics cascade
        if (spec.isRise() && wy > riseFrontY && !isAir(target.blockAt(i))) riseFrontY = wy;   // track the rising front
    }

    private void ensureChunk(int wx, int wz) {
        int cx = wx >> 4, cz = wz >> 4;
        if (!world.isChunkLoaded(cx, cz)) world.getChunkAt(cx, cz);
    }

    /** Splatter score for a meteor's cell: a blend of radial distance to the impact (SPREAD-like organic
     *  blob) and perpendicular distance to a small per-impact seam (a little RIFT tearing open), both
     *  noise-perturbed via the shared column cache. {@code blend} 0 = pure blob, 1 = pure mini-rift. */
    private double meteorScore(int i, double px, double pz, double sin, double cos, double blend) {
        int z = i % sizeZ, x = (i / sizeZ) / sizeY;
        double wx = minX + x + 0.5, wz = minZ + z + 0.5;
        double dx = wx - px, dz = wz - pz;
        double radial = Math.sqrt(dx * dx + dz * dz);
        double perp = Math.abs(dx * sin - dz * cos) * spec.riftPerpWeight;
        return (1.0 - blend) * radial + blend * perp + columnOffset(x, z);
    }

    private static double distSq(Impact im, double px, double pz) {
        double dx = im.cx - px, dz = im.cz - pz;
        return dx * dx + dz * dz;
    }

    private static boolean isAir(String blockData) {
        // Match the block TYPE, not a substring — "st[air]s"/"..._stairs" contains "air"! Only real air ends in air.
        return blockData.equals("air") || blockData.endsWith(":air") || blockData.endsWith("_air");
    }

    private double progress() {
        return totalCells == 0 ? 1.0 : Math.min(1.0, (double) placedCells / totalCells);
    }

    // --- display entities (main thread) ---

    /** Spawn any displays whose reveal threshold the front has now passed, then advance in-flight ones. */
    private void tickDisplays() {
        if (layered) {   // gate displays to the Y-layer front — never spawn one above the filled layers (no floaters)
            while (displayPtr < displayQueue.length && displayY[displayPtr] <= frontLayer + layerLead) {
                spawnRevealDisplay(displayQueue[displayPtr++]);
            }
        } else {
            double p = progress();
            while (displayPtr < displayQueue.length && displayThreshold[displayPtr] <= p) {
                spawnRevealDisplay(displayQueue[displayPtr++]);
            }
        }
        for (Iterator<RevealingDisplay> it = livingDisplays.iterator(); it.hasNext(); ) {
            if (it.next().tick()) it.remove();
        }
    }

    /** Spawn one captured display and animate it in per {@code spec.displayTransition}
     *  (spinpop = spin + overshoot·pop; grow = scale-only; none = instant). */
    private void spawnRevealDisplay(MorphDisplay dc) {
        Transformation fin = dc.transformation();
        if ("none".equals(spec.displayTransition)) {
            Display e = dc.spawn(world, minX, minY, minZ, displayKey, fin);
            if (e != null) spawnedDisplays.add(e);
            return;
        }
        // Spawn at zero scale (grows in), then physically TELEPORT the entity from the emerge origin (a nearby
        // surface, or high in the sky) to its captured position over the transition — position is moved by
        // teleport, not transform-translation interpolation (which some forks don't apply).
        Vector3f zeroScale = new Vector3f(0, 0, 0);
        Transformation seed = new Transformation(fin.getTranslation(), fin.getLeftRotation(), zeroScale, fin.getRightRotation());
        Display e = dc.spawn(world, minX, minY, minZ, displayKey, seed);
        if (e == null) return;
        spawnedDisplays.add(e);
        Location target = e.getLocation().clone();
        Vector3f off = emergeOffset(dc);
        Location origin = target.clone().add(off.x, off.y, off.z);
        int ticks = Math.max(2, spec.displayTransitionTicks + spec.displayPopTicks);
        if (off.x != 0 || off.y != 0 || off.z != 0) {
            try { e.setTeleportDuration(Math.min(59, Math.max(1, spec.displayTeleportSmooth))); } catch (Throwable ignored) { }
            e.teleport(origin);
        }
        livingDisplays.add(new RevealingDisplay(e, fin, origin, target, ticks));
    }

    /** The seed translation offset for a display's entrance: from the nearest solid surface within
     *  {@code display.surface-search} blocks (so it slides OUT of that surface), or — if it's floating free
     *  in the air — from {@code display.sky-height} blocks straight up (so it descends from the sky). */
    private Vector3f emergeOffset(MorphDisplay dc) {
        if (!spec.displayEmerge || world == null) return new Vector3f(0, 0, 0);
        int wx = (int) Math.floor(minX + dc.relX());
        int wy = (int) Math.floor(minY + dc.relY());
        int wz = (int) Math.floor(minZ + dc.relZ());
        int R = spec.displaySurfaceSearch;
        int[][] dirs = {{0, -1, 0}, {0, 1, 0}, {-1, 0, 0}, {1, 0, 0}, {0, 0, -1}, {0, 0, 1}};
        int bestD = Integer.MAX_VALUE;
        int[] bestDir = null;
        for (int[] d : dirs) {
            for (int step = 1; step <= R; step++) {
                boolean solid;
                try { solid = world.getBlockAt(wx + d[0] * step, wy + d[1] * step, wz + d[2] * step).getType().isSolid(); }
                catch (Throwable ex) { break; }
                if (solid) { if (step < bestD) { bestD = step; bestDir = d; } break; }
            }
        }
        if (bestDir != null) {                    // slide OUT of the surface from a fixed depth (dramatic, not 1 block)
            float depth = (float) spec.displayEmergeDepth;
            return new Vector3f(bestDir[0] * depth, bestDir[1] * depth, bestDir[2] * depth);
        }
        return new Vector3f(0, (float) spec.displaySkyHeight, 0);   // free-floating → descend from the sky
    }

    /**
     * Remove (or shrink out) the display entities inside this footprint before the target's are spawned.
     * By default this retires EVERY display in the cuboid so the active snapshot is the single source of
     * truth (mirrors how a block set overwrites whatever was there, and stops hand-placed originals piling
     * up alongside their re-spawned copies). {@code display.replace-all: false} narrows it to only the
     * displays THIS engine itself placed (the {@code ownerKeyId} PDC tag from the constructor), leaving
     * foreign holograms.
     */
    private void retireOldDisplays() {
        BoundingBox box = new BoundingBox(minX, minY, minZ, minX + sizeX, minY + sizeY, minZ + sizeZ);
        for (Entity e : world.getNearbyEntities(box)) {
            if (!(e instanceof Display d)) continue;
            boolean owned = d.getPersistentDataContainer().has(displayKey, PersistentDataType.BYTE);
            if (!owned && !spec.displayReplaceAll) continue;   // owned-only mode leaves foreign displays be
            if (spec.displayShrinkOutOld) {
                int ticks = spec.displayTransitionTicks;
                try {
                    Transformation cur = d.getTransformation();
                    d.setInterpolationDelay(0);
                    d.setInterpolationDuration(ticks);
                    d.setTransformation(new Transformation(cur.getTranslation(), cur.getLeftRotation(),
                            new Vector3f(0, 0, 0), cur.getRightRotation()));
                    Bukkit.getScheduler().runTaskLater(plugin, () -> { if (d.isValid()) d.remove(); }, ticks + 1L);
                } catch (RuntimeException ex) {
                    d.remove();
                }
            } else {
                d.remove();
            }
        }
    }

    /** On finish: spawn any displays the front never reached (instant, at their final transform) and
     *  snap every in-flight animation to its final transform so nothing is left mid-grow. */
    private void flushDisplaysToFinal() {
        while (displayPtr < displayQueue.length) {
            MorphDisplay dc = displayQueue[displayPtr++];
            Display e = dc.spawn(world, minX, minY, minZ, displayKey, dc.transformation());
            if (e != null) spawnedDisplays.add(e);
        }
        for (RevealingDisplay r : livingDisplays) r.settle();
        livingDisplays.clear();
    }

    /** One display animating in: the ENTITY is teleported from its emerge origin to its captured position
     *  over {@code total} ticks (eased), while its scale grows 0→final. Position is moved by teleport (not
     *  transform-translation interpolation, which some forks ignore); scale uses a 1-tick transform lerp. */
    private static final class RevealingDisplay {
        private final Display e;
        private final Transformation finalT;
        private final Vector3f finalScale;
        private final double ox, oy, oz, tx, ty, tz;
        private final float yaw, pitch;
        private final int total;
        private int age;

        RevealingDisplay(Display e, Transformation finalT, Location origin, Location target, int total) {
            this.e = e; this.finalT = finalT; this.finalScale = finalT.getScale();
            this.ox = origin.getX(); this.oy = origin.getY(); this.oz = origin.getZ();
            this.tx = target.getX(); this.ty = target.getY(); this.tz = target.getZ();
            this.yaw = target.getYaw(); this.pitch = target.getPitch();
            this.total = Math.max(1, total);
        }

        /** @return true when the animation is complete (or the entity is gone). */
        boolean tick() {
            if (!e.isValid()) return true;
            age++;
            double t = Math.min(1.0, (double) age / total);
            double k = 1 - Math.pow(1 - t, 3);                 // easeOutCubic
            try {
                double x = ox + (tx - ox) * k, y = oy + (ty - oy) * k, z = oz + (tz - oz) * k;
                e.teleport(new Location(e.getWorld(), x, y, z, yaw, pitch));
                float s = (float) k;
                e.setInterpolationDelay(0);
                e.setInterpolationDuration(1);
                e.setTransformation(new Transformation(finalT.getTranslation(), finalT.getLeftRotation(),
                        new Vector3f(finalScale.x * s, finalScale.y * s, finalScale.z * s), finalT.getRightRotation()));
            } catch (RuntimeException ex) {
                return true;
            }
            if (t >= 1.0) { settle(); return true; }
            return false;
        }

        void settle() {
            if (!e.isValid()) return;
            try {
                e.teleport(new Location(e.getWorld(), tx, ty, tz, yaw, pitch));
                e.setInterpolationDuration(0);
                e.setTransformation(finalT);
            } catch (RuntimeException ignored) { }
        }
    }

    /** One meteor's territory: its impact column, land height, and the cells it paints (near→far).
     *  {@code preTimed} impacts (structure build-bands) carry their own launch timing + {@code spreadTicks}
     *  window; auto impacts (flat-ground Voronoi) are timed by {@link #scheduleLaunches}. */
    private static final class Impact {
        final double cx, cz, landY;
        final int[] cells;
        int launchTick, landTick, pointer, fxId, spreadTicks;
        boolean launched, impacted, preTimed;
        Impact(double cx, double cz, double landY, int[] cells) {
            this.cx = cx; this.cz = cz; this.landY = landY; this.cells = cells;
        }
    }

    /** A small floating decoration (lantern / balloon) that fades in with a particle bloom at {@code fadeTick}. */
    private static final class Fade {
        final int[] cells;
        final double cx, cy, cz;
        int fadeTick;
        boolean done;
        Fade(int[] cells, double cx, double cy, double cz) {
            this.cells = cells; this.cx = cx; this.cy = cy; this.cz = cz;
        }
    }
}
