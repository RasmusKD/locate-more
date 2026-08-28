package com.rasmus.locatemore;

import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;

/**
 * The one home for the region-absent trust decision, shared verbatim by the
 * sync engine (LocateMore.smartLocate) and the async shadow path
 * (AsyncLocate.Task.decide). It used to live in both, in different shapes,
 * which meant the most safety-critical logic in the mod had to be changed
 * twice, correctly, in two idioms.
 *
 * <p>INVARIANT: this shortcut may only ever be PERMISSIVE. For a candidate
 * whose region file is absent, generation would run exactly this math
 * (findValidGenerationPoint per member, in draw order), so the first member
 * that validates is the chunk's winner and the verdict is the answer. For
 * one-member sets this degenerates to the single-set shortcut (100% referee
 * agreement across the battery before it shipped); for multi-member sets
 * the draw replication carries it (427/427 referee-confirmed across 7
 * seeds). Distrusted or oversized sets return LOAD_REQUIRED with the
 * predicted winner, and the chunk load is the authority - the prediction
 * only feeds the standing referee.
 */
final class TrustVerdict {

    enum Kind {
        HIT, ABSENT, LOAD_REQUIRED
    }

    /** canStart must be the memoized generation-point math for the search's
     * dimension; it is the only generation input this decision consumes. */
    interface CanStart {
        boolean test(Structure structure, ChunkPos pos);
    }

    record Verdict(Kind kind, LocateMore.VerifyResult result, Holder<Structure> predictedWinner) {
    }

    private static final Verdict ABSENT = new Verdict(Kind.ABSENT, null, null);

    private TrustVerdict() {
    }

    /**
     * Vanilla's per-structure decision order for a candidate whose region
     * file is absent: the first requested holder whose generation point
     * validates selects the branch. No requested holder validating is
     * math-ABSENT (generation would place nothing requested here).
     */
    static Verdict absentRegion(long seed, LocateMore.Candidate candidate,
            Holder<StructureSet> setHolder, CanStart canStart, LocateMore.Stats stats) {
        StructureSet set = setHolder == null ? null : setHolder.value();
        ChunkPos pos = candidate.pos();
        for (Holder<Structure> holder : candidate.holders()) {
            if (!canStart.test(holder.value(), pos)) {
                continue;
            }
            if (set != null && set.structures().size() == 1) {
                stats.mathSkips++;
                return new Verdict(Kind.HIT, new LocateMore.VerifyResult(
                        candidate.placement().getLocatePos(pos), holder, pos), null);
            }
            if (set != null && SetDraw.trusted(setHolder.unwrapKey().orElse(null),
                    set.structures().size())) {
                Holder<Structure> winner = SetDraw.winner(seed, pos, set,
                        member -> canStart.test(member.value(), pos));
                if (winner != null) {
                    for (Holder<Structure> requested : candidate.holders()) {
                        if (requested.value() == winner.value()) {
                            stats.drawSkips++;
                            return new Verdict(Kind.HIT, new LocateMore.VerifyResult(
                                    candidate.placement().getLocatePos(pos), requested, pos), null);
                        }
                    }
                    stats.drawSkips++;
                }
                return ABSENT;
            }
            // Distrusted or oversized set: referee mode. The prediction is
            // judged by the load (draw=hits/loads in the summary).
            Holder<Structure> predicted = set == null ? null : SetDraw.winner(seed, pos, set,
                    member -> canStart.test(member.value(), pos));
            return new Verdict(Kind.LOAD_REQUIRED, null, predicted);
        }
        return ABSENT;
    }
}
