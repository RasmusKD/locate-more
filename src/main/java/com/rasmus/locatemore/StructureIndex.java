package com.rasmus.locatemore;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.stream.LongStream;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Per-dimension persistent memory of candidate chunks known to be absent from
 * disk. Strictly negative: an entry only ever removes a disk scan, and while
 * the generation config is unchanged a stale entry costs one redundant chunk
 * resolution rather than a wrong answer (the fingerprint invalidates the index
 * when structure sets, biomes, noise settings or the data version change; a
 * config change the fingerprint cannot see requires /locatemore index clear,
 * as the README documents). Positive
 * verdicts always re-scan. Keys are packed ChunkPos longs of placement
 * candidate chunks (one per region, so density stays low), delta-encoded on
 * save and capped at two million entries.
 *
 * Justifying measurement (seed 20260821, jungle_pyramid 20): first search
 * after a restart is 923 ms with the index vs 3.4 s rebuilding without it.
 * If that ratio ever stops holding, delete this class first.
 *
 * Thread model: reads from the search worker, mutations applied on the server
 * thread only (AsyncLocate drains a queue per tick); both synchronize here.
 */
public final class StructureIndex extends SavedData {

    private static final int FORMAT = 2;

    public static final SavedDataType<StructureIndex> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("locatemore", "structure_index"),
            StructureIndex::new,
            RecordCodecBuilder.create(instance -> instance.group(
                    Codec.INT.fieldOf("format").forGetter(index -> FORMAT),
                    Codec.LONG.fieldOf("seed").forGetter(StructureIndex::seedForSave),
                    Codec.INT.fieldOf("cfg").forGetter(StructureIndex::configForSave),
                    Codec.LONG_STREAM.fieldOf("absent").forGetter(StructureIndex::encodeAbsent)
            ).apply(instance, StructureIndex::decode)),
            DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES);

    private final LongOpenHashSet absentChunks = new LongOpenHashSet();
    private long seed;
    private int configHash;
    private boolean seedInitialized;

    public StructureIndex() {
    }

    private static StructureIndex decode(int format, long seed, int configHash, LongStream absentDeltas) {
        StructureIndex index = new StructureIndex();
        if (format != FORMAT) {
            return index; // other format: start fresh
        }
        index.seed = seed;
        index.configHash = configHash;
        index.seedInitialized = true;
        long[] deltas = absentDeltas.toArray();
        long previous = 0;
        for (long delta : deltas) {
            previous += delta;
            index.absentChunks.add(previous);
        }
        return index;
    }

    private LongStream encodeAbsent() {
        long[] keys;
        synchronized (this) {
            keys = absentChunks.toLongArray();
        }
        java.util.Arrays.sort(keys);
        LongArrayList deltas = new LongArrayList(keys.length);
        long previous = 0;
        for (long key : keys) {
            deltas.add(key - previous);
            previous = key;
        }
        return LongStream.of(deltas.toLongArray());
    }

    private synchronized long seedForSave() {
        return seed;
    }

    private synchronized int configForSave() {
        return configHash;
    }

    /**
     * Server thread, once per search start: wipe when the world seed or the
     * generation config fingerprint changed. The fingerprint covers the
     * structure-set registry and the game data version, so datapack changes
     * that add or remove sets, or a version upgrade, invalidate the index.
     */
    public synchronized void validate(long levelSeed, int fingerprint) {
        if (!seedInitialized || seed != levelSeed || configHash != fingerprint) {
            if (seedInitialized && !absentChunks.isEmpty()) {
                absentChunks.clear();
            }
            seed = levelSeed;
            configHash = fingerprint;
            seedInitialized = true;
            setDirty();
        }
    }

    /** Worker thread: is this candidate chunk known to be absent from disk? */
    public synchronized boolean isKnownAbsentFromDisk(long chunkPack) {
        return absentChunks.contains(chunkPack);
    }

    private static final int MAX_ENTRIES = 2_000_000;

    /** Server thread only (drained from the mutation queue). */
    public synchronized boolean apply(long chunkPack, boolean absent) {
        if (absent) {
            if (absentChunks.size() >= MAX_ENTRIES) {
                absentChunks.clear(); // pure cost optimization; safe to drop
            }
            return absentChunks.add(chunkPack);
        }
        return absentChunks.remove(chunkPack);
    }

    public synchronized int size() {
        return absentChunks.size();
    }

    public synchronized void clear() {
        absentChunks.clear();
        setDirty();
    }
}
