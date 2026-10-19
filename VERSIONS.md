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

Open, identical on 26.1.2 and 26.3 (not a port regression): `locate biome minecraft:lush_caves` at 0 100 0 answers
[0, 36, -576] where vanilla finds [352, -28, -320], 84 blocks nearer. The heuristic parameter-interval prefilter (not the
certified tier) skips the column, and its referee then revokes it for the session ("Interval referee disagreement").

Full 26.3 table: `C:\Users\Rasmus\Desktop\Ny mappe (3)\mc-26.3-splits.md`.
