# Version splits

One source tree, two jar variants. The 26.1/26.2 jar builds and declines 26.3 in `fabric.mod.json`. The 26.3 variant is
scaffolded but BLOCKED: it does not compile (see below). Add new finds here.

## Build layout

`./gradlew build` produces `locatemore-X.Y.Z+26.1.jar` (26.1.x and 26.2, `minecraft >=26.1 <26.3`).
`./gradlew build -PmcTarget=26.3` would produce `+26.3` (pins in `gradle-26.3.properties`); files under
`src/mc26_3/java` and `src/mc26_3/resources` replace the same-named file of `src/main`, `src/mc26_3/remove.txt` lists main
files the 26.3 jar drops. `verifyMixinClasses` (part of `check`, next to `verifyJar`) fails when a mixins.json entry has
no class in that variant. Static proof per jar:
`python C:\Users\Rasmus\.claude\skills\minecraft-mods\scripts\linkcheck.py <jar> <mapped minecraft jar>`
(the 26.1 jar is clean on 26.1.2 and 26.2). Run `clean` between the two variants.

## 26.3 is blocked: worldgen sampling was rewritten

26.3 replaced the density-function and climate-sampler stack that `BiomeLocate`, `NearSearch` and the structure engine
replicate. `./gradlew compileJava -PmcTarget=26.3` fails on:

| What | 26.1/26.2 | 26.3 | Used by |
|---|---|---|---|
| `DensityFunction` | `net.minecraft.world.level.levelgen.DensityFunction`, `compute(FunctionContext)` returns double, `SinglePointContext(x,y,z)`, `minValue/maxValue` | package `levelgen.densityfunction`, compiled/float based (`DensitySampler`, `SamplerContext`, `DensityFunctionCompiler`) | `BiomeLocate` (flat-function bounds, climate point sampling) |
| `Climate.Sampler` | record of six `DensityFunction` | record of six `DensitySampler.Bound`, `sample(x,y,z)` | `BiomeLocate`, `NearSearch` |
| `RandomState.sampler()` | exists | gone; `createClimateSampler(SamplerContext)` | `BiomeLocate`, `NearSearch`, `AsyncLocate` |
| `BiomeSource.getNoiseBiome(x,y,z,Climate.Sampler)` | exists | gone | `BiomeLocate`, `AsyncLocate` |
| `Structure.GenerationContext` constructor | `(RegistryAccess, ChunkGenerator, BiomeSource, RandomState, StructureTemplateManager, long, ChunkPos, LevelHeightAccessor, Predicate)` | takes a `Climate.Sampler` (and optionally `BiomeResolver`, `WorldgenRandom`) | `AsyncLocate` (structure start replication) |
| `ServerLevel.getStructureManager()` | exists | gone | `AsyncLocate` |
| `StructureManager.getStartForStructure(SectionPos, Structure, ChunkAccess)` | exists | `(Structure, StructureAccess)` | `LocateMore` |
| `ServerPlayer.drop(ItemStack, boolean)` | exists | `drop(ItemStack, boolean, Prediction)` | `HitPresentation` |

These are replications of vanilla worldgen: a wrong port gives wrong coordinates, not a crash. A 26.3 jar needs a rewrite of
the biome search against the new sampler and a green `test/run.sh` differential (golden `test/golden/26.3.txt`, blessed only
after two coordinates match an unmodified vanilla server on the same seed). That needs a server run, which the static-only
rule of the 26.3 port did not allow. Not guessed.

Full 26.3 table: `C:\Users\Rasmus\Desktop\Ny mappe (3)\mc-26.3-splits.md`.
