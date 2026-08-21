package com.rasmus.locatemore.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.Map;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheck;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Debug access to vanilla's in-memory caches for controlled measurements. */
@Mixin(StructureCheck.class)
public interface StructureCheckAccessor {

    @Accessor("loadedChunks")
    Long2ObjectMap<Object2IntMap<Structure>> locatemore$loadedChunks();

    @Accessor("featureChecks")
    Map<Structure, ?> locatemore$featureChecks();
}
