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
 * <p>Currently referee-only: the async engine computes the predicted winner
 * here, still loads the chunk, and counts agreement in the summary line
 * (draw=hits/loads). Trust ships in a later release, gated on that counter.
 */
final class SetDraw {

    private SetDraw() {
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
