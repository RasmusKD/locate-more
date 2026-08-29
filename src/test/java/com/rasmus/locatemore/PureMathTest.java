package com.rasmus.locatemore;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * The pure-math slice: formulas the battery exercises only indirectly.
 * No server, no registry bootstrap; every subject is plain arithmetic
 * over plain inputs.
 */
class PureMathTest {

    @Test
    void octantCoversTheCompass() {
        assertEquals("N", HitPresentation.octant(0, -16));
        assertEquals("NE", HitPresentation.octant(16, -16));
        assertEquals("E", HitPresentation.octant(16, 0));
        assertEquals("SE", HitPresentation.octant(16, 16));
        assertEquals("S", HitPresentation.octant(0, 16));
        assertEquals("SW", HitPresentation.octant(-16, 16));
        assertEquals("W", HitPresentation.octant(-16, 0));
        assertEquals("NW", HitPresentation.octant(-16, -16));
    }

    @Test
    void octantSectorBoundariesRoundToTheNearerName() {
        // 22.5 degrees rounds up into NE; just below stays N.
        assertEquals("NE", HitPresentation.octant(100, -241));
        assertEquals("N", HitPresentation.octant(100, -242));
    }

    @Test
    void horizDistSqrIgnoresY() {
        assertEquals(25L, LocateMore.horizDistSqr(new BlockPos(3, 77, 4), new BlockPos(0, -30, 0)));
        assertEquals(0L, LocateMore.horizDistSqr(new BlockPos(-5, 0, 9), new BlockPos(-5, 200, 9)));
    }

    @Test
    void trackNameStripsNamespaceAndNumbers() {
        assertEquals("mansion #3", HitPresentation.trackName("minecraft:mansion", 3));
        assertEquals("village #1", HitPresentation.trackName("#minecraft:village", 1));
        assertEquals("plain #2", HitPresentation.trackName("plain", 2));
    }

    @Test
    void mergeIntervalsMergesOverlapAndTouch() {
        List<long[]> raw = new ArrayList<>();
        raw.add(new long[]{3, 9});
        raw.add(new long[]{0, 5});
        raw.add(new long[]{9, 12});
        raw.add(new long[]{20, 25});
        long[] merged = BiomeLocate.mergeIntervals(raw);
        assertArrayEquals(new long[]{0, 12, 20, 25}, merged);
    }

    @Test
    void mergeIntervalsKeepsDisjointOrder() {
        List<long[]> raw = new ArrayList<>();
        raw.add(new long[]{50, 60});
        raw.add(new long[]{-10, -5});
        assertArrayEquals(new long[]{-10, -5, 50, 60}, BiomeLocate.mergeIntervals(raw));
    }

    /**
     * The biome engine's min_distance ring skip rests on this bound: ring
     * r's farthest column sits at exactly r*32*sqrt(2), so a ring is
     * skippable when 2*(r*32)^2 < minDistSqr. Verified against a direct
     * enumeration of the ring's corner offsets.
     */
    @Test
    void ringSkipBoundMatchesCornerGeometry() {
        for (int ring = 1; ring <= 8; ring++) {
            long corner = 2L * (ring * 32L) * (ring * 32L);
            long worst = 0;
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    long ox = dx * 32L;
                    long oz = dz * 32L;
                    worst = Math.max(worst, ox * ox + oz * oz);
                }
            }
            assertEquals(corner, worst, "ring " + ring);
        }
    }
}
