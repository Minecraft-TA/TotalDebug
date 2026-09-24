package com.github.minecraft_ta.totalDebugCompanion.itemrender;

import java.util.LinkedHashMap;
import java.util.function.ToLongFunction;

/** Worker-confined LRU with an explicit retained-size budget. */
final class RenderCache<K, V> {
    private final LinkedHashMap<K, V> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final long budget;
    private final ToLongFunction<V> weigh;
    private long retained;

    RenderCache(long budget, ToLongFunction<V> weigh) {
        this.budget = budget;
        this.weigh = weigh;
    }

    V get(K key) { return entries.get(key); }

    void put(K key, V value) {
        V previous = entries.remove(key);
        if (previous != null) retained -= weigh.applyAsLong(previous);
        long weight = weigh.applyAsLong(value);
        if (weight > budget) return;
        while (retained + weight > budget && !entries.isEmpty())
            retained -= weigh.applyAsLong(entries.pollFirstEntry().getValue());
        entries.put(key, value);
        retained += weight;
    }

    void clear() { entries.clear(); retained = 0; }
}
