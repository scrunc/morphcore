package dev.servereer.morphcore;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * File-backed store for {@link MorphEffect} packs under {@code plugins/MorphCore/morphs/}. Scans every
 * {@code .yml}/{@code .yaml} there; when the folder is empty (fresh install) it copies the bundled
 * default packs out of the jar so ALL the stock choreographies are available from the first launch.
 */
public final class MorphPackStore {

    /** Bundled packs shipped in the jar (must exist under src/main/resources/morphs/). Any that are missing
     *  from the folder are written out on load, so a jar upgrade drops in new stock packs without ever
     *  overwriting a pack the operator has edited. */
    private static final String[] BUNDLED = {
            "sweep", "spread", "rift", "meteor", "veins", "spiral", "rain", "collapse", "life", "shatter",
            "eruption", "geyser", "upheaval", "springup"
    };

    private final Plugin plugin;
    private final File dir;

    public MorphPackStore(Plugin plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "morphs");
    }

    public List<MorphEffect> loadAll() {
        if (!dir.exists()) dir.mkdirs();
        copyBundled();                 // create any bundled pack not yet on disk (fresh install AND jar upgrade)
        File[] files = list();
        Arrays.sort(files, Comparator.comparing(File::getName));
        List<MorphEffect> out = new ArrayList<>();
        for (File f : files) {
            try {
                YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
                String fileId = f.getName().replaceAll("\\.(yml|yaml)$", "");
                MorphEffect e = MorphEffect.from(fileId, y, plugin.getLogger());
                if (e != null) out.add(e);
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed to load morph pack " + f.getName() + ": " + ex.getMessage());
            }
        }
        return out;
    }

    private File[] list() {
        File[] f = dir.listFiles((d, n) -> n.endsWith(".yml") || n.endsWith(".yaml"));
        return f == null ? new File[0] : f;
    }

    private void copyBundled() {
        for (String id : BUNDLED) {
            if (new File(dir, id + ".yml").exists()) continue;   // don't touch a pack already on disk (may be edited)
            try { plugin.saveResource("morphs/" + id + ".yml", false); }
            catch (IllegalArgumentException ignored) { /* not present in the jar — skip */ }
        }
    }
}
