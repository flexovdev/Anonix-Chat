package me.flexov.anonymouschat.service;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

@Service
public class RateLimitService {

    private final Map<String, Deque<Long>> buckets = new ConcurrentHashMap<>();
    private final LongSupplier currentTimeMillis;

    public RateLimitService() {
        this(System::currentTimeMillis);
    }

    RateLimitService(LongSupplier currentTimeMillis) {
        this.currentTimeMillis = currentTimeMillis;
    }

    public void assertAllowed(String scope, String key, int limit, Duration window, String message) {
        if (!tryAcquire(scope, key, limit, window)) {
            throw new RateLimitExceededException(message);
        }
    }

    public boolean tryAcquire(String scope, String key, int limit, Duration window) {
        if (key == null || key.isBlank()) {
            return true;
        }

        long now = currentTimeMillis.getAsLong();
        long windowStart = now - window.toMillis();
        String bucketKey = scope + ":" + key;
        Deque<Long> bucket = buckets.computeIfAbsent(bucketKey, ignored -> new ArrayDeque<>());

        synchronized (bucket) {
            while (!bucket.isEmpty() && bucket.peekFirst() < windowStart) {
                bucket.removeFirst();
            }

            if (bucket.size() >= limit) {
                return false;
            }

            bucket.addLast(now);
            if (bucket.isEmpty()) {
                buckets.remove(bucketKey);
            }
            return true;
        }
    }
}
