package com.database.atypon.Node.utils.cache;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CacheTest {

    @Test
    void isConstructableWithoutStaticSingleton() throws Exception {
        Cache cache = new Cache();
        assertThat(cache).isNotNull();
        // the hand-rolled singleton accessor must be gone
        assertThatThrownBy(() -> Cache.class.getDeclaredMethod("getInstance"))
                .isInstanceOf(NoSuchMethodException.class);
    }

    @Test
    void addRejectsNulls() {
        Cache cache = new Cache();
        assertThatThrownBy(() -> cache.add(null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
