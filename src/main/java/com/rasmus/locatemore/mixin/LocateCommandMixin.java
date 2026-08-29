package com.rasmus.locatemore.mixin;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.rasmus.locatemore.BiomeLocate;
import com.rasmus.locatemore.LocateMore;
import com.rasmus.locatemore.LocateMoreGameRules;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.ResourceOrTagArgument;
import net.minecraft.commands.arguments.ResourceOrTagKeyArgument;
import net.minecraft.server.commands.LocateCommand;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Routes the vanilla one-result /locate structure executor through the async
 * engine (count = 1), so the command can never stall a tick: vanilla's
 * executor blocks the server thread for the whole search, and even the
 * bounded sync engine costs seconds for a worst-case query (a rare set
 * variant kilometres away on a cold JIT). Eyes of ender, trades and other
 * synchronous-by-contract call sites keep the budgeted sync path; the
 * command is ours to make asynchronous, and the count variant already was.
 *
 * <p>With the gamerule or kill switch off (or the lab bypass on), vanilla's
 * executor runs untouched.
 */
@Mixin(LocateCommand.class)
public class LocateCommandMixin {

    @Inject(method = "locateStructure", at = @At("HEAD"), cancellable = true)
    private static void locatemore$asyncLocate(CommandSourceStack source,
            ResourceOrTagKeyArgument.Result<Structure> resourceOrTag,
            CallbackInfoReturnable<Integer> cir) throws CommandSyntaxException {
        if (!LocateMoreGameRules.enabled(source.getLevel()) || LocateMore.labBypass()
                || !LocateMore.vanillaAsyncSupported(source, resourceOrTag)) {
            return;
        }
        cir.setReturnValue(LocateMore.vanillaLocateAsync(source, resourceOrTag));
    }

    /**
     * Same treatment for /locate biome: vanilla's spiral blocks the server
     * thread for the whole search and is only approximately nearest; the
     * async engine samples the same grid in exact distance order off-thread.
     * The gamerule and kill switch gate it exactly like the structure path.
     */
    @Inject(method = "locateBiome", at = @At("HEAD"), cancellable = true)
    private static void locatemore$asyncLocateBiome(CommandSourceStack source,
            ResourceOrTagArgument.Result<Biome> resourceOrTag,
            CallbackInfoReturnable<Integer> cir) {
        if (!LocateMoreGameRules.enabled(source.getLevel()) || LocateMore.labBypass()) {
            return;
        }
        cir.setReturnValue(BiomeLocate.start(source, resourceOrTag, 1));
    }

    /**
     * Same treatment for /locate poi: vanilla synchronously loads and pins
     * every poi column within its 256-block radius on the server thread;
     * the async engine reads through the poi storage's own worker instead,
     * searches all explored terrain, and its 3D ordering matches vanilla's
     * comparator whenever the answer lies within vanilla's radius.
     */
    @Inject(method = "locatePoi", at = @At("HEAD"), cancellable = true)
    private static void locatemore$asyncLocatePoi(CommandSourceStack source,
            ResourceOrTagArgument.Result<net.minecraft.world.entity.ai.village.poi.PoiType> resourceOrTag,
            CallbackInfoReturnable<Integer> cir) {
        if (!LocateMoreGameRules.enabled(source.getLevel()) || LocateMore.labBypass()) {
            return;
        }
        cir.setReturnValue(com.rasmus.locatemore.PoiLocate.start(source, resourceOrTag, 1, 0));
    }
}
