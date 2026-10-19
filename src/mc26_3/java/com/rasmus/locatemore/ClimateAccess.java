package com.rasmus.locatemore;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseRouterData;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.generator.SimpleDensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.util.Interval;

/**
 * The 26.3 side of the climate surface (see the 26.1 file for the contract).
 * The sampler is the compiled float stack with caches disabled, so one
 * instance has no mutable state and is shared across the math pool. The
 * bounds come from the router's density function ranges, selected exactly
 * the way vanilla's ChunkMap selects the router for the level's RandomState.
 */
final class ClimateAccess {
    private final Climate.Sampler sampler;
    private final NoiseRouter router;
    private volatile Resolved resolved;
    private final Interval[] ranges = new Interval[6];

    private record Resolved(BiomeSource source, BiomeResolver resolver) {
    }

    private ClimateAccess(Climate.Sampler sampler, NoiseRouter router) {
        this.sampler = sampler;
        this.router = router;
    }

    static ClimateAccess of(ServerLevel level) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        NoiseRouter router = generator instanceof NoiseBasedChunkGenerator noise
                ? noise.generatorSettings().value().noiseRouter()
                : NoiseRouterData.none();
        Climate.Sampler sampler = level.getChunkSource().randomState()
                .createClimateSampler(SamplerContext.EMPTY_UNCACHED);
        return new ClimateAccess(sampler, router);
    }

    /** The same lookup vanilla's chunk generation does for one quart position. */
    Holder<Biome> biome(BiomeSource source, int quartX, int quartY, int quartZ) {
        Resolved current = resolved;
        if (current == null || current.source != source) {
            current = new Resolved(source, source.createResolver(sampler));
            resolved = current;
        }
        return current.resolver.getNoiseBiome(quartX, quartY, quartZ);
    }

    /** One of the five 2D climate values at a block position, as a float like the target point. */
    float flat(int dim, int blockX, int blockY, int blockZ) {
        return bound(dim).sampleValue(blockX, blockY, blockZ);
    }

    float depth(int blockX, int blockY, int blockZ) {
        return flat(5, blockX, blockY, blockZ);
    }

    /** Provable lower bound of the dimension's values. */
    double min(int dim) {
        return range(dim).min();
    }

    /** Provable upper bound of the dimension's values. */
    double max(int dim) {
        return range(dim).max();
    }

    private net.minecraft.world.level.levelgen.densityfunction.DensitySampler.Bound bound(int dim) {
        return switch (dim) {
            case 0 -> sampler.temperature();
            case 1 -> sampler.humidity();
            case 2 -> sampler.continentalness();
            case 3 -> sampler.erosion();
            case 4 -> sampler.weirdness();
            default -> sampler.depth();
        };
    }

    private Interval range(int dim) {
        Interval cached = ranges[dim];
        if (cached == null) {
            DensityFunction function = switch (dim) {
                case 0 -> router.temperature();
                case 1 -> router.vegetation();
                case 2 -> router.continents();
                case 3 -> router.erosion();
                case 4 -> router.ridges();
                default -> router.depth();
            };
            cached = function.rewriteChildren(NO_BLENDER).range();
            ranges[dim] = cached;
        }
        return cached;
    }

    /**
     * The climate sampler has no blender, so blend_alpha is 1 and blend_offset 0
     * there. The declared range of blend_offset is infinite (it covers real
     * chunk generation next to old chunks), which would make the depth bound
     * infinite and every certified shortcut built on it unsound, so the bound
     * is taken over the function with those two inputs at their sampler values.
     */
    private static final DfRewriteRule NO_BLENDER = new DfRewriteRule() {
        @Override
        public DensityFunction rewrite(DensityFunction function) {
            if (function == SimpleDensityFunction.BLEND_ALPHA) {
                return DensityFunctions.constant(1.0F);
            }
            if (function == SimpleDensityFunction.BLEND_OFFSET) {
                return DensityFunctions.constant(0.0F);
            }
            if (function instanceof DensityFunctions.HolderHolder holder) {
                return rewrite(holder.function().value());
            }
            return function.rewriteChildren(this);
        }
    };
}
