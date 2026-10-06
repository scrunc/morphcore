# Changelog

All notable changes to MorphCore. The release workflow copies the section for the pushed tag into the
GitHub Release, followed by every commit since the previous tag.

## [1.0.1] - 2026-10-06

### Fixed
- **Stuck sky after a morph.** `darken-sky` gives each nearby player a client-side downfall + frozen night
  (`setPlayerWeather` / `setPlayerTime`). The audience list is rebuilt every second, and on dispose only the
  players *still in range* were reset — anyone who walked or teleported away kept frozen time and rain until
  they relogged, and `/time` / `/weather` could not override it. `MorphFx` now tracks every darkened player in
  a set, darkens newcomers as they enter range, restores players the moment they leave it, and restores all
  of them on dispose / cancel / completion.

## [1.0.0] - 2026-09-28

### Added
- Initial release: procedural arena-morph animation engine (sweep, meteor, organic, rise, eruption, geyser,
  rift, shatter, rain, collapse, life), MorphFx cosmetic layer, YAML morph packs, Bukkit service API.
