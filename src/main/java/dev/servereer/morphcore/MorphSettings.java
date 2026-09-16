package dev.servereer.morphcore;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Engine tunables that apply to every morph regardless of style — per-tick placement budget and the
 * structural-support knobs the meteor/rise generators use. Style/FX choices (which choreography, boss
 * bar, particles, ...) live in {@link MorphSpec} instead; these are the ones the animation loop itself
 * needs to stay responsive on a big or oddly-shaped region.
 *
 * <p>Each consumer plugin owns its own config file and keys — MorphCore never reads one directly. {@link
 * #resolve} expects a section shaped like KOTH's own {@code arena.*} block (same key names, just handed
 * over as a section instead of read via a hardcoded "arena." prefix), so an existing config needs zero
 * migration; a brand new consumer can equally just pass {@code null} and get sane defaults.
 */
public record MorphSettings(
        int maxBlocksPerTick,
        boolean structureAware,
        int floatMaxCells,
        int minStructureHeight,
        int layerLead,
        int meteorBandHeight,
        int meteorMaxBands,
        int meteorPerBandCells,
        int meteorMaxPerBand,
        int fxAudiencePadding) {

    public static final MorphSettings DEFAULTS =
            new MorphSettings(12000, true, 8, 3, 1, 2, 20, 40, 6, 0);

    /** Resolve from a section shaped like {@code arena.*}; missing keys fall back to {@link #DEFAULTS}.
     *  {@code section} may be null (returns {@link #DEFAULTS} outright). */
    public static MorphSettings resolve(ConfigurationSection section) {
        if (section == null) return DEFAULTS;
        return new MorphSettings(
                Math.max(1, section.getInt("max-blocks-per-tick", DEFAULTS.maxBlocksPerTick())),
                section.getBoolean("meteor.structure-aware", DEFAULTS.structureAware()),
                Math.max(1, section.getInt("structure.float-max-cells", DEFAULTS.floatMaxCells())),
                Math.max(2, section.getInt("structure.min-height", DEFAULTS.minStructureHeight())),
                Math.max(0, section.getInt("layer-lead", DEFAULTS.layerLead())),
                Math.max(1, section.getInt("meteor.band-height", DEFAULTS.meteorBandHeight())),
                Math.max(2, section.getInt("meteor.max-bands", DEFAULTS.meteorMaxBands())),
                Math.max(4, section.getInt("meteor.per-band-cells", DEFAULTS.meteorPerBandCells())),
                Math.max(1, section.getInt("meteor.max-per-band", DEFAULTS.meteorMaxPerBand())),
                Math.max(0, section.getInt("fx.audience-padding", DEFAULTS.fxAudiencePadding())));
    }
}
