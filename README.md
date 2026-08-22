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

Three mechanisms carry the speed:

- **Own verification path.** Vanilla keeps its structure cache on the server
  thread, so the worker never touches it. The worker scans chunk NBT itself,
  runs the biome math on a small thread pool, and sends the few candidates
  that need chunk generation to the server thread in a budgeted queue. An
  ordering barrier keeps the streamed results nearest-first.
- **A persistent index of negative results.** Each dimension saves a small
  file that records which candidate chunks are absent from disk. The file
  survives restarts. The index is strictly negative: it skips work and never
  produces an answer. A stale entry costs one redundant lookup. Positive
  findings are always re-verified against the world.
- **A session memo for the biome math.** The memo is not saved on purpose,
  because datapacks can change generation without changing the seed.

## Measured

Release build, virgin world, seed 20260821, fixed position, 20 of each
structure. Reproducible.

| structure | first ever (cold) | after restart (index only) | warm |
|---|---|---|---|
| jungle_pyramid | 6.5 s | 0.92 s | 0.18 s |
| desert_pyramid | 11.7 s | 1.9 s | n/a |
| #village (tag) | 5.2 s | | |
| shipwreck | 0.34 s | | |
| fortress (Nether) | 0.39 s | | |
| stronghold | 0.35 s | | |

The restart column is the persistent index alone: the session memo is empty
after a restart, and the counters in the summary line prove it (memoHits=0).
Control, same world and warm cache: repeated vanilla nearest-searches took 10
seconds and found 13 of 20. The lab mode `vanilla` reproduces that method. The
async search never blocks a tick.

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
hits in exact distance order. Server budgets from `locatemore.json` apply.
When `result.orderingGuaranteed()` is false, a nearer structure may exist in
a chunk the search was not allowed to resolve. `/locatemore apitest
<structure> <count>` runs the same call from in game.

## Limitations

- **Plain `/locate` results deliberately differ from vanilla.** Vanilla can
  return a structure that is not the nearest (MC-138887); with
  `improveVanillaLocate` on (the default) this mod returns the true nearest,
  for the command, for eyes of ender, and for other mods. If you need exact
  vanilla parity, for speedrun practice or seed tooling, set the key to
  false and every result matches vanilla again.

- A structure in ungenerated terrain requires generating its candidate chunk
  to the first stage. Vanilla locate does the same. Each probe adds 4 to 12 KB
  to the world save, and the summary line reports the count.
- The index assumes stable generation rules. If a datapack or mod changes
  where structures can generate mid-world, run `/locatemore index clear`. A
  stale entry can hide a structure under the new rules. It can never invent
  one.
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
| `/locate structure <id> <count> sync` | same algorithm, synchronous, for measurements (config-gated) |
| `/locate structure <id> <count> vanilla` | vanilla-method lab, for measurements (config-gated) |
| `/locatemore cache stats` | cache and index sizes |
| `/locatemore cache clear` | clear vanilla's in-memory caches |
| `/locatemore index clear` | wipe this dimension's index and the session memo |
| `/locatemore verify <structure>` | drift tripwire: shadow parse vs vanilla over 20 chunks |
| `/locatemore prune` | delete empty region files left by pre-1.2.1 scans |
| `/locatemore apitest <structure> <count>` | run the public API end to end |

Operator permission required, same as vanilla `/locate`. Structure tags and
all dimensions work. Vanilla clients on a dedicated server see correct output,
because every line uses vanilla translation keys.

## Roadmap

- `prewarm <radius>`: index every structure set in an area up front, so later
  locates, treasure maps, and eye-of-ender throws answer instantly
- Explorer maps and cartographer trades (the skip-known path) through the
  engine; they keep the vanilla path for now because that path mutates
  structure references

## Versions

One jar runs on 26.1.2 and 26.2. Fabric, requires Fabric API. MIT license.
