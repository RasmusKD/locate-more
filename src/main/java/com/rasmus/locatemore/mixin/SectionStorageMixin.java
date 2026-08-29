package com.rasmus.locatemore.mixin;

import com.rasmus.locatemore.PoiLocate;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The clean-column cache's invalidation hook: a column can be dirtied and
 * flushed between two searches, in which case no dirty snapshot ever sees
 * it, so the cache must be dropped at the moment of mutation itself.
 * setDirty is that moment for every poi mutation. Storages the poi engine
 * has never cached miss the instance map and cost one lookup.
 */
@Mixin(SectionStorage.class)
public class SectionStorageMixin {

    @Inject(method = "setDirty", at = @At("HEAD"))
    private void locatemore$invalidateCleanCache(long sectionPos, CallbackInfo ci) {
        PoiLocate.invalidate(this, sectionPos);
    }
}
