package dev.servereer.morphcore;

import org.bukkit.Material;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The single source of truth for available arena-morph styles, loaded from {@code morphs/*.yml} via
 * {@link MorphPackStore} — a dropped-in pack appears automatically with its own name/icon/lore, and
 * {@link ArenaMorph} resolves the chosen id's formula/generator at build time. Package-private: consumers
 * reach this through the {@link MorphCore} service, published by {@link MorphCorePlugin}.
 */
final class MorphRegistry {

    private final Plugin plugin;
    private final MorphPackStore store;
    private final Map<String, MorphEffect> effects = new LinkedHashMap<>();

    MorphRegistry(Plugin plugin) {
        this.plugin = plugin;
        this.store = new MorphPackStore(plugin);
        reload();
    }

    void reload() {
        effects.clear();
        for (MorphEffect e : store.loadAll()) effects.put(e.id, e);   // later files override earlier ids
        if (effects.isEmpty()) {
            plugin.getLogger().warning("No morph effects loaded from /plugins/MorphCore/morphs/ — morphs fall back to a plain sweep.");
        }
    }

    MorphEffect effect(String id) {
        return id == null ? null : effects.get(id.trim().toLowerCase(Locale.ROOT));
    }

    /** All style ids in load order — menus cycle through these. */
    List<String> styleIds() {
        return new ArrayList<>(effects.keySet());
    }

    String displayName(String id) {
        MorphEffect e = effect(id);
        return e != null ? e.name : ("&f" + id);
    }

    Material icon(String id) {
        MorphEffect e = effect(id);
        return e != null ? e.icon : Material.STONE;
    }

    List<String> lore(String id) {
        MorphEffect e = effect(id);
        return e != null ? new ArrayList<>(e.lore) : new ArrayList<>();
    }

    int count() {
        return effects.size();
    }
}
