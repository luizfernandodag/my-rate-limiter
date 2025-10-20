package com.example.my_rate_limiter;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests with an in-memory mock of DistributedKeyValueStore.
 */
public class DistributedHighThroughputRateLimiterTests {

    private MockStore store;
    private DistributedHighThroughputRateLimiter limiter;

    @Before
    public void setup() {
        store = new MockStore();
        limiter = new DistributedHighThroughputRateLimiter(store, 8, 100, 64);
    }

    @After
    public void teardown() {
        limiter.shutdown();
    }

    @Test
    public void testAllowsUnderLimit() throws Exception {
        String key = "clientA";
        int limit = 1000;
// simulate 500 rapid requests
        for (int i = 0; i < 500; i++) {
            CompletableFuture<Boolean> f = limiter.isAllowed(key, limit);
            Assert.assertTrue(f.get());
        }
    }

    @Test
    public void testEventuallyBlocksOverLimit() throws Exception {
        String key = "clientB";
        int limit = 200;
// send 300 requests
        boolean last = true;
        for (int i = 0; i < 300; i++) {
            last = limiter.isAllowed(key, limit).get();
        }
// allow background flush to execute
        TimeUnit.MILLISECONDS.sleep(300);
// after flush the distributed count should reflect many requests and further requests should be blocked
        boolean allowedNow = limiter.isAllowed(key, limit).get();
        Assert.assertFalse("Expected further requests to be disallowed eventually", allowedNow);
    }

// Mock implementation of DistributedKeyValueStore for testing
    static class MockStore implements DistributedKeyValueStore {

        private final Map<String, AtomicInteger> data = new ConcurrentHashMap<>();

        @Override
        public CompletableFuture<Integer> incrementByAndExpire(String key, int delta, int expirationSeconds) {
            return CompletableFuture.supplyAsync(() -> {
                AtomicInteger ai = data.computeIfAbsent(key, k -> new AtomicInteger(0));
                int v = ai.addAndGet(delta);
                return v;
            });
        }
    }
}
