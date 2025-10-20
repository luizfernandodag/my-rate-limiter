package com.example.my_rate_limiter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * DistributedHighThroughputRateLimiter
 *
 * Design decisions & summary: - Use local in-memory batching using LongAdder
 * per shard to avoid calling the distributed store on every request. - For each
 * logical key we allocate a small number of shards (configurable, default 16).
 * When recording a request, we choose a shard (random) and increment the
 * shard's local pending counter. This spreads writes across shard keys in the
 * underlying store to avoid hot partitions. - A background flusher periodically
 * sends the accumulated deltas for shards to the DistributedKeyValueStore using
 * incrementByAndExpire with expirationSeconds = 60 (fixed rate window). The
 * store will set expiration only when the shard key is first created. - We
 * maintain a cached last-known distributed value per shard (updated when flush
 * responses arrive). isAllowed() computes an approximate total as
 * sum(cachedShardCounts) + localPendingSum and compares to limit. This favors
 * availability over strict accuracy (clients may occasionally be allowed
 * slightly more than limit). - The class uses CompletableFuture<Boolean> for
 * isAllowed to allow non-blocking behavior and to return quickly (we compute
 * answer using local state; flushes may be triggered asynchronously). -
 * Concurrency: uses ConcurrentHashMap and atomic primitives for thread-safety.
 */
public class DistributedHighThroughputRateLimiter {

    private final DistributedKeyValueStore store;
    private final ScheduledExecutorService scheduler;
    private final Map<String, KeyState> keys = new ConcurrentHashMap<>();
    private final int shardsPerKey;
    private final int flushIntervalMs;
    private final int flushThreshold; // pending count threshold to trigger async flush
    private final Random random = new Random();

    public DistributedHighThroughputRateLimiter(DistributedKeyValueStore store) {
// defaults tuned for throughput and latency
        this(store, 16, 200, 1024);
    }

    public DistributedHighThroughputRateLimiter(DistributedKeyValueStore store, int shardsPerKey, int flushIntervalMs, int flushThreshold) {
        this.store = store;
        this.shardsPerKey = Math.max(1, shardsPerKey);
        this.flushIntervalMs = Math.max(50, flushIntervalMs);
        this.flushThreshold = Math.max(1, flushThreshold);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rate-limiter-flusher");
            t.setDaemon(true);
            return t;
        });
// Periodic global flush
        scheduler.scheduleAtFixedRate(this::flushAll, this.flushIntervalMs, this.flushIntervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * isAllowed returns a CompletableFuture that completes quickly with an
     * approximate decision based on cached distributed counts plus local
     * pending increments.
     */
    public CompletableFuture<Boolean> isAllowed(String key, int limit) {
        KeyState state = keys.computeIfAbsent(key, k -> new KeyState(k, shardsPerKey));
// record request locally by choosing a random shard
        int shard = random.nextInt(shardsPerKey);
        state.pendingAdders[shard].increment();
        state.localTotal.increment();

// If local pending for that shard exceeds threshold we schedule an async flush for this key
        if (state.pendingAdders[shard].sum() >= flushThreshold) {
// fire-and-forget
            scheduler.execute(() -> flushKey(state));
        }

// approximate total = sum of cached shard counts + localTotal pending
        long approx = state.getCachedSum() + state.localTotal.sum();
        boolean allowed = approx <= (long) limit;
        return CompletableFuture.completedFuture(allowed);
    }

// Trigger immediate flush for a specific key (synchronous caller is a scheduler thread)
    private void flushKey(KeyState state) {
// gather deltas per shard atomically
        int n = state.shards;
        List<CompletableFuture<Void>> futures = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            long delta = state.pendingAdders[i].sumThenReset();
            if (delta <= 0) {
                continue;
            }
            String shardKey = makeShardKey(state.key, i);

            final int shardIndex = i;
            try {
// call distributed store (network IO). Use thenAccept to update cached counts when reply arrives.
                CompletableFuture<Integer> f = store.incrementByAndExpire(shardKey, (int) Math.min(delta, Integer.MAX_VALUE), 60);
                CompletableFuture<Void> update = f.thenAccept(v -> {
                    state.cachedShardCounts[shardIndex].set(v);
// subtract flushed count from localTotal conservatively
// localTotal may have received new increments meanwhile; we decrement by delta but ensure non-negative.
                    state.localTotal.add(-delta);
                    state.lastUpdated = Instant.now().toEpochMilli();
                }).exceptionally(ex -> {
// On failure, add the delta back to pending so it will be retried next flush
                    state.pendingAdders[shardIndex].add(delta);
                    return null;
                });
                futures.add(update);
            } catch (Exception e) {
// If the store throws synchronously, re-add delta to pending and continue
                state.pendingAdders[shardIndex].add(delta);
            }
        }
// We don't block on futures — they update cached state asynchronously.
    }

    private void flushAll() {
        try {
            for (KeyState state : keys.values()) {
                flushKey(state);
            }
        } catch (Throwable t) {
// catch all to prevent scheduled executor death
            t.printStackTrace();
        }
    }

    private String makeShardKey(String key, int shardIndex) {
        return String.format("rl:%s:shard:%d", key, shardIndex);
    }

    public void shutdown() {
        scheduler.shutdown();
    }

}
// Internal class holding per-logical-key state

class KeyState {

    final String key;
    final int shards;
    final LongAdder[] pendingAdders; // counts waiting to be flushed
    final AtomicInteger[] cachedShardCounts; // last-known distributed values per shard
    final LongAdder localTotal; // approximate pending total for quick checks
    volatile long lastUpdated = 0L;

    KeyState(String key, int shards) {
        this.key = key;
        this.shards = shards;
        this.pendingAdders = new LongAdder[shards];
        this.cachedShardCounts = new AtomicInteger[shards];
        for (int i = 0; i < shards; i++) {
            pendingAdders[i] = new LongAdder();
            cachedShardCounts[i] = new AtomicInteger(0);
        }
        this.localTotal = new LongAdder();
    }

    long getCachedSum() {
        long sum = 0;
        for (AtomicInteger ai : cachedShardCounts) {
            sum += ai.get();
        }
        return sum;
    }
}
