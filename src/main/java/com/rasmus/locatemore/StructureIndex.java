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
 * disk. Strictly negative: an entry only ever removes a disk scan; a stale
 * entry costs one redundant chunk resolution, never a wrong answer. Positive
 * verdicts always re-scan. Keys are packed ChunkPos longs of placement
 * candidate chunks (one per region, so density stays tiny), delta-encoded on
 * save which gzips to well under 100 KB for a heavily searched world.
 *
 * Thread model: reads from the search worker, mutations applied on the server
 * thread only (AsyncLocate drains a queue per tick); both synchronize here.
 */
public final class StructureIndex extends SavedData {

    private static final int FORMAT = 1;

    public static final SavedDataType<StructureIndex> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("locatemore", "structure_index"),
            StructureIndex::new,
            RecordCodecBuilder.create(instance -> instance.group(
                    Codec.INT.fieldOf("format").forGetter(index -> FORMAT),
                    Codec.LONG.fieldOf("seed").forGetter(index -> index.seed),
                    Codec.LONG_STREAM.fieldOf("absent").forGetter(StructureIndex::encodeAbsent)
            ).apply(instance, StructureIndex::decode)),
            DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES);

    private final LongOpenHashSet absentChunks = new LongOpenHashSet();
    private long seed;
    private boolean seedInitialized;

    public StructureIndex() {
    }

    private static StructureIndex decode(int format, long seed, LongStream absentDeltas) {
        StructureIndex index = new StructureIndex();
        if (format != FORMAT) {
            return index; // future format: start fresh
        }
        index.seed = seed;
        index.seedInitialized = true;
        long[] deltas = absentDeltas.toArray();
        long previous = 0;
        for (long delta : deltas) {
            previous += delta;
            index.absentChunks.add(previous);
        }
        return index;
    }

    private synchronized LongStream encodeAbsent() {
        long[] keys = absentChunks.toLongArray();
        java.util.Arrays.sort(keys);
        LongArrayList deltas = new LongArrayList(keys.length);
        long previous = 0;
        for (long key : keys) {
            deltas.add(key - previous);
            previous = key;
        }
        return LongStream.of(deltas.toLongArray());
    }

    /** Server thread, once per search start: wipe if this is a different world seed. */
    public synchronized void validateSeed(long levelSeed) {
        if (!seedInitialized || seed != levelSeed) {
            if (seedInitialized && !absentChunks.isEmpty()) {
                absentChunks.clear();
            }
            seed = levelSeed;
            seedInitialized = true;
            setDirty();
        }
    }

    /** Worker thread: is this candidate chunk known to be absent from disk? */
    public synchronized boolean isKnownAbsentFromDisk(long chunkPack) {
        return absentChunks.contains(chunkPack);
    }

    /** Server thread only (drained from the mutation queue). */
    public synchronized boolean apply(long chunkPack, boolean absent) {
        return absent ? absentChunks.add(chunkPack) : absentChunks.remove(chunkPack);
    }

    public synchronized int size() {
        return absentChunks.size();
    }

    public synchronized void clear() {
        absentChunks.clear();
        setDirty();
    }
}
