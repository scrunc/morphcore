package dev.servereer.morphcore;

import java.util.Collections;
import java.util.List;

/**
 * A plain, snapshot-format-agnostic capture of a cuboid's blocks + display entities, relative to its own
 * min corner — the input {@link ArenaMorph} animates FROM/TO. A consumer plugin builds one of these once
 * per morph call from whatever its own persisted snapshot format is (KOTH's {@code ArenaSnapshot} today,
 * some other plugin's own capture type tomorrow); MorphCore itself never reads or writes a snapshot file,
 * so this carries only what the animation loop actually needs.
 *
 * <p>Blocks are palette-compressed the same way a typical snapshot format already stores them (a table of
 * distinct {@code BlockData} strings + one palette index per cell), so building a {@code MorphGrid} from
 * an existing snapshot is usually just handing over its palette/index arrays as-is — no per-cell re-encode.
 */
public final class MorphGrid {

    private final String world;
    private final int minX, minY, minZ, sizeX, sizeY, sizeZ;
    private final String[] palette;     // palette[i] = a BlockData string
    private final short[] indices;      // one palette index per cell, x→y→z order
    private final List<MorphDisplay> displays;

    public MorphGrid(String world, int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ,
                      String[] palette, short[] indices, List<MorphDisplay> displays) {
        this.world = world;
        this.minX = minX; this.minY = minY; this.minZ = minZ;
        this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ;
        this.palette = palette;
        this.indices = indices;
        this.displays = displays != null ? displays : Collections.emptyList();
    }

    public String world() { return world; }
    public int minX() { return minX; }
    public int minY() { return minY; }
    public int minZ() { return minZ; }
    public int sizeX() { return sizeX; }
    public int sizeY() { return sizeY; }
    public int sizeZ() { return sizeZ; }
    public int cellCount() { return indices.length; }

    public List<MorphDisplay> displays() { return displays; }

    /** The BlockData string for cell {@code i} (linear x→y→z index). */
    public String blockAt(int i) { return palette[indices[i] & 0xFFFF]; }

    /** The palette index for cell {@code i} — a stable key for caching parsed {@code BlockData}. */
    public int paletteIndexAt(int i) { return indices[i] & 0xFFFF; }
}
