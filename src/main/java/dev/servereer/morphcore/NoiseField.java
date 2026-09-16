package dev.servereer.morphcore;

/**
 * A tiny, dependency-free value-noise field with fractal (fBm) summing and optional domain warping —
 * enough to make an arena morph's reveal boundary look <b>organic</b> (wavy, blobby, tendrilled) instead
 * of a clean geometric ring. Deterministic for a given seed; cheap enough to sample once per changed cell.
 *
 * <p>Not Perlin/simplex — just hashed-lattice value noise with a quintic fade, which is plenty for
 * perturbing a distance field. Output of {@link #fbm} is roughly in {@code [-1, 1]}.
 */
public final class NoiseField {

    private final long seed;

    public NoiseField(long seed) {
        this.seed = seed;
    }

    /** Domain-warped fractal noise at world x/z. {@code warp} (blocks) bends the sample point for a more
     *  organic, less grid-aligned result; pass 0 to skip warping. */
    public double warped(double x, double z, double freq, int octaves, double warp) {
        if (warp > 0) {
            double wx = fbm((x + 31.4) * freq, (z - 17.9) * freq, octaves, 2.0, 0.5);
            double wz = fbm((x - 42.1) * freq, (z + 9.2) * freq, octaves, 2.0, 0.5);
            x += wx * warp;
            z += wz * warp;
        }
        return fbm(x * freq, z * freq, octaves, 2.0, 0.5);
    }

    /** Sum {@code octaves} of value noise, each doubling in frequency and halving in weight. ~[-1,1]. */
    public double fbm(double x, double y, int octaves, double lacunarity, double gain) {
        double amp = 1, freq = 1, sum = 0, norm = 0;
        for (int i = 0; i < octaves; i++) {
            sum += amp * noise(x * freq, y * freq);
            norm += amp;
            amp *= gain;
            freq *= lacunarity;
        }
        return norm == 0 ? 0 : sum / norm;
    }

    /** Single-octave value noise in {@code [-1, 1]}. */
    public double noise(double x, double y) {
        int x0 = fastFloor(x), y0 = fastFloor(y);
        double fx = x - x0, fy = y - y0;
        double u = fade(fx), v = fade(fy);
        double a = lerp(lattice(x0, y0), lattice(x0 + 1, y0), u);
        double b = lerp(lattice(x0, y0 + 1), lattice(x0 + 1, y0 + 1), u);
        return lerp(a, b, v);
    }

    /** Deterministic hashed value at an integer lattice point, in {@code [-1, 1]}. */
    private double lattice(int x, int y) {
        long h = seed + x * 0x9E3779B97F4A7C15L + y * 0xD6E8FEB86659FD93L;
        h ^= (h >>> 30);
        h *= 0xBF58476D1CE4E5B9L;
        h ^= (h >>> 27);
        h *= 0x94D049BB133111EBL;
        h ^= (h >>> 31);
        return ((h >>> 11) * 0x1.0p-53) * 2.0 - 1.0;
    }

    private static int fastFloor(double v) { int i = (int) v; return v < i ? i - 1 : i; }
    private static double fade(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
}
