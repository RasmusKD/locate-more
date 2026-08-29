# LocateMore

Find the N nearest structures, biomes or POIs instead of just the closest.
Exact distance order. The server keeps ticking.

```
/locate structure minecraft:mansion 20
/locate biome minecraft:mushroom_fields 3
/locate poi #minecraft:village 5
```

Vanilla `/locate` blocks the server thread and returns one result, and that
result is [not always the nearest](https://bugs.mojang.com/browse/MC-138887).
LocateMore adds an optional count from 1 to 100 to the vanilla command and
routes vanilla's own nearest-structure search through the same engine, so
plain `/locate`, eyes of ender, explorer maps and other mods get the true
nearest too, without blocking a tick. Results stream into chat nearest
first, every line teleports on click, and a boss bar shows progress.

## Why it is fast

Vanilla spirals outward and re-checks overlapping areas. LocateMore starts
from the seed: the seed determines the only chunks where each structure can
start, and the engine walks exactly those candidates in true distance
order, verifying each with the same checks vanilla uses. Candidates in
ungenerated terrain are usually settled by pure math (the same
generation-point math and weighted set draw generation itself would run,
each earned by a referee before it was trusted, and revoked for the session
if a referee ever disagrees), so no chunks are loaded or generated for
them. The results match vanilla; there are simply more of them, sooner.

Measured (release build, virgin world, seed 20260821, count 20; warm is the
same search again):

| structure | cold | warm |
|---|---|---|
| mansion | 1.7 s | 0.06 s |
| ancient_city | 0.10 s | |
| monument | 1.5 s | |
| jungle_pyramid | 3.7 s | 0.10 s |
| #village (tag, multi-set) | 0.17 s | |
| fortress (Nether, multi-set) | 0.04 s | |

Vanilla in the same world: 10 seconds of blocked ticks for 13 of 20. Every
release is diffed coordinate-for-coordinate against real generation on both
supported versions before it ships (test/run-both.sh).

## Biomes and POIs too

`/locate biome` samples the same grid vanilla does, through the same
climate sampler, but off-thread and in exact distance order (vanilla's
spiral is only approximately nearest). N results are N distinct patches: a
hit within `biomeSeparationBlocks` (default 512) of an accepted one is
suppressed. Default radius 12800, double vanilla's, since the extra range
is pure math.

`/locate poi` searches ALL explored terrain instead of vanilla's 256-block
radius, reading the poi storage off-thread, and never pins poi data in
memory the way vanilla's query does. POIs are world state, so unexplored
terrain cannot be searched; in-memory changes not yet on disk are included,
and hits are re-checked against live memory before printing.

## API for other mods

```java
LocateMoreApi.findNearest(level, structures, origin, 5).thenAccept(result -> {
    for (LocateMoreApi.StructureHit hit : result.hits()) {
        // hit.pos(), hit.structure(), hit.distance()
    }
});
```

`com.rasmus.locatemore.api` is the contract; every other package is
internal. Call on the server thread; the future completes there, hits in
exact distance order, with `complete` and `orderingGuaranteed` flags for
budget-cut and unresolved cases. `SearchOptions` lowers budgets per call,
turns off chunk generation (`mathOnly()`), and excludes previous hits.

## Limitations

- Plain `/locate` deliberately returns the true nearest, which vanilla
  sometimes does not (MC-138887). For exact vanilla parity (speedrun
  practice, seed tooling): `/gamerule locatemore:exact_locate false`, per
  world, live; `improveVanillaLocate` in `config/locatemore.json` is the
  server-wide kill switch.
- Multi-set structures in ungenerated terrain need their candidate chunk
  generated to the first stage, exactly like vanilla locate; each probe
  adds 4 to 12 KB to the save and the summary reports the count.
  Single-set structures never need probes.
- Nothing persists between sessions; a datapack reload aborts running
  searches and clears the session memo.
- Default bounds: 1,000,000 block radius, 60 s wall clock, 50,000 candidate
  checks, 2 concurrent searches. Partial results say so. All of it lives in
  `config/locatemore.json`.

## Commands

| command | what |
|---|---|
| `/locate structure <id\|#tag> <count> [min_distance]` | async search, streams the N nearest (optionally at least that far away) |
| `/locate biome <id\|#tag> <count> [min_distance]` | async biome search, N distinct patches |
| `/locate poi <id\|#tag> <count> [min_distance]` | async poi search over all explored terrain |
| `/locatemore near structure\|biome <a> structure\|biome <b> <radius>` | the nearest a with a b within radius, e.g. a village next to a desert (mixed pairs put the structure first) |
| `/locatemore track <x> <y> <z> <name>` | live distance + arrow in the action bar, self-clearing |
| `/locatemore compass <x> <y> <z> <name>` | named compass pointing at the spot |
| `/locatemore verify <structure>` | drift tripwire: shadow parse vs vanilla over 20 chunks |
| `/locatemore prune` | delete empty region files (vanilla's scan path still leaves them, MC-311323) |
| `/gamerule locatemore:exact_locate` | per-world toggle for the vanilla call sites |

Operator permission required, same as vanilla `/locate`. With a permissions
mod (LuckPerms etc.) the `/locatemore` subcommands can be granted
individually: `locatemore.track`, `locatemore.compass`, `locatemore.prune`,
`locatemore.verify`, `locatemore.near`. Structure tags and all dimensions
work. Vanilla clients on a dedicated server see correct output, because
every line uses vanilla translation keys.

## Versions

One jar runs on 26.1.2 and 26.2. Fabric, requires Fabric API. MIT license.
Server-side: installed on a server, vanilla clients get everything; in
singleplayer a client install works.
