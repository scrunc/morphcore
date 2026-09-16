package dev.servereer.morphcore;

import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.util.Transformation;

import java.util.function.Supplier;

/**
 * One display entity captured inside a {@link MorphGrid}, positioned relative to the grid's min corner —
 * enough for {@link ArenaMorph} to sequence its reveal against the block sweep front and spawn it when
 * the front reaches it. The actual entity-format knowledge (block/item/text display, NBT-level fidelity)
 * stays entirely with the consumer plugin's own capture type; this only carries a position plus a
 * {@link Spawner} callback so MorphCore never needs to know that format.
 */
public final class MorphDisplay {

    /** Instantiates the captured entity in the world. Implemented by the consumer plugin (a method
     *  reference onto its own display-capture type is the usual shape), never by MorphCore itself. */
    @FunctionalInterface
    public interface Spawner {
        /** Spawn in {@code world} at the grid's min corner + this display's relative offset, stamped with
         *  {@code ownerKey} so a later morph can find and retire it. {@code initial} is the "seed" transform
         *  to animate in from (null = appear at its final transform immediately). Return null if it can't
         *  spawn (unsupported fork, bad captured data, etc.) — the caller treats that as a skip, not an error. */
        Display spawn(World world, int minX, int minY, int minZ, NamespacedKey ownerKey, Transformation initial);
    }

    private final double relX, relY, relZ;
    private final Spawner spawner;
    private final Supplier<Transformation> finalTransformation;

    public MorphDisplay(double relX, double relY, double relZ, Spawner spawner, Supplier<Transformation> finalTransformation) {
        this.relX = relX;
        this.relY = relY;
        this.relZ = relZ;
        this.spawner = spawner;
        this.finalTransformation = finalTransformation;
    }

    /** Position relative to the grid's min corner (blocks) — used to sync this display's reveal to the
     *  block sweep front. */
    public double relX() { return relX; }
    public double relY() { return relY; }
    public double relZ() { return relZ; }

    /** The captured (final) transformation, so the caller can build a "seed" (e.g. zero-scale) transform
     *  to animate in from. */
    public Transformation transformation() { return finalTransformation.get(); }

    public Display spawn(World world, int minX, int minY, int minZ, NamespacedKey ownerKey, Transformation initial) {
        return spawner.spawn(world, minX, minY, minZ, ownerKey, initial);
    }
}
