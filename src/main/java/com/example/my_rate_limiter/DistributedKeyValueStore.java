package com.example.my_rate_limiter;

import java.util.concurrent.CompletableFuture;


public interface DistributedKeyValueStore {
/**
* Increment the value stored at "key" by delta, and set expirationSeconds if the key is newly created.
* Returns the post-increment value for that key.
*/
CompletableFuture<Integer> incrementByAndExpire(String key, int delta, int expirationSeconds) throws Exception;
}

