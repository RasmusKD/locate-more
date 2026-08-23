# Porting checklist

The mod replicates two vanilla behaviors and leans on two vanilla-API
contracts. The replications are the port risk: their bytes are ours and they
fail silently (wrong coordinates, not crashes). The contracts are cheaper:
vanilla's own methods are called directly, so they only break if Mojang
moves the decision elsewhere, which the constant-pool audit sees.

## 1. The replicated and contracted behaviors

| ours | vanilla source of truth | kind | breaks as |
|---|---|---|---|
| `SetDraw.order` | the weighted without-replacement draw in `ChunkGenerator.createStructures` (`WorldgenRandom` + `setLargeFeatureSeed`, weight-subtraction roll, remove and retry on rejection); the multi-set trust rests on this | replication | wrong structure type or false absences in shared placements |
| `ShadowScan.parse` | `StructureCheck.tryLoadFromStorage` (NBT shape, datafix context) | replication | wrong absences (status-gated: an unrecognized format on a decided chunk fails to a load, not into the math) |
| `structureCanStart` / `mathCanStart` | `Structure.generate` calling the same `findValidGenerationPoint` (all math trust rests on this) | contract | wrong positions, only if the decision moves |
| `StructurePlacement.isStructureChunk` calls in both engines | `ChunkGenerator.createStructures` calling the same composed filter | contract | wrong candidates, only if the entry point changes |

## 2. Mapping-fragile surfaces

- `MinecraftServerAccessor` binds the private field name `storageSource`.
  A rename crashes at startup (`defaultRequire: 1`).
- `ChunkGeneratorMixin` targets `findNearestMapStructure`; a signature change
  crashes at startup. Both crashes are the intended failure mode: loud.

## 3. The differential run

```
test/run.sh
```

Boots the dev server on seed 20260821, runs the battery in `test/commands.txt`
(async engine, generation-backed sync engine via the local locatemore-lab mod,
and the parse tripwire), and diffs the coordinates against
`test/golden/<mc-version>.txt`. Empty diff = ship.

## 4. When the diff is not empty

Check two of the moved coordinates by hand against an UNMODIFIED vanilla
server on the same seed (`/locate structure ...` at 0,100,0):

- Vanilla moved too: worldgen changed this MC version. Re-bless with
  `test/run.sh --bless` and record the MC version and what moved in the
  commit message. The golden directory's history is the mod's record of
  vanilla worldgen changes.
- Vanilla did not move: you broke a replication. Do not bless. Start at the
  table in section 1.

## 5. Runtime tripwires (already shipped)

- `math=hits/loads` and `draw=hits/loads` referees in the summary line audit
  every remaining multi-set load (distrusted or oversized sets); a miss logs a
  WARN with seed, dimension and chunk, and a draw miss additionally distrusts
  that placement for the session, falling back to chunk loads.
- `/locatemore verify <structure>` compares the replicated NBT parse against
  vanilla's independent path over the 20 nearest.

These catch drift after release; the differential run catches it before.
