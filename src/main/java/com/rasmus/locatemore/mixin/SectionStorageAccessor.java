package com.rasmus.locatemore.mixin;

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import java.util.Optional;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The poi engine's whole vanilla surface: the storage handle (thread-safe
 * reads through the IOWorker, so pending writes are visible and files
 * never tear), the dirty-column set (in-memory truth not yet written), and
 * the read-only section getter (null = never loaded, empty = loaded and
 * absent) that can check residency without pinning columns the way
 * getOrLoad does.
 */
@Mixin(SectionStorage.class)
public interface SectionStorageAccessor {

    @Accessor("simpleRegionStorage")
    SimpleRegionStorage locatemore$simpleRegionStorage();

    @Accessor("dirtyChunks")
    LongLinkedOpenHashSet locatemore$dirtyChunks();

    @Invoker("get")
    Optional<Object> locatemore$get(long sectionPos);
}
