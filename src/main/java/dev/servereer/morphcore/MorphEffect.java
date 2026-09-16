package dev.servereer.morphcore;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * One arena-morph effect loaded from a {@code morphs/*.yml} pack. It's either <b>formula-based</b>
 * (a compiled {@link Expr} reveal score, fully data-defined) or <b>generator-based</b> (it names a Java
 * generator — {@code rift/meteor/life/shatter} — for looks a formula can't express). Presentation
 * (name/icon/lore) always comes from the pack, so {@code /koth start} lists it with no code changes.
 */
public final class MorphEffect {

    public final String id;
    public final String name;
    public final Material icon;
    public final List<String> lore;
    public final String generator;   // null → formula-based
    public final Expr formula;       // null → generator-based
    public final Map<String, Object> params;

    private MorphEffect(String id, String name, Material icon, List<String> lore,
                        String generator, Expr formula, Map<String, Object> params) {
        this.id = id; this.name = name; this.icon = icon; this.lore = lore;
        this.generator = generator; this.formula = formula; this.params = params;
    }

    public boolean isFormula() { return formula != null; }

    /** Parse one pack (the YAML root) into an effect, or null on a fatal error (logged). */
    public static MorphEffect from(String fileId, ConfigurationSection root, Logger log) {
        String id = sanitize(root.getString("id", fileId));
        if (id.isEmpty()) id = sanitize(fileId);
        if (id.isEmpty()) { log.warning("Morph pack has no usable id — skipped."); return null; }
        String name = root.getString("name", id);
        Material icon = matchMaterial(root.getString("icon", "STONE"));
        List<String> lore = root.getStringList("lore");

        String generator = null;
        Expr formula = null;
        Map<String, Object> params = Collections.emptyMap();
        ConfigurationSection style = root.getConfigurationSection("style");
        if (style != null) {
            ConfigurationSection ps = style.getConfigurationSection("params");
            if (ps != null) params = ps.getValues(false);
            String gen = style.getString("generator");
            String score = style.getString("score");
            if (gen != null && !gen.isBlank()) {
                generator = gen.trim().toLowerCase(Locale.ROOT);
            } else if (score != null && !score.isBlank()) {
                try { formula = Expr.compile(score); }
                catch (RuntimeException ex) {
                    log.warning("Morph pack '" + id + "' has a bad score formula: " + ex.getMessage());
                    return null;
                }
            }
        }
        if (generator == null && formula == null) {
            log.warning("Morph pack '" + id + "' defines neither style.score nor style.generator — skipped.");
            return null;
        }
        return new MorphEffect(id, name, icon, lore, generator, formula, params);
    }

    public double paramD(String key, double def) {
        Object o = params.get(key);
        if (o instanceof Number num) return num.doubleValue();
        if (o != null) { try { return Double.parseDouble(String.valueOf(o).trim()); } catch (NumberFormatException ignored) { } }
        return def;
    }

    public int paramI(String key, int def) { return (int) Math.round(paramD(key, def)); }

    private static String sanitize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
    }

    private static Material matchMaterial(String n) {
        if (n == null) return Material.STONE;
        Material m = Material.matchMaterial(n.trim());
        return m != null ? m : Material.STONE;
    }
}
