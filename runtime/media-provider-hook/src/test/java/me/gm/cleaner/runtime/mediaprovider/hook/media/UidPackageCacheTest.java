package me.gm.cleaner.runtime.mediaprovider.hook.media;

import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class UidPackageCacheTest {
    @Test
    public void hitWithinTtl_skipsPackageManagerLookup() {
        final UidPackageCache cache = new UidPackageCache();
        final AtomicInteger lookups = new AtomicInteger();
        final java.util.function.IntFunction<List<String>> lookup = uid -> {
            lookups.incrementAndGet();
            return Collections.singletonList("pkg." + uid);
        };

        assertEquals("pkg.10001", cache.get(10001, 100L, lookup));
        assertEquals("pkg.10001", cache.get(10001, 999L, lookup));
        assertEquals(1, lookups.get());
    }

    @Test
    public void expiredEntry_isLookedUpAgain() {
        final UidPackageCache cache = new UidPackageCache();
        final AtomicInteger lookups = new AtomicInteger();
        final java.util.function.IntFunction<List<String>> lookup = uid -> {
            final int count = lookups.incrementAndGet();
            return Collections.singletonList("pkg." + count);
        };

        assertEquals("pkg.1", cache.get(10001, 100L, lookup));
        assertEquals("pkg.2", cache.get(10001, 1100L, lookup));
        assertEquals(2, lookups.get());
    }

    @Test
    public void invalidUidAndEmptyLookup_areNotCached() {
        final UidPackageCache cache = new UidPackageCache();
        final AtomicInteger lookups = new AtomicInteger();
        final java.util.function.IntFunction<List<String>> lookup = uid -> {
            lookups.incrementAndGet();
            return Collections.emptyList();
        };

        assertNull(cache.get(-1, 100L, lookup));
        assertNull(cache.get(10001, 100L, lookup));
        assertNull(cache.get(10001, 101L, lookup));
        assertEquals(2, lookups.get());
    }

    @Test
    public void cacheIsBounded() {
        final UidPackageCache cache = new UidPackageCache();
        final java.util.function.IntFunction<List<String>> lookup = uid -> Collections.singletonList("pkg." + uid);
        for (int uid = 10000; uid < 10000 + UidPackageCache.MAX_ENTRIES + 1; uid++) {
            cache.get(uid, 100L, lookup);
        }
        // The cache's eviction policy is LRU and must never retain more than its declared bound.
        assertEquals(UidPackageCache.MAX_ENTRIES, cache.sizeForTest());
    }
}
