# Version splits

One source tree, two jar variants. The 26.1/26.2 jar declines 26.3 in `fabric.mod.json`; the 26.3 jar (`minecraft >=26.3`) is ported and proven by the
differential and the vanilla oracle (see below). Add new finds here.

## Build layout

`./gradlew build` produces `locatemore-X.Y.Z+26.1.jar` (26.1.x and 26.2, `minecraft >=26.1 <26.3`).
`./gradlew build -PmcTarget=26.3` produces `+26.3` (pins in `gradle-26.3.properties`); files under
`src/mc26_3/java` and `src/mc26_3/resources` replace the same-named file of `src/main`, `src/mc26_3/remove.txt` lists main
files the 26.3 jar drops. `verifyMixinClasses` (part of `check`, next to `verifyJar`) fails when a mixins.json entry has
no class in that variant. Static proof per jar:
`python C:\Users\Rasmus\.claude\skills\minecraft-mods\scripts\linkcheck.py <jar> <mapped minecraft jar>`
(each jar against its own mapped jar; the only ERROR lines, on every version including 26.1.2, are the two Fabric API
attachment methods on `ServerPlayer` that Fabric injects at runtime, which a static check cannot see). Run `clean`
between the two variants.

## 26.3: worldgen sampling was rewritten (ported)

26.3 replaced the density-function and climate-sampler stack that `BiomeLocate`, `NearSearch` and the structure engine
replicate. The version-specific surface now sits behind two package-private classes with a 26.1 implementation in
`src/main` and a 26.3 one in `src/mc26_3/java`: `ClimateAccess` (climate sampling, biome lookup, value bounds) and
`VersionCompat` (template manager, `GenerationContext`, biome at quart, structure start lookup, player drop).

| What | 26.1/26.2 | 26.3 | Handled in |
|---|---|---|---|
| `DensityFunction` | `levelgen.DensityFunction`, `compute(FunctionContext)` double, `minValue/maxValue` | package `levelgen.densityfunction`, compiled float stack (`DensitySampler.Bound.sampleValue`), `range()` returns an `Interval` | `ClimateAccess` |
| `Climate.Sampler` | record of six `DensityFunction` | record of six `DensitySampler.Bound` | `ClimateAccess` |
| `RandomState.sampler()` | exists | gone; `createClimateSampler(SamplerContext)` (`EMPTY_UNCACHED` is stateless and shared across the math pool; `builder().enableCaches()` is not thread-safe) | `ClimateAccess`, `VersionCompat` |
| `BiomeSource.getNoiseBiome(x,y,z,sampler)` | exists | gone; `createResolver(sampler)` returns a `BiomeResolver` | `ClimateAccess`, `VersionCompat` |
| `Structure.GenerationContext` constructor | `(..., StructureTemplateManager, long, ChunkPos, LevelHeightAccessor, Predicate)` | takes a `Climate.Sampler` and `BiomeResolver` | `VersionCompat.generationContext` |
| `ServerLevel.getStructureManager()` | exists (a template manager) | `getStructureTemplateManager()` | `VersionCompat.templateManager` |
| `StructureManager.getStartForStructure(SectionPos, Structure, ChunkAccess)` | exists | `(Structure, StructureAccess)` | `VersionCompat.startForStructure` |
| `ServerPlayer.drop(ItemStack, boolean)` | exists | `drop(ItemStack, boolean, Prediction)` | `VersionCompat.dropFromPlayer` |

Finds while porting:

- The router's declared depth range is infinite on 26.3, because `blend_offset` declares an infinite range (it covers real
  chunk generation next to old chunks). The biome engine's certified shortcuts need finite sound bounds, so the 26.3
  `ClimateAccess` takes the bound over the router function with `blend_alpha` = 1 and `blend_offset` = 0, which is what the
  blender-less climate sampler computes. `lmlab ranges` samples 52k points per dimension and must report `outside=0`.
- Vanilla's `StructureTemplate$Palette.cache` is an unsynchronized `HashMap`. Concurrent shadow verification sometimes throws a
  `ConcurrentModificationException`; the shadow check logs "Shadow verification failed" and the answer is unaffected.
- The dedicated server logs chat as `System chat: ...`; `test/run.sh` strips the prefix before diffing.
- 26.3 generates a different village layout on the golden seed than 26.1 (the village at [-480, 656] is gone, [272, 1168] is
  new); vanilla's own `/locate` agrees with the mod on every one of those, so `test/golden/26.3.txt` differs from 26.1.2 only there.
- Unflushed chunks made `locatemore verify` report "0 agree"; `test/commands.txt` now runs `save-all flush` before it.
- Vanilla's own biome `/locate` on the server thread can run past the 60 s watchdog on 26.1 under low priority;
  `MAX_TICK_TIME=-1 test/run.sh` disables it for oracle runs (26.3's compiled sampler is much faster).

Proof: `test/run.sh` golden (`MC_TARGET=26.3`) and `test/oracle.py`, which compares the mod's answers with vanilla's own
`/locate` (every mixin stepped aside by `lmlab bypass on`) at 8 fixed origins over 16 overworld structures, 3 nether, 1 end,
17 overworld biomes, 5 nether, 2 end and 4 pois, and re-asks vanilla from every answer that was nearer than vanilla's.
See `PORTING.md`.

Fixed on both lines (it was never a port regression): `locate biome minecraft:lush_caves` at 0 100 0 answered
[0, 36, -576] where vanilla finds [352, -28, -320]. The biome prefilter's heuristic tier rejected every column whose value of
one flat climate dimension lay outside the target's declared intervals. Multinoise is nearest-point, so that is not a bound:
at vanilla's hit (`lmlab climate 352 -28 -320 minecraft:lush_caves`, identical on 26.1.2 and 26.3) humidity is 0.35, far
below lush caves' declared 0.7..1, and lush caves still wins (fitness 0.1225 against river's 0.1455) because the depth is
0.62 and every surface point sits at depth 0 or 1. The miss was silent: the 1-in-64 referee saw no lush column, and it
revoked the tier only later, when dripstone caves (same class) hit a referee column, so the answer depended on what the
session had searched before (the same command answered [192, -28, -384] after the revocation, and dripstone caves from
0 100 0 answered [-288, 36, -576] or [-224, 36, -544] depending on thread timing). The tier is gone. Columns are now skipped
only by proofs: the box-cover proof in `ProvenRejection` (non-target points containing the value cover every target box in
the other five dimensions, so a non-target strictly wins at every y) plus the existing margin proof; `ProvenRejectionTest`
checks the rule against vanilla's own parameter list by brute force, and fails on the old rule. Lush, dripstone and deep
dark get no flat prune (none is provable); mushroom fields, river and the surface biomes keep theirs. The proofs are built on
the biome worker (cached per parameter list and target, about 0.2 s cold on a first search, never on the server thread).
Measured (biome-only battery, 8 origins, 17 overworld, 5 nether, 2 end biomes): 26.1.2 62.1 s before, 6.9 s after; 26.3
17.3 s before, 5.9 s after (before, the session had already revoked the heuristic tier at the first origin, so most old
searches ran unfiltered). Oracle on 26.3 after the fix: 317 pairs, 0 violations, 121 of 121 nearer hits confirmed by
vanilla, lush caves from 0 100 0 now [192, -28, -384] (429 blocks, nearer than vanilla's 492).

Version refusal and client proof (1.21.0): the `+26.1` jar declares `minecraft >=26.1 <26.3` and the `+26.3` jar `>=26.3`
(`mc_range` in `build.gradle`). A 26.3 dedicated server (Fabric launcher, `nogui`, Fabric API 0.163.0+26.3) with the `+26.1` jar stops
at mod resolution with "Mod 'LocateMore' (locatemore) 1.21.0+26.1 requires any version between 26.1 (inclusive) and 26.3 (exclusive)
of 'Minecraft'", instead of the `NoClassDefFoundError` on `DensityFunction$FunctionContext` that the 1.20.7 jar (metadata `>=26.1`)
gave. The client gametest (`src/gametest`, run through the WSL2 + Xvfb wrapper) passes on 26.1.2, 26.2 and 26.3 with the same source:
village x3, pillager outpost, desert, lush caves and `poi #minecraft:village` x3 all answer in chat.

Full 26.3 table: `C:\Users\Rasmus\Desktop\Ny mappe (3)\mc-26.3-splits.md`.
