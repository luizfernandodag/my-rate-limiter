# Distributed High Throughput Rate Limiter

## 📌 Overview
This Java project implements a **high-throughput distributed rate limiter** designed with a focus on **availability, scalability, and low network overhead**, trading strict consistency for better performance.

It leverages a **distributed key-value store** (via the `DistributedKeyValueStore` interface) to maintain rate-limiting counters using an **optimistic, efficient, and fault-tolerant architecture**.

---

## 🚀 Key Features
- **High Throughput**: Optimized to handle massive request volumes for microservices and APIs.
- **Low Latency**: `isAllowed()` checks use in-memory caching to avoid remote calls.
- **Sharding Mechanism**: Distributes rate limit keys across multiple shards to prevent hot spots.
- **Asynchronous Batch Updates**: Counters are flushed in batches to reduce network calls.
- **Fault Tolerance**: Failed updates are retried automatically with rollback logic.

---

## 🔧 How It Works
### 1️⃣ Local Caching & Sharding
- Each rate limit key (e.g., userId, IP) is partitioned into multiple **shards**.
- Requests are distributed randomly across shards for load balancing.

### 2️⃣ Local In-Memory Counters
- Each shard uses a **`LongAdder`** for extremely fast, thread-safe increments.

### 3️⃣ Optimistic Rate Check
- `isAllowed()` combines **local pending increments** with **cached distributed values** to quickly determine if a request is allowed.

### 4️⃣ Asynchronous Flush Mechanism
- A **background scheduler** flushes pending counters to the distributed store.
- On success: local cache is updated.
- On failure: increments are rolled back for future retry.

---

## 📁 Project Structure
```text
src/main/java/com/example/
├── DistributedKeyValueStore.java   # Interface for distributed storage
├── DistributedHighThroughputRateLimiter.java  # Main rate limiter implementation
```

---

## 📦 Dependencies
- `DistributedKeyValueStore` (external or mock implementation)
- `java.util.concurrent` (ConcurrentHashMap, CompletableFuture, Executors, LongAdder)

---

## 🛠 Installation & Setup
### ✅ Prerequisites
- **JDK 8+** installed

### ▶ Usage Example
#### 1. Instantiate the Limiter
```java
DistributedKeyValueStore myStore = new InMemoryKeyValueStore(); // Mock Example
DistributedHighThroughputRateLimiter rateLimiter = new DistributedHighThroughputRateLimiter(myStore);
```

#### 2. Check Requests
```java
String key = "userId:123";
int limit = 100;

CompletableFuture<Boolean> isAllowedFuture = rateLimiter.isAllowed(key, limit);
isAllowedFuture.thenAccept(isAllowed -> {
    if (isAllowed) {
        System.out.println("Request allowed for key: " + key);
    } else {
        System.out.println("Rate limit exceeded for key: " + key);
    }
});
```

#### 3. Shutdown Limiter
```java
rateLimiter.shutdown();
```

---

## 🧠 Design Considerations
### 🔄 Trade-offs
| Aspect | Benefit | Trade-off |
|-------|---------|-----------|
| Availability | Fast response with local cache | May exceed limit briefly due to optimistic updates |
| Sharding | Avoids hot partitions | Increases complexity |
| Asynchronous Flush | Higher throughput | Slight delay in persistence |

### ⚙ Optimizations
- **Flush Threshold & Frequency**: Configurable for tuning performance.
- **LongAdder Usage**: Outperforms AtomicLong in high-concurrency environments.

---

## 📚 Conclusion
This rate limiter is ideal for **large-scale distributed systems** where **speed and availability** are critical. It efficiently manages load with sharding, caching, and asynchronous operations while gracefully handling network failures.

---

### ✨ Future Improvements
- Adaptive flush strategy based on traffic patterns
- Metrics and monitoring integration
- Pluggable eviction policies for local cache

