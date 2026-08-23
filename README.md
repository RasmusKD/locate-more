# LocateMore

Find the N nearest structures instead of just the closest. Exact distance
order. The server keeps ticking.

```
/locate structure minecraft:mansion 20
```

Vanilla `/locate` blocks the server thread and returns one result. That result
is [not always the nearest](https://bugs.mojang.com/browse/MC-138887).
LocateMore adds an optional count from 1 to 100 to the vanilla command, and
routes vanilla's own nearest-structure search through the same engine, so
plain `/locate`, eyes of ender, and other mods get the true nearest too. The
search runs off the server thread. Results stream into chat as they are
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
  answer. Multi-structure sets (villages, nether complexes) still resolve
  through real generation, because the weighted draw between set members is
  generation's call, and the summary line's `math=` counter referees every
  such load. The same trust covers the vanilla call sites: plain `/locate`,
  eyes of ender, and other mods calling vanilla's search. Only the lab modes
  keep real generation as their referee.
- **The filesystem as the negative source.** Each search lists the region
  directory once. A candidate whose region file is absent cannot be on disk
  and goes straight to the math, with no disk round trip.
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

Cold search, nothing warm, count 20 where the structure allows it:

| structure | cold | warm (memo) |
|---|---|---|
| mansion | 0.16 s | |
| ancient_city | 0.11 s | |
| monument | 0.22 s | |
| jungle_pyramid (20 nearest) | 0.98 s | 0.06 s |
| #village (tag, multi-set) | 1.4 s | |
| fortress (Nether, multi-set) | 0.19 s | |

Single-set structures are pure math: zero chunks loaded or generated, at any
count. Before shipping that shortcut, a built-in referee counted 100%
math-vs-generation agreement across the whole single-set battery, and an
async search returned positions identical to the generation-backed sync mode
from the same origin. Multi-set structures (villages, nether complexes)
verify through real generation, and the `math=` counter in the summary line
referees every such load.

Control, same world and warm cache: repeated vanilla nearest-searches took 10
seconds and found 13 of 20. The async search never blocks a tick, and every
release is diffed coordinate-for-coordinate against real generation on a
fixed seed before it ships (test/run.sh, PORTING.md).

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

Call on the server thread; the future completes on the server thread with
hits in exact distance order. When every worker slot is busy the request
queues (player searches admit first) instead of failing. Server budgets from
`locatemore.json` apply as hard ceilings; `SearchOptions` lowers them per
call, turns off chunk generation (`SearchOptions.mathOnly()`: instant and
exact where provable, partial and flagged elsewhere, never writes to the
world), and excludes previous hits so "find the next ones" is one call.
When `result.orderingGuaranteed()` is false, a nearer structure may exist in
a chunk the search was not allowed to resolve. `LocateMoreApi.API_VERSION`
marks the contract.

## Limitations

- **Plain `/locate` results deliberately differ from vanilla.** Vanilla can
  return a structure that is not the nearest (MC-138887); this mod returns
  the true nearest, for the command, for eyes of ender, and for other mods.
  If you need exact vanilla parity, for speedrun practice or seed tooling:
  `/gamerule locatemore:exact_locate false` flips it live, per world. The
  `improveVanillaLocate` config key is the server-wide kill switch (the
  effective value is the AND of both). The gamerule is the mod's one
  deliberate exception to zero persistent state: it records intent in
  level.dat, which vanilla ignores harmlessly if the mod is removed.

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
| `/locate structure <id\|#tag> <count>` | async search, streams the N nearest |
| `/locate structure <id> <count> next` | same, skipping the structure you stand in |
| `/locatemore verify <structure>` | drift tripwire: shadow parse vs vanilla over 20 chunks |
| `/locatemore prune` | delete empty region files (vanilla's scan path still leaves them, MC-311323) |
| `/gamerule locatemore:exact_locate` | per-world toggle for the vanilla call sites |

Operator permission required, same as vanilla `/locate`. Structure tags and
all dimensions work. Vanilla clients on a dedicated server see correct output,
because every line uses vanilla translation keys.

## Roadmap

- Explorer maps and cartographer trades (the skip-known path) through the
  engine; they keep the vanilla path for now because that path mutates
  structure references

## Not on the roadmap

- **prewarm.** Considered and dropped. Cold single-set searches are 0.1 to
  0.2 s and touch no chunks, so there is nothing to warm. The only searches
  with real cost are multi-set ones in ungenerated terrain, and warming those
  means generating their chunks early: the same work, moved earlier, plus
  save growth. Run Chunky if you want a warm world.

## Versions

One jar runs on 26.1.2 and 26.2. Fabric, requires Fabric API. MIT license.
