package me.gm.cleaner.runtime.mediaprovider.hook.media;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * Small process-local cache for UID-to-package lookups on the MediaProvider FUSE hot path.
 * Entries expire quickly to limit stale identity exposure after package/UID changes.
 */
final class UidPackageCache {
    static final int MAX_ENTRIES = 256;
    static final long TTL_MILLIS = 1_000L;

    private static final class Entry {
        final String packageName;
        final long createdAtElapsed;

        Entry(String packageName, long createdAtElapsed) {
            this.packageName = packageName;
            this.createdAtElapsed = createdAtElapsed;
        }
    }

    private final Map<Integer, Entry> entries = new LinkedHashMap<>(32, 0.75f, true);

    int sizeForTest() {
        synchronized (entries) {
            return entries.size();
        }
    }

    String get(int uid, long nowElapsed, IntFunction<List<String>> packageLookup) {
        if (uid < 0) {
            return null;
        }
        synchronized (entries) {
            final Entry cached = entries.get(uid);
            if (cached != null) {
                final long age = nowElapsed - cached.createdAtElapsed;
                if (age >= 0L && age < TTL_MILLIS) {
                    return cached.packageName;
                }
                entries.remove(uid);
            }
        }

        final List<String> packages = packageLookup.apply(uid);
        if (packages == null || packages.isEmpty() || packages.get(0) == null
                || packages.get(0).isEmpty()) {
            return null;
        }
        final String packageName = packages.get(0);
        synchronized (entries) {
            entries.put(uid, new Entry(packageName, nowElapsed));
            if (entries.size() > MAX_ENTRIES) {
                final Iterator<Integer> oldest = entries.keySet().iterator();
                if (oldest.hasNext()) {
                    oldest.next();
                    oldest.remove();
                }
            }
        }
        return packageName;
    }
}
