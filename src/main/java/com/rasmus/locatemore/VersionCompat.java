package com.rasmus.locatemore;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * Vanilla members that moved in Minecraft 26.3, behind one signature each.
 * The 26.3 jar replaces this file (see VERSIONS.md); the engines call these
 * instead of the moved members so their own code is shared by both jars.
 */
final class VersionCompat {
    private VersionCompat() {
    }

    static StructureTemplateManager templateManager(ServerLevel level) {
        return level.getStructureManager();
    }

    /** The context vanilla's own start check builds for one candidate chunk. */
    static Structure.GenerationContext generationContext(RegistryAccess registryAccess,
            ChunkGenerator generator, BiomeSource biomeSource, RandomState randomState,
            StructureTemplateManager templateManager, long seed, ChunkPos pos,
            LevelHeightAccessor heightAccessor, HolderSet<Biome> biomes) {
        return new Structure.GenerationContext(registryAccess, generator, biomeSource, randomState,
                templateManager, seed, pos, heightAccessor, biomes::contains);
    }

    /** The biome a generation context sees at a quart position. */
    static Holder<Biome> biomeAt(Structure.GenerationContext context, int quartX, int quartY, int quartZ) {
        return context.biomeSource().getNoiseBiome(quartX, quartY, quartZ, context.randomState().sampler());
    }

    static StructureStart startForStructure(StructureManager manager, ChunkAccess chunk, Structure structure) {
        return manager.getStartForStructure(SectionPos.bottomOf(chunk), structure, chunk);
    }

    static ItemEntity dropFromPlayer(ServerPlayer player, ItemStack stack) {
        return player.drop(stack, false);
    }
}
