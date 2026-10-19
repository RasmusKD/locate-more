package com.rasmus.locatemore;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * The one place the biome engine touches the climate sampler stack. Minecraft
 * 26.3 rewrote it (compiled float samplers instead of double density
 * functions), so the 26.3 jar replaces this file; everything else in the
 * engine talks to this narrow surface. Dimensions are 0 temperature,
 * 1 humidity, 2 continentalness, 3 erosion, 4 weirdness (the five 2D ones),
 * 5 depth. Safe to share across threads, like the sampler stack it wraps.
 */
final class ClimateAccess {
    private final Climate.Sampler sampler;

    private ClimateAccess(Climate.Sampler sampler) {
        this.sampler = sampler;
    }

    static ClimateAccess of(ServerLevel level) {
        return new ClimateAccess(level.getChunkSource().randomState().sampler());
    }

    /** The same lookup vanilla's chunk generation does for one quart position. */
    Holder<Biome> biome(BiomeSource source, int quartX, int quartY, int quartZ) {
        return source.getNoiseBiome(quartX, quartY, quartZ, sampler);
    }

    /** One of the five 2D climate values at a block position, as a float like the target point. */
    float flat(int dim, int blockX, int blockY, int blockZ) {
        return (float) function(dim).compute(new DensityFunction.SinglePointContext(blockX, blockY, blockZ));
    }

    float depth(int blockX, int blockY, int blockZ) {
        return flat(5, blockX, blockY, blockZ);
    }

    /** Provable lower bound of the dimension's values. */
    double min(int dim) {
        return function(dim).minValue();
    }

    /** Provable upper bound of the dimension's values. */
    double max(int dim) {
        return function(dim).maxValue();
    }

    private DensityFunction function(int dim) {
        return switch (dim) {
            case 0 -> sampler.temperature();
            case 1 -> sampler.humidity();
            case 2 -> sampler.continentalness();
            case 3 -> sampler.erosion();
            case 4 -> sampler.weirdness();
            default -> sampler.depth();
        };
    }
}
