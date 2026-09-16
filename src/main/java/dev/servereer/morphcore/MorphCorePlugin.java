package dev.servereer.morphcore;

import org.bukkit.Material;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Registers the {@link MorphCore} service and owns the style registry ({@code plugins/MorphCore/morphs/}).
 * Never reloaded by PlugMan-style tools in the same way KOTH itself might be — a fresh classloader here
 * would invalidate every consumer's cached service reference, so treat it like PixelAudio: install it,
 * restart the server (or the whole stack) to pick up a jar update, don't hot-swap it alone.
 */
public final class MorphCorePlugin extends JavaPlugin implements MorphCore {

    private MorphRegistry registry;

    @Override
    public void onEnable() {
        registry = new MorphRegistry(this);   // scans/copies plugins/MorphCore/morphs/ on first run
        getServer().getServicesManager().register(MorphCore.class, this, this, ServicePriority.Normal);
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
    }

    @Override public List<String> styleIds() { return registry.styleIds(); }
    @Override public String displayName(String id) { return registry.displayName(id); }
    @Override public Material icon(String id) { return registry.icon(id); }
    @Override public List<String> lore(String id) { return registry.lore(id); }
    @Override public int styleCount() { return registry.count(); }
    @Override public void reload() { registry.reload(); }

    @Override
    public ArenaMorph morph(Plugin owner, MorphGrid target, MorphGrid compare, int seconds,
                             MorphSpec spec, MorphSettings settings, String ownerKeyId) {
        MorphEffect eff = registry.effect(spec.styleId);
        return new ArenaMorph(owner, target, compare, seconds, spec, settings, eff, ownerKeyId);
    }
}
