# LocateMore

Find the N nearest structures instead of just the closest. Exact distance
order. The server keeps ticking.

```
/locate structure minecraft:mansion 20
```

Vanilla `/locate` blocks the server thread and returns one result. That result
is [not always the nearest](https://bugs.mojang.com/browse/MC-138887).
LocateMore adds an optional count from 1 to 100 to the vanilla command. The
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

Seed 20260821, fixed position, 20 of each structure. Reproducible.

| structure | first ever (cold) | after restart (index only) | warm |
|---|---|---|---|
| jungle_pyramid | 6.2 s | 1.0 s | 0.17 s |
| desert_pyramid | 10.6 s | 2.0 s | n/a |
| shipwreck | 0.2 s | | |
| fortress (Nether) | 0.33 s | | |
| mansion (earlier world) | 4.7 s | | 0.02 s |

Control, same world and warm cache: repeated vanilla nearest-searches took 10
seconds and found 16 of 20. The lab mode `vanilla` reproduces that method. The
async search never blocks a tick.

## Limitations

- A structure in ungenerated terrain requires generating its candidate chunk
  to the first stage. Vanilla locate does the same. Each probe adds 4 to 12 KB
  to the world save, and the summary line reports the count.
- The index assumes stable generation rules. If a datapack or mod changes
  where structures can generate mid-world, run `/locatemore index clear`. A
  stale entry can hide a structure under the new rules. It can never invent
  one.
- Hard bounds: 1,000,000 block radius, 60 second wall clock, 50,000 candidate
  checks, 2 concurrent searches. Partial results say so.
- Spread-out structures in a fresh world need many chunk generations. The
  search throttles them to protect tick speed, so it can take seconds. The
  boss bar counts while it works.

## Commands

| command | what |
|---|---|
| `/locate structure <id\|#tag> <count>` | async search, streams the N nearest |
| `/locate structure <id> <count> sync` | same algorithm, synchronous, for measurements |
| `/locate structure <id> <count> vanilla` | vanilla-method lab, for measurements |
| `/locatemore cache stats` | cache and index sizes |
| `/locatemore cache clear` | clear vanilla's in-memory caches |
| `/locatemore index clear` | wipe this dimension's index |

Operator permission required, same as vanilla `/locate`. Structure tags and
all dimensions work. Vanilla clients on a dedicated server see correct output,
because every line uses vanilla translation keys.

## Roadmap

- `prewarm <radius>`: index every structure set in an area up front, so later
  locates, treasure maps, and eye-of-ender throws answer instantly
- The same engine behind vanilla's own call sites: explorer maps, eyes of
  ender, cartographer trades
- `next` to skip the structure you stand in, a config file, an API event for
  other mods

## Versions

One jar runs on 26.1.2 and 26.2. Fabric, requires Fabric API. MIT license.
