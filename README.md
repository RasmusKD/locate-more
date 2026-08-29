# LocateMore

Find the N nearest structures or biomes instead of just the closest. Exact
distance order. The server keeps ticking.

```
/locate structure minecraft:mansion 20
/locate biome minecraft:mushroom_fields 3
```

Vanilla `/locate` blocks the server thread and returns one result. That result
is [not always the nearest](https://bugs.mojang.com/browse/MC-138887).
LocateMore adds an optional count from 1 to 100 to the vanilla command, and
routes vanilla's own nearest-structure search through the same engine, so
plain `/locate`, eyes of ender, and other mods get the true nearest too. The
/locate command - with or without a count - runs entirely off the server
thread; the remaining synchronous-by-contract call sites (eyes of ender,
trades) keep a budgeted on-thread search that falls back to vanilla if a
worst-case query exceeds three seconds. Results stream into chat as they are
confirmed, nearest first. Every line teleports on click. A boss bar shows
progress.

## Why it is fast

Vanilla locate spirals outward and re-checks overlapping areas. LocateMore
starts from the seed instead. The seed determines the only chunks where each
structure can start: one candidate per placement region. LocateMore walks
exactly those candidates in true distance order and verifies each one with the
same checks vanilla uses. The results match vanilla. There are simply more of
them, sooner.

Four mechanisms carry the speed:

- **Generation's math as the verdict.** For a structure set with one member
  (mansions, ancient cities, monuments, temples, outposts), generation would
  run exactly the math the worker just ran: the same generation-point
  function behind the same frequency and exclusion-zone filters. So a
  candidate that is not on disk needs no chunk at all; the verdict is the
  answer. Multi-structure sets (villages, nether complexes) get the same
  treatment through a replica of generation's weighted draw between the set
  members: the first drawn member whose generation point validates is the
  chunk's winner, so a fortress search skips the chunks the draw gives to
  bastions without loading them. The replica shipped as a measure-only
  referee first and earned trust with 427 of 427 predictions confirmed by
  real chunk loads across 7 seeds; if any referee ever observes a
  disagreement, that placement falls back to chunk loads for the session
  with a WARN, and sets larger than 8 members (datapacks) always load. The
  same trust covers the vanilla call sites: plain `/locate`, eyes of ender,
  and other mods calling vanilla's search. Only the lab modes keep real
  generation as their referee.
- **The filesystem as the negative source.** A candidate whose region file
  is absent cannot be on disk and goes straight to the math: one memoized
  stat per region the search actually visits, so the cost tracks the search,
  not the age of the world.
- **Own verification path.** Vanilla keeps its structure cache on the server
  thread, so the worker never touches it. The worker scans chunk NBT itself,
  runs the biome math on a small thread pool, and sends the few candidates
  that need chunk generation to the server thread in a budgeted queue. An
  ordering barrier keeps the streamed results nearest-first.
- **A session memo for the biome math.** The memo is not saved on purpose,
  because datapacks can change generation without changing the seed. It is
  the mod's only cache, and it lives only in memory: nothing is persisted,
  so nothing can go stale across sessions. A persistent negative index was
  measured against the region catalog and saved zero time, so the mod does
  not keep one (an orphaned `structure_index` file in old worlds is
  harmless).

## Measured

Release build, virgin world, seed 20260821, fixed position, 20 of each
structure. Reproducible.

Cold search on a freshly booted server (JIT cold, memo empty), count 20;
warm is the same search again in the same session. Cold cost is pure seed
math across every candidate the count requires - no chunks are loaded or
generated for any of these:

| structure | cold | warm (memo) |
|---|---|---|
| mansion | 1.7 s | 0.06 s |
| ancient_city | 0.10 s | |
| monument | 1.5 s | |
| jungle_pyramid | 3.7 s | 0.10 s |
| #village (tag, multi-set) | 0.17 s | |
| fortress (Nether, multi-set) | 0.04 s | |

Ungenerated candidates are pure math on every path, single-set and
multi-set alike: zero chunks loaded or generated, at any count, and the
summary line's `avoided=` figure counts the loads the math replaced. Both
trusts were earned by referees before they shipped (100% math agreement on
the single-set battery; 427/427 draw predictions across 7 seeds), and two
watchdogs stay on duty: the release battery generates the multi-set answers
for real and diffs them against the trusted engine's, and a standing sample
compares draw predictions against chunks already on disk
(`drawSeen=` in the summary).

Control, same world and warm cache: repeated vanilla nearest-searches took 10
seconds and found 13 of 20. The async search never blocks a tick, and every
release is diffed coordinate-for-coordinate against real generation on a
fixed seed before it ships (test/run.sh, PORTING.md).

## Biomes too

`/locate biome` gets the same two upgrades. Vanilla walks a square spiral
in 32-block steps on the server thread, blocking ticks until it returns the
first match - which is only approximately nearest, because a ring's corner
is farther than the next ring's edge. LocateMore samples the same grid
through the same climate sampler, off-thread, in exact distance order, and
an optional count streams the N nearest matches with clickable teleports
(y included: a deep dark at -40 is not "here, but lower").

N biome results are N distinct places: a hit within
`biomeSeparationBlocks` (default 512) of an accepted hit is suppressed, so
one giant swamp cannot fill the list. The search radius is
`biomeMaxDistanceBlocks` (default 12800, double vanilla's 6400 - the search
is pure math off the server thread, so the extra range costs no ticks).
The `exact_locate` gamerule and the `improveVanillaLocate` kill switch
gate the vanilla one-result path exactly like the structure call sites.

## API for other mods

Plain `findNearestMapStructure` calls are already accelerated by the mixin.
The API adds multi-result and an async handoff:

```java
LocateMoreApi.findNearest(level, structures, origin, 5).thenAccept(result -> {
    for (LocateMoreApi.StructureHit hit : result.hits()) {
        // hit.pos(), hit.structure(), hit.distance()
    }
});
```

The API lives in `com.rasmus.locatemore.api`; every other package is
internal. Call on the server thread; the future completes on the server
thread with hits in exact distance order. `SearchResult` carries two flags:
`orderingGuaranteed` (false when unresolved candidates mean a nearer hit
could be missing) and `complete` (false when a budget stopped the search
before the space within range was exhausted - the difference between "only
three exist" and "we ran out of time after three"). When every worker slot
is busy the request queues (player searches admit first) instead of
failing. Server budgets from `locatemore.json` apply as hard ceilings, but
the chat-facing `maxCount` knob does not clamp API calls; `SearchOptions`
lowers budgets per call, turns off chunk generation
(`SearchOptions.mathOnly()`: instant and
exact where provable, partial and flagged elsewhere, never writes to the
world), and excludes previous hits so "find the next ones" is one call.
When `result.orderingGuaranteed()` is false, a nearer structure may exist in
a chunk the search was not allowed to resolve. `LocateMoreApi.API_VERSION`
marks the contract.

## Limitations

- **Plain `/locate` results deliberately differ from vanilla.** Vanilla can
  return a structure that is not the nearest (MC-138887); this mod returns
  the true nearest, for the command, for eyes of ender, and for other mods.
  Explorer maps (cartographer trades, treasure maps in chests, dolphins) go
  through the engine too: candidates are walked in true distance order,
  pruned with math where provable, and exactly the first candidate vanilla's
  own skip-known filter and canBeReferenced accept is loaded - the reference
  mutation is vanilla's addReference, never reimplemented. The multi-second
  trade freeze becomes one chunk generation. Note that this path writes by
  design: taking a map reference dirties that one chunk, exactly as vanilla.
  If you need exact vanilla parity, for speedrun practice or seed tooling:
  `/gamerule locatemore:exact_locate false` flips it live, per world. The
  `improveVanillaLocate` config key is the server-wide kill switch (the
  effective value is the AND of both). The gamerule lives in level.dat like
  any gamerule and is harmless if the mod is removed; it selects which
  engine answers, so it does not breach the state rule below - no LocateMore
  state is ever an input to a search result.

- For multi-structure sets, a structure in ungenerated terrain requires
  generating its candidate chunk to the first stage. Vanilla locate does the
  same for every structure. Each probe adds 4 to 12 KB to the world save, and
  the summary line reports the count. Single-set structures never need
  probes, on any path.
- Nothing persists between sessions, so there is no state to go stale across
  restarts. Within a session, a datapack reload aborts running searches and
  clears the math memo automatically.
- Default bounds: 1,000,000 block radius, 60 second wall clock, 50,000
  candidate checks, 2 concurrent searches. Partial results say so. All of
  them, plus a switch that forbids probe chunk generation entirely, live in
  `config/locatemore.json`.
- Spread-out structures in a fresh world need many chunk generations. The
  search throttles them to protect tick speed, so it can take seconds. The
  boss bar counts while it works.

## Commands

| command | what |
|---|---|
| `/locate structure <id\|#tag> <count> [min_distance]` | async search, streams the N nearest (optionally at least that far away) |
| `/locate biome <id\|#tag> <count> [min_distance]` | async biome search, N distinct patches |
| `/locatemore near structure <a> structure\|biome <b> <radius>` | the nearest a with a b within radius, e.g. a village next to a desert |
| `/locatemore track <x> <y> <z> <name>` | live distance + arrow in the action bar, self-clearing |
| `/locatemore compass <x> <y> <z> <name>` | named compass pointing at the spot |
| `/locatemore verify <structure>` | drift tripwire: shadow parse vs vanilla over 20 chunks |
| `/locatemore prune` | delete empty region files (vanilla's scan path still leaves them, MC-311323) |
| `/gamerule locatemore:exact_locate` | per-world toggle for the vanilla call sites |

Operator permission required, same as vanilla `/locate`. With a permissions
mod (LuckPerms etc.) the `/locatemore` subcommands can be granted individually:
`locatemore.track`, `locatemore.compass`, `locatemore.prune`,
`locatemore.verify`, `locatemore.near`. Structure tags and
all dimensions work. Vanilla clients on a dedicated server see correct output,
because every line uses vanilla translation keys.

## Not on the roadmap

- **prewarm.** Considered and dropped. Cold single-set searches are 0.1 to
  0.2 s and touch no chunks, so there is nothing to warm. The only searches
  with real cost are multi-set ones in ungenerated terrain, and warming those
  means generating their chunks early: the same work, moved earlier, plus
  save growth. Run Chunky if you want a warm world.

## Versions

One jar runs on 26.1.2 and 26.2. Fabric, requires Fabric API. MIT license.
Server-side: installed on a server, vanilla clients get everything; in
singleplayer a client install works.
