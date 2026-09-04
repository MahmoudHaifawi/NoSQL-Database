package com.database.atypon.Node.index;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Fixed-capacity LRU map. When an entry is evicted to stay within capacity,
 * {@code onEvict} is invoked with it — the Pager uses this to flush a dirty page
 * before it drops out of the buffer pool. {@code remove}/{@code clear} do NOT fire it.
 */
public class LruCache<K, V> {

    private final int capacity;
    private final BiConsumer<K, V> onEvict;
    private final LinkedHashMap<K, V> map;

    public LruCache(int capacity, BiConsumer<K, V> onEvict) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.capacity = capacity;
        this.onEvict = onEvict;
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                if (size() > LruCache.this.capacity) {
                    if (LruCache.this.onEvict != null) {
                        LruCache.this.onEvict.accept(eldest.getKey(), eldest.getValue());
                    }
                    return true;
                }
                return false;
            }
        };
    }

    public V get(K key) { return map.get(key); }
    public void put(K key, V value) { map.put(key, value); }
    public V remove(K key) { return map.remove(key); }
    public boolean containsKey(K key) { return map.containsKey(key); }
    public int size() { return map.size(); }
    public void clear() { map.clear(); }
}
