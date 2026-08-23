package com.rasmus.locatemore;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;

/**
 * Replicates generation's weighted draw between the members of a structure
 * set (ChunkGenerator.createStructures): a WorldgenRandom seeded with
 * setLargeFeatureSeed on the candidate chunk picks without replacement,
 * retrying the next member when generation rejects one. Given the same seed
 * and chunk, this returns the members in the exact order generation would
 * try them, so the first member whose generation point validates is
 * generation's winner.
 *
 * <p>Trusted since the referee earned it: 427/427 predictions confirmed by
 * real chunk loads across 7 seeds (five of them headless batteries) with
 * zero disagreements. Both engines treat the predicted winner as the
 * verdict for region-absent candidates, with two guard rails: sets larger
 * than {@link #MAX_TRUSTED_MEMBERS} keep loading (the replication was only
 * proven against vanilla-scale sets, datapacks can be arbitrarily large),
 * and any referee-observed disagreement distrusts that placement for the
 * rest of the session, falling back to loads. The verify command remains
 * the manual tripwire: it still loads every chunk and compares.
 */
final class SetDraw {

    /** Datapack guard: draws in larger sets fall back to chunk loads. */
    private static final int MAX_TRUSTED_MEMBERS = 8;

    private static final java.util.Set<net.minecraft.world.level.levelgen.structure.placement.StructurePlacement> DISTRUSTED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private SetDraw() {
    }

    /** Whether the draw verdict may replace a chunk load for this placement. */
    static boolean trusted(net.minecraft.world.level.levelgen.structure.placement.StructurePlacement placement,
            int members) {
        return members <= MAX_TRUSTED_MEMBERS && !DISTRUSTED.contains(placement);
    }

    /** Session-wide fallback to loads after a referee-observed disagreement. */
    static void distrust(net.minecraft.world.level.levelgen.structure.placement.StructurePlacement placement) {
        DISTRUSTED.add(placement);
    }

    /** Members in the exact order generation would try them. */
    static List<Holder<Structure>> order(long seed, ChunkPos pos, StructureSet set) {
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
        random.setLargeFeatureSeed(seed, pos.x(), pos.z());
        List<StructureSet.StructureSelectionEntry> remaining = new ArrayList<>(set.structures());
        int total = 0;
        for (StructureSet.StructureSelectionEntry entry : remaining) {
            total += entry.weight();
        }
        List<Holder<Structure>> out = new ArrayList<>(remaining.size());
        while (!remaining.isEmpty()) {
            int roll = random.nextInt(total);
            int index = 0;
            for (StructureSet.StructureSelectionEntry entry : remaining) {
                roll -= entry.weight();
                if (roll < 0) {
                    break;
                }
                index++;
            }
            StructureSet.StructureSelectionEntry chosen = remaining.remove(index);
            total -= chosen.weight();
            out.add(chosen.structure());
        }
        return out;
    }
}
