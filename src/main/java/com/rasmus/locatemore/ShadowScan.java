package com.rasmus.locatemore;

import com.mojang.datafixers.DataFixer;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one copy of the replicated StructureCheck.tryLoadFromStorage parse.
 * Both the async shadow path and the /locatemore verify tripwire go through
 * here, so a vanilla NBT-layout change breaks exactly one place and the
 * tripwire is comparing this code against vanilla's independent path.
 */
public final class ShadowScan {

    private static final Logger LOGGER = LoggerFactory.getLogger("locatemore-shadow");

    /** Sentinel: the scan or datafix failed; vanilla would answer CHUNK_LOAD_NEEDED. */
    public static final Object2IntMap<Structure> SCAN_FAILED =
            Object2IntMaps.unmodifiable(new Object2IntOpenHashMap<>());

    private ShadowScan() {
    }

    public static CollectFields newCollector() {
        return new CollectFields(
                new FieldSelector(IntTag.TYPE, "DataVersion"),
                new FieldSelector(net.minecraft.nbt.StringTag.TYPE, "Status"),
                new FieldSelector(net.minecraft.nbt.StringTag.TYPE, "status"),
                new FieldSelector("Level", "Structures", CompoundTag.TYPE, "Starts"),
                new FieldSelector("structures", CompoundTag.TYPE, "starts"));
    }

    /**
     * Parse a completed scan. Returns the chunk's start map, null when the
     * chunk carries no structure data on disk, or {@link #SCAN_FAILED} when
     * the datafix failed.
     */
    public static Object2IntMap<Structure> parse(CollectFields collector, ChunkPos pos,
            ResourceKey<Level> dimension, Optional<Identifier> generatorTypeName,
            DataFixer fixer, RegistryAccess registryAccess) {
        Tag result = collector.getResult();
        if (!(result instanceof CompoundTag chunkTag)) {
            return null;
        }
        int version = NbtUtils.getDataVersion(chunkTag);
        SimpleRegionStorageBridge.injectContext(chunkTag,
                ChunkMap.getChunkDataFixContextTag(dimension, generatorTypeName));
        CompoundTag fixed;
        try {
            fixed = DataFixTypes.CHUNK.updateToCurrentVersion(fixer, chunkTag, version);
        } catch (Exception e) {
            LOGGER.warn("Failed to partially datafix chunk {}", pos, e);
            return SCAN_FAILED;
        }
        Optional<CompoundTag> starts = fixed.getCompound("structures").flatMap(t -> t.getCompound("starts"));
        if (starts.isEmpty()) {
            // A chunk at or past STRUCTURE_STARTS always carries the starts
            // compound (empty when nothing generates), so its absence on such
            // a chunk means the parse no longer recognizes the format - fail
            // to a chunk load (slow-right), never into the math path
            // (silently wrong wherever disk and current generation disagree).
            // Below that status generation has not decided yet and the math
            // IS the right authority.
            String status = fixed.getString("status").orElse("minecraft:empty");
            return "minecraft:empty".equals(status) ? null : SCAN_FAILED;
        }
        Object2IntMap<Structure> known = new Object2IntOpenHashMap<>();
        var registry = registryAccess.lookupOrThrow(Registries.STRUCTURE);
        starts.get().forEach((name, tag) -> {
            Identifier id = Identifier.tryParse(name);
            if (id != null) {
                Structure structure = registry.getValue(id);
                if (structure != null) {
                    tag.asCompound().ifPresent(data -> {
                        if (!"INVALID".equals(data.getStringOr("id", ""))) {
                            known.put(structure, data.getIntOr("references", 0));
                        }
                    });
                }
            }
        });
        return known;
    }

    /** Blocking scan+parse for the server-thread tripwire; never used off-thread. */
    public static Object2IntMap<Structure> scanBlocking(ServerLevel level, ChunkPos pos) {
        CollectFields collector = newCollector();
        try {
            level.getChunkSource().chunkScanner().scanChunk(pos, collector).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return SCAN_FAILED;
        }
        return parse(collector, pos, level.dimension(),
                level.getChunkSource().getGenerator().getTypeNameForDataFixer(),
                level.getServer().getFixerUpper(), level.registryAccess());
    }

    /** Indirection kept tiny so the mixin-free jar has one vanilla-static call site. */
    private static final class SimpleRegionStorageBridge {
        static void injectContext(CompoundTag tag, CompoundTag context) {
            net.minecraft.world.level.chunk.storage.SimpleRegionStorage.injectDatafixingContext(tag, context);
        }
    }
}
