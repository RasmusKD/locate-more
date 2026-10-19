package com.rasmus.locatemore;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/** The 26.3 side of VersionCompat (see the 26.1 file for the contract). */
final class VersionCompat {
    private VersionCompat() {
    }

    static StructureTemplateManager templateManager(ServerLevel level) {
        return level.getStructureTemplateManager();
    }

    /**
     * Built like vanilla's StructureCheck builds it: a caching climate sampler
     * of its own per context, so the context is single-threaded by construction.
     */
    static Structure.GenerationContext generationContext(RegistryAccess registryAccess,
            ChunkGenerator generator, BiomeSource biomeSource, RandomState randomState,
            StructureTemplateManager templateManager, long seed, ChunkPos pos,
            LevelHeightAccessor heightAccessor, HolderSet<Biome> biomes) {
        return new Structure.GenerationContext(registryAccess, generator, biomeSource,
                randomState.createClimateSampler(SamplerContext.builder().enableCaches().build()),
                randomState, templateManager, seed, pos, heightAccessor, biomes::contains);
    }

    /** The biome a generation context sees at a quart position. */
    static Holder<Biome> biomeAt(Structure.GenerationContext context, int quartX, int quartY, int quartZ) {
        return context.biomeResolver().getNoiseBiome(quartX, quartY, quartZ);
    }

    static StructureStart startForStructure(StructureManager manager, ChunkAccess chunk, Structure structure) {
        return manager.getStartForStructure(structure, chunk);
    }

    static ItemEntity dropFromPlayer(ServerPlayer player, ItemStack stack) {
        return player.drop(stack, false, Prediction.PREDICTED);
    }
}
