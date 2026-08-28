package com.rasmus.locatemore.mixin;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The live parameter list behind a multinoise source: the biome engine
 * derives its per-dimension prefilter intervals from it, so datapack
 * biomes are covered automatically and no cubiomes-style hardcoded
 * tables exist anywhere.
 */
@Mixin(MultiNoiseBiomeSource.class)
public interface MultiNoiseBiomeSourceInvoker {

    @Invoker("parameters")
    Climate.ParameterList<Holder<Biome>> locatemore$parameters();
}
