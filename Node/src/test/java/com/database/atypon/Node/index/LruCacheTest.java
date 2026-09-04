package com.database.atypon.Node.index;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LruCacheTest {

    @Test
    void evictsLeastRecentlyUsedAndFiresCallback() {
        List<String> evicted = new ArrayList<>();
        LruCache<Integer, String> cache = new LruCache<>(2, (k, v) -> evicted.add(v));
        cache.put(1, "a");
        cache.put(2, "b");
        cache.get(1);       // touch 1 -> 2 is now least-recently-used
        cache.put(3, "c");  // evicts 2 ("b")
        assertThat(evicted).containsExactly("b");
        assertThat(cache.get(2)).isNull();
        assertThat(cache.get(1)).isEqualTo("a");
        assertThat(cache.get(3)).isEqualTo("c");
        assertThat(cache.size()).isEqualTo(2);
    }

    @Test
    void removeAndClearDoNotFireEviction() {
        List<String> evicted = new ArrayList<>();
        LruCache<Integer, String> cache = new LruCache<>(2, (k, v) -> evicted.add(v));
        cache.put(1, "a");
        assertThat(cache.remove(1)).isEqualTo("a");
        cache.put(2, "b");
        cache.clear();
        assertThat(evicted).isEmpty();
        assertThat(cache.size()).isZero();
    }

    @Test
    void rejectsNonPositiveCapacity() {
        assertThatThrownBy(() -> new LruCache<Integer, String>(0, (k, v) -> {}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
