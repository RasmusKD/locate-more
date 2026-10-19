package com.rasmus.locatemore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.datafixers.util.Pair;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.OverworldBiomeBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The biome prefilter's column rejection against vanilla's own overworld
 * parameter list (the same list MultiNoiseBiomeSource searches, built by
 * OverworldBiomeBuilder without a server). Winners are brute-forced with
 * vanilla's fitness, so every assertion is checked against the nearest-point
 * rule itself.
 */
class ProvenRejectionTest {

    private static List<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> overworld;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void vanillaParameters() throws Exception {
        List<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> out = new ArrayList<>();
        Method addBiomes = OverworldBiomeBuilder.class.getDeclaredMethod("addBiomes", Consumer.class);
        addBiomes.setAccessible(true);
        addBiomes.invoke(new OverworldBiomeBuilder(),
                (Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>>) out::add);
        overworld = out;
    }

    private static ResourceKey<Biome> winner(long[] sample) {
        long best = Long.MAX_VALUE;
        ResourceKey<Biome> winner = null;
        for (var pair : overworld) {
            long fitness = ProvenRejection.fitness(pair.getFirst(), sample);
            if (fitness < best) {
                best = fitness;
                winner = pair.getSecond();
            }
        }
        return winner;
    }

    private static boolean inside(long[] flat, long value) {
        for (int i = 0; i < flat.length; i += 2) {
            if (value >= flat[i] && value <= flat[i + 1]) {
                return true;
            }
        }
        return false;
    }

    private static long[] declaredUnion(int dim, Predicate<ResourceKey<Biome>> isTarget) {
        List<long[]> raw = new ArrayList<>();
        for (var pair : overworld) {
            if (isTarget.test(pair.getSecond())) {
                Climate.Parameter p = ProvenRejection.parameter(pair.getFirst(), dim);
                raw.add(new long[]{p.min(), p.max()});
            }
        }
        return BiomeLocate.mergeIntervals(raw);
    }

    /**
     * The measured miss: golden seed, /locate biome minecraft:lush_caves from
     * 0 100 0. Vanilla's hit [352, -28, -320] samples the quart at block
     * 352 -28 -320 with these six quantized values (lmlab climate, identical on 26.1.2 and 26.3):
     * humidity 0.35, far below lush caves' declared 0.7..1, so the old
     * declared-interval prefilter skipped the column. Lush caves still wins
     * there (fitness 12250000 against river's 14554225) because every surface
     * point sits at depth 0 or 1 and the sample's depth is 0.62.
     */
    @Test
    void lushCavesHitOutsideItsDeclaredHumidityIsNotRejected() {
        long[] sample = MEASURED_HIT;
        Predicate<ResourceKey<Biome>> lush = Biomes.LUSH_CAVES::equals;
        assertEquals(Biomes.LUSH_CAVES, winner(sample), "vanilla's nearest point at the hit");
        assertFalse(inside(declaredUnion(1, lush), sample[1]), "humidity is outside the declared interval");
        for (int dim = 0; dim < 5; dim++) {
            assertFalse(inside(ProvenRejection.rejected(dim, overworld, lush), sample[dim]), "dim " + dim);
        }
    }

    /** Quantized temperature, humidity, continentalness, erosion, weirdness, depth at the hit. */
    private static final long[] MEASURED_HIT = {2524, 3500, 1278, -876, 23, 6185};

    /**
     * Soundness: wherever the rule rejects a value, brute force over vanilla's
     * parameter list never picks a target there. Random samples over the
     * declared parameter space plus a margin, depth biased toward the cave band.
     */
    @Test
    void rejectionNeverHidesAWinner() {
        Random random = new Random(20260821L);
        String[] names = {"lush_caves", "dripstone_caves", "deep_dark", "mushroom_fields", "river",
                "desert", "swamp", "cherry_grove", "ocean"};
        int checked = 0;
        for (String name : names) {
            Predicate<ResourceKey<Biome>> isTarget = name.equals("ocean")
                    ? key -> key.identifier().getPath().contains("ocean")
                    : key -> key.identifier().getPath().equals(name);
            long[][] rejected = new long[5][];
            for (int dim = 0; dim < 5; dim++) {
                rejected[dim] = ProvenRejection.rejected(dim, overworld, isTarget);
            }
            for (int s = 0; s < 1500; s++) {
                long[] sample = new long[6];
                for (int dim = 0; dim < 6; dim++) {
                    sample[dim] = (long) ((random.nextDouble() * 2.8 - 1.4) * 10000);
                }
                if (random.nextBoolean()) {
                    sample[5] = (long) ((random.nextDouble() * 1.4 - 0.2) * 10000);
                }
                boolean anyRejects = false;
                for (int dim = 0; dim < 5; dim++) {
                    anyRejects |= inside(rejected[dim], sample[dim]);
                }
                if (anyRejects) {
                    checked++;
                    ResourceKey<Biome> winner = winner(sample);
                    assertFalse(isTarget.test(winner), name + " won at a rejected sample, winner " + winner);
                }
            }
        }
        assertTrue(checked > 3000, "too few rejected samples to mean anything: " + checked);
    }

    /** Where the surface does tile, the proof keeps the cheap prune: mushroom
     * fields is rejected across continentalness above its own band. */
    @Test
    void mushroomFieldsKeepsItsContinentalnessPrune() {
        long[] rejected = ProvenRejection.rejected(2, overworld, Biomes.MUSHROOM_FIELDS::equals);
        assertTrue(inside(rejected, -10000));
        assertTrue(inside(rejected, 0));
        assertTrue(inside(rejected, 10000));
        assertFalse(inside(rejected, -11000), "inside its own band");
        assertFalse(inside(rejected, -13000), "below every interval nothing beats it");
    }

    @Test
    void coverNeedsEveryPointOfTheBox() {
        long[][] box = {{0, 9}, {0, 9}};
        List<long[][]> halves = List.of(new long[][]{{0, 4}, {0, 9}}, new long[][]{{5, 9}, {0, 9}});
        assertTrue(ProvenRejection.covered(box, halves, 0));
        List<long[][]> gap = List.of(new long[][]{{0, 4}, {0, 9}}, new long[][]{{6, 9}, {0, 9}});
        assertFalse(ProvenRejection.covered(box, gap, 0));
        List<long[][]> corner = List.of(new long[][]{{0, 9}, {0, 4}}, new long[][]{{0, 4}, {5, 9}});
        assertFalse(ProvenRejection.covered(box, corner, 0));
    }
}
