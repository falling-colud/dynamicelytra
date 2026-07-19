# Dynamic Elytra

**Jet-style elytra vapor trails + first-person speed lines** · Minecraft 1.21.1 · NeoForge · client-side · MIT

Two effects in one mod:

- **Trails (world-space).** Streams two contrail ribbons off your elytra wingtips as you glide — thin in a straight line, dense white vapor on a hard bank, driven live by airspeed and turn G-force. Optional trails for other players, fast free-falls and riptide launches. Pure vanilla immediate-mode geometry.
- **Speed lines (first-person).** Anime-style streaks that radiate from where you're heading and bloom the faster you move, scaled per movement source (sprint / falling / vehicle / riptide / elytra). Three styles — speed lines, motion smear, edge vignette — drawn over the finished frame, so they show over Iris. *(This was the standalone Slipstream mod, now folded in.)*

## How it works

**Trails** — two NeoForge events (no mixins): `ClientTickEvent.Post` samples wingtips and pushes trail points; `RenderLevelStageEvent` (AFTER_TRANSLUCENT_BLOCKS) draws a camera-facing `TRIANGLE_STRIP` per wing with the vanilla `POSITION_COLOR` core shader.

**Speed lines** — a `ClientTickEvent.Post` handler samples the player's speed + active source and eases a 0..1 intensity; a `RenderGuiEvent.Pre` handler draws immediate-mode `POSITION_COLOR` streaks (and gradient fills for the vignette) in GUI space.

### Key classes
- `TrailManager` — the trail event handlers.
- `trail/WingtipSampler` — airspeed / turn-G / wingtip maths.
- `render/RibbonRenderer` — immediate-mode ribbon drawing with full GL-state restore.
- `speedlines/SpeedLinesRenderer` — first-person speed-lines sampling + screen-space drawing.

## Building

```
./gradlew build
```

Requires JDK 21. The output jar is written to `build/libs/dynamicelytra-<version>.jar`. The build depends only on
NeoForge — no other dependencies.

## Configuration

In game, open **Mods → Dynamic Elytra → Config**. NeoForge generates the screen from the mod's `ModConfigSpec`, in two
sections: **Vapor Trails** and **Speed Lines**. Everything is also in `config/dynamicelytra-client.toml`.

Upgrading from 2.0.x or earlier: if a legacy `config/vapor.json` (from the old Sodium options page) is present,
its values seed the new defaults, so tuned settings carry over on first launch. That file is only read — never
written or deleted — so you can keep it as a backup or remove it once the TOML looks right.

## Compatibility

Client-side only — no server install needed. Composes with Sodium, Iris and shaderpacks. Built for Minecraft
1.21.1 on NeoForge (`net.neoforged.moddev`).

## License

MIT © falling_colud
