package com.rasmus.locatemore.mixin;

import com.mojang.datafixers.util.Pair;
import com.rasmus.locatemore.Config;
import com.rasmus.locatemore.LocateMore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Routes vanilla's nearest-structure search through the exact-order engine, so
 * /locate without a count, eyes of ender, and every mod calling this method
 * get the true nearest result (MC-138887) instead of the first raster hit.
 *
 * Scope: only createReference=false calls. The skip-known path (explorer
 * maps) carries reference-mutation semantics the engine deliberately does not
 * reimplement, and stays vanilla. If the engine gives up on a budget, vanilla
 * runs unchanged, so behavior can only improve, never regress.
 */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin {

    @Inject(method = "findNearestMapStructure", at = @At("HEAD"), cancellable = true)
    private void locatemore$exactNearest(ServerLevel level, HolderSet<Structure> holders, BlockPos pos,
            int radius, boolean skipExistingChunks,
            CallbackInfoReturnable<Pair<BlockPos, Holder<Structure>>> cir) {
        if (!Config.improveVanillaLocate || skipExistingChunks || LocateMore.VANILLA_BYPASS.get()) {
            return;
        }
        boolean[] gaveUp = new boolean[1];
        Pair<BlockPos, Holder<Structure>> hit = LocateMore.findNearestExact(level, holders, pos, radius, gaveUp);
        if (!gaveUp[0]) {
            cir.setReturnValue(hit);
        }
    }
}
